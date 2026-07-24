package io.github.ethanbird.senseime.brain.api

import java.net.URI
import java.util.Locale

/**
 * Versioned, secret-free provider configuration.
 *
 * API keys are deliberately not part of this value object. Android persists them separately and
 * supplies a [ProviderCredential] only for the lifetime of a request.
 */
data class ProviderProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val displayName: String,
    val apiStyle: ProviderApiStyle = ProviderApiStyle.OPENAI_RESPONSES,
    val baseUrl: String = DEFAULT_OPENAI_BASE_URL,
    val model: String,
    /**
     * Controls whether the provider may spend latency and tokens on an explicit reasoning phase.
     *
     * [ThinkingMode.DISABLED] is the safe mobile default: the keyboard should respond quickly
     * unless the user deliberately opts into provider-side thinking.
     */
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val streaming: Boolean = true,
    val structuredOutput: StructuredOutputMode = StructuredOutputMode.JSON_SCHEMA,
    val timeouts: ProviderTimeouts = ProviderTimeouts(),
    val allowInsecureLocalhost: Boolean = false,
) {
    fun validate(): ProviderProfileValidation = ProviderProfileValidator.validate(this)

    fun requireValid(): ProviderProfile {
        val validation = validate()
        if (!validation.isValid) throw InvalidProviderProfileException(validation.errors)
        return this
    }

    /**
     * Resolves the provider endpoint without allowing a profile to smuggle query/fragment data.
     */
    fun endpointUrl(): String {
        requireValid()
        val root = baseUrl.trimEnd('/')
        return root + when (apiStyle) {
            ProviderApiStyle.OPENAI_RESPONSES -> "/responses"
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS -> "/chat/completions"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
    }
}

enum class ProviderApiStyle {
    OPENAI_RESPONSES,
    OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
}

enum class ThinkingMode {
    /** Let the provider choose. Not every compatible API exposes an automatic mode. */
    AUTO,

    /** Prefer the lowest-latency path and do not request an explicit reasoning phase. */
    DISABLED,

    /** Explicitly request provider-side reasoning when the provider supports it. */
    ENABLED,
}

enum class ReasoningEffort(val wireValue: String?) {
    DEFAULT(null),
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

enum class StructuredOutputMode {
    /** Strict `sense.editor.patch.v1` JSON Schema, preferred when the provider supports it. */
    JSON_SCHEMA,

    /** Provider only guarantees a JSON object; the local strict decoder remains authoritative. */
    JSON_OBJECT,

    /** Compatibility fallback. The system prompt still requires the exact patch protocol. */
    PROMPT_ONLY,
}

data class ProviderTimeouts(
    val connectTimeoutMs: Long = 15_000,
    val firstEventTimeoutMs: Long = 30_000,
    val streamIdleTimeoutMs: Long = 30_000,
    val totalTimeoutMs: Long = 120_000,
)

enum class ProviderProfileErrorCode {
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_ID,
    INVALID_DISPLAY_NAME,
    INVALID_BASE_URL,
    INSECURE_BASE_URL,
    INVALID_MODEL,
    INVALID_TIMEOUT,
}

data class ProviderProfileError(
    val code: ProviderProfileErrorCode,
    val path: String,
    val message: String,
)

data class ProviderProfileValidation(
    val errors: List<ProviderProfileError>,
) {
    val isValid: Boolean
        get() = errors.isEmpty()
}

class InvalidProviderProfileException(
    val errors: List<ProviderProfileError>,
) : IllegalArgumentException(
    errors.joinToString(prefix = "Invalid provider profile: ", separator = "; ") {
        "${it.path}: ${it.message}"
    },
)

object ProviderProfileValidator {
    private val safeId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private const val MAX_LABEL_CHARS = 128
    private const val MAX_MODEL_CHARS = 256
    private const val MIN_TIMEOUT_MS = 250L
    private const val MAX_TIMEOUT_MS = 300_000L

    fun validate(profile: ProviderProfile): ProviderProfileValidation {
        val errors = mutableListOf<ProviderProfileError>()

        if (profile.schemaVersion != ProviderProfile.CURRENT_SCHEMA_VERSION) {
            errors += error(
                ProviderProfileErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                "$.schema_version",
                "expected ${ProviderProfile.CURRENT_SCHEMA_VERSION}",
            )
        }
        if (!safeId.matches(profile.id)) {
            errors += error(
                ProviderProfileErrorCode.INVALID_ID,
                "$.id",
                "must be 1..128 safe identifier characters",
            )
        }
        validateText(
            profile.displayName,
            MAX_LABEL_CHARS,
            "$.display_name",
            ProviderProfileErrorCode.INVALID_DISPLAY_NAME,
            errors,
        )
        validateText(
            profile.model,
            MAX_MODEL_CHARS,
            "$.model",
            ProviderProfileErrorCode.INVALID_MODEL,
            errors,
        )
        validateUrl(profile, errors)
        validateTimeouts(profile.timeouts, errors)

        return ProviderProfileValidation(errors)
    }

    private fun validateUrl(
        profile: ProviderProfile,
        errors: MutableList<ProviderProfileError>,
    ) {
        val uri = try {
            URI(profile.baseUrl)
        } catch (_: Exception) {
            null
        }
        if (
            uri == null ||
            !uri.isAbsolute ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            errors += error(
                ProviderProfileErrorCode.INVALID_BASE_URL,
                "$.base_url",
                "must be an absolute provider root without credentials, query, or fragment",
            )
            return
        }

        val scheme = uri.scheme.lowercase(Locale.ROOT)
        if (scheme == "https") return
        val host = uri.host.lowercase(Locale.ROOT)
        val localHost = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (scheme != "http" || !profile.allowInsecureLocalhost || !localHost) {
            errors += error(
                ProviderProfileErrorCode.INSECURE_BASE_URL,
                "$.base_url",
                "HTTPS is required (HTTP is opt-in for loopback development only)",
            )
        }
    }

    private fun validateTimeouts(
        timeouts: ProviderTimeouts,
        errors: MutableList<ProviderProfileError>,
    ) {
        fun bounded(value: Long, path: String) {
            if (value !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
                errors += error(
                    ProviderProfileErrorCode.INVALID_TIMEOUT,
                    path,
                    "must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms",
                )
            }
        }
        bounded(timeouts.connectTimeoutMs, "$.timeouts.connect_timeout_ms")
        bounded(timeouts.firstEventTimeoutMs, "$.timeouts.first_event_timeout_ms")
        bounded(timeouts.streamIdleTimeoutMs, "$.timeouts.stream_idle_timeout_ms")
        bounded(timeouts.totalTimeoutMs, "$.timeouts.total_timeout_ms")
        if (timeouts.firstEventTimeoutMs > timeouts.totalTimeoutMs) {
            errors += error(
                ProviderProfileErrorCode.INVALID_TIMEOUT,
                "$.timeouts.first_event_timeout_ms",
                "must not exceed total_timeout_ms",
            )
        }
        if (timeouts.streamIdleTimeoutMs > timeouts.totalTimeoutMs) {
            errors += error(
                ProviderProfileErrorCode.INVALID_TIMEOUT,
                "$.timeouts.stream_idle_timeout_ms",
                "must not exceed total_timeout_ms",
            )
        }
    }

    private fun validateText(
        value: String,
        maxChars: Int,
        path: String,
        code: ProviderProfileErrorCode,
        errors: MutableList<ProviderProfileError>,
    ) {
        if (
            value.isBlank() ||
            value.length > maxChars ||
            value.any { it.code < 0x20 || it == '\u007f' }
        ) {
            errors += error(code, path, "must be non-blank, bounded, and contain no controls")
        }
    }

    private fun error(
        code: ProviderProfileErrorCode,
        path: String,
        message: String,
    ) = ProviderProfileError(code, path, message)
}
