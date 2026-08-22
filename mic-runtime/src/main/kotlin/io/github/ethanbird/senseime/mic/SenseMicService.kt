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
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val sentPackets = AtomicLong(0)
    private val discoveryReceivedPackets = AtomicLong(0)
    private val discoveryRejectedPackets = AtomicLong(0)
    private val discoveryResponses = AtomicLong(0)
    private val latestStats = AtomicReference(SenseMicClientStats(0, 0, 0))
    private val secureRandom = SecureRandom()
    private val authFailures = ConcurrentHashMap<InetAddress, AuthFailureWindow>()
    private val executor: ExecutorService = Executors.newCachedThreadPool(SenseMicThreadFactory())
    private val lifecycleExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(SenseMicLifecycleThreadFactory())
    private val handshakeMaterialLock = Any()
    private val nextClientId = AtomicLong(1)

    private lateinit var settingsStore: SenseMicSettingsStore
    @Volatile private var settings = SenseMicSettings()
    @Volatile private var serverKeyPair: KeyPair = SenseMicCrypto.generateP256KeyPair()
    @Volatile private var serverNonce: ByteArray = SenseMicCrypto.randomBytes(16)
    @Volatile private var deviceId: Long = 0L
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var discoveryEndpoint: SenseMicDiscoveryEndpoint? = null
    @Volatile private var controlSocket: ServerSocket? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var discoveryLockGeneration: Long? = null
    @Volatile private var activeLanNetwork: Network? = null
    @Volatile private var activeLanAddresses: List<String> = emptyList()
    @Volatile private var runtimeGeneration: Long? = null
    @Volatile private var activeClient: ClientOwner? = null
    @Volatile private var streamingLocksClient: ClientOwner? = null
    @Volatile private var destroying = false
    private val pendingClientSockets = LinkedHashMap<Socket, ClientOwner>()
    private val rebindGate = SenseMicRebindGate()
    private val runtimeTransitionGate = SenseMicRuntimeTransitionGate()
    private var pendingNetworkRebind: ScheduledFuture<*>? = null
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            requestNetworkRebind("available:${network.networkHandle}")
        }

        override fun onLost(network: Network) {
            requestNetworkRebind("lost:${network.networkHandle}")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            requestNetworkRebind("capabilities:${network.networkHandle}")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            requestNetworkRebind("link-properties:${network.networkHandle}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SenseMicSettingsStore(this)
        settings = settingsStore.load()
        deviceId = installationDeviceId()
        createNotificationChannel()
        registerLanNetworkCallback()
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
                activeClient?.let(::publishStreamingStatus)
                updateNotification()
            }
            ACTION_RELOAD -> {
                val previous = settings
                val reloaded = settingsStore.load()
                settings = reloaded
                if (!reloaded.enabled) {
                    stopRuntime()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (running.get() && reloaded != previous) {
                    requestRuntimeRestart("settings changed")
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
        synchronized(lifecycleLock) {
            destroying = true
            pendingNetworkRebind?.cancel(false)
            pendingNetworkRebind = null
        }
        unregisterLanNetworkCallback()
        stopRuntime()
        lifecycleExecutor.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRuntime(forceRestart: Boolean = false, expectedGeneration: Long? = null) {
        runtimeTransitionGate.run {
            startRuntimeTransition(forceRestart, expectedGeneration)
        }
    }

    private fun startRuntimeTransition(forceRestart: Boolean, expectedGeneration: Long?) {
        if (destroying) return
        if (!forceRestart && running.get()) {
            updateNotification()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            settings = settingsStore.update { it.copy(enabled = false) }
            val detached = synchronized(lifecycleLock) {
                running.set(false)
                val resources = detachRuntimeLocked()
                runtimeGeneration = null
                SenseMicRuntime.reset(
                    SenseMicStatus(
                        phase = SenseMicPhase.ERROR,
                        errorMessage = "麦克风权限尚未授予",
                    ),
                )
                resources
            }
            closeDetachedRuntime(detached)
            stopSelf()
            return
        }

        val detached: DetachedRuntime
        val generation: Long
        synchronized(lifecycleLock) {
            if (destroying || !settings.enabled) return
            if (expectedGeneration != null && runtimeGeneration != expectedGeneration) return
            if (!forceRestart && running.get()) return
            detached = detachRuntimeLocked()
            generation = SenseMicRuntime.begin(
                SenseMicStatus(
                    phase = SenseMicPhase.STARTING,
                    endpointSummary = localEndpointSummary(),
                ),
            )
            runtimeGeneration = generation
            running.set(true)
            muted.set(false)
            sentPackets.set(0)
            discoveryReceivedPackets.set(0)
            discoveryRejectedPackets.set(0)
            discoveryResponses.set(0)
            latestStats.set(SenseMicClientStats(0, 0, 0))
        }
        closeDetachedRuntime(detached)
        rotateHandshakeMaterial()
        startForegroundCompat(waitingNotification())
        var discovery: DatagramSocket? = null
        var endpoint: SenseMicDiscoveryEndpoint? = null
        var control: ServerSocket? = null
        try {
            val selectedLan = selectLanNetwork()
            if (!isCurrentRuntime(generation)) return
            if (selectedLan == null) {
                Log.w(TAG, "No physical LAN network found; endpoints remain unbound")
                SenseMicRuntime.publish(
                    generation,
                    SenseMicStatus(
                        phase = SenseMicPhase.STARTING,
                        endpointSummary = "等待 Wi-Fi 或以太网",
                        errorMessage = "局域网尚未就绪",
                    ),
                )
                updateNotification()
                return
            }
            Log.i(
                TAG,
                "Selected ${selectedLan.transportLabel} network=${selectedLan.network.networkHandle} " +
                    "addresses=${selectedLan.addresses.joinToString()}",
            )

            // Bind both public endpoints before advertising WAITING. This prevents a half-started
            // service (for example TCP ready while discovery failed) from looking healthy.
            val boundDiscovery = SenseMicDiscoveryEndpoint.openSocket(
                port = SenseMicProtocol.DISCOVERY_PORT,
                bindToNetwork = { socket -> selectedLan.network.bindSocket(socket) },
            )
            discovery = boundDiscovery
            val boundEndpoint = SenseMicDiscoveryEndpoint(
                socket = boundDiscovery,
                createResponse = { nonce -> createDiscoveryResponse(generation, nonce) },
                observer = discoveryObserver(generation),
            )
            endpoint = boundEndpoint
            val boundControl = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(IPV4_ANY_ADDRESS, SenseMicProtocol.CONTROL_PORT))
                soTimeout = 1_000
            }
            control = boundControl
            val installed = synchronized(lifecycleLock) {
                if (!isCurrentRuntimeLocked(generation)) {
                    false
                } else {
                    activeLanNetwork = selectedLan.network
                    activeLanAddresses = selectedLan.addresses
                    discoverySocket = boundDiscovery
                    discoveryEndpoint = boundEndpoint
                    controlSocket = boundControl
                    acquireDiscoveryLockLocked(generation)
                    true
                }
            }
            if (!installed) {
                boundEndpoint.close()
                boundDiscovery.closeQuietly()
                boundControl.closeQuietly()
                return
            }
            Log.i(
                TAG,
                "Discovery UDP bound on ${boundDiscovery.localSocketAddress}; " +
                    "control TCP bound on ${boundControl.localSocketAddress}",
            )
            publishWaitingStatus(generation)
            updateNotification()
            executor.execute { runDiscoveryLoop(generation, boundEndpoint, boundDiscovery) }
            executor.execute { runControlLoop(generation, boundControl) }
        } catch (error: Exception) {
            endpoint?.close()
            discovery.closeQuietly()
            control.closeQuietly()
            Log.e(TAG, "Sense Mic endpoint startup failed", error)
            abortRuntime(generation, "服务端口启动失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun stopRuntime() {
        runtimeTransitionGate.run(::stopRuntimeTransition)
    }

    private fun stopRuntimeTransition() {
        val detached = synchronized(lifecycleLock) {
            pendingNetworkRebind?.cancel(false)
            pendingNetworkRebind = null
            rebindGate.cancel()
            running.set(false)
            val resources = detachRuntimeLocked()
            runtimeGeneration = null
            SenseMicRuntime.reset()
            resources
        }
        closeDetachedRuntime(detached)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun runDiscoveryLoop(
        generation: Long,
        endpoint: SenseMicDiscoveryEndpoint,
        socket: DatagramSocket,
    ) {
        try {
            endpoint.run {
                isCurrentRuntime(generation) &&
                    discoverySocket === socket &&
                    discoveryEndpoint === endpoint
            }
        } catch (error: Exception) {
            if (isCurrentRuntime(generation) && discoverySocket === socket) {
                Log.e(TAG, "Discovery endpoint terminated", error)
                abortRuntime(generation, "发现服务已结束：${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            endpoint.close()
            synchronized(lifecycleLock) {
                if (runtimeGeneration == generation && discoverySocket === socket) discoverySocket = null
                if (runtimeGeneration == generation && discoveryEndpoint === endpoint) discoveryEndpoint = null
            }
        }
    }

    private fun runControlLoop(generation: Long, server: ServerSocket) {
        try {
            while (
                isCurrentRuntime(generation) &&
                    controlSocket === server &&
                    !Thread.currentThread().isInterrupted
            ) {
                try {
                    val socket = server.accept().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        soTimeout = CONTROL_READ_TIMEOUT_MILLIS
                    }
                    val reservation = synchronized(lifecycleLock) {
                        if (!isCurrentRuntimeLocked(generation) || controlSocket !== server) {
                            null to false
                        } else if (activeClient != null || pendingClientSockets.isNotEmpty()) {
                            null to true
                        } else {
                            val owner = ClientOwner(generation, nextClientId.getAndIncrement(), socket)
                            pendingClientSockets[socket] = owner
                            owner to false
                        }
                    }
                    val client = reservation.first
                    if (client == null) {
                        if (reservation.second) {
                            sendError(socket.getOutputStream(), 2, "Sense Mic 正在服务另一台电脑")
                        }
                        socket.closeQuietly()
                        continue
                    }
                    try {
                        executor.execute { beginPendingClient(client) }
                    } catch (error: RejectedExecutionException) {
                        synchronized(lifecycleLock) { pendingClientSockets.remove(socket) }
                        sendError(socket.getOutputStream(), 2, "Sense Mic 正在服务另一台电脑")
                        socket.closeQuietly()
                        throw error
                    }
                } catch (_: SocketTimeoutException) {
                    // Periodic cancellation point.
                }
            }
        } catch (error: Exception) {
            if (isCurrentRuntime(generation) && controlSocket === server) {
                abortRuntime(generation, "控制服务已结束：${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            server.closeQuietly()
            synchronized(lifecycleLock) {
                if (runtimeGeneration == generation && controlSocket === server) controlSocket = null
            }
        }
    }

    /** Keep the foreground error visible and make ACTION_RELOAD able to start a fresh runtime. */
    private fun abortRuntime(generation: Long, message: String) {
        val aborted = runtimeTransitionGate.run {
            val detached = synchronized(lifecycleLock) {
                if (!isCurrentRuntimeLocked(generation)) return@run false
                running.set(false)
                val resources = detachRuntimeLocked()
                runtimeGeneration = null
                SenseMicRuntime.finish(
                    generation,
                    SenseMicStatus(
                        phase = SenseMicPhase.ERROR,
                        endpointSummary = resources.endpointSummary,
                        discoveryReceivedPackets = discoveryReceivedPackets.get(),
                        discoveryRejectedPackets = discoveryRejectedPackets.get(),
                        discoveryResponses = discoveryResponses.get(),
                        errorMessage = message,
                    ),
                )
                resources
            }
            closeDetachedRuntime(detached)
            updateNotification()
            true
        }
        if (aborted) requestRuntimeRecovery("endpoint failure")
    }

    private fun beginPendingClient(client: ClientOwner) {
        val claimed = synchronized(lifecycleLock) {
            pendingClientSockets.remove(client.socket)
            if (!isCurrentRuntimeLocked(client.generation) || activeClient != null) {
                false
            } else {
                activeClient = client
                true
            }
        }
        if (claimed) handleClient(client) else client.socket.closeQuietly()
    }

    private fun handleClient(client: ClientOwner) {
        val socket = client.socket
        var sessionKey: ByteArray? = null
        var pairKey: ByteArray? = null
        var transcript: ByteArray? = null
        try {
            if (!isCurrentClient(client)) return
            publishForClient(
                client,
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
            val streamNetwork = selectLanNetwork(socket.inetAddress)?.network ?: activeLanNetwork
            val streamKey = sessionKey.copyOf()
            val future = executor.submit {
                runCatching {
                    runCaptureLoop(
                        client = client,
                        clientName = hello.clientName,
                        destination = destination,
                        sessionId = sessionId,
                        sessionKey = streamKey,
                        streamSettings = currentSettings,
                        streamNetwork = streamNetwork,
                    )
                }.onFailure { error ->
                    if (isCurrentClient(client)) {
                        publishErrorForClient(
                            client,
                            "麦克风传输已结束：${error.message ?: error.javaClass.simpleName}",
                        )
                        socket.closeQuietly()
                    }
                }
            }
            val futureInstalled = synchronized(lifecycleLock) {
                if (activeClient === client && isCurrentRuntimeLocked(client.generation)) {
                    client.captureFuture = future
                    true
                } else {
                    false
                }
            }
            if (!futureInstalled) future.cancel(true)

            while (isCurrentClient(client) && !socket.isClosed) {
                val frame = readControlFrame(input) ?: break
                when (frame.first) {
                    SenseMicControlType.PING -> {
                        SenseMicWireCodec.decodeStats(frame.second)?.let { stats ->
                            synchronized(lifecycleLock) {
                                if (activeClient === client && isCurrentRuntimeLocked(client.generation)) {
                                    latestStats.set(stats)
                                }
                            }
                        }
                        publishStreamingStatus(client, hello.clientName)
                        writeControlFrame(output, SenseMicControlType.PONG)
                    }
                    SenseMicControlType.STOP -> break
                    else -> Unit
                }
            }
        } catch (_: SocketTimeoutException) {
            // Missing heartbeats terminate the client and release the microphone.
        } catch (error: Exception) {
            if (isCurrentClient(client)) {
                publishErrorForClient(client, "电脑连接已结束：${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            client.captureFuture?.cancel(true)
            client.captureFuture = null
            socket.closeQuietly()
            sessionKey?.fill(0)
            pairKey?.fill(0)
            transcript?.fill(0)
            releaseStreamingLocks(client)
            val releasedCurrent = synchronized(lifecycleLock) {
                if (activeClient === client) {
                    activeClient = null
                    muted.set(false)
                    isCurrentRuntimeLocked(client.generation)
                } else {
                    false
                }
            }
            if (releasedCurrent) {
                publishWaitingStatus(client.generation)
                updateNotification()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCaptureLoop(
        client: ClientOwner,
        clientName: String,
        destination: InetSocketAddress,
        sessionId: Int,
        sessionKey: ByteArray,
        streamSettings: SenseMicSettings,
        streamNetwork: Network?,
    ) {
        var recorder: AudioRecord? = null
        var udp: DatagramSocket? = null
        val pcmFrame = ShortArray(SenseMicProtocol.DEFAULT_FRAME_SAMPLES)
        val encoded = ByteArray(SenseMicProtocol.MAX_AUDIO_PAYLOAD_BYTES)
        val parity = ByteArray(SenseMicProtocol.MAX_AUDIO_PAYLOAD_BYTES)
        try {
            if (!acquireStreamingLocks(client)) return
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
            udp = DatagramSocket(null).apply {
                streamNetwork?.bindSocket(this)
                bind(InetSocketAddress(IPV4_ANY_ADDRESS, 0))
                sendBufferSize = 64 * 1024
                connect(destination)
            }
            Log.i(
                TAG,
                "Audio UDP connected to $destination via network=" +
                    (streamNetwork?.networkHandle?.toString() ?: "system-default"),
            )
            recorder.startRecording()
            publishStreamingStatus(client, clientName)
            updateNotification()

            var frameSequence = 0
            var packetCounter = 0L
            var timestampSamples = 0L
            var parityCount = 0
            var parityLength = 0
            var parityStartSequence = 0
            var parityStartTimestamp = 0L
            while (isCurrentClient(client) && !Thread.currentThread().isInterrupted) {
                var filled = 0
                while (filled < pcmFrame.size && isCurrentClient(client)) {
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
                    generation = client.generation,
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
                        generation = client.generation,
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
            releaseStreamingLocks(client)
        }
    }

    private fun sendEncryptedDatagram(
        generation: Long,
        socket: DatagramSocket,
        sessionKey: ByteArray,
        destination: InetSocketAddress,
        header: SenseMicAudioHeader,
        payload: ByteArray,
    ) {
        val packet = SenseMicCrypto.encryptAudio(sessionKey, header, payload).datagram
        socket.send(DatagramPacket(packet, packet.size, destination))
        synchronized(lifecycleLock) {
            if (isCurrentRuntimeLocked(generation)) sentPackets.incrementAndGet()
        }
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

    private fun publishWaitingStatus(generation: Long) {
        synchronized(lifecycleLock) {
            if (!isCurrentRuntimeLocked(generation) || activeClient != null || pendingClientSockets.isNotEmpty()) return
            SenseMicRuntime.publish(
                generation,
                SenseMicStatus(
                    phase = SenseMicPhase.WAITING,
                    endpointSummary = localEndpointSummary(),
                    sentPackets = sentPackets.get(),
                    discoveryReceivedPackets = discoveryReceivedPackets.get(),
                    discoveryRejectedPackets = discoveryRejectedPackets.get(),
                    discoveryResponses = discoveryResponses.get(),
                ),
            )
        }
    }

    private fun publishStreamingStatus(
        client: ClientOwner,
        clientName: String? = SenseMicRuntime.status().clientName,
    ) {
        val stats = latestStats.get()
        publishForClient(
            client,
            SenseMicStatus(
                phase = if (muted.get()) SenseMicPhase.MUTED else SenseMicPhase.STREAMING,
                clientName = clientName,
                endpointSummary = localEndpointSummary(),
                sentPackets = sentPackets.get(),
                receivedPackets = stats.receivedPackets,
                lostPackets = stats.lostPackets,
                jitterMillis = stats.jitterMillis,
                discoveryReceivedPackets = discoveryReceivedPackets.get(),
                discoveryRejectedPackets = discoveryRejectedPackets.get(),
                discoveryResponses = discoveryResponses.get(),
            ),
        )
    }

    private fun publishErrorForClient(client: ClientOwner, message: String) {
        publishForClient(
            client,
            SenseMicStatus(
                phase = SenseMicPhase.WAITING,
                endpointSummary = localEndpointSummary(),
                discoveryReceivedPackets = discoveryReceivedPackets.get(),
                discoveryRejectedPackets = discoveryRejectedPackets.get(),
                discoveryResponses = discoveryResponses.get(),
                errorMessage = message,
            ),
        )
        updateNotification()
    }

    private fun publishForClient(client: ClientOwner, status: SenseMicStatus): Boolean =
        synchronized(lifecycleLock) {
            if (activeClient !== client || !isCurrentRuntimeLocked(client.generation)) return@synchronized false
            SenseMicRuntime.publish(client.generation, status)
        }

    private fun publishDiscoveryDiagnostics(generation: Long) {
        SenseMicRuntime.update(generation) { current ->
            current.copy(
                discoveryReceivedPackets = discoveryReceivedPackets.get(),
                discoveryRejectedPackets = discoveryRejectedPackets.get(),
                discoveryResponses = discoveryResponses.get(),
            )
        }
    }

    private fun discoveryObserver(generation: Long): SenseMicDiscoveryObserver =
        object : SenseMicDiscoveryObserver {
            override fun onReceived(remote: InetSocketAddress, byteCount: Int) {
                val accepted = synchronized(lifecycleLock) {
                    if (!isCurrentRuntimeLocked(generation)) return@synchronized false
                    discoveryReceivedPackets.incrementAndGet()
                    true
                }
                if (!accepted) return
                Log.i(TAG, "Discovery datagram received from $remote ($byteCount bytes)")
            }

            override fun onRejected(remote: InetSocketAddress, byteCount: Int) {
                val rejected = synchronized(lifecycleLock) {
                    if (!isCurrentRuntimeLocked(generation)) return@synchronized null
                    discoveryRejectedPackets.incrementAndGet()
                } ?: return
                if (rejected == 1L || rejected and (rejected - 1L) == 0L) {
                    Log.w(TAG, "Rejected malformed discovery datagram #$rejected from $remote ($byteCount bytes)")
                }
                publishDiscoveryDiagnostics(generation)
            }

            override fun onResponded(remote: InetSocketAddress, byteCount: Int) {
                val accepted = synchronized(lifecycleLock) {
                    if (!isCurrentRuntimeLocked(generation)) return@synchronized false
                    discoveryResponses.incrementAndGet()
                    true
                }
                if (!accepted) return
                Log.i(TAG, "Discovery response sent to $remote ($byteCount bytes)")
                publishDiscoveryDiagnostics(generation)
            }

            override fun onFailure(
                stage: SenseMicDiscoveryStage,
                remote: InetSocketAddress?,
                error: Throwable,
            ) {
                if (!isCurrentRuntime(generation)) return
                Log.e(TAG, "Discovery $stage failed${remote?.let { " for $it" }.orEmpty()}", error)
                publishDiscoveryDiagnostics(generation)
            }
        }

    private fun createDiscoveryResponse(generation: Long, requestNonce: ByteArray): ByteArray {
        check(isCurrentRuntime(generation)) { "runtime generation expired" }
        val material = snapshotHandshakeMaterial()
        return SenseMicWireCodec.encodeDiscoveryResponse(
            SenseMicDiscoveryResponse(
                controlPort = SenseMicProtocol.CONTROL_PORT,
                deviceId = deviceId,
                requestNonce = requestNonce,
                serverNonce = material.nonce,
                serverPublicKey = material.keyPair.public.encoded,
                deviceName = fitSenseMicDiscoveryDeviceName(Build.MODEL),
            ),
        )
    }

    private fun localEndpointSummary(): String {
        val addresses = activeLanAddresses
        return if (addresses.isEmpty()) {
            "UDP ${SenseMicProtocol.DISCOVERY_PORT} · TCP ${SenseMicProtocol.CONTROL_PORT}"
        } else {
            addresses.joinToString(" / ") { "$it:${SenseMicProtocol.CONTROL_PORT}" }
        }
    }

    @Suppress("DEPRECATION")
    private fun selectLanNetwork(remoteAddress: InetAddress? = null): ActiveLanNetwork? {
        val manager = getSystemService(ConnectivityManager::class.java)
        val candidates = runCatching {
            manager.allNetworks.mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isWifi && !isEthernet) return@mapNotNull null
                val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
                SenseMicLanNetworkCandidate(
                    value = network,
                    isWifi = isWifi,
                    isEthernet = isEthernet,
                    isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    links = properties.linkAddresses.map { link ->
                        SenseMicLanLink(link.address, link.prefixLength)
                    },
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to enumerate LAN networks", error)
        }.getOrDefault(emptyList())
        val selected = SenseMicLanNetworkSelector.select(candidates, remoteAddress) ?: return null
        return ActiveLanNetwork(
            network = selected.value,
            addresses = SenseMicLanNetworkSelector.usableIpv4Addresses(selected)
                .mapNotNull { it.hostAddress }
                .sorted(),
            transportLabel = if (selected.isWifi) "Wi-Fi" else "Ethernet",
        )
    }

    private fun registerLanNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, networkCallback)
            synchronized(lifecycleLock) { networkCallbackRegistered = true }
        }.onFailure { error ->
            Log.w(TAG, "Failed to register LAN network callback", error)
        }
    }

    private fun unregisterLanNetworkCallback() {
        val registered = synchronized(lifecycleLock) {
            val value = networkCallbackRegistered
            networkCallbackRegistered = false
            value
        }
        if (!registered) return
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }.onFailure { error ->
            Log.w(TAG, "Failed to unregister LAN network callback", error)
        }
    }

    private fun requestNetworkRebind(reason: String) {
        val generation = synchronized(lifecycleLock) {
            if (destroying || !settings.enabled) return
            runtimeGeneration?.takeIf { isCurrentRuntimeLocked(it) }
        }
        scheduleRuntimeRestart(
            generation = generation,
            delayMillis = NETWORK_REBIND_DEBOUNCE_MILLIS,
            reason = "network $reason",
            skipIfLanUnchanged = true,
        )
    }

    private fun requestRuntimeRestart(reason: String) {
        val generation = synchronized(lifecycleLock) {
            if (destroying || !settings.enabled || !running.get()) return
            runtimeGeneration ?: return
        }
        scheduleRuntimeRestart(generation, 0L, reason)
    }

    private fun requestRuntimeRecovery(reason: String) {
        val recoverable = synchronized(lifecycleLock) {
            !destroying && settings.enabled && !running.get() && runtimeGeneration == null
        }
        if (recoverable) {
            scheduleRuntimeRestart(null, ENDPOINT_RETRY_MILLIS, reason)
        }
    }

    private fun scheduleRuntimeRestart(
        generation: Long?,
        delayMillis: Long,
        reason: String,
        skipIfLanUnchanged: Boolean = false,
    ) {
        synchronized(lifecycleLock) {
            if (destroying || !settings.enabled) return
            if (generation == null) {
                if (running.get() || runtimeGeneration != null) return
            } else if (!isCurrentRuntimeLocked(generation)) {
                return
            }
            val ticket = rebindGate.request(generation)
            pendingNetworkRebind?.cancel(false)
            pendingNetworkRebind = try {
                lifecycleExecutor.schedule(
                    {
                        val stillCurrent = synchronized(lifecycleLock) {
                            rebindGate.isLatest(ticket, runtimeGeneration) &&
                                !destroying &&
                                settings.enabled &&
                                if (generation == null) {
                                    !running.get() && runtimeGeneration == null
                                } else {
                                    isCurrentRuntimeLocked(generation)
                                }
                        }
                        if (stillCurrent) {
                            if (
                                generation != null &&
                                skipIfLanUnchanged &&
                                isLanBindingCurrent(generation)
                            ) {
                                Log.i(TAG, "Keeping Sense Mic runtime generation=$generation after unchanged $reason")
                                return@schedule
                            }
                            Log.i(TAG, "Restarting Sense Mic runtime generation=$generation after $reason")
                            startRuntime(
                                forceRestart = generation != null,
                                expectedGeneration = generation,
                            )
                        }
                    },
                    delayMillis,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: RejectedExecutionException) {
                null
            }
        }
    }

    private fun isLanBindingCurrent(generation: Long): Boolean {
        val selected = selectLanNetwork()
        return synchronized(lifecycleLock) {
            if (!isCurrentRuntimeLocked(generation)) return@synchronized false
            if (selected == null) {
                activeLanNetwork == null &&
                    discoverySocket == null &&
                    controlSocket == null
            } else {
                activeLanNetwork == selected.network &&
                    activeLanAddresses == selected.addresses &&
                    discoverySocket != null &&
                    controlSocket != null
            }
        }
    }

    private fun isCurrentRuntime(generation: Long): Boolean = synchronized(lifecycleLock) {
        isCurrentRuntimeLocked(generation)
    }

    private fun isCurrentRuntimeLocked(generation: Long): Boolean =
        running.get() && runtimeGeneration == generation && SenseMicRuntime.isCurrent(generation)

    private fun isCurrentClient(client: ClientOwner): Boolean = synchronized(lifecycleLock) {
        activeClient === client && isCurrentRuntimeLocked(client.generation)
    }

    private fun detachRuntimeLocked(): DetachedRuntime {
        val generation = runtimeGeneration
        val endpointSummary = localEndpointSummary()
        val clients = buildList {
            activeClient?.let(::add)
            pendingClientSockets.values.forEach(::add)
        }.distinctBy { it.id }
        val detached = DetachedRuntime(
            generation = generation,
            discoveryEndpoint = discoveryEndpoint,
            discoverySocket = discoverySocket,
            controlSocket = controlSocket,
            clients = clients,
            endpointSummary = endpointSummary,
        )
        discoveryEndpoint = null
        discoverySocket = null
        controlSocket = null
        activeClient = null
        pendingClientSockets.clear()
        activeLanNetwork = null
        activeLanAddresses = emptyList()
        return detached
    }

    private fun closeDetachedRuntime(runtime: DetachedRuntime) {
        runtime.discoveryEndpoint?.close()
        runtime.discoverySocket.closeQuietly()
        runtime.controlSocket.closeQuietly()
        runtime.clients.forEach { client ->
            client.socket.closeQuietly()
            client.captureFuture?.cancel(true)
            client.captureFuture = null
            releaseStreamingLocks(client)
        }
        runtime.generation?.let { generation ->
            releaseDiscoveryLock(generation)
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

    @SuppressLint("MissingPermission")
    private fun acquireDiscoveryLockLocked(generation: Long) {
        check(Thread.holdsLock(lifecycleLock))
        if (!isCurrentRuntimeLocked(generation)) return
        if (multicastLock != null) return
        multicastLock = applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("$packageName:SenseMicDiscovery")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        discoveryLockGeneration = generation
        Log.i(TAG, "Wi-Fi multicast lock acquired for discovery")
    }

    private fun releaseDiscoveryLock(generation: Long) {
        synchronized(lifecycleLock) {
            if (discoveryLockGeneration != generation) return
            runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
                .onFailure { error -> Log.w(TAG, "Failed to release discovery multicast lock", error) }
            multicastLock = null
            discoveryLockGeneration = null
        }
    }

    private fun acquireStreamingLocks(client: ClientOwner): Boolean = synchronized(lifecycleLock) {
        if (activeClient !== client || !isCurrentRuntimeLocked(client.generation)) return@synchronized false
        if (streamingLocksClient != null && streamingLocksClient !== client) {
            return@synchronized false
        }
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
        streamingLocksClient = client
        true
    }

    private fun releaseStreamingLocks(client: ClientOwner) {
        synchronized(lifecycleLock) {
            if (streamingLocksClient !== client) return
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            wifiLock = null
            streamingLocksClient = null
        }
    }

    private data class HandshakeMaterial(val keyPair: KeyPair, val nonce: ByteArray)

    private data class AuthFailureWindow(val startedAtMillis: Long, val failures: Int)

    private data class ClientOwner(
        val generation: Long,
        val id: Long,
        val socket: Socket,
        @Volatile var captureFuture: Future<*>? = null,
    )

    private data class DetachedRuntime(
        val generation: Long?,
        val discoveryEndpoint: SenseMicDiscoveryEndpoint?,
        val discoverySocket: DatagramSocket?,
        val controlSocket: ServerSocket?,
        val clients: List<ClientOwner>,
        val endpointSummary: String,
    )

    private data class ActiveLanNetwork(
        val network: Network,
        val addresses: List<String>,
        val transportLabel: String,
    )

    private class SenseMicThreadFactory : ThreadFactory {
        private val nextId = AtomicLong(1)
        override fun newThread(task: Runnable): Thread =
            Thread(task, "Sense-Mic-${nextId.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
    }

    private class SenseMicLifecycleThreadFactory : ThreadFactory {
        override fun newThread(task: Runnable): Thread =
            Thread(task, "Sense-Mic-Lifecycle").apply {
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
        private const val NETWORK_REBIND_DEBOUNCE_MILLIS = 350L
        private const val ENDPOINT_RETRY_MILLIS = 2_000L
        private const val TAG = "SenseMicService"
        private val IPV4_ANY_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
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
