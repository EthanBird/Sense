package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SogouAsrResponseDecoderTest {
    @Test
    fun `partial response decodes current transcript`() {
        val response = decode(
            """{"results":[{"alternatives":[{"transcript":"你好我是"}],"stability":0.01}]}""",
        )

        assertFalse(response.isFinal)
        assertEquals("你好我是", response.transcript?.text)
        assertNull(response.serverError)
    }

    @Test
    fun `final response decodes punctuation and alternatives`() {
        val response = decode(
            """
                {
                  "results":[{
                    "alternatives":[
                      {"transcript":"你好，Sense。","confidence":0.86},
                      {"transcript":"你好，先思。"}
                    ],
                    "is_final":true
                  }],
                  "env_map":{"asr_mode":"wfst"}
                }
            """.trimIndent(),
        )

        assertTrue(response.isFinal)
        assertEquals("你好，Sense。", response.transcript?.text)
        assertEquals(listOf("你好，先思。"), response.transcript?.alternatives)
    }

    @Test
    fun `metadata-only response is a non-terminal no-op`() {
        val response = decode("""{"env_map":{"asr_mode":"wfst"}}""")

        assertFalse(response.isFinal)
        assertNull(response.transcript)
    }

    @Test
    fun `server error is surfaced without a transcript`() {
        val response = decode("""{"error":{"message":"bad config"}}""")

        assertTrue(response.isFinal)
        assertNull(response.transcript)
        assertEquals("bad config", response.serverError)
    }

    @Test
    fun `false error marker does not mask a transcript`() {
        val response = decode(
            """{"error":false,"results":[{"alternatives":[{"transcript":"你好"}]}]}""",
        )

        assertEquals("你好", response.transcript?.text)
        assertNull(response.serverError)
    }

    @Test
    fun `malformed results shape is rejected`() {
        val result = SogouAsrResponseDecoder.decode("""{"results":{}}""")

        assertTrue(result.isFailure)
        assertEquals(
            CloudSpeechFailureKind.PROTOCOL,
            (result.exceptionOrNull() as CloudSpeechResponseDecodingException).failureKind,
        )
    }

    private fun decode(json: String): SogouAsrResponse =
        SogouAsrResponseDecoder.decode(json).getOrThrow()
}
