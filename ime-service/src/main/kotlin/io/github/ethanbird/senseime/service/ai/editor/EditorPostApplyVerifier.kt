package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1

/**
 * Minimal fresh reads used after mutating an editor that only exposed the original selection.
 *
 * Requiring a second editable selection snapshot would reject valid START/END results because
 * those intentionally collapse the selection. Instead, the adapter proves the absolute
 * selection and the replacement adjacent to (or inside) that selection.
 */
data class SelectionOnlyPostApplyObservation(
    val selection: TextSelectionV1?,
    val textBeforeCursor: String? = null,
    val textAfterCursor: String? = null,
    val selectedText: String? = null,
)

object EditorPostApplyVerifier {
    fun verifySelectionOnly(
        plan: EditorPatchPlan.Replace,
        observation: SelectionOnlyPostApplyObservation,
    ): Boolean {
        val expectedSelection = plan.expectedState.selection
        if (observation.selection != expectedSelection) return false

        val replacement = plan.expectedState.textWindow
        val targetStart = plan.checkpoint.targetRange.start
        val replacementEnd = targetStart + replacement.length
        return when {
            !expectedSelection.isCollapsed ->
                observation.selectedText == replacement

            expectedSelection.start == targetStart ->
                observation.textAfterCursor?.startsWith(replacement) == true

            expectedSelection.start == replacementEnd ->
                observation.textBeforeCursor?.endsWith(replacement) == true

            else -> false
        }
    }
}
