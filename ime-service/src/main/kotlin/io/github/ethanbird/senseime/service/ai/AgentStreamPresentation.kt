package io.github.ethanbird.senseime.service.ai

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessLimits
import io.github.ethanbird.senseime.ai.protocol.HarnessPhase

/**
 * Bounded public presentation state for one Agent run.
 *
 * The description is a concise user-facing progress sentence, never a hidden-reasoning buffer.
 * Keeping preview and description together makes a repair reset atomic from the UI's perspective.
 */
internal class AgentStreamPresentation(
    private val maxPreviewChars: Int = 4_096,
    private val maxDescriptionChars: Int =
        BoundedHarnessLimits.DEFAULT_MAX_DESCRIPTION_CHARS,
) {
    private val previewBuilder = StringBuilder()
    private val descriptionBuilder = StringBuilder()

    init {
        require(maxPreviewChars > 0)
        require(maxDescriptionChars > 0)
    }

    val preview: String
        get() = rollingTail(previewBuilder, maxPreviewChars)

    val description: String
        get() = descriptionBuilder.toString()

    fun appendPreview(text: String) {
        if (text.isEmpty()) return
        previewBuilder.append(text)
        // Trim in chunks rather than once per provider token. This keeps append amortized while
        // the public value remains a strict rolling tail window.
        if (previewBuilder.length > maxPreviewChars + PREVIEW_TRIM_SLACK_CHARS) {
            val proposedStart = previewBuilder.length - maxPreviewChars
            val safeStart = safeUtf16Start(previewBuilder, proposedStart)
            previewBuilder.delete(0, safeStart)
        }
    }

    fun appendDescription(text: String) {
        appendHeadBounded(descriptionBuilder, text, maxDescriptionChars)
    }

    fun replace(
        preview: String,
        description: String,
    ) {
        previewBuilder.setLength(0)
        descriptionBuilder.setLength(0)
        appendPreview(preview)
        appendDescription(description)
    }

    /** Reconciles the surface with the validated terminal result, including an empty result. */
    fun complete(authoritativePreview: String) {
        replace(preview = authoritativePreview, description = "")
    }

    fun descriptionOr(fallback: String): String =
        if (descriptionBuilder.isEmpty()) fallback else descriptionBuilder.toString()

    fun reset() {
        previewBuilder.setLength(0)
        descriptionBuilder.setLength(0)
    }

    private fun appendHeadBounded(target: StringBuilder, text: String, limit: Int) {
        val remaining = limit - target.length
        if (remaining > 0) target.append(text.take(remaining))
    }

    private fun rollingTail(target: StringBuilder, limit: Int): String {
        if (target.length <= limit) return target.toString()
        val proposedStart = target.length - (limit - ELLIPSIS.length)
        val safeStart = safeUtf16Start(target, proposedStart)
        return ELLIPSIS + target.substring(safeStart)
    }

    private fun safeUtf16Start(text: CharSequence, proposedStart: Int): Int {
        if (
            proposedStart in 1 until text.length &&
            text[proposedStart - 1].isHighSurrogate() &&
            text[proposedStart].isLowSurrogate()
        ) {
            return proposedStart + 1
        }
        return proposedStart
    }

    private companion object {
        const val PREVIEW_TRIM_SLACK_CHARS = 1_024
        const val ELLIPSIS = "…"
    }
}

internal fun agentStatusLabel(event: AiEvent.Status): String = when (event.label) {
    "provider_recovering" -> "连接中断，正在校准续接…"
    "provider_repairing" -> "响应格式需校准，正在修正…"
    else -> when (event.phase) {
        HarnessPhase.CONNECTING -> "正在连接模型…"
        HarnessPhase.UNDERSTANDING -> "正在理解输入框…"
        HarnessPhase.GENERATING -> "正在生成…"
        HarnessPhase.VALIDATING -> "正在校验编辑结果…"
    }
}

internal fun String.isRecoveryStatusLabel(): Boolean =
    this == "provider_recovering" || this == "provider_repairing"

internal fun agentStatusForPresentation(
    event: AiEvent.Status,
    currentDescription: String,
): String {
    val providerStatus = agentStatusLabel(event)
    return if (event.label.isRecoveryStatusLabel()) {
        providerStatus
    } else {
        currentDescription.takeIf(String::isNotEmpty) ?: providerStatus
    }
}
