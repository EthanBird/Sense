package io.github.ethanbird.senseime.speech

import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal interface SogouAsrCallback {
    fun onPartialResult(transcript: CloudSpeechTranscript)

    fun onResult(result: CloudSpeechHttpResult)
}

internal interface SogouAsrLiveCall : CloudSpeechCall {
    /** Copies one sample-aligned PCM16 chunk for ordered background encoding. */
    fun sendPcm(pcm: ByteArray): Boolean

    /** Closes microphone input while leaving the socket alive for the final transcript. */
    fun finishInput(): Boolean
}

/** Cancellable SRSS transport. It owns copied PCM and wipes audio buffers on every terminal path. */
internal class SogouAsrWebSocketClient(
    callbackExecutor: Executor,
    connectTimeoutMillis: Int = 8_000,
    private val totalTimeoutMillis: Int = 60_000,
    private val frameDelayMillis: Long = 8L,
    private val worker: ExecutorService = Executors.newCachedThreadPool(
        NamedDaemonThreadFactory("SenseSogouAsr"),
    ),
    private val timeoutScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            NamedDaemonThreadFactory("SenseSogouTimeout"),
        ),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(0L, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build(),
) : AutoCloseable {
    private val callbackExecutor = SerialExecutor(callbackExecutor)
    private val nextCallId = AtomicLong(0L)
    private val calls = ConcurrentHashMap<Long, ResponseCall>()
    private val closed = AtomicBoolean(false)

    init {
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(totalTimeoutMillis in connectTimeoutMillis..MAX_TIMEOUT_MILLIS)
        require(frameDelayMillis in 0L..MAX_FRAME_DELAY_MILLIS)
    }

    /** Takes an immediate private copy of the PCM region; the caller may then erase the WAV. */
    fun transcribe(
        profile: SpeechProviderProfile,
        audio: Pcm16WavAudio,
        callback: SogouAsrCallback,
    ): Result<CloudSpeechCall> = runCatching {
        check(!closed.get()) { "Sogou ASR client is closed" }
        profile.requireValid()
        require(profile.protocol == SpeechProviderProtocol.SOGOU_SRSS) {
            "profile is not a Sogou SRSS provider"
        }
        require(audio.pcmByteCount > 0) { "audio is empty" }
        val pcmEnd = Pcm16WavEncoder.HEADER_BYTES + audio.pcmByteCount
        require(pcmEnd <= audio.bytes.size) { "WAV PCM range is truncated" }
        val pcm = audio.bytes.copyOfRange(Pcm16WavEncoder.HEADER_BYTES, pcmEnd)
        val call = CallState(
            callId = nextCallId.incrementAndGet(),
            profile = profile,
            pcm = pcm,
            callback = callback,
        )
        calls[call.callId] = call
        try {
            call.timeoutFuture = timeoutScheduler.schedule(
                { call.timeout() },
                totalTimeoutMillis.toLong(),
                TimeUnit.MILLISECONDS,
            )
            call.prepareFuture = worker.submit { prepareAndConnect(call) }
        } catch (error: RuntimeException) {
            call.abandonBeforeStart()
            throw error
        }
        if (call.isTerminal()) call.prepareFuture?.cancel(true)
        call
    }

    /** Opens SRSS before microphone capture so partial text can arrive while the user is speaking. */
    fun startStreaming(
        profile: SpeechProviderProfile,
        callback: SogouAsrCallback,
    ): Result<SogouAsrLiveCall> = runCatching {
        check(!closed.get()) { "Sogou ASR client is closed" }
        profile.requireValid()
        require(profile.protocol == SpeechProviderProtocol.SOGOU_SRSS) {
            "profile is not a Sogou SRSS provider"
        }
        require(profile.streamingOptimization) {
            "profile did not opt into streaming optimization"
        }
        val call = LiveCallState(
            callId = nextCallId.incrementAndGet(),
            profile = profile,
            callback = callback,
        )
        calls[call.callId] = call
        try {
            call.timeoutFuture = timeoutScheduler.schedule(
                { call.timeout() },
                totalTimeoutMillis.toLong(),
                TimeUnit.MILLISECONDS,
            )
            call.prepareFuture = worker.submit { prepareAndConnectLive(call) }
        } catch (error: RuntimeException) {
            call.abandonBeforeStart()
            throw error
        }
        if (call.isTerminal()) call.prepareFuture?.cancel(true)
        call
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        calls.values.toList().forEach(ResponseCall::cancel)
        worker.shutdownNow()
        timeoutScheduler.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
    }

    private fun prepareAndConnect(call: CallState) {
        if (call.isTerminal()) return
        val handshake: SogouAsrHandshake
        val frames: List<ByteArray>
        try {
            handshake = SogouAsrProtocol.prepareHandshake(
                languageTag = call.profile.languageTag,
                interimResults = call.profile.interimResults,
            )
            frames = SogouOpusPacketEncoder.encode(call.pcm)
        } catch (_: RuntimeException) {
            call.fail(
                CloudSpeechFailureKind.INTERNAL,
                "搜狗语音请求准备失败",
            )
            return
        }
        if (!call.installPrepared(handshake, frames)) return

        try {
            val requestBuilder = Request.Builder().url(
                requireNotNull(call.profile.endpointUrl),
            )
            handshake.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            val webSocket = httpClient.newWebSocket(
                requestBuilder.build(),
                Listener(call),
            )
            if (!call.attach(webSocket)) webSocket.cancel()
        } catch (_: RuntimeException) {
            call.fail(
                CloudSpeechFailureKind.INVALID_CONFIGURATION,
                "搜狗语音 WebSocket 地址无效",
            )
        }
    }

    private fun prepareAndConnectLive(call: LiveCallState) {
        if (call.isTerminal()) return
        val handshake = try {
            SogouAsrProtocol.prepareHandshake(
                languageTag = call.profile.languageTag,
                interimResults = true,
            )
        } catch (_: RuntimeException) {
            call.fail(
                CloudSpeechFailureKind.INTERNAL,
                "搜狗流式语音请求准备失败",
            )
            return
        }
        if (!call.installHandshake(handshake)) return

        try {
            val requestBuilder = Request.Builder().url(
                requireNotNull(call.profile.endpointUrl),
            )
            handshake.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            val webSocket = httpClient.newWebSocket(
                requestBuilder.build(),
                Listener(call),
            )
            if (!call.attach(webSocket)) webSocket.cancel()
        } catch (_: RuntimeException) {
            call.fail(
                CloudSpeechFailureKind.INVALID_CONFIGURATION,
                "搜狗流式语音 WebSocket 地址无效",
            )
        }
    }

    private fun streamPreparedAudio(
        call: CallState,
        webSocket: WebSocket,
    ) {
        val prepared = call.preparedSnapshot() ?: return
        if (!webSocket.send(prepared.handshake.encryptedConfigBase64)) {
            call.fail(CloudSpeechFailureKind.NETWORK, "搜狗语音配置发送失败")
            return
        }
        prepared.frames.forEachIndexed { index, packet ->
            if (call.isTerminal()) return
            if (!webSocket.send(packet.toByteString())) {
                call.fail(CloudSpeechFailureKind.NETWORK, "搜狗语音音频发送失败")
                return
            }
            if (frameDelayMillis > 0L && index != prepared.frames.lastIndex) {
                try {
                    Thread.sleep(frameDelayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        if (!call.isTerminal() && !webSocket.send(END_OF_AUDIO_MESSAGE)) {
            call.fail(CloudSpeechFailureKind.NETWORK, "搜狗语音结束包发送失败")
        }
    }

    private interface ResponseCall : CloudSpeechCall {
        val profile: SpeechProviderProfile

        fun isTerminal(): Boolean
        fun wasTimedOut(): Boolean
        fun attach(value: WebSocket): Boolean
        fun onOpened(value: WebSocket): Boolean
        fun acceptResponseBytes(byteCount: Int): Boolean
        fun partial(transcript: CloudSpeechTranscript)
        fun hasPartialResult(): Boolean
        fun succeed(transcript: CloudSpeechTranscript)
        fun fail(
            kind: CloudSpeechFailureKind,
            message: String,
            httpStatus: Int? = null,
        )
    }

    private inner class Listener(
        private val call: ResponseCall,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            if (!call.onOpened(webSocket)) {
                webSocket.cancel()
                return
            }
            if (call is CallState) {
                try {
                    call.streamFuture = worker.submit { streamPreparedAudio(call, webSocket) }
                } catch (_: RuntimeException) {
                    call.fail(CloudSpeechFailureKind.INTERNAL, "搜狗语音发送任务启动失败")
                }
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (!call.acceptResponseBytes(text.toByteArray(Charsets.UTF_8).size)) return
            val response = SogouAsrResponseDecoder.decode(text).getOrElse { error ->
                val decodingError = error as? CloudSpeechResponseDecodingException
                call.fail(
                    decodingError?.failureKind ?: CloudSpeechFailureKind.PROTOCOL,
                    decodingError?.message ?: "搜狗语音响应解析失败",
                )
                return
            }
            response.serverError?.let { message ->
                call.fail(
                    CloudSpeechFailureKind.SERVER,
                    "搜狗语音服务返回错误：$message",
                )
                return
            }
            val transcript = response.transcript
            if (response.isFinal) {
                if (transcript == null) {
                    call.fail(CloudSpeechFailureKind.NO_AUDIO, "搜狗语音未返回识别文本")
                } else {
                    call.succeed(transcript)
                }
            } else if (transcript != null && call.profile.interimResults) {
                call.partial(transcript)
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            call.acceptResponseBytes(bytes.size)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            if (!call.isTerminal()) {
                call.fail(
                    if (call.hasPartialResult()) {
                        CloudSpeechFailureKind.PROTOCOL
                    } else {
                        CloudSpeechFailureKind.NO_AUDIO
                    },
                    "搜狗语音连接结束时没有最终结果",
                )
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (call.isTerminal()) return
            val status = response?.code
            val kind = when {
                call.wasTimedOut() || t is SocketTimeoutException ->
                    CloudSpeechFailureKind.TIMEOUT
                status == 401 || status == 403 -> CloudSpeechFailureKind.AUTHENTICATION
                status == 429 -> CloudSpeechFailureKind.RATE_LIMIT
                status != null && status >= 500 -> CloudSpeechFailureKind.SERVER
                status != null -> CloudSpeechFailureKind.PROTOCOL
                else -> CloudSpeechFailureKind.NETWORK
            }
            call.fail(kind, "搜狗语音连接失败", httpStatus = status)
        }
    }

    private inner class CallState(
        override val callId: Long,
        override val profile: SpeechProviderProfile,
        val pcm: ByteArray,
        val callback: SogouAsrCallback,
    ) : ResponseCall {
        private val terminal = AtomicBoolean(false)
        private val timedOut = AtomicBoolean(false)
        private val responseBytes = AtomicLong(0L)
        private val bufferLock = Any()

        @Volatile
        private var webSocket: WebSocket? = null

        @Volatile
        var prepareFuture: Future<*>? = null

        @Volatile
        var streamFuture: Future<*>? = null

        @Volatile
        var timeoutFuture: ScheduledFuture<*>? = null

        private var handshake: SogouAsrHandshake? = null
        private var frames: List<ByteArray>? = null
        private var lastPartialText: String? = null

        override fun isTerminal(): Boolean = terminal.get()

        override fun wasTimedOut(): Boolean = timedOut.get()

        override fun attach(value: WebSocket): Boolean {
            webSocket = value
            if (isTerminal()) {
                value.cancel()
                return false
            }
            return true
        }

        override fun onOpened(value: WebSocket): Boolean = attach(value)

        fun installPrepared(
            value: SogouAsrHandshake,
            packets: List<ByteArray>,
        ): Boolean = synchronized(bufferLock) {
            pcm.fill(0)
            if (isTerminal()) {
                packets.forEach { it.fill(0) }
                false
            } else {
                handshake = value
                frames = packets
                true
            }
        }

        fun preparedSnapshot(): PreparedAudio? = synchronized(bufferLock) {
            val currentHandshake = handshake ?: return@synchronized null
            val currentFrames = frames ?: return@synchronized null
            PreparedAudio(currentHandshake, currentFrames)
        }

        override fun acceptResponseBytes(byteCount: Int): Boolean {
            if (byteCount > CloudSpeechResponseDecoder.MAX_RESPONSE_BYTES) {
                fail(
                    CloudSpeechFailureKind.RESPONSE_TOO_LARGE,
                    "搜狗语音单条响应超过大小限制",
                )
                return false
            }
            val total = responseBytes.addAndGet(byteCount.toLong())
            if (total > MAX_TOTAL_RESPONSE_BYTES) {
                fail(
                    CloudSpeechFailureKind.RESPONSE_TOO_LARGE,
                    "搜狗语音响应总量超过大小限制",
                )
                return false
            }
            return !isTerminal()
        }

        override fun partial(transcript: CloudSpeechTranscript) {
            if (isTerminal() || lastPartialText == transcript.text) return
            lastPartialText = transcript.text
            callbackExecutor.execute {
                runCatching { callback.onPartialResult(transcript) }
            }
        }

        override fun hasPartialResult(): Boolean = !lastPartialText.isNullOrBlank()

        override fun succeed(transcript: CloudSpeechTranscript) {
            complete(CloudSpeechHttpResult.Success(transcript), gracefulClose = true)
        }

        override fun fail(
            kind: CloudSpeechFailureKind,
            message: String,
            httpStatus: Int?,
        ) {
            complete(
                CloudSpeechHttpResult.Failure(
                    CloudSpeechFailure(kind, httpStatus, message),
                ),
                gracefulClose = false,
            )
        }

        override fun cancel() {
            complete(
                CloudSpeechHttpResult.Failure(
                    CloudSpeechFailure(
                        CloudSpeechFailureKind.CANCELLED,
                        message = "语音转写已取消",
                    ),
                ),
                gracefulClose = false,
            )
        }

        fun timeout() {
            timedOut.set(true)
            fail(CloudSpeechFailureKind.TIMEOUT, "搜狗语音请求超时")
        }

        fun abandonBeforeStart() {
            if (!terminal.compareAndSet(false, true)) return
            calls.remove(callId, this)
            timeoutFuture?.cancel(false)
            eraseBuffers()
        }

        private fun complete(
            result: CloudSpeechHttpResult,
            gracefulClose: Boolean,
        ) {
            if (!terminal.compareAndSet(false, true)) return
            timeoutFuture?.cancel(false)
            calls.remove(callId, this)
            if (gracefulClose) {
                webSocket?.close(NORMAL_CLOSE_CODE, "done")
            } else {
                webSocket?.cancel()
            }
            prepareFuture?.cancel(true)
            streamFuture?.cancel(true)
            eraseBuffers()
            callbackExecutor.execute {
                runCatching { callback.onResult(result) }
            }
        }

        private fun eraseBuffers() {
            synchronized(bufferLock) {
                pcm.fill(0)
                frames?.forEach { it.fill(0) }
                frames = null
                handshake = null
            }
        }
    }

    private inner class LiveCallState(
        override val callId: Long,
        override val profile: SpeechProviderProfile,
        private val callback: SogouAsrCallback,
    ) : ResponseCall, SogouAsrLiveCall {
        private val terminal = AtomicBoolean(false)
        private val timedOut = AtomicBoolean(false)
        private val responseBytes = AtomicLong(0L)
        private val bufferLock = Any()
        private val encoderLock = Any()
        private val encoder = SogouOpusStreamEncoder()
        private val sendExecutor = SerialExecutor(worker)
        private val pendingPcm = ArrayDeque<ByteArray>()

        @Volatile
        private var webSocket: WebSocket? = null

        @Volatile
        var prepareFuture: Future<*>? = null

        @Volatile
        var timeoutFuture: ScheduledFuture<*>? = null

        private var handshake: SogouAsrHandshake? = null
        private var opened = false
        private var configSent = false
        private var finishRequested = false
        private var endSent = false
        private var totalPcmBytes = 0
        private var lastPartialText: String? = null

        override fun isTerminal(): Boolean = terminal.get()

        override fun wasTimedOut(): Boolean = timedOut.get()

        fun installHandshake(value: SogouAsrHandshake): Boolean = synchronized(bufferLock) {
            if (isTerminal()) {
                false
            } else {
                handshake = value
                true
            }
        }

        override fun attach(value: WebSocket): Boolean {
            synchronized(bufferLock) {
                webSocket = value
                if (isTerminal()) {
                    value.cancel()
                    return false
                }
            }
            return true
        }

        override fun onOpened(value: WebSocket): Boolean {
            if (!attach(value)) return false
            synchronized(bufferLock) {
                if (isTerminal()) return false
                opened = true
            }
            scheduleDrain()
            return true
        }

        override fun sendPcm(pcm: ByteArray): Boolean {
            require(pcm.isNotEmpty()) { "PCM chunk is empty" }
            require(pcm.size % Pcm16AudioFormat.BYTES_PER_SAMPLE == 0) {
                "PCM16 chunk must be sample-aligned"
            }
            val maximum = Pcm16AudioFormat.maxPcmBytes(
                Pcm16AudioFormat.ABSOLUTE_MAX_DURATION_MILLIS,
            )
            val copy = pcm.copyOf()
            val accepted = synchronized(bufferLock) {
                if (
                    isTerminal() ||
                    finishRequested ||
                    totalPcmBytes.toLong() + copy.size.toLong() > maximum.toLong()
                ) {
                    false
                } else {
                    pendingPcm.addLast(copy)
                    totalPcmBytes += copy.size
                    true
                }
            }
            if (!accepted) {
                copy.fill(0)
                return false
            }
            scheduleDrain()
            return true
        }

        override fun finishInput(): Boolean {
            val accepted = synchronized(bufferLock) {
                if (isTerminal() || finishRequested || totalPcmBytes <= 0) {
                    false
                } else {
                    finishRequested = true
                    true
                }
            }
            if (accepted) scheduleDrain()
            return accepted
        }

        private fun scheduleDrain() {
            if (isTerminal()) return
            try {
                sendExecutor.execute(Runnable(::drain))
            } catch (_: RuntimeException) {
                fail(CloudSpeechFailureKind.INTERNAL, "搜狗流式语音发送任务启动失败")
            }
        }

        private fun drain() {
            while (!isTerminal()) {
                val socket = synchronized(bufferLock) {
                    if (!opened) return
                    webSocket ?: return
                }
                val config = synchronized(bufferLock) {
                    if (!configSent) {
                        configSent = true
                        handshake?.encryptedConfigBase64
                    } else {
                        null
                    }
                }
                if (config != null) {
                    if (!socket.send(config)) {
                        fail(CloudSpeechFailureKind.NETWORK, "搜狗流式语音配置发送失败")
                        return
                    }
                    continue
                }

                val chunk = synchronized(bufferLock) {
                    if (pendingPcm.isEmpty()) null else pendingPcm.removeFirst()
                }
                if (chunk != null) {
                    val sent = try {
                        encodeAndSend(socket, chunk)
                    } catch (_: RuntimeException) {
                        fail(CloudSpeechFailureKind.INTERNAL, "搜狗流式语音编码失败")
                        return
                    } finally {
                        chunk.fill(0)
                    }
                    if (!sent) {
                        fail(CloudSpeechFailureKind.NETWORK, "搜狗流式语音音频发送失败")
                        return
                    }
                    continue
                }

                val shouldFinish = synchronized(bufferLock) {
                    if (finishRequested && !endSent) {
                        endSent = true
                        true
                    } else {
                        false
                    }
                }
                if (!shouldFinish) return
                val flushed = try {
                    finishEncoderAndSend(socket)
                } catch (_: RuntimeException) {
                    fail(CloudSpeechFailureKind.INTERNAL, "搜狗流式语音收尾编码失败")
                    return
                }
                if (!flushed || !socket.send(END_OF_AUDIO_MESSAGE)) {
                    fail(CloudSpeechFailureKind.NETWORK, "搜狗流式语音结束包发送失败")
                }
                return
            }
        }

        private fun encodeAndSend(
            socket: WebSocket,
            pcm: ByteArray,
        ): Boolean = synchronized(encoderLock) {
            var sent = true
            encoder.append(pcm) { packet ->
                try {
                    if (sent) sent = socket.send(packet.toByteString())
                } finally {
                    packet.fill(0)
                }
            }
            sent
        }

        private fun finishEncoderAndSend(socket: WebSocket): Boolean =
            synchronized(encoderLock) {
                var sent = true
                encoder.finish { packet ->
                    try {
                        if (sent) sent = socket.send(packet.toByteString())
                    } finally {
                        packet.fill(0)
                    }
                }
                sent
            }

        override fun acceptResponseBytes(byteCount: Int): Boolean {
            if (byteCount > CloudSpeechResponseDecoder.MAX_RESPONSE_BYTES) {
                fail(
                    CloudSpeechFailureKind.RESPONSE_TOO_LARGE,
                    "搜狗流式语音单条响应超过大小限制",
                )
                return false
            }
            val total = responseBytes.addAndGet(byteCount.toLong())
            if (total > MAX_TOTAL_RESPONSE_BYTES) {
                fail(
                    CloudSpeechFailureKind.RESPONSE_TOO_LARGE,
                    "搜狗流式语音响应总量超过大小限制",
                )
                return false
            }
            return !isTerminal()
        }

        override fun partial(transcript: CloudSpeechTranscript) {
            if (isTerminal() || lastPartialText == transcript.text) return
            lastPartialText = transcript.text
            callbackExecutor.execute {
                runCatching { callback.onPartialResult(transcript) }
            }
        }

        override fun hasPartialResult(): Boolean = !lastPartialText.isNullOrBlank()

        override fun succeed(transcript: CloudSpeechTranscript) {
            complete(CloudSpeechHttpResult.Success(transcript), gracefulClose = true)
        }

        override fun fail(
            kind: CloudSpeechFailureKind,
            message: String,
            httpStatus: Int?,
        ) {
            complete(
                CloudSpeechHttpResult.Failure(
                    CloudSpeechFailure(kind, httpStatus, message),
                ),
                gracefulClose = false,
            )
        }

        override fun cancel() {
            complete(
                CloudSpeechHttpResult.Failure(
                    CloudSpeechFailure(
                        CloudSpeechFailureKind.CANCELLED,
                        message = "流式语音转写已取消",
                    ),
                ),
                gracefulClose = false,
            )
        }

        fun timeout() {
            timedOut.set(true)
            fail(CloudSpeechFailureKind.TIMEOUT, "搜狗流式语音请求超时")
        }

        fun abandonBeforeStart() {
            if (!terminal.compareAndSet(false, true)) return
            calls.remove(callId, this)
            timeoutFuture?.cancel(false)
            eraseBuffers()
        }

        private fun complete(
            result: CloudSpeechHttpResult,
            gracefulClose: Boolean,
        ) {
            if (!terminal.compareAndSet(false, true)) return
            timeoutFuture?.cancel(false)
            calls.remove(callId, this)
            if (gracefulClose) {
                webSocket?.close(NORMAL_CLOSE_CODE, "done")
            } else {
                webSocket?.cancel()
            }
            prepareFuture?.cancel(true)
            eraseBuffers()
            callbackExecutor.execute {
                runCatching { callback.onResult(result) }
            }
        }

        private fun eraseBuffers() {
            synchronized(bufferLock) {
                pendingPcm.forEach { it.fill(0) }
                pendingPcm.clear()
                handshake = null
            }
            synchronized(encoderLock) {
                encoder.close()
            }
        }
    }

    private data class PreparedAudio(
        val handshake: SogouAsrHandshake,
        val frames: List<ByteArray>,
    )

    private companion object {
        const val END_OF_AUDIO_MESSAGE = "{}"
        const val NORMAL_CLOSE_CODE = 1_000
        const val PING_INTERVAL_SECONDS = 15L
        const val MAX_TIMEOUT_MILLIS = 120_000
        const val MAX_FRAME_DELAY_MILLIS = 100L
        const val MAX_TOTAL_RESPONSE_BYTES = 2L * 1_024L * 1_024L
    }
}
