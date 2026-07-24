package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechProviderCredentialPolicyTest {
    @Test
    fun `valid cloud provider key is accepted by settings and runtime rule`() {
        val key = "sk-sense_123.ABC"

        assertTrue(SpeechProviderCredentialPolicy.isValid(key))
        assertTrue(SpeechProviderCredentialPolicy.isValid(key.toCharArray()))
    }

    @Test
    fun `spaces controls unicode empty and overlong keys are rejected`() {
        listOf(
            "",
            "has space",
            "line\nbreak",
            "密钥",
            "x".repeat(SpeechProviderCredentialPolicy.MAX_CHARS + 1),
        ).forEach { key ->
            assertFalse(SpeechProviderCredentialPolicy.isValid(key))
            assertFalse(SpeechProviderCredentialPolicy.isValid(key.toCharArray()))
        }
    }
}
