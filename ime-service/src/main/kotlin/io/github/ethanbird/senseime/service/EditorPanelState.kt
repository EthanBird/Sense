package io.github.ethanbird.senseime.service

internal data class EditorSelectionState(
    val hasSelection: Boolean = false,
    val selectionMode: Boolean = false,
) {
    fun withHostSelection(value: Boolean): EditorSelectionState =
        if (hasSelection == value) this else copy(hasSelection = value)

    fun toggleSelectionMode(): EditorSelectionState =
        copy(selectionMode = !selectionMode)

    fun resetSelectionMode(): EditorSelectionState =
        if (!selectionMode) this else copy(selectionMode = false)
}

internal enum class EditorContextCommand {
    COPY,
    CUT,
    PASTE,
}

internal enum class EditorFeedback {
    COPIED,
    CUT,
}

internal data class EditorContextOutcome(
    val feedback: EditorFeedback? = null,
    val leaveEditor: Boolean = false,
    val resetSelectionMode: Boolean = false,
)

/**
 * Converts the host InputConnection acknowledgement into local UI effects.
 * A rejected host command must never produce a success Toast or close the
 * editor panel.
 */
internal object EditorContextActionPolicy {
    fun resolve(command: EditorContextCommand, accepted: Boolean): EditorContextOutcome {
        if (!accepted) return EditorContextOutcome()
        return when (command) {
            EditorContextCommand.COPY -> EditorContextOutcome(
                feedback = EditorFeedback.COPIED,
            )

            EditorContextCommand.CUT -> EditorContextOutcome(
                feedback = EditorFeedback.CUT,
                resetSelectionMode = true,
            )

            EditorContextCommand.PASTE -> EditorContextOutcome(
                leaveEditor = true,
                resetSelectionMode = true,
            )
        }
    }
}
