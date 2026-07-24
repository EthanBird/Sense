package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorClassifierTest {
    @Test
    fun `content filter is not misreported as a network failure`() {
        assertEquals(
            ClassifiedProviderError(
                code = HarnessErrorCode.PROVIDER_CONTENT_FILTER,
                retryable = false,
            ),
            ProviderErrorClassifier.fromProviderPayload(
                message = "provider content filter interrupted the response",
                providerCode = "content_filter",
            ),
        )
    }

    @Test
    fun `DeepSeek length finish reason is an explicit output truncation`() {
        assertEquals(
            ClassifiedProviderError(
                code = HarnessErrorCode.OUTPUT_TRUNCATED,
                retryable = false,
            ),
            ProviderErrorClassifier.fromProviderPayload(
                message = "provider output reached its token limit",
                providerCode = "finish_reason_length",
            ),
        )
    }

    @Test
    fun `DeepSeek insufficient system resource is transient provider unavailability`() {
        assertEquals(
            ClassifiedProviderError(
                code = HarnessErrorCode.PROVIDER_UNAVAILABLE,
                retryable = true,
            ),
            ProviderErrorClassifier.fromProviderPayload(
                message = "provider has insufficient system resources",
                providerCode = "insufficient_system_resource",
                providerRetryable = true,
            ),
        )
    }

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
