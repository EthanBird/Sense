package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpeechProtocolTest {
    @Test
    fun `OpenAI-compatible request uses multipart wav bearer and required fields`() {
        val profile = SpeechProviderPresetCatalog
            .require(SpeechProviderPresetCatalog.OPENAI_COMPATIBLE)
            .defaultProfile("zh-CN")
        val wav = Pcm16WavEncoder.encode(byteArrayOf(1, 0, 2, 0))
        val request = CloudSpeechRequestFactory.create(
            profile = profile,
            wavAudio = wav,
            boundaryFactory = SpeechMultipartBoundaryFactory {
                "Sense0123456789abcdef0123456789abcdef"
            },
        ).getOrThrow()
        val body = request.body.toString(Charsets.ISO_8859_1)

        assertEquals("POST", request.method)
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            request.endpointUrl,
        )
        assertEquals(CloudSpeechAuthorizationScheme.BEARER, request.authorizationScheme)
        assertTrue(request.contentType.startsWith("multipart/form-data; boundary=Sense"))
        assertTrue(body.contains("name=\"model\"\r\n\r\ngpt-4o-mini-transcribe"))
        assertTrue(body.contains("name=\"language\"\r\n\r\nzh"))
        assertTrue(body.contains("name=\"file\"; filename=\"speech.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
        assertTrue(body.endsWith("--\r\n"))
    }

    @Test
    fun `Deepgram request uses raw wav token and encoded prerecorded query`() {
        val profile = SpeechProviderPresetCatalog
            .require(SpeechProviderPresetCatalog.DEEPGRAM)
            .defaultProfile("zh-CN")
            .copy(model = "nova 3")
        val wav = Pcm16WavEncoder.encode(byteArrayOf(1, 0, 2, 0))
        val request = CloudSpeechRequestFactory.create(profile, wav).getOrThrow()

        assertEquals(CloudSpeechAuthorizationScheme.DEEPGRAM_TOKEN, request.authorizationScheme)
        assertEquals("audio/wav", request.contentType)
        assertEquals(
            "https://api.deepgram.com/v1/listen?" +
                "model=nova%203&language=zh-CN&smart_format=true",
            request.endpointUrl,
        )
        assertArrayEquals(wav.bytes, request.body)
        assertFalse(request.body === wav.bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `multipart rejects CRLF field injection`() {
        SpeechMultipartEncoder.encode(
            boundary = "Sense0123456789abcdef0123456789abcdef",
            fields = listOf("model" to "safe\r\nX-Evil: yes"),
            fileFieldName = "file",
            fileName = "speech.wav",
            fileContentType = "audio/wav",
            fileBytes = byteArrayOf(1, 2),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `multipart rejects a boundary colliding with binary audio`() {
        SpeechMultipartEncoder.encode(
            boundary = "Sense0123456789abcdef",
            fields = listOf("model" to "m"),
            fileFieldName = "file",
            fileName = "speech.wav",
            fileContentType = "audio/wav",
            fileBytes = "xx--Sense0123456789abcdefxx".toByteArray(),
        )
    }

    @Test
    fun `request diagnostics redact body and never have credential input`() {
        val request = CloudSpeechRequestFactory.create(
            SpeechProviderPresetCatalog
                .require(SpeechProviderPresetCatalog.DEEPGRAM)
                .defaultProfile(),
            Pcm16WavEncoder.encode(byteArrayOf(1, 0)),
        ).getOrThrow()

        assertTrue(request.toString().contains("body=<redacted:"))
        assertFalse(request.toString().contains("RIFF"))
        assertFalse(request.toString().contains("Authorization"))
    }
}
