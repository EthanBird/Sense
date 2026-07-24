package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorClassifierTest {
    @Test
    fun `unknown client status stays generic and non-retryable`() {
        val result = ProviderErrorClassifier.fromHttpStatus(418)

        assertEquals(HarnessErrorCode.PROVIDER_FAILURE, result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun `payload authentication fingerprint is actionable`() {
        val result = ProviderErrorClassifier.fromProviderPayload(
            message = "Authentication failed",
            type = "authentication_error",
            providerCode = "invalid_api_key",
        )

        assertEquals(HarnessErrorCode.PROVIDER_AUTHENTICATION, result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun `payload service failure is retryable even when provider omits flag`() {
        val result = ProviderErrorClassifier.fromProviderPayload(
            message = "Service temporarily unavailable",
            type = "server_error",
        )

        assertEquals(HarnessErrorCode.PROVIDER_UNAVAILABLE, result.code)
        assertTrue(result.retryable)
    }

    @Test
    fun `explicit status wins over ambiguous provider text`() {
        val result = ProviderErrorClassifier.fromProviderPayload(
            message = "request failed",
            statusCode = 402,
        )

        assertEquals(HarnessErrorCode.PROVIDER_QUOTA, result.code)
        assertFalse(result.retryable)
    }
}
