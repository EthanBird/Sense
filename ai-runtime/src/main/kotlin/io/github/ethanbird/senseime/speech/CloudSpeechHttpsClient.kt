package io.github.ethanbird.senseime.speech

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
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
import javax.net.ssl.HttpsURLConnection

sealed interface CloudSpeechHttpResult {
    data class Success(
        val transcript: CloudSpeechTranscript,
    ) : CloudSpeechHttpResult

    data class Failure(
        val failure: CloudSpeechFailure,
    ) : CloudSpeechHttpResult
}

fun interface CloudSpeechHttpCallback {
    fun onResult(result: CloudSpeechHttpResult)
}

interface CloudSpeechHttpCall {
    val callId: Long

    fun cancel()
}

/**
 * Cancellable bounded HTTPS transport for prerecorded speech.
 *
 * Redirects are disabled so credentials cannot be forwarded to another origin. Request audio and
 * the private key copy are wiped on every terminal path; neither is included in diagnostics.
 */
class CloudSpeechHttpsClient(
    callbackExecutor: Executor,
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 30_000,
    private val totalTimeoutMillis: Int = 45_000,
    private val worker: ExecutorService = Executors.newCachedThreadPool(
        NamedDaemonThreadFactory("SenseSpeechHttps"),
    ),
    private val timeoutScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            NamedDaemonThreadFactory("SenseSpeechTimeout"),
        ),
) : AutoCloseable {
    private val callbackExecutor = SerialExecutor(callbackExecutor)
    private val nextCallId = AtomicLong(0L)
    private val calls = ConcurrentHashMap<Long, CallState>()
    private val closed = AtomicBoolean(false)

    init {
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(readTimeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(totalTimeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(totalTimeoutMillis >= connectTimeoutMillis)
    }

    /**
     * Takes ownership of [request] and erases its body at completion.
     *
     * The key is copied immediately. Callers remain responsible for erasing their own array.
     */
    fun transcribe(
        request: CloudSpeechHttpRequest,
        apiKey: CharArray,
        callback: CloudSpeechHttpCallback,
    ): Result<CloudSpeechHttpCall> =
        runCatching {
            check(!closed.get()) { "HTTPS client is closed" }
            validateApiKey(apiKey)
            val id = nextCallId.incrementAndGet()
            val call = CallState(
                callId = id,
                request = request,
                apiKey = apiKey.copyOf(),
                callback = callback,
            )
            calls[id] = call
            try {
                call.timeoutFuture = timeoutScheduler.schedule(
                    { call.timeout() },
                    totalTimeoutMillis.toLong(),
                    TimeUnit.MILLISECONDS,
                )
                call.workerFuture = worker.submit { execute(call) }
            } catch (error: RuntimeException) {
                call.abandonBeforeStart()
                throw error
            }
            if (call.isTerminal()) call.workerFuture?.cancel(true)
            call
        }.onFailure {
            request.eraseBody()
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        calls.values.toList().forEach(CallState::cancel)
        worker.shutdownNow()
        timeoutScheduler.shutdownNow()
    }

    private fun execute(call: CallState) {
        if (call.isTerminal()) return
        var connection: HttpsURLConnection? = null
        try {
            val uri = URI(call.request.endpointUrl)
            if (
                uri.scheme?.lowercase(Locale.ROOT) != "https" ||
                uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null ||
                uri.rawFragment != null
            ) {
                call.fail(
                    CloudSpeechFailureKind.INVALID_CONFIGURATION,
                    "语音服务地址必须是安全的 HTTPS 地址",
                )
                return
            }
            connection = uri.toURL().openConnection() as? HttpsURLConnection
                ?: run {
                    call.fail(
                        CloudSpeechFailureKind.INVALID_CONFIGURATION,
                        "语音服务地址未使用 HTTPS",
                    )
                    return
                }
            if (!call.attach(connection)) return
            connection.requestMethod = call.request.method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setFixedLengthStreamingMode(call.request.contentLength)
            connection.setRequestProperty("Content-Type", call.request.contentType)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val authorization = call.request.authorizationScheme.headerPrefix + String(call.apiKey)
            connection.setRequestProperty("Authorization", authorization)
            connection.outputStream.use { output ->
                if (call.isTerminal()) return
                output.write(call.request.body)
                output.flush()
            }
            if (call.isTerminal()) return

            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.use {
                    runCatching { readBounded(it, MAX_ERROR_RESPONSE_BYTES) }
                }
                call.failForHttpStatus(status)
                return
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > CloudSpeechResponseDecoder.MAX_RESPONSE_BYTES) {
                call.fail(
                    CloudSpeechFailureKind.RESPONSE_TOO_LARGE,
                    "语音服务响应超过大小限制",
                    httpStatus = status,
                )
                return
            }
            val response = connection.inputStream.use {
                readBounded(it, CloudSpeechResponseDecoder.MAX_RESPONSE_BYTES)
            }
            val transcript = CloudSpeechResponseDecoder
                .decode(call.request.protocol, response)
                .getOrElse { error ->
                    val decodingError = error as? CloudSpeechResponseDecodingException
                    call.fail(
                        kind = decodingError?.failureKind ?: CloudSpeechFailureKind.PROTOCOL,
                        message = decodingError?.message ?: "无法解析语音服务响应",
                        httpStatus = status,
                    )
                    return
                }
            call.succeed(transcript)
        } catch (_: SocketTimeoutException) {
            call.fail(
                CloudSpeechFailureKind.TIMEOUT,
                "语音服务连接或响应超时",
            )
        } catch (_: ResponseTooLargeIOException) {
            call.fail(
                CloudSpeechFailureKind.RESPONSE_TOO_LARGE,
                "语音服务响应超过大小限制",
            )
        } catch (_: IOException) {
            when {
                call.wasTimedOut() -> call.fail(
                    CloudSpeechFailureKind.TIMEOUT,
                    "语音服务总请求时间超限",
                )
                !call.isTerminal() -> call.fail(
                    CloudSpeechFailureKind.NETWORK,
                    "无法连接语音服务",
                )
            }
        } catch (_: SecurityException) {
            call.fail(
                CloudSpeechFailureKind.NETWORK,
                "系统阻止了语音网络请求",
            )
        } catch (_: RuntimeException) {
            call.fail(
                CloudSpeechFailureKind.INTERNAL,
                "语音网络适配器发生内部错误",
            )
        } finally {
            connection?.disconnect()
            call.detach(connection)
        }
    }

    private fun readBounded(
        input: InputStream,
        maxBytes: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, READ_BUFFER_BYTES))
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (total > maxBytes - read) {
                throw ResponseTooLargeIOException()
            }
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun validateApiKey(apiKey: CharArray) {
        SpeechProviderCredentialPolicy.requireValid(apiKey)
    }

    private inner class CallState(
        override val callId: Long,
        val request: CloudSpeechHttpRequest,
        val apiKey: CharArray,
        val callback: CloudSpeechHttpCallback,
    ) : CloudSpeechHttpCall {
        private val terminal = AtomicBoolean(false)
        private val timedOut = AtomicBoolean(false)

        @Volatile
        var connection: HttpsURLConnection? = null

        @Volatile
        var workerFuture: Future<*>? = null

        @Volatile
        var timeoutFuture: ScheduledFuture<*>? = null

        fun attach(value: HttpsURLConnection): Boolean {
            connection = value
            if (isTerminal()) {
                value.disconnect()
                return false
            }
            return true
        }

        fun detach(value: HttpsURLConnection?) {
            if (connection === value) connection = null
        }

        fun isTerminal(): Boolean = terminal.get()

        fun wasTimedOut(): Boolean = timedOut.get()

        fun abandonBeforeStart() {
            if (!terminal.compareAndSet(false, true)) return
            timeoutFuture?.cancel(false)
            calls.remove(callId, this)
            request.eraseBody()
            apiKey.fill('\u0000')
        }

        override fun cancel() {
            connection?.disconnect()
            workerFuture?.cancel(true)
            complete(
                CloudSpeechHttpResult.Failure(
                    CloudSpeechFailure(
                        kind = CloudSpeechFailureKind.CANCELLED,
                        message = "语音转写已取消",
                    ),
                ),
            )
        }

        fun timeout() {
            timedOut.set(true)
            connection?.disconnect()
            workerFuture?.cancel(true)
            complete(
                CloudSpeechHttpResult.Failure(
                    CloudSpeechFailure(
                        kind = CloudSpeechFailureKind.TIMEOUT,
                        message = "语音服务总请求时间超限",
                    ),
                ),
            )
        }

        fun succeed(transcript: CloudSpeechTranscript) {
            complete(CloudSpeechHttpResult.Success(transcript))
        }

        fun fail(
            kind: CloudSpeechFailureKind,
            message: String,
            httpStatus: Int? = null,
        ) {
            complete(
                CloudSpeechHttpResult.Failure(
                    CloudSpeechFailure(
                        kind = kind,
                        httpStatus = httpStatus,
                        message = message,
                    ),
                ),
            )
        }

        fun failForHttpStatus(status: Int) {
            val (kind, message) = when (status) {
                401, 403 ->
                    CloudSpeechFailureKind.AUTHENTICATION to "语音 API Key 无效或无权限"
                402 ->
                    CloudSpeechFailureKind.QUOTA to "语音服务额度不足"
                408, 504 ->
                    CloudSpeechFailureKind.TIMEOUT to "语音服务响应超时"
                429 ->
                    CloudSpeechFailureKind.RATE_LIMIT to "语音服务请求过于频繁"
                in 400..499 ->
                    CloudSpeechFailureKind.INVALID_CONFIGURATION to
                        "语音服务拒绝了当前地址、模型或参数"
                in 500..599 ->
                    CloudSpeechFailureKind.SERVER to "语音服务暂时不可用"
                else ->
                    CloudSpeechFailureKind.PROTOCOL to "语音服务返回了异常状态"
            }
            fail(kind, message, httpStatus = status)
        }

        private fun complete(result: CloudSpeechHttpResult) {
            if (!terminal.compareAndSet(false, true)) return
            timeoutFuture?.cancel(false)
            calls.remove(callId, this)
            connection?.disconnect()
            request.eraseBody()
            apiKey.fill('\u0000')
            callbackExecutor.execute {
                runCatching { callback.onResult(result) }
            }
        }
    }

    private class ResponseTooLargeIOException : IOException()

    private companion object {
        const val READ_BUFFER_BYTES = 8 * 1_024
        const val MAX_ERROR_RESPONSE_BYTES = 32 * 1_024
        const val MAX_TIMEOUT_MILLIS = 120_000
    }
}
