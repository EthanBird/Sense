package io.github.ethanbird.senseime.brain.api

import java.util.Locale

/**
 * Long-lived provider directory aligned with jcode's models.dev registry plus its static providers.
 * All entries use protocols already implemented by Sense; models and endpoints remain editable.
 */
object ProviderPresetCatalog {
    private fun compatible(
        id: ProviderPresetId,
        displayName: String,
        baseUrl: String,
        model: String,
        structuredOutput: StructuredOutputMode = StructuredOutputMode.PROMPT_ONLY,
        authMode: ProviderAuthMode = ProviderAuthMode.API_KEY,
    ) = ProviderPreset(
        id = id,
        displayName = displayName,
        providerName = displayName,
        baseUrl = baseUrl,
        model = model,
        apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
        structuredOutput = structuredOutput,
        authMode = authMode,
    )

    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(ProviderPresetId.OPENAI, "OpenAI API", "OpenAI", ProviderProfile.DEFAULT_OPENAI_BASE_URL, "gpt-5.6-sol", ProviderApiStyle.OPENAI_RESPONSES, StructuredOutputMode.JSON_SCHEMA),
        ProviderPreset(ProviderPresetId.CODEX_SUBSCRIPTION, "Codex 订阅", "Codex", "https://chatgpt.com/backend-api/codex", "gpt-5.6-sol", ProviderApiStyle.OPENAI_RESPONSES, StructuredOutputMode.JSON_SCHEMA, ProviderAuthMode.CODEX_SUBSCRIPTION),
        compatible(ProviderPresetId.ANTHROPIC, "Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-6"),
        compatible(ProviderPresetId.GOOGLE, "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3-flash-preview"),
        compatible(ProviderPresetId.DEEPSEEK, "DeepSeek", "https://api.deepseek.com/v1", "deepseek-v4-flash", StructuredOutputMode.JSON_OBJECT),
        compatible(ProviderPresetId.ZHIPUAI, "智谱 BigModel", "https://open.bigmodel.cn/api/paas/v4", "glm-5.2"),
        compatible(ProviderPresetId.ZHIPUAI_CODING_PLAN, "智谱 Coding Plan", "https://open.bigmodel.cn/api/coding/paas/v4", "glm-5.2"),
        compatible(ProviderPresetId.MISTRAL, "Mistral", "https://api.mistral.ai/v1", "mistral-large-latest"),
        compatible(ProviderPresetId.OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-5.5"),
        compatible(ProviderPresetId.GROQ, "Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
        compatible(ProviderPresetId.TOGETHER_AI, "Together AI", "https://api.together.xyz/v1", "openai/gpt-oss-120b"),
        compatible(ProviderPresetId.ALIBABA_CN, "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.7-plus"),
        compatible(ProviderPresetId.ALIBABA_CODING_PLAN_CN, "阿里云 Coding Plan", "https://coding.dashscope.aliyuncs.com/v1", "qwen3.7-plus"),
        compatible(ProviderPresetId.ALIBABA_TOKEN_PLAN_CN, "阿里云 Token Plan（中国）", "https://coding.dashscope.aliyuncs.com/v1", "qwen3.7-plus"),
        compatible(ProviderPresetId.ALIBABA_TOKEN_PLAN, "Alibaba Token Plan", "https://coding-intl.dashscope.aliyuncs.com/v1", "qwen3.7-plus"),
        compatible(ProviderPresetId.MOONSHOT_AI, "Moonshot AI", "https://api.moonshot.ai/v1", "kimi-k3"),
        compatible(ProviderPresetId.MINIMAX, "MiniMax", "https://api.minimax.io/v1", "MiniMax-M3"),
        compatible(ProviderPresetId.MINIMAX_CODING_PLAN, "MiniMax Coding Plan", "https://api.minimax.io/v1", "MiniMax-M3"),
        compatible(ProviderPresetId.SILICONFLOW, "SiliconFlow", "https://api.siliconflow.com/v1", "deepseek-ai/DeepSeek-V4"),
        compatible(ProviderPresetId.TENCENT_CODING_PLAN, "腾讯云 Coding Plan", "https://api.lkeap.cloud.tencent.com/coding/v3", "tc-code-latest"),
        compatible(ProviderPresetId.TENCENT_TOKENHUB, "腾讯云 TokenHub", "https://tokenhub.tencentmaas.com/v1", "hy3"),
        compatible(ProviderPresetId.ZAI, "Z.AI", "https://api.z.ai/api/paas/v4", "glm-5.2"),
        compatible(ProviderPresetId.ZAI_CODING_PLAN, "Z.AI Coding Plan", "https://api.z.ai/api/coding/paas/v4", "glm-5.2"),
        compatible(ProviderPresetId.XIAOMI, "Xiaomi MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.5-pro"),
        compatible(ProviderPresetId.XIAOMI_TOKEN_PLAN_CN, "Xiaomi Token Plan", "https://token-plan-cn.xiaomimimo.com/v1", "mimo-v2.5-pro"),
        compatible(ProviderPresetId.OLLAMA_CLOUD, "Ollama Cloud", "https://ollama.com/v1", "gpt-oss:120b"),
        compatible(ProviderPresetId.KIMI_FOR_CODING, "Kimi for Coding", "https://api.kimi.com/coding/v1", "k3"),
        compatible(ProviderPresetId.TENCENT_TOKENHUB_EP, "腾讯云 TokenHub EP", "https://tokenhub.tencentmaas.com/plan/v3", "auto"),
        compatible(ProviderPresetId.CUSTOM, "自定义 OpenAI-compatible", ProviderProfile.DEFAULT_OPENAI_BASE_URL, "gpt-5-mini", StructuredOutputMode.PROMPT_ONLY),
    )

    val default: ProviderPreset get() = requirePreset(ProviderPresetId.OPENAI)

    fun requirePreset(id: ProviderPresetId): ProviderPreset = presets.first { it.id == id }

    fun detect(profile: ProviderProfile): ProviderPreset = when {
        ProviderCompatibility.isOfficialDeepSeek(profile.baseUrl) -> requirePreset(ProviderPresetId.DEEPSEEK)
        else -> presets.firstOrNull {
            it.id != ProviderPresetId.CUSTOM && normalizeBaseUrl(it.baseUrl) == normalizeBaseUrl(profile.baseUrl)
        } ?: requirePreset(ProviderPresetId.CUSTOM)
    }

    private fun normalizeBaseUrl(value: String) = value.trim().trimEnd('/').lowercase(Locale.ROOT)
}

enum class ProviderPresetId {
    OPENAI, CODEX_SUBSCRIPTION, ANTHROPIC, GOOGLE, DEEPSEEK, ZHIPUAI,
    ZHIPUAI_CODING_PLAN, MISTRAL, OPENROUTER, GROQ, TOGETHER_AI, ALIBABA_CN,
    ALIBABA_CODING_PLAN_CN, ALIBABA_TOKEN_PLAN_CN, ALIBABA_TOKEN_PLAN, MOONSHOT_AI,
    MINIMAX, MINIMAX_CODING_PLAN, SILICONFLOW, TENCENT_CODING_PLAN, TENCENT_TOKENHUB,
    ZAI, ZAI_CODING_PLAN, XIAOMI, XIAOMI_TOKEN_PLAN_CN, OLLAMA_CLOUD, KIMI_FOR_CODING,
    TENCENT_TOKENHUB_EP, CUSTOM,
}

data class ProviderPreset(
    val id: ProviderPresetId,
    val displayName: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiStyle: ProviderApiStyle,
    val structuredOutput: StructuredOutputMode,
    val authMode: ProviderAuthMode = ProviderAuthMode.API_KEY,
) {
    val isCustom get() = id == ProviderPresetId.CUSTOM
    fun profile(id: String = "primary") = ProviderProfile(
        id = id,
        displayName = providerName,
        authMode = authMode,
        apiStyle = apiStyle,
        baseUrl = baseUrl,
        model = model,
        thinkingMode = ThinkingMode.DISABLED,
        reasoningEffort = ReasoningEffort.DEFAULT,
        streaming = true,
        structuredOutput = structuredOutput,
    )
}

enum class ProviderReasoningStrength {
    QUICK, BALANCED, DEEP;
    fun applyTo(profile: ProviderProfile): ProviderProfile = when (this) {
        QUICK -> profile.copy(thinkingMode = ThinkingMode.DISABLED, reasoningEffort = ReasoningEffort.DEFAULT)
        BALANCED -> profile.copy(thinkingMode = ThinkingMode.AUTO, reasoningEffort = ReasoningEffort.DEFAULT)
        DEEP -> profile.copy(thinkingMode = ThinkingMode.ENABLED, reasoningEffort = ReasoningEffort.HIGH)
    }
    companion object {
        fun from(profile: ProviderProfile) = when {
            profile.thinkingMode == ThinkingMode.DISABLED -> QUICK
            profile.thinkingMode == ThinkingMode.ENABLED -> DEEP
            else -> BALANCED
        }
    }
}
