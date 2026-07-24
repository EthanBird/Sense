package io.github.ethanbird.senseime.service.ai

import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessLimits

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
        get() = previewBuilder.toString()

    val description: String
        get() = descriptionBuilder.toString()

    fun appendPreview(text: String) {
        appendBounded(previewBuilder, text, maxPreviewChars)
    }

    fun appendDescription(text: String) {
        appendBounded(descriptionBuilder, text, maxDescriptionChars)
    }

    fun descriptionOr(fallback: String): String =
        if (descriptionBuilder.isEmpty()) fallback else descriptionBuilder.toString()

    fun reset() {
        previewBuilder.setLength(0)
        descriptionBuilder.setLength(0)
    }

    private fun appendBounded(target: StringBuilder, text: String, limit: Int) {
        val remaining = limit - target.length
        if (remaining > 0) target.append(text.take(remaining))
    }
}
