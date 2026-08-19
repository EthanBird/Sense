package io.github.ethanbird.senseime.mic

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Stable v1 wire constants shared with the Rust Sense Mic Client. */
object SenseMicProtocol {
    const val VERSION: Int = 1
    const val DISCOVERY_PORT: Int = 49_173
    const val CONTROL_PORT: Int = 49_174
    const val DEFAULT_SAMPLE_RATE: Int = 48_000
    const val DEFAULT_FRAME_SAMPLES: Int = 960
    const val CHANNELS: Int = 1
    const val CODEC_OPUS: Int = 1
    const val OPUS_FRAME_MILLIS: Int = 20
    const val FEC_GROUP_SIZE: Int = 4
    const val AUDIO_HEADER_BYTES: Int = 36
    const val GCM_TAG_BYTES: Int = 16
    const val MAX_CONTROL_PAYLOAD_BYTES: Int = 4_096
    const val MAX_AUDIO_PAYLOAD_BYTES: Int = 1_275

    val DISCOVERY_MAGIC: ByteArray = byteArrayOf('S'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte())
    val AUDIO_MAGIC: ByteArray = byteArrayOf('S'.code.toByte(), 'M'.code.toByte(), 'U'.code.toByte(), 'A'.code.toByte())
    val TRANSCRIPT_LABEL: ByteArray = "sense-mic-handshake-v1".encodeToByteArray()
}

enum class SenseMicDiscoveryType(val wire: Int) {
    REQUEST(1),
    RESPONSE(2),
}

enum class SenseMicControlType(val wire: Int) {
    HELLO(1),
    WELCOME(2),
    ERROR(3),
    PING(4),
    PONG(5),
    STOP(6),
}

enum class SenseMicAudioKind(val wire: Int) {
    AUDIO(1),
    XOR_FEC(2),
}

data class SenseMicDiscoveryResponse(
    val controlPort: Int,
    val deviceId: Long,
    val requestNonce: ByteArray,
    val serverNonce: ByteArray,
    val serverPublicKey: ByteArray,
    val deviceName: String,
)

data class SenseMicClientHello(
    val udpPort: Int,
    val clientNonce: ByteArray,
    val clientPublicKey: ByteArray,
    val clientName: String,
    val proof: ByteArray,
)

data class SenseMicWelcome(
    val sessionId: Int,
    val sampleRate: Int,
    val frameSamples: Int,
    val channels: Int,
    val codec: Int,
    val bitrate: Int,
    val serverProof: ByteArray,
)

data class SenseMicClientStats(
    val receivedPackets: Long,
    val lostPackets: Long,
    val jitterMillis: Int,
)

data class SenseMicAudioHeader(
    val kind: SenseMicAudioKind,
    val sessionId: Int,
    val packetCounter: Long,
    val frameSequence: Int,
    val timestampSamples: Long,
    val payloadLength: Int,
    val fecGroupSize: Int,
)

data class SenseMicEncryptedPacket(
    val header: SenseMicAudioHeader,
    val datagram: ByteArray,
)

object SenseMicWireCodec {
    private const val NONCE_BYTES = 16
    private const val PROOF_BYTES = 32

    fun encodeDiscoveryRequest(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES)
        return ByteBuffer.allocate(6 + nonce.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(SenseMicProtocol.DISCOVERY_MAGIC)
            put(SenseMicProtocol.VERSION.toByte())
            put(SenseMicDiscoveryType.REQUEST.wire.toByte())
            put(nonce)
        }.array()
    }

    fun decodeDiscoveryRequest(bytes: ByteArray, length: Int = bytes.size): ByteArray? {
        if (length != 22) return null
        val input = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        if (!input.takeBytes(4).contentEquals(SenseMicProtocol.DISCOVERY_MAGIC)) return null
        if (input.u8() != SenseMicProtocol.VERSION) return null
        if (input.u8() != SenseMicDiscoveryType.REQUEST.wire) return null
        return input.takeBytes(NONCE_BYTES)
    }

    fun encodeDiscoveryResponse(value: SenseMicDiscoveryResponse): ByteArray {
        require(value.controlPort in 1..65_535)
        require(value.requestNonce.size == NONCE_BYTES)
        require(value.serverNonce.size == NONCE_BYTES)
        require(value.serverPublicKey.size in 1..512)
        val name = value.deviceName.encodeToByteArray()
        require(name.size in 1..63)
        return ByteBuffer.allocate(6 + 2 + 8 + 16 + 16 + 2 + 1 + value.serverPublicKey.size + name.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(SenseMicProtocol.DISCOVERY_MAGIC)
                put(SenseMicProtocol.VERSION.toByte())
                put(SenseMicDiscoveryType.RESPONSE.wire.toByte())
                putShort(value.controlPort.toShort())
                putLong(value.deviceId)
                put(value.requestNonce)
                put(value.serverNonce)
                putShort(value.serverPublicKey.size.toShort())
                put(name.size.toByte())
                put(value.serverPublicKey)
                put(name)
            }.array()
    }

    fun decodeDiscoveryResponse(bytes: ByteArray, length: Int = bytes.size): SenseMicDiscoveryResponse? =
        runCatching {
            if (length < 53) return null
            val input = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
            if (!input.takeBytes(4).contentEquals(SenseMicProtocol.DISCOVERY_MAGIC)) return null
            if (input.u8() != SenseMicProtocol.VERSION) return null
            if (input.u8() != SenseMicDiscoveryType.RESPONSE.wire) return null
            val port = input.u16()
            val deviceId = input.long
            val requestNonce = input.takeBytes(NONCE_BYTES)
            val serverNonce = input.takeBytes(NONCE_BYTES)
            val publicKeyLength = input.u16()
            val nameLength = input.u8()
            require(publicKeyLength in 1..512 && nameLength in 1..63)
            require(input.remaining() == publicKeyLength + nameLength)
            SenseMicDiscoveryResponse(
                controlPort = port,
                deviceId = deviceId,
                requestNonce = requestNonce,
                serverNonce = serverNonce,
                serverPublicKey = input.takeBytes(publicKeyLength),
                deviceName = input.takeBytes(nameLength).decodeToString(),
            )
        }.getOrNull()

    fun encodeControlFrame(type: SenseMicControlType, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= SenseMicProtocol.MAX_CONTROL_PAYLOAD_BYTES)
        return ByteBuffer.allocate(12 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(SenseMicProtocol.DISCOVERY_MAGIC)
            put(SenseMicProtocol.VERSION.toByte())
            put(type.wire.toByte())
            putShort(0)
            putInt(payload.size)
            put(payload)
        }.array()
    }

    fun decodeControlFrameHeader(header: ByteArray): Pair<SenseMicControlType, Int>? {
        if (header.size != 12) return null
        val input = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (!input.takeBytes(4).contentEquals(SenseMicProtocol.DISCOVERY_MAGIC)) return null
        if (input.u8() != SenseMicProtocol.VERSION) return null
        val type = input.u8().let { wire -> SenseMicControlType.entries.firstOrNull { it.wire == wire } }
            ?: return null
        if (input.u16() != 0) return null
        val payloadLength = input.int
        if (payloadLength !in 0..SenseMicProtocol.MAX_CONTROL_PAYLOAD_BYTES) return null
        return type to payloadLength
    }

    fun encodeClientHello(value: SenseMicClientHello): ByteArray {
        require(value.udpPort in 1..65_535)
        require(value.clientNonce.size == NONCE_BYTES)
        require(value.clientPublicKey.size in 1..512)
        require(value.proof.size == PROOF_BYTES)
        val name = value.clientName.encodeToByteArray()
        require(name.size in 1..63)
        return ByteBuffer.allocate(2 + 16 + 2 + 1 + value.clientPublicKey.size + name.size + 32)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(value.udpPort.toShort())
                put(value.clientNonce)
                putShort(value.clientPublicKey.size.toShort())
                put(name.size.toByte())
                put(value.clientPublicKey)
                put(name)
                put(value.proof)
            }.array()
    }

    fun decodeClientHello(payload: ByteArray): SenseMicClientHello? = runCatching {
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val udpPort = input.u16()
        val nonce = input.takeBytes(NONCE_BYTES)
        val publicKeyLength = input.u16()
        val nameLength = input.u8()
        require(publicKeyLength in 1..512 && nameLength in 1..63)
        require(input.remaining() == publicKeyLength + nameLength + PROOF_BYTES)
        SenseMicClientHello(
            udpPort = udpPort,
            clientNonce = nonce,
            clientPublicKey = input.takeBytes(publicKeyLength),
            clientName = input.takeBytes(nameLength).decodeToString(),
            proof = input.takeBytes(PROOF_BYTES),
        )
    }.getOrNull()

    fun encodeWelcome(value: SenseMicWelcome): ByteArray {
        require(value.serverProof.size == PROOF_BYTES)
        return ByteBuffer.allocate(4 + 4 + 2 + 1 + 1 + 4 + 32).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(value.sessionId)
            putInt(value.sampleRate)
            putShort(value.frameSamples.toShort())
            put(value.channels.toByte())
            put(value.codec.toByte())
            putInt(value.bitrate)
            put(value.serverProof)
        }.array()
    }

    fun decodeWelcome(payload: ByteArray): SenseMicWelcome? = runCatching {
        require(payload.size == 48)
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        SenseMicWelcome(
            sessionId = input.int,
            sampleRate = input.int,
            frameSamples = input.u16(),
            channels = input.u8(),
            codec = input.u8(),
            bitrate = input.int,
            serverProof = input.takeBytes(PROOF_BYTES),
        )
    }.getOrNull()

    fun encodeStats(value: SenseMicClientStats): ByteArray =
        ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN).apply {
            putLong(value.receivedPackets)
            putLong(value.lostPackets)
            putShort(value.jitterMillis.coerceIn(0, 65_535).toShort())
        }.array()

    fun decodeStats(payload: ByteArray): SenseMicClientStats? = runCatching {
        require(payload.size == 18)
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        SenseMicClientStats(input.long, input.long, input.u16())
    }.getOrNull()

    fun encodeAudioHeader(header: SenseMicAudioHeader): ByteArray {
        require(header.payloadLength in 0..SenseMicProtocol.MAX_AUDIO_PAYLOAD_BYTES)
        require(header.fecGroupSize in 0..255)
        return ByteBuffer.allocate(SenseMicProtocol.AUDIO_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(SenseMicProtocol.AUDIO_MAGIC)
            put(SenseMicProtocol.VERSION.toByte())
            put(header.kind.wire.toByte())
            putShort(SenseMicProtocol.AUDIO_HEADER_BYTES.toShort())
            putInt(header.sessionId)
            putLong(header.packetCounter)
            putInt(header.frameSequence)
            putLong(header.timestampSamples)
            putShort(header.payloadLength.toShort())
            put(header.fecGroupSize.toByte())
            put(0.toByte())
        }.array()
    }

    fun decodeAudioHeader(datagram: ByteArray): SenseMicAudioHeader? = runCatching {
        require(datagram.size >= SenseMicProtocol.AUDIO_HEADER_BYTES + SenseMicProtocol.GCM_TAG_BYTES)
        val input = ByteBuffer.wrap(datagram, 0, SenseMicProtocol.AUDIO_HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        require(input.takeBytes(4).contentEquals(SenseMicProtocol.AUDIO_MAGIC))
        require(input.u8() == SenseMicProtocol.VERSION)
        val kindWire = input.u8()
        val kind = SenseMicAudioKind.entries.first { it.wire == kindWire }
        require(input.u16() == SenseMicProtocol.AUDIO_HEADER_BYTES)
        val result = SenseMicAudioHeader(
            kind = kind,
            sessionId = input.int,
            packetCounter = input.long,
            frameSequence = input.int,
            timestampSamples = input.long,
            payloadLength = input.u16(),
            fecGroupSize = input.u8(),
        )
        require(input.u8() == 0)
        require(result.payloadLength <= SenseMicProtocol.MAX_AUDIO_PAYLOAD_BYTES)
        require(datagram.size == SenseMicProtocol.AUDIO_HEADER_BYTES + result.payloadLength + SenseMicProtocol.GCM_TAG_BYTES)
        result
    }.getOrNull()

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xff
    private fun ByteBuffer.u16(): Int = short.toInt() and 0xffff
    private fun ByteBuffer.takeBytes(count: Int): ByteArray = ByteArray(count).also(::get)
}

data class SenseMicHandshakeSecrets(
    val pairKey: ByteArray,
    val transcript: ByteArray,
    val sessionKey: ByteArray,
)

object SenseMicCrypto {
    private const val NONCE_BYTES = 16
    private const val KEY_BYTES = 32
    private const val PBKDF2_ITERATIONS = 80_000
    private val secureRandom = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    fun generateP256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        generateKeyPair()
    }

    fun decodeP256PublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun derivePairKey(pairCode: CharArray, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray {
        require(pairCode.size in 6..12)
        require(serverNonce.size == NONCE_BYTES && clientNonce.size == NONCE_BYTES)
        val salt = serverNonce + clientNonce
        val spec = PBEKeySpec(pairCode, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            salt.fill(0)
        }
    }

    fun transcript(
        serverPublicKey: ByteArray,
        clientPublicKey: ByteArray,
        serverNonce: ByteArray,
        clientNonce: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { output ->
        output.write(SenseMicProtocol.TRANSCRIPT_LABEL)
        output.writeU16(serverPublicKey.size)
        output.write(serverPublicKey)
        output.writeU16(clientPublicKey.size)
        output.write(clientPublicKey)
        output.write(serverNonce)
        output.write(clientNonce)
        output.toByteArray()
    }

    fun proof(pairKey: ByteArray, role: String, transcript: ByteArray): ByteArray =
        hmac(pairKey, role.encodeToByteArray() + transcript)

    fun verifyProof(expected: ByteArray, actual: ByteArray): Boolean =
        MessageDigest.isEqual(expected, actual)

    fun deriveSessionKey(
        privateKey: PrivateKey,
        peerPublicKey: PublicKey,
        pairKey: ByteArray,
        transcript: ByteArray,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()
        return try {
            hkdfSha256(sharedSecret, pairKey, transcript, KEY_BYTES)
        } finally {
            sharedSecret.fill(0)
        }
    }

    fun encryptAudio(key: ByteArray, header: SenseMicAudioHeader, plaintext: ByteArray): SenseMicEncryptedPacket {
        require(key.size == KEY_BYTES)
        require(plaintext.size == header.payloadLength)
        val aad = SenseMicWireCodec.encodeAudioHeader(header)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce(header.sessionId, header.packetCounter)),
        )
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        return SenseMicEncryptedPacket(header, aad + encrypted)
    }

    fun decryptAudio(key: ByteArray, datagram: ByteArray): Pair<SenseMicAudioHeader, ByteArray>? = runCatching {
        require(key.size == KEY_BYTES)
        val header = requireNotNull(SenseMicWireCodec.decodeAudioHeader(datagram))
        val aad = datagram.copyOfRange(0, SenseMicProtocol.AUDIO_HEADER_BYTES)
        val ciphertext = datagram.copyOfRange(SenseMicProtocol.AUDIO_HEADER_BYTES, datagram.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce(header.sessionId, header.packetCounter)),
        )
        cipher.updateAAD(aad)
        header to cipher.doFinal(ciphertext)
    }.getOrNull()

    private fun nonce(sessionId: Int, packetCounter: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(sessionId)
            putLong(packetCounter)
        }.array()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        val pseudoRandomKey = hmac(salt, inputKeyMaterial)
        return try {
            val output = ByteArrayOutputStream()
            var previous = byteArrayOf()
            var counter = 1
            while (output.size() < outputLength) {
                previous = hmac(pseudoRandomKey, previous + info + byteArrayOf(counter.toByte()))
                output.write(previous)
                counter++
            }
            output.toByteArray().copyOf(outputLength)
        } finally {
            pseudoRandomKey.fill(0)
        }
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        require(value in 0..65_535)
        write(value ushr 8)
        write(value)
    }
}
