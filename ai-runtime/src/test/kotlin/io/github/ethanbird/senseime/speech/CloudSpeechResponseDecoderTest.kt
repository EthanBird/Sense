package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpeechResponseDecoderTest {
    @Test
    fun `OpenAI response decodes escaped unicode text`() {
        val transcript = decode(
            SpeechProviderProtocol.OPENAI_TRANSCRIPTIONS,
            """{"text":"先思\u8f93\u5165\u6cd5 \ud83c\udf1f"}""",
        )

        assertEquals("先思输入法 🌟", transcript.text)
    }

    @Test
    fun `Deepgram response chooses first candidate and keeps bounded alternatives`() {
        val transcript = decode(
            SpeechProviderProtocol.DEEPGRAM_LISTEN,
            """
                {
                  "metadata":{"duration":1.2},
                  "results":{"channels":[{"alternatives":[
                    {"transcript":"先思输入法","confidence":0.9},
                    {"transcript":"先试输入法","confidence":0.2}
                  ]}]}
                }
            """.trimIndent(),
        )

        assertEquals("先思输入法", transcript.text)
        assertEquals(listOf("先试输入法"), transcript.alternatives)
    }

    @Test
    fun `transcript ceiling does not split surrogate pair`() {
        val prefix = "a".repeat(CloudSpeechResponseDecoder.MAX_TRANSCRIPT_CHARS - 1)
        val transcript = decode(
            SpeechProviderProtocol.OPENAI_TRANSCRIPTIONS,
            """{"text":"$prefix😀tail"}""",
        )

        assertEquals(
            CloudSpeechResponseDecoder.MAX_TRANSCRIPT_CHARS - 1,
            transcript.text.length,
        )
        assertFalse(transcript.text.last().isHighSurrogate())
        assertFalse(transcript.text.last().isLowSurrogate())
    }

    @Test
    fun `duplicate JSON key is rejected`() {
        val result = CloudSpeechResponseDecoder.decode(
            SpeechProviderProtocol.OPENAI_TRANSCRIPTIONS,
            """{"text":"first","text":"second"}""".toByteArray(),
        )

        assertTrue(result.isFailure)
        assertEquals(
            CloudSpeechFailureKind.PROTOCOL,
            (result.exceptionOrNull() as CloudSpeechResponseDecodingException).failureKind,
        )
    }

    @Test
    fun `blank provider transcript maps to no audio`() {
        val result = CloudSpeechResponseDecoder.decode(
            SpeechProviderProtocol.OPENAI_TRANSCRIPTIONS,
            """{"text":"  "}""".toByteArray(),
        )

        assertTrue(result.isFailure)
        assertEquals(
            CloudSpeechFailureKind.NO_AUDIO,
            (result.exceptionOrNull() as CloudSpeechResponseDecodingException).failureKind,
        )
    }

    @Test
    fun `oversized response is rejected before JSON parsing`() {
        val result = CloudSpeechResponseDecoder.decode(
            SpeechProviderProtocol.OPENAI_TRANSCRIPTIONS,
            ByteArray(CloudSpeechResponseDecoder.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() },
        )

        assertTrue(result.isFailure)
        assertEquals(
            CloudSpeechFailureKind.RESPONSE_TOO_LARGE,
            (result.exceptionOrNull() as CloudSpeechResponseDecodingException).failureKind,
        )
    }

    private fun decode(
        protocol: SpeechProviderProtocol,
        json: String,
    ): CloudSpeechTranscript =
        CloudSpeechResponseDecoder.decode(protocol, json.toByteArray()).getOrThrow()
}
