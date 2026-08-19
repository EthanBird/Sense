package io.github.ethanbird.senseime.mic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground companion-device service that owns microphone capture independently from the IME.
 *
 * The service is intentionally demand-driven: enabling it opens only discovery/control sockets;
 * [AudioRecord] exists only while one authenticated desktop client is connected. A persistent
 * notification provides mute and stop controls for the complete background lifetime.
 */
class SenseMicService : Service() {
    private val running = AtomicBoolean(false)
    private val clientActive = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val sentPackets = AtomicLong(0)
    private val latestStats = AtomicReference(SenseMicClientStats(0, 0, 0))
    private val secureRandom = SecureRandom()
    private val authFailures = ConcurrentHashMap<InetAddress, AuthFailureWindow>()
    private val executor: ExecutorService = Executors.newCachedThreadPool(SenseMicThreadFactory())
    private val handshakeMaterialLock = Any()

    private lateinit var settingsStore: SenseMicSettingsStore
    @Volatile private var settings = SenseMicSettings()
    @Volatile private var serverKeyPair: KeyPair = SenseMicCrypto.generateP256KeyPair()
    @Volatile private var serverNonce: ByteArray = SenseMicCrypto.randomBytes(16)
    @Volatile private var deviceId: Long = 0L
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var controlSocket: ServerSocket? = null
    @Volatile private var activeClientSocket: Socket? = null
    @Volatile private var captureFuture: Future<*>? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = SenseMicSettingsStore(this)
        settings = settingsStore.load()
        deviceId = installationDeviceId()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings = settingsStore.update { it.copy(enabled = false) }
                stopRuntime()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                muted.set(!muted.get())
                publishStreamingStatus()
                updateNotification()
            }
            ACTION_RELOAD -> {
                settings = settingsStore.load()
                if (!settings.enabled) {
                    stopRuntime()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (running.get()) {
                    // A quality or pairing-code change applies at the next authenticated session.
                    // Closing the current control socket also stops AudioRecord in handleClient.finally.
                    rotateHandshakeMaterial()
                    activeClientSocket.closeQuietly()
                }
            }
            ACTION_START, null -> {
                settings = settingsStore.update { it.copy(enabled = true) }
            }
        }

        if (settings.enabled) startRuntime()
        return START_STICKY
    }

    override fun onDestroy() {
        stopRuntime()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRuntime() {
        if (!running.compareAndSet(false, true)) {
            updateNotification()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            settings = settingsStore.update { it.copy(enabled = false) }
            SenseMicRuntime.publish(
                SenseMicStatus(
                    phase = SenseMicPhase.ERROR,
                    errorMessage = "麦克风权限尚未授予",
                ),
            )
            stopSelf()
            return
        }

        rotateHandshakeMaterial()
        sentPackets.set(0)
        latestStats.set(SenseMicClientStats(0, 0, 0))
        startForegroundCompat(waitingNotification())
        SenseMicRuntime.publish(
            SenseMicStatus(
                phase = SenseMicPhase.STARTING,
                endpointSummary = localEndpointSummary(),
            ),
        )
        var discovery: DatagramSocket? = null
        var control: ServerSocket? = null
        try {
            // Bind both public endpoints before advertising WAITING. This prevents a half-started
            // service (for example TCP ready while discovery failed) from looking healthy.
            val boundDiscovery = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(SenseMicProtocol.DISCOVERY_PORT))
                soTimeout = 1_000
            }
            discovery = boundDiscovery
            val boundControl = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(SenseMicProtocol.CONTROL_PORT))
                soTimeout = 1_000
            }
            control = boundControl
            discoverySocket = boundDiscovery
            controlSocket = boundControl
            publishWaitingStatus()
            updateNotification()
            executor.execute { runDiscoveryLoop(boundDiscovery) }
            executor.execute { runControlLoop(boundControl) }
        } catch (error: Exception) {
            discovery.closeQuietly()
            control.closeQuietly()
            abortRuntime("服务端口启动失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun stopRuntime() {
        if (!running.getAndSet(false) && SenseMicRuntime.status().phase == SenseMicPhase.OFF) return
        activeClientSocket.closeQuietly()
        activeClientSocket = null
        discoverySocket.closeQuietly()
        discoverySocket = null
        controlSocket.closeQuietly()
        controlSocket = null
        captureFuture?.cancel(true)
        captureFuture = null
        clientActive.set(false)
        releaseStreamingLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        SenseMicRuntime.publish(SenseMicStatus())
    }

    private fun runDiscoveryLoop(socket: DatagramSocket) {
        try {
            val buffer = ByteArray(256)
            while (
                running.get() &&
                discoverySocket === socket &&
                !Thread.currentThread().isInterrupted
            ) {
                try {
                    val request = DatagramPacket(buffer, buffer.size)
                    socket.receive(request)
                    val requestNonce = SenseMicWireCodec.decodeDiscoveryRequest(request.data, request.length)
                        ?: continue
                    val material = snapshotHandshakeMaterial()
                    val response = SenseMicWireCodec.encodeDiscoveryResponse(
                        SenseMicDiscoveryResponse(
                            controlPort = SenseMicProtocol.CONTROL_PORT,
                            deviceId = deviceId,
                            requestNonce = requestNonce,
                            serverNonce = material.nonce,
                            serverPublicKey = material.keyPair.public.encoded,
                            deviceName = Build.MODEL.take(63).ifBlank { "Sense Android" },
                        ),
                    )
                    socket.send(DatagramPacket(response, response.size, request.address, request.port))
                } catch (_: SocketTimeoutException) {
                    // Periodic cancellation point.
                }
            }
        } catch (error: Exception) {
            if (running.get() && discoverySocket === socket) {
                abortRuntime("发现服务已结束：${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            socket.closeQuietly()
            if (discoverySocket === socket) discoverySocket = null
        }
    }

    private fun runControlLoop(server: ServerSocket) {
        try {
            while (
                running.get() &&
                controlSocket === server &&
                !Thread.currentThread().isInterrupted
            ) {
                try {
                    val socket = server.accept().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        soTimeout = CONTROL_READ_TIMEOUT_MILLIS
                    }
                    if (!clientActive.compareAndSet(false, true)) {
                        sendError(socket.getOutputStream(), 2, "Sense Mic 正在服务另一台电脑")
                        socket.closeQuietly()
                        continue
                    }
                    executor.execute { handleClient(socket) }
                } catch (_: SocketTimeoutException) {
                    // Periodic cancellation point.
                }
            }
        } catch (error: Exception) {
            if (running.get() && controlSocket === server) {
                abortRuntime("控制服务已结束：${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            server.closeQuietly()
            if (controlSocket === server) controlSocket = null
        }
    }

    /** Keep the foreground error visible and make ACTION_RELOAD able to start a fresh runtime. */
    private fun abortRuntime(message: String) {
        if (!running.getAndSet(false)) return
        activeClientSocket.closeQuietly()
        activeClientSocket = null
        discoverySocket.closeQuietly()
        controlSocket.closeQuietly()
        captureFuture?.cancel(true)
        captureFuture = null
        clientActive.set(false)
        releaseStreamingLocks()
        publishError(message)
    }

    private fun handleClient(socket: Socket) {
        var sessionKey: ByteArray? = null
        var pairKey: ByteArray? = null
        var transcript: ByteArray? = null
        try {
            activeClientSocket = socket
            SenseMicRuntime.publish(
                SenseMicStatus(
                    phase = SenseMicPhase.AUTHENTICATING,
                    endpointSummary = localEndpointSummary(),
                ),
            )
            updateNotification()

            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())
            val (type, payload) = readControlFrame(input) ?: error("握手帧缺失")
            require(type == SenseMicControlType.HELLO) { "首个控制帧必须是 HELLO" }
            val hello = requireNotNull(SenseMicWireCodec.decodeClientHello(payload)) { "HELLO 格式错误" }
            enforceAuthRate(socket.inetAddress)

            val currentSettings = settingsStore.load().also { settings = it }
            val material = snapshotHandshakeMaterial()
            val code = currentSettings.pairCode.toCharArray()
            pairKey = try {
                SenseMicCrypto.derivePairKey(code, material.nonce, hello.clientNonce)
            } finally {
                code.fill('\u0000')
            }
            transcript = SenseMicCrypto.transcript(
                serverPublicKey = material.keyPair.public.encoded,
                clientPublicKey = hello.clientPublicKey,
                serverNonce = material.nonce,
                clientNonce = hello.clientNonce,
            )
            val expectedProof = SenseMicCrypto.proof(pairKey, "client", transcript)
            if (!SenseMicCrypto.verifyProof(expectedProof, hello.proof)) {
                registerAuthFailure(socket.inetAddress)
                sendError(output, 1, "配对码校验失败")
                return
            }
            clearAuthFailure(socket.inetAddress)
            val clientPublicKey = SenseMicCrypto.decodeP256PublicKey(hello.clientPublicKey)
            sessionKey = SenseMicCrypto.deriveSessionKey(
                privateKey = material.keyPair.private,
                peerPublicKey = clientPublicKey,
                pairKey = pairKey,
                transcript = transcript,
            )
            val sessionId = secureRandom.nextInt().let { if (it == 0) 1 else it }
            val welcome = SenseMicWelcome(
                sessionId = sessionId,
                sampleRate = SenseMicProtocol.DEFAULT_SAMPLE_RATE,
                frameSamples = SenseMicProtocol.DEFAULT_FRAME_SAMPLES,
                channels = SenseMicProtocol.CHANNELS,
                codec = SenseMicProtocol.CODEC_OPUS,
                bitrate = currentSettings.quality.bitrate,
                serverProof = SenseMicCrypto.proof(pairKey, "server", transcript),
            )
            writeControlFrame(output, SenseMicControlType.WELCOME, SenseMicWireCodec.encodeWelcome(welcome))

            val destination = InetSocketAddress(socket.inetAddress, hello.udpPort)
            val streamKey = sessionKey.copyOf()
            captureFuture = executor.submit {
                runCatching {
                    runCaptureLoop(
                        clientName = hello.clientName,
                        destination = destination,
                        sessionId = sessionId,
                        sessionKey = streamKey,
                        streamSettings = currentSettings,
                    )
                }.onFailure { error ->
                    if (running.get() && clientActive.get()) {
                        publishError(
                            "麦克风传输已结束：${error.message ?: error.javaClass.simpleName}",
                            recoverable = true,
                        )
                        socket.closeQuietly()
                    }
                }
            }

            while (running.get() && !socket.isClosed) {
                val frame = readControlFrame(input) ?: break
                when (frame.first) {
                    SenseMicControlType.PING -> {
                        SenseMicWireCodec.decodeStats(frame.second)?.let(latestStats::set)
                        publishStreamingStatus(hello.clientName)
                        writeControlFrame(output, SenseMicControlType.PONG)
                    }
                    SenseMicControlType.STOP -> break
                    else -> Unit
                }
            }
        } catch (_: SocketTimeoutException) {
            // Missing heartbeats terminate the client and release the microphone.
        } catch (error: Exception) {
            if (running.get()) {
                publishError("电脑连接已结束：${error.message ?: error.javaClass.simpleName}", recoverable = true)
            }
        } finally {
            captureFuture?.cancel(true)
            captureFuture = null
            socket.closeQuietly()
            activeClientSocket = null
            sessionKey?.fill(0)
            pairKey?.fill(0)
            transcript?.fill(0)
            clientActive.set(false)
            releaseStreamingLocks()
            if (running.get()) {
                muted.set(false)
                publishWaitingStatus()
                updateNotification()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCaptureLoop(
        clientName: String,
        destination: InetSocketAddress,
        sessionId: Int,
        sessionKey: ByteArray,
        streamSettings: SenseMicSettings,
    ) {
        var recorder: AudioRecord? = null
        var udp: DatagramSocket? = null
        val pcmFrame = ShortArray(SenseMicProtocol.DEFAULT_FRAME_SAMPLES)
        val encoded = ByteArray(SenseMicProtocol.MAX_AUDIO_PAYLOAD_BYTES)
        val parity = ByteArray(SenseMicProtocol.MAX_AUDIO_PAYLOAD_BYTES)
        try {
            acquireStreamingLocks()
            val minimum = AudioRecord.getMinBufferSize(
                SenseMicProtocol.DEFAULT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minimum > 0) { "设备不支持 48 kHz 单声道录音" }
            recorder = AudioRecord.Builder()
                .setAudioSource(resolveAudioSource(streamSettings.captureProfile))
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SenseMicProtocol.DEFAULT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum * 2, pcmFrame.size * 2 * 4))
                .build()
            require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }

            val encoder = OpusEncoder(
                SenseMicProtocol.DEFAULT_SAMPLE_RATE,
                SenseMicProtocol.CHANNELS,
                OpusApplication.OPUS_APPLICATION_VOIP,
            ).apply {
                bitrate = streamSettings.quality.bitrate
                useVBR = false
                useInbandFEC = true
                packetLossPercent = 8
                complexity = 8
                useDTX = false
            }
            udp = DatagramSocket().apply {
                sendBufferSize = 64 * 1024
                connect(destination)
            }
            recorder.startRecording()
            publishStreamingStatus(clientName)
            updateNotification()

            var frameSequence = 0
            var packetCounter = 0L
            var timestampSamples = 0L
            var parityCount = 0
            var parityLength = 0
            var parityStartSequence = 0
            var parityStartTimestamp = 0L
            while (running.get() && clientActive.get() && !Thread.currentThread().isInterrupted) {
                var filled = 0
                while (filled < pcmFrame.size && running.get() && clientActive.get()) {
                    val read = recorder.read(
                        pcmFrame,
                        filled,
                        pcmFrame.size - filled,
                        AudioRecord.READ_BLOCKING,
                    )
                    require(read >= 0) { "麦克风读取失败：$read" }
                    if (read == 0) continue
                    filled += read
                }
                if (filled != pcmFrame.size) break
                if (muted.get()) pcmFrame.fill(0)

                val encodedLength = encoder.encode(
                    pcmFrame,
                    0,
                    pcmFrame.size,
                    encoded,
                    0,
                    encoded.size,
                )
                require(encodedLength in 1..SenseMicProtocol.MAX_AUDIO_PAYLOAD_BYTES)
                val payload = encoded.copyOf(encodedLength)
                sendEncryptedDatagram(
                    socket = udp,
                    sessionKey = sessionKey,
                    destination = destination,
                    header = SenseMicAudioHeader(
                        kind = SenseMicAudioKind.AUDIO,
                        sessionId = sessionId,
                        packetCounter = packetCounter++,
                        frameSequence = frameSequence,
                        timestampSamples = timestampSamples,
                        payloadLength = payload.size,
                        fecGroupSize = 0,
                    ),
                    payload = payload,
                )

                if (parityCount == 0 || parityLength != payload.size) {
                    parity.fill(0)
                    parityLength = payload.size
                    parityStartSequence = frameSequence
                    parityStartTimestamp = timestampSamples
                    parityCount = 0
                }
                payload.indices.forEach { index ->
                    parity[index] = (parity[index].toInt() xor payload[index].toInt()).toByte()
                }
                parityCount++
                if (parityCount == SenseMicProtocol.FEC_GROUP_SIZE) {
                    val fecPayload = parity.copyOf(parityLength)
                    sendEncryptedDatagram(
                        socket = udp,
                        sessionKey = sessionKey,
                        destination = destination,
                        header = SenseMicAudioHeader(
                            kind = SenseMicAudioKind.XOR_FEC,
                            sessionId = sessionId,
                            packetCounter = packetCounter++,
                            frameSequence = parityStartSequence,
                            timestampSamples = parityStartTimestamp,
                            payloadLength = fecPayload.size,
                            fecGroupSize = SenseMicProtocol.FEC_GROUP_SIZE,
                        ),
                        payload = fecPayload,
                    )
                    parityCount = 0
                }

                frameSequence++
                timestampSamples += SenseMicProtocol.DEFAULT_FRAME_SAMPLES
                pcmFrame.fill(0)
                payload.fill(0)
            }
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            udp.closeQuietly()
            pcmFrame.fill(0)
            encoded.fill(0)
            parity.fill(0)
            sessionKey.fill(0)
            releaseStreamingLocks()
        }
    }

    private fun sendEncryptedDatagram(
        socket: DatagramSocket,
        sessionKey: ByteArray,
        destination: InetSocketAddress,
        header: SenseMicAudioHeader,
        payload: ByteArray,
    ) {
        val packet = SenseMicCrypto.encryptAudio(sessionKey, header, payload).datagram
        socket.send(DatagramPacket(packet, packet.size, destination))
        sentPackets.incrementAndGet()
    }

    private fun readControlFrame(input: DataInputStream): Pair<SenseMicControlType, ByteArray>? {
        val header = ByteArray(12)
        return try {
            input.readFully(header)
            val decoded = SenseMicWireCodec.decodeControlFrameHeader(header) ?: error("控制帧头错误")
            val payload = ByteArray(decoded.second)
            input.readFully(payload)
            decoded.first to payload
        } catch (_: java.io.EOFException) {
            null
        }
    }

    private fun writeControlFrame(
        output: OutputStream,
        type: SenseMicControlType,
        payload: ByteArray = byteArrayOf(),
    ) {
        output.write(SenseMicWireCodec.encodeControlFrame(type, payload))
        output.flush()
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val text = message.encodeToByteArray().take(240).toByteArray()
        val payload = byteArrayOf((code ushr 8).toByte(), code.toByte(), text.size.toByte()) + text
        runCatching { writeControlFrame(output, SenseMicControlType.ERROR, payload) }
    }

    private fun resolveAudioSource(profile: SenseMicCaptureProfile): Int = when (profile) {
        SenseMicCaptureProfile.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        SenseMicCaptureProfile.UNPROCESSED -> {
            val supported = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) &&
                getSystemService(AudioManager::class.java)
                    .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            if (supported) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun waitingNotification(): Notification = buildNotification(SenseMicPhase.WAITING, null)

    private fun updateNotification() {
        val status = SenseMicRuntime.status()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status.phase, status.clientName))
    }

    private fun buildNotification(phase: SenseMicPhase, clientName: String?): Notification {
        val contentText = when (phase) {
            SenseMicPhase.STREAMING -> getString(R.string.sense_mic_notification_connected, clientName ?: "电脑")
            SenseMicPhase.MUTED -> getString(R.string.sense_mic_notification_muted)
            else -> getString(R.string.sense_mic_notification_waiting)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(SETTINGS_SECTION_EXTRA, SETTINGS_MIC_SECTION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SenseMicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val muteIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SenseMicService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sense Mic")
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_lock_silent_mode,
                getString(if (muted.get()) R.string.sense_mic_action_unmute else R.string.sense_mic_action_mute),
                muteIntent,
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.sense_mic_action_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.sense_mic_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.sense_mic_service_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun publishWaitingStatus() {
        if (!running.get() || clientActive.get()) return
        SenseMicRuntime.publish(
            SenseMicStatus(
                phase = SenseMicPhase.WAITING,
                endpointSummary = localEndpointSummary(),
                sentPackets = sentPackets.get(),
            ),
        )
    }

    private fun publishStreamingStatus(clientName: String? = SenseMicRuntime.status().clientName) {
        val stats = latestStats.get()
        SenseMicRuntime.publish(
            SenseMicStatus(
                phase = if (muted.get()) SenseMicPhase.MUTED else SenseMicPhase.STREAMING,
                clientName = clientName,
                endpointSummary = localEndpointSummary(),
                sentPackets = sentPackets.get(),
                receivedPackets = stats.receivedPackets,
                lostPackets = stats.lostPackets,
                jitterMillis = stats.jitterMillis,
            ),
        )
    }

    private fun publishError(message: String, recoverable: Boolean = false) {
        SenseMicRuntime.publish(
            SenseMicStatus(
                phase = if (recoverable) SenseMicPhase.WAITING else SenseMicPhase.ERROR,
                endpointSummary = localEndpointSummary(),
                errorMessage = message,
            ),
        )
        updateNotification()
    }

    private fun localEndpointSummary(): String {
        val addresses = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
                .sorted()
                .toList()
        }.getOrDefault(emptyList())
        return if (addresses.isEmpty()) {
            "UDP ${SenseMicProtocol.DISCOVERY_PORT} · TCP ${SenseMicProtocol.CONTROL_PORT}"
        } else {
            addresses.joinToString(" / ") { "$it:${SenseMicProtocol.CONTROL_PORT}" }
        }
    }

    private fun snapshotHandshakeMaterial(): HandshakeMaterial = synchronized(handshakeMaterialLock) {
        HandshakeMaterial(serverKeyPair, serverNonce.copyOf())
    }

    private fun rotateHandshakeMaterial() {
        val replacementKeyPair = SenseMicCrypto.generateP256KeyPair()
        val replacementNonce = SenseMicCrypto.randomBytes(16)
        val previousNonce = synchronized(handshakeMaterialLock) {
            val previous = serverNonce
            serverKeyPair = replacementKeyPair
            serverNonce = replacementNonce
            previous
        }
        previousNonce.fill(0)
    }

    private fun installationDeviceId(): Long {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: packageName
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$packageName:$androidId".encodeToByteArray())
        return java.nio.ByteBuffer.wrap(digest).long
    }

    private fun enforceAuthRate(address: InetAddress) {
        val window = authFailures[address] ?: return
        val now = System.currentTimeMillis()
        if (now - window.startedAtMillis > AUTH_WINDOW_MILLIS) {
            authFailures.remove(address)
            return
        }
        if (window.failures >= AUTH_MAX_FAILURES) {
            Thread.sleep(AUTH_LOCKOUT_MILLIS)
            error("配对尝试过于频繁")
        }
    }

    private fun registerAuthFailure(address: InetAddress) {
        val now = System.currentTimeMillis()
        authFailures.compute(address) { _, previous ->
            if (previous == null || now - previous.startedAtMillis > AUTH_WINDOW_MILLIS) {
                AuthFailureWindow(now, 1)
            } else {
                previous.copy(failures = previous.failures + 1)
            }
        }
        Thread.sleep(AUTH_FAILURE_DELAY_MILLIS)
    }

    private fun clearAuthFailure(address: InetAddress) {
        authFailures.remove(address)
    }

    private fun acquireStreamingLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:SenseMic")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifiManager.createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$packageName:SenseMic",
            ).apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseStreamingLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private data class HandshakeMaterial(val keyPair: KeyPair, val nonce: ByteArray)

    private data class AuthFailureWindow(val startedAtMillis: Long, val failures: Int)

    private class SenseMicThreadFactory : ThreadFactory {
        private val nextId = AtomicLong(1)
        override fun newThread(task: Runnable): Thread =
            Thread(task, "Sense-Mic-${nextId.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
    }

    companion object {
        const val ACTION_START = "io.github.ethanbird.senseime.mic.START"
        const val ACTION_STOP = "io.github.ethanbird.senseime.mic.STOP"
        const val ACTION_TOGGLE_MUTE = "io.github.ethanbird.senseime.mic.TOGGLE_MUTE"
        const val ACTION_RELOAD = "io.github.ethanbird.senseime.mic.RELOAD"
        private const val NOTIFICATION_CHANNEL_ID = "sense_mic_service_v1"
        private const val NOTIFICATION_ID = 52_173
        private const val CONTROL_READ_TIMEOUT_MILLIS = 5_000
        private const val AUTH_MAX_FAILURES = 5
        private const val AUTH_WINDOW_MILLIS = 60_000L
        private const val AUTH_LOCKOUT_MILLIS = 1_500L
        private const val AUTH_FAILURE_DELAY_MILLIS = 300L
        private const val SETTINGS_SECTION_EXTRA =
            "io.github.ethanbird.senseime.extra.INITIAL_SETTINGS_SECTION"
        private const val SETTINGS_MIC_SECTION = "MIC"
    }
}

private fun Socket?.closeQuietly() {
    runCatching { this?.close() }
}

private fun ServerSocket?.closeQuietly() {
    runCatching { this?.close() }
}

private fun DatagramSocket?.closeQuietly() {
    runCatching { this?.close() }
}
