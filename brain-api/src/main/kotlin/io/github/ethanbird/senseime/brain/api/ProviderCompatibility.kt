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
            if (!supportsDeepSeekReasoningConfiguration(profile)) {
                add(ProviderCompatibilityIssue.DEEPSEEK_REASONING_CONFIGURATION_UNSUPPORTED)
            }
        }
    }

    /**
     * DeepSeek V4 has a binary thinking switch. Omitting it (Sense AUTO) is supported and currently
     * defaults to enabled. Its effort API accepts HIGH; LOW/MEDIUM are accepted but mapped to HIGH.
     * Effort must be omitted when thinking is disabled, and NONE/MINIMAL are not valid DeepSeek
     * effort values when thinking is active.
     */
    private fun supportsDeepSeekReasoningConfiguration(profile: ProviderProfile): Boolean =
        when (profile.thinkingMode) {
            ThinkingMode.DISABLED -> profile.reasoningEffort == ReasoningEffort.DEFAULT
            ThinkingMode.AUTO,
            ThinkingMode.ENABLED,
            -> profile.reasoningEffort !in setOf(
                ReasoningEffort.NONE,
                ReasoningEffort.MINIMAL,
            )
        }

    /**
     * Selects an explicit mode for documents written before `thinking_mode` was persisted.
     *
     * Official DeepSeek profiles deliberately migrate to the low-latency disabled mode because
     * omitting its switch currently defaults to thinking enabled. Other endpoints retain their
     * historical provider-default behavior; Sense must not guess third-party capabilities.
     */
    fun thinkingModeForLegacyProfile(baseUrl: String): ThinkingMode =
        if (isOfficialDeepSeek(baseUrl)) ThinkingMode.DISABLED else ThinkingMode.AUTO

    /**
     * DeepSeek retired both historical aliases on 2026-07-24. Migrate only those exact aliases on
     * the official host; account-specific models and compatible gateways remain untouched.
     */
    fun activeModelForSavedProfile(baseUrl: String, model: String): String =
        if (
            isOfficialDeepSeek(baseUrl) &&
            model.lowercase(Locale.ROOT) in RETIRED_DEEPSEEK_MODEL_ALIASES
        ) {
            "deepseek-v4-flash"
        } else {
            model
        }

    fun isOfficialDeepSeek(baseUrl: String): Boolean {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return false
        return host.lowercase(Locale.ROOT) == OFFICIAL_DEEPSEEK_HOST
    }

    private const val OFFICIAL_DEEPSEEK_HOST = "api.deepseek.com"
    private val RETIRED_DEEPSEEK_MODEL_ALIASES = setOf(
        "deepseek-chat",
        "deepseek-reasoner",
    )
}

enum class ProviderCompatibilityIssue {
    DEEPSEEK_REQUIRES_CHAT_COMPLETIONS,
    DEEPSEEK_REASONING_CONFIGURATION_UNSUPPORTED,
}
