package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessLimits
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol

internal data class BrainPreviewReplacePayload(
    val attempt: Int,
    val text: String,
    val description: String,
)

/**
 * Pure validation boundary shared by Android Bundle encoding/decoding and host-side tests.
 */
internal object BrainPreviewReplaceWirePolicy {
    fun requirePayload(
        attempt: Int?,
        text: String?,
        description: String?,
    ): BrainPreviewReplacePayload {
        require(attempt == 2) { "Preview replacement must be attempt 2" }
        val requiredText = requireNotNull(text) { "Missing preview replacement text" }
        val requiredDescription =
            requireNotNull(description) { "Missing preview replacement description" }
        require(requiredText.isNotEmpty()) { "Preview replacement text must not be empty" }
        require(requiredText.length <= SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS) {
            "Preview replacement text is too large"
        }
        require(
            requiredDescription.length <=
                BoundedHarnessLimits.DEFAULT_MAX_DESCRIPTION_CHARS,
        ) {
            "Preview replacement description is too large"
        }
        return BrainPreviewReplacePayload(attempt, requiredText, requiredDescription)
    }
}
