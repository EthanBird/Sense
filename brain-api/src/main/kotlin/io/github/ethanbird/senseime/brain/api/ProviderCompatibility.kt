package io.github.ethanbird.senseime.brain.api

import java.net.URI
import java.util.Locale

/**
 * Known provider capability checks that can prevent a request before it consumes tokens.
 *
 * This is intentionally conservative: only an official provider host is recognized. Compatible
 * gateways and self-hosted endpoints remain configurable without Sense guessing their features.
 */
object ProviderCompatibility {
    fun issues(profile: ProviderProfile): List<ProviderCompatibilityIssue> {
        if (!isOfficialDeepSeek(profile.baseUrl)) return emptyList()
        return buildList {
            if (profile.apiStyle != ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS) {
                add(ProviderCompatibilityIssue.DEEPSEEK_REQUIRES_CHAT_COMPLETIONS)
            }
            if (profile.structuredOutput == StructuredOutputMode.JSON_SCHEMA) {
                add(ProviderCompatibilityIssue.DEEPSEEK_REQUIRES_JSON_OBJECT)
            }
        }
    }

    fun isOfficialDeepSeek(baseUrl: String): Boolean {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return false
        return host.lowercase(Locale.ROOT) == OFFICIAL_DEEPSEEK_HOST
    }

    private const val OFFICIAL_DEEPSEEK_HOST = "api.deepseek.com"
}

enum class ProviderCompatibilityIssue {
    DEEPSEEK_REQUIRES_CHAT_COMPLETIONS,
    DEEPSEEK_REQUIRES_JSON_OBJECT,
}
