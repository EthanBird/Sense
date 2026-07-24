package io.github.ethanbird.senseime.service.ai.editor

/**
 * Selects the authoritative text shown when a guarded AI transaction completes.
 *
 * Streaming preview is only provisional. A no-change patch resolves to the fresh editor read that
 * passed the guard, while a replacement resolves to the exact state the local apply plan verifies.
 */
object EditorCompletionPreviewPolicy {
    fun resolve(
        plan: EditorPatchPlan,
        guardedLiveEditor: LiveEditorRead,
    ): String = when (plan) {
        EditorPatchPlan.NoChange -> guardedLiveEditor.text
        is EditorPatchPlan.Replace -> plan.expectedState.textWindow
    }
}
