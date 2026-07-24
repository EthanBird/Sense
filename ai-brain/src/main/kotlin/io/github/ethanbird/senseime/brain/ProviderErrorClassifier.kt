package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode

internal data class ClassifiedProviderError(
    val code: HarnessErrorCode,
    val retryable: Boolean,
)

/**
 * Converts provider-specific status codes and error envelopes into stable, user-actionable
 * harness failures. Provider text is only used for classification and is never shown to the IME.
 */
internal object ProviderErrorClassifier {
    fun fromHttpStatus(statusCode: Int): ClassifiedProviderError = when (statusCode) {
        400, 404, 422 -> classified(HarnessErrorCode.PROVIDER_CONFIGURATION)
        401, 403 -> classified(HarnessErrorCode.PROVIDER_AUTHENTICATION)
        402 -> classified(HarnessErrorCode.PROVIDER_QUOTA)
        408 -> classified(HarnessErrorCode.PROVIDER_UNAVAILABLE, retryable = true)
        429 -> classified(HarnessErrorCode.PROVIDER_RATE_LIMIT, retryable = true)
        in 500..599 -> classified(HarnessErrorCode.PROVIDER_UNAVAILABLE, retryable = true)
        else -> classified(HarnessErrorCode.PROVIDER_FAILURE)
    }

    fun fromProviderPayload(
        message: String?,
        type: String? = null,
        providerCode: String? = null,
        statusCode: Int? = null,
        providerRetryable: Boolean = false,
    ): ClassifiedProviderError {
        if (statusCode != null) {
            val byStatus = fromHttpStatus(statusCode)
            if (
                byStatus.code != HarnessErrorCode.PROVIDER_FAILURE ||
                statusCode !in 200..299
            ) {
                return byStatus.copy(retryable = byStatus.retryable || providerRetryable)
            }
        }

        val fingerprint = sequenceOf(providerCode, type, message)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()

        val code = when {
            fingerprint.containsAny(
                "finish_reason_length",
                "output reached its token limit",
                "max_output_tokens",
            ) -> HarnessErrorCode.OUTPUT_TRUNCATED

            fingerprint.containsAny(
                "content_filter",
                "content filter",
                "safety policy",
            ) -> HarnessErrorCode.PROVIDER_CONTENT_FILTER

            fingerprint.containsAny(
                "invalid_api_key",
                "authentication",
                "unauthorized",
                "forbidden",
                "permission_denied",
                "access denied",
            ) -> HarnessErrorCode.PROVIDER_AUTHENTICATION

            fingerprint.containsAny(
                "insufficient_quota",
                "quota_exceeded",
                "payment_required",
                "insufficient balance",
                "insufficient credit",
                "billing",
            ) -> HarnessErrorCode.PROVIDER_QUOTA

            fingerprint.containsAny(
                "rate_limit",
                "rate limit",
                "too_many_requests",
                "too many requests",
            ) -> HarnessErrorCode.PROVIDER_RATE_LIMIT

            fingerprint.containsAny(
                "server_error",
                "service_unavailable",
                "insufficient_system_resource",
                "insufficient system resources",
                "temporarily unavailable",
                "overloaded",
                "timeout",
            ) -> HarnessErrorCode.PROVIDER_UNAVAILABLE

            fingerprint.containsAny(
                "invalid_request",
                "invalid model",
                "invalid_model",
                "model_not_found",
                "unsupported",
                "invalid parameter",
                "invalid_parameter",
                "not found",
            ) -> HarnessErrorCode.PROVIDER_CONFIGURATION

            else -> HarnessErrorCode.PROVIDER_FAILURE
        }
        val inherentlyRetryable =
            code == HarnessErrorCode.PROVIDER_RATE_LIMIT ||
                code == HarnessErrorCode.PROVIDER_UNAVAILABLE
        return classified(code, retryable = inherentlyRetryable || providerRetryable)
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any(::contains)

    private fun classified(
        code: HarnessErrorCode,
        retryable: Boolean = false,
    ) = ClassifiedProviderError(code, retryable)
}
