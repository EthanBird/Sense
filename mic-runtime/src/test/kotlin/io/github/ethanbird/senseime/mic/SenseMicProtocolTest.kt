package io.github.ethanbird.senseime.mic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseMicProtocolTest {
    @Test
    fun discoveryPacketsRoundTripWithoutLosingTheRequestNonce() {
        val requestNonce = ByteArray(16) { it.toByte() }
        assertArrayEquals(
            requestNonce,
            SenseMicWireCodec.decodeDiscoveryRequest(
                SenseMicWireCodec.encodeDiscoveryRequest(requestNonce),
            ),
        )

        val response = SenseMicDiscoveryResponse(
            controlPort = SenseMicProtocol.CONTROL_PORT,
            deviceId = 0x1020304050607080L,
            requestNonce = requestNonce,
            serverNonce = ByteArray(16) { (it + 16).toByte() },
            serverPublicKey = byteArrayOf(1, 2, 3, 4),
            deviceName = "Sense Phone",
        )
        val decoded = requireNotNull(
            SenseMicWireCodec.decodeDiscoveryResponse(
                SenseMicWireCodec.encodeDiscoveryResponse(response),
            ),
        )
        assertEquals(response.controlPort, decoded.controlPort)
        assertEquals(response.deviceId, decoded.deviceId)
        assertArrayEquals(response.requestNonce, decoded.requestNonce)
        assertArrayEquals(response.serverNonce, decoded.serverNonce)
        assertArrayEquals(response.serverPublicKey, decoded.serverPublicKey)
        assertEquals(response.deviceName, decoded.deviceName)
    }

    @Test
    fun controlPayloadsRoundTripAndRejectMalformedHeaders() {
        val hello = SenseMicClientHello(
            udpPort = 50_000,
            clientNonce = ByteArray(16) { 7 },
            clientPublicKey = byteArrayOf(8, 9, 10),
            clientName = "Sense PC",
            proof = ByteArray(32) { 11 },
        )
        val decodedHello = requireNotNull(
            SenseMicWireCodec.decodeClientHello(SenseMicWireCodec.encodeClientHello(hello)),
        )
        assertEquals(hello.udpPort, decodedHello.udpPort)
        assertEquals(hello.clientName, decodedHello.clientName)
        assertArrayEquals(hello.clientNonce, decodedHello.clientNonce)
        assertArrayEquals(hello.clientPublicKey, decodedHello.clientPublicKey)
        assertArrayEquals(hello.proof, decodedHello.proof)

        val welcome = SenseMicWelcome(
            sessionId = -7,
            sampleRate = 48_000,
            frameSamples = 960,
            channels = 1,
            codec = 1,
            bitrate = 64_000,
            serverProof = ByteArray(32) { 12 },
        )
        val decodedWelcome = requireNotNull(
            SenseMicWireCodec.decodeWelcome(SenseMicWireCodec.encodeWelcome(welcome)),
        )
        assertEquals(welcome.sessionId, decodedWelcome.sessionId)
        assertEquals(welcome.sampleRate, decodedWelcome.sampleRate)
        assertEquals(welcome.frameSamples, decodedWelcome.frameSamples)
        assertEquals(welcome.channels, decodedWelcome.channels)
        assertEquals(welcome.codec, decodedWelcome.codec)
        assertEquals(welcome.bitrate, decodedWelcome.bitrate)
        assertArrayEquals(welcome.serverProof, decodedWelcome.serverProof)

        val stats = SenseMicClientStats(1_000, 23, 108)
        assertEquals(stats, SenseMicWireCodec.decodeStats(SenseMicWireCodec.encodeStats(stats)))

        val frame = SenseMicWireCodec.encodeControlFrame(SenseMicControlType.PING, byteArrayOf(1, 2))
        assertEquals(SenseMicControlType.PING to 2, SenseMicWireCodec.decodeControlFrameHeader(frame.copyOf(12)))
        frame[6] = 1
        assertNull(SenseMicWireCodec.decodeControlFrameHeader(frame.copyOf(12)))
    }

    @Test
    fun p256HandshakeProducesMutuallyAuthenticatedIdenticalSessionKeys() {
        val server = SenseMicCrypto.generateP256KeyPair()
        val client = SenseMicCrypto.generateP256KeyPair()
        val serverNonce = ByteArray(16) { it.toByte() }
        val clientNonce = ByteArray(16) { (31 - it).toByte() }
        val pairKey = SenseMicCrypto.derivePairKey("381042".toCharArray(), serverNonce, clientNonce)
        val transcript = SenseMicCrypto.transcript(
            server.public.encoded,
            client.public.encoded,
            serverNonce,
            clientNonce,
        )

        val serverSessionKey = SenseMicCrypto.deriveSessionKey(
            server.private,
            client.public,
            pairKey,
            transcript,
        )
        val clientSessionKey = SenseMicCrypto.deriveSessionKey(
            client.private,
            server.public,
            pairKey,
            transcript,
        )
        assertArrayEquals(serverSessionKey, clientSessionKey)
        assertTrue(
            SenseMicCrypto.verifyProof(
                SenseMicCrypto.proof(pairKey, "client", transcript),
                SenseMicCrypto.proof(pairKey, "client", transcript),
            ),
        )
        assertFalse(
            SenseMicCrypto.verifyProof(
                SenseMicCrypto.proof(pairKey, "client", transcript),
                SenseMicCrypto.proof(pairKey, "server", transcript),
            ),
        )
    }

    @Test
    fun authenticatedAudioRoundTripsAndDetectsTampering() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val payload = ByteArray(127) { (it xor 0x5a).toByte() }
        val header = SenseMicAudioHeader(
            kind = SenseMicAudioKind.AUDIO,
            sessionId = 0x12345678,
            packetCounter = 42,
            frameSequence = 17,
            timestampSamples = 16_320,
            payloadLength = payload.size,
            fecGroupSize = 0,
        )
        val packet = SenseMicCrypto.encryptAudio(key, header, payload).datagram
        assertEquals(AUDIO_VECTOR_HEX, packet.toHex())
        val decrypted = requireNotNull(SenseMicCrypto.decryptAudio(key, packet))
        assertEquals(header, decrypted.first)
        assertArrayEquals(payload, decrypted.second)

        packet[SenseMicProtocol.AUDIO_HEADER_BYTES + 4] =
            (packet[SenseMicProtocol.AUDIO_HEADER_BYTES + 4].toInt() xor 1).toByte()
        assertNull(SenseMicCrypto.decryptAudio(key, packet))
    }

    @Test
    fun audioHeaderRequiresExactDatagramLengthAndReservedFlags() {
        val key = ByteArray(32) { 3 }
        val payload = byteArrayOf(1, 2, 3)
        val header = SenseMicAudioHeader(
            kind = SenseMicAudioKind.XOR_FEC,
            sessionId = 5,
            packetCounter = 6,
            frameSequence = 7,
            timestampSamples = 8,
            payloadLength = payload.size,
            fecGroupSize = SenseMicProtocol.FEC_GROUP_SIZE,
        )
        val packet = SenseMicCrypto.encryptAudio(key, header, payload).datagram
        assertEquals(header, SenseMicWireCodec.decodeAudioHeader(packet))
        assertNull(SenseMicWireCodec.decodeAudioHeader(packet + 0))
        packet[35] = 1
        assertNull(SenseMicWireCodec.decodeAudioHeader(packet))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        // The Rust protocol test consumes this exact Android AES-GCM datagram as its v1 fixture.
        const val AUDIO_VECTOR_HEX =
            "534d55410101002412345678000000000000002a000000110000000000003fc0007f0000" +
                "bf297999cfc8f05c62c054c1db314b2b5405769235716b9396aa787b2628c7a67f5a7e" +
                "b7e9612147c4a3ce8d13ef56947ecf48a16ce4135052cc7bdc982001c63917150724bfe9" +
                "90d1d63e2da1e9416a4489e2b60e1b729ba2526eb7d4ab426e67652a42be1c126a1ed22" +
                "692fae059c53517ca3974d3887ec9dfd784fabe0ce23c9d695861711d76e33b70a3947635"
    }
}
