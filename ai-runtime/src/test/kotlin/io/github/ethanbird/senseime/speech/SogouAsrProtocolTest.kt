package io.github.ethanbird.senseime.speech

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SogouAsrProtocolTest {
    @Test
    fun `Sense language tags map to supported Sogou codes`() {
        assertEquals("zh-cmn-Hans-CN", SogouAsrProtocol.languageCode("zh-CN"))
        assertEquals("zh-yue-Hant-HK", SogouAsrProtocol.languageCode("zh-HK"))
        assertEquals("zh-cmn-Hans-CN", SogouAsrProtocol.languageCode("zh-TW"))
        assertEquals("en-US", SogouAsrProtocol.languageCode("en-US"))
        assertEquals("zh-cmn-Hans-CN", SogouAsrProtocol.languageCode("fr-FR"))
    }

    @Test
    fun `configuration matches SRSS metadata and uses anonymous request ids`() {
        val ids = SogouAsrProtocol.RequestIds(
            sliceId = "slice-id",
            audioId = "audio-id",
            deviceAid = "device-aid",
            deviceQid = "device-qid",
            deviceUuid = "device-uuid",
        )

        val config = SogouAsrProtocol.buildConfig("en-US", false, ids)

        assertTrue(config.contains("\"encoding\":\"OPUS_WITH_HEADER\""))
        assertTrue(config.contains("\"language_code\":\"en-US\""))
        assertTrue(config.contains("\"audio_slice_id\":\"slice-id\""))
        assertTrue(config.contains("\"audio_id\":\"audio-id\""))
        assertTrue(config.contains("\"device_uuid\":\"device-uuid\""))
        assertTrue(config.contains("\"user_category\":\"anonymous\""))
        assertTrue(config.contains("\"interim_results\":false"))
        assertFalse(config.contains("API Key"))
    }

    @Test
    fun `handshake encrypts config with expected SRSS headers`() {
        val handshake = SogouAsrProtocol.prepareHandshake("zh-CN", true)
        val encryptedConfig = Base64.getDecoder().decode(handshake.encryptedConfigBase64)
        val encryptedKey = Base64.getDecoder().decode(
            handshake.headers.getValue("X-Srss-Cipher-Key-Sec"),
        )
        val iv = Base64.getDecoder().decode(
            handshake.headers.getValue("X-Srss-Cipher-Key-Vec"),
        )

        assertTrue(encryptedConfig.isNotEmpty())
        assertEquals(0, encryptedConfig.size % 16)
        assertEquals(384, encryptedKey.size)
        assertEquals(16, iv.size)
        assertEquals("1", handshake.headers["X-Srss-Cipher-Key-Type"])
        assertEquals("okhttp/4.9.3", handshake.headers["User-Agent"])
        assertFalse(handshake.encryptedConfigBase64.contains("OPUS_WITH_HEADER"))
        assertTrue(handshake.sliceId.matches(UUID_PATTERN))
    }

    @Test
    fun `Opus packets use one 20 millisecond PCM frame and big endian length`() {
        val pcm = ByteArray(SogouOpusPacketEncoder.FRAME_PCM_BYTES * 2 + 2)
        pcm.indices.forEach { index -> pcm[index] = (index * 31).toByte() }

        val packets = SogouOpusPacketEncoder.encode(pcm)

        assertEquals(320, SogouOpusPacketEncoder.FRAME_SAMPLES)
        assertEquals(640, SogouOpusPacketEncoder.FRAME_PCM_BYTES)
        assertEquals(3, packets.size)
        packets.forEach { packet ->
            val declaredLength =
                ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
            assertEquals(packet.size - 2, declaredLength)
            assertTrue(declaredLength > 0)
        }
    }

    private companion object {
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
                "[0-9a-f]{4}-[0-9a-f]{12}$",
        )
    }
}
