package io.github.ethanbird.senseime.brain.api

import java.util.Locale

/**
 * Secret-free defaults for the Provider picker.
 *
 * The catalog deliberately contains only OpenAI-compatible endpoints already supported by the
 * Brain transport. API keys never enter this model and remain owned by ProviderSettingsStore.
 * Models stay editable in Advanced settings because availability can vary by account and region.
 */
object ProviderPresetCatalog {
    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = ProviderPresetId.OPENAI,
            displayName = "OpenAI",
            providerName = "OpenAI",
            baseUrl = ProviderProfile.DEFAULT_OPENAI_BASE_URL,
            model = "gpt-4.1-mini",
            apiStyle = ProviderApiStyle.OPENAI_RESPONSES,
            structuredOutput = StructuredOutputMode.JSON_SCHEMA,
        ),
        ProviderPreset(
            id = ProviderPresetId.DEEPSEEK,
            displayName = "DeepSeek",
            providerName = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-v4-flash",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            structuredOutput = StructuredOutputMode.JSON_OBJECT,
        ),
        ProviderPreset(
            id = ProviderPresetId.GEMINI,
            displayName = "Google Gemini",
            providerName = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            model = "gemini-2.5-flash",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            structuredOutput = StructuredOutputMode.PROMPT_ONLY,
        ),
        ProviderPreset(
            id = ProviderPresetId.OPENROUTER,
            displayName = "OpenRouter",
            providerName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openai/gpt-4.1-mini",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            structuredOutput = StructuredOutputMode.PROMPT_ONLY,
        ),
        ProviderPreset(
            id = ProviderPresetId.SILICONFLOW,
            displayName = "硅基流动",
            providerName = "SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "Qwen/Qwen3-8B",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            structuredOutput = StructuredOutputMode.PROMPT_ONLY,
        ),
        ProviderPreset(
            id = ProviderPresetId.KIMI,
            displayName = "月之暗面 Kimi",
            providerName = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            model = "moonshot-v1-8k",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            structuredOutput = StructuredOutputMode.PROMPT_ONLY,
        ),
        ProviderPreset(
            id = ProviderPresetId.ZHIPU,
            displayName = "智谱 GLM",
            providerName = "Zhipu",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-4-flash",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            structuredOutput = StructuredOutputMode.PROMPT_ONLY,
        ),
        ProviderPreset(
            id = ProviderPresetId.QWEN,
            displayName = "通义百炼",
            providerName = "Qwen",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-plus",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            structuredOutput = StructuredOutputMode.PROMPT_ONLY,
        ),
        ProviderPreset(
            id = ProviderPresetId.CUSTOM,
            displayName = "自定义 OpenAI-compatible",
            providerName = "Custom",
            baseUrl = ProviderProfile.DEFAULT_OPENAI_BASE_URL,
            model = "gpt-4.1-mini",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            structuredOutput = StructuredOutputMode.PROMPT_ONLY,
        ),
    )

    val default: ProviderPreset
        get() = requirePreset(ProviderPresetId.OPENAI)

    fun requirePreset(id: ProviderPresetId): ProviderPreset =
        presets.first { it.id == id }

    /**
     * Recovers the picker state for profiles saved before presets existed.
     *
     * A model override does not turn a known endpoint into Custom. This preserves expert changes
     * while still keeping the common settings screen compact.
     */
    fun detect(profile: ProviderProfile): ProviderPreset =
        when {
            ProviderCompatibility.isOfficialDeepSeek(profile.baseUrl) ->
                requirePreset(ProviderPresetId.DEEPSEEK)
            else -> presets.firstOrNull {
                it.id != ProviderPresetId.CUSTOM &&
                    normalizeBaseUrl(it.baseUrl) == normalizeBaseUrl(profile.baseUrl)
            } ?: requirePreset(ProviderPresetId.CUSTOM)
        }

    private fun normalizeBaseUrl(value: String): String =
        value.trim().trimEnd('/').lowercase(Locale.ROOT)
}

enum class ProviderPresetId {
    OPENAI,
    DEEPSEEK,
    GEMINI,
    OPENROUTER,
    SILICONFLOW,
    KIMI,
    ZHIPU,
    QWEN,
    CUSTOM,
}

data class ProviderPreset(
    val id: ProviderPresetId,
    val displayName: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiStyle: ProviderApiStyle,
    val structuredOutput: StructuredOutputMode,
) {
    val isCustom: Boolean
        get() = id == ProviderPresetId.CUSTOM

    fun profile(id: String = "primary"): ProviderProfile =
        ProviderProfile(
            id = id,
            displayName = providerName,
            apiStyle = apiStyle,
            baseUrl = baseUrl,
            model = model,
            thinkingMode = ThinkingMode.DISABLED,
            reasoningEffort = ReasoningEffort.DEFAULT,
            streaming = true,
            structuredOutput = structuredOutput,
        )
}

/**
 * User-facing latency/capability choice stored through the existing, versioned profile fields.
 */
enum class ProviderReasoningStrength {
    QUICK,
    BALANCED,
    DEEP;

    fun applyTo(profile: ProviderProfile): ProviderProfile =
        when (this) {
            QUICK -> profile.copy(
                thinkingMode = ThinkingMode.DISABLED,
                reasoningEffort = ReasoningEffort.DEFAULT,
            )
            BALANCED -> profile.copy(
                thinkingMode = ThinkingMode.AUTO,
                reasoningEffort = ReasoningEffort.DEFAULT,
            )
            DEEP -> profile.copy(
                thinkingMode = ThinkingMode.ENABLED,
                reasoningEffort = ReasoningEffort.HIGH,
            )
        }

    companion object {
        fun from(profile: ProviderProfile): ProviderReasoningStrength =
            when {
                profile.thinkingMode == ThinkingMode.DISABLED -> QUICK
                profile.thinkingMode == ThinkingMode.ENABLED -> DEEP
                else -> BALANCED
            }
    }
}
