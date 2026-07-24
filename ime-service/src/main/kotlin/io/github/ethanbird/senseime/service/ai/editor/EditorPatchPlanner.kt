package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.SelectionAfter
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1

enum class EditorApplyPath {
    API_34_REPLACE_TEXT,
    API_29_33_BATCH_FALLBACK,
}

sealed interface EditorApplyCommand {
    data object BeginBatchEdit : EditorApplyCommand

    data object EndBatchEdit : EditorApplyCommand

    data object FinishComposingText : EditorApplyCommand

    data class SetSelection(
        val start: Int,
        val end: Int,
    ) : EditorApplyCommand

    /**
     * Maps to InputConnection.replaceText(start, end, text, newCursorPosition, null).
     */
    data class ReplaceText(
        val start: Int,
        val end: Int,
        val text: String,
        val newCursorPosition: Int = 1,
    ) : EditorApplyCommand

    data class CommitText(
        val text: String,
        val newCursorPosition: Int = 1,
    ) : EditorApplyCommand
}

data class EditorCheckpoint(
    val fieldIdentity: String,
    val editorGeneration: Long,
    val textStartOffset: Int,
    val originalTextWindow: String,
    val originalTextWindowSha256: String,
    val targetRange: TextSelectionV1,
    val originalTargetText: String,
    val originalSelection: TextSelectionV1?,
)

data class ExpectedEditorState(
    val textStartOffset: Int,
    val textWindow: String,
    val textWindowSha256: String,
    val selection: TextSelectionV1,
)

sealed interface EditorPatchPlan {
    data object NoChange : EditorPatchPlan

    /**
     * [finallyCommands] must be executed in a finally block after [beginCommand] is invoked, even
     * when an editor returns false for an intermediate operation or the connection disappears.
     */
    data class Replace(
        val path: EditorApplyPath,
        val checkpoint: EditorCheckpoint,
        val expectedState: ExpectedEditorState,
        val beginCommand: EditorApplyCommand.BeginBatchEdit,
        val bodyCommands: List<EditorApplyCommand>,
        val finallyCommands: List<EditorApplyCommand>,
    ) : EditorPatchPlan {
        val orderedCommands: List<EditorApplyCommand>
            get() = listOf(beginCommand) + bodyCommands + finallyCommands
    }
}

/**
 * Produces an Android-independent, auditable command plan from a guarded patch.
 */
object EditorPatchPlanner {
    const val MIN_SUPPORTED_SDK = 29
    const val REPLACE_TEXT_SDK = 34

    fun plan(
        guardedPatch: GuardedEditorPatch,
        sdkInt: Int,
    ): EditorPatchPlan {
        require(sdkInt >= MIN_SUPPORTED_SDK) {
            "Sense AI editor patches require API $MIN_SUPPORTED_SDK or newer"
        }
        val patch = guardedPatch.patch
        if (patch.operation.type == PatchOperationType.NO_CHANGE) {
            return EditorPatchPlan.NoChange
        }

        val target = requireNotNull(guardedPatch.targetRange) {
            "guarded replace patch has no target range"
        }
        val originalTargetText = requireNotNull(guardedPatch.originalTargetText) {
            "guarded replace patch has no checkpoint text"
        }
        val replacement = requireNotNull(patch.operation.text)
        val selectionAfter = requireNotNull(patch.operation.selectionAfter)
        val live = guardedPatch.liveEditor
        val localStart = target.start - live.textStartOffset
        val localEnd = target.end - live.textStartOffset
        val expectedWindow = live.text.replaceRange(localStart, localEnd, replacement)
        val replacementEnd = target.start + replacement.length
        val expectedSelection = when (selectionAfter) {
            SelectionAfter.START -> TextSelectionV1(target.start, target.start)
            SelectionAfter.END -> TextSelectionV1(replacementEnd, replacementEnd)
            SelectionAfter.SELECT_REPLACEMENT ->
                TextSelectionV1(target.start, replacementEnd)
        }
        val checkpoint = EditorCheckpoint(
            fieldIdentity = live.fieldIdentity,
            editorGeneration = live.editorGeneration,
            textStartOffset = live.textStartOffset,
            originalTextWindow = live.text,
            originalTextWindowSha256 = EditorTextDigest.sha256Utf8(live.text),
            targetRange = target,
            originalTargetText = originalTargetText,
            originalSelection = live.selection,
        )
        val expected = ExpectedEditorState(
            textStartOffset = live.textStartOffset,
            textWindow = expectedWindow,
            textWindowSha256 = EditorTextDigest.sha256Utf8(expectedWindow),
            selection = expectedSelection,
        )

        val path: EditorApplyPath
        val body: List<EditorApplyCommand>
        if (sdkInt >= REPLACE_TEXT_SDK) {
            path = EditorApplyPath.API_34_REPLACE_TEXT
            body = buildList {
                add(
                    EditorApplyCommand.ReplaceText(
                        start = target.start,
                        end = target.end,
                        text = replacement,
                    ),
                )
                // replaceText(..., 1, null) already leaves the cursor at END.
                if (selectionAfter != SelectionAfter.END) {
                    add(
                        EditorApplyCommand.SetSelection(
                            expectedSelection.start,
                            expectedSelection.end,
                        ),
                    )
                }
            }
        } else {
            path = EditorApplyPath.API_29_33_BATCH_FALLBACK
            body = listOf(
                EditorApplyCommand.FinishComposingText,
                EditorApplyCommand.SetSelection(target.start, target.end),
                EditorApplyCommand.CommitText(replacement),
                EditorApplyCommand.SetSelection(
                    expectedSelection.start,
                    expectedSelection.end,
                ),
            )
        }

        return EditorPatchPlan.Replace(
            path = path,
            checkpoint = checkpoint,
            expectedState = expected,
            beginCommand = EditorApplyCommand.BeginBatchEdit,
            bodyCommands = body,
            finallyCommands = listOf(EditorApplyCommand.EndBatchEdit),
        )
    }
}
