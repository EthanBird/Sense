package io.github.ethanbird.senseime.service

internal enum class EditorHistoryHaptic {
    CONFIRM,
    REJECT,
}

internal data class EditorHistoryFeedback(
    val haptic: EditorHistoryHaptic,
)

/** Maps an editor-history result to feedback that never paints over the keyboard. */
internal object EditorHistoryFeedbackPolicy {
    fun afterAttempt(accepted: Boolean): EditorHistoryFeedback = if (accepted) {
        EditorHistoryFeedback(
            haptic = EditorHistoryHaptic.CONFIRM,
        )
    } else {
        EditorHistoryFeedback(
            haptic = EditorHistoryHaptic.REJECT,
        )
    }
}
