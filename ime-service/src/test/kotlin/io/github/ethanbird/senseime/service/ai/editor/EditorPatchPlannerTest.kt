package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SelectionAfter
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPatchPlannerTest {
    @Test
    fun api34UsesReplaceTextAndBalancedBatchForEndCursor() {
        val guarded = EditorAiTestFixtures.accepted()
        val plan = EditorPatchPlanner.plan(guarded, sdkInt = 34) as EditorPatchPlan.Replace

        assertEquals(EditorApplyPath.API_34_REPLACE_TEXT, plan.path)
        assertEquals(
            listOf(
                EditorApplyCommand.BeginBatchEdit,
                EditorApplyCommand.ReplaceText(0, 11, "updated"),
                EditorApplyCommand.EndBatchEdit,
            ),
            plan.orderedCommands,
        )
        assertEquals(TextSelectionV1(7, 7), plan.expectedState.selection)
        assertEquals("updated", plan.expectedState.textWindow)
        assertEquals("hello world", plan.checkpoint.originalTargetText)
    }

    @Test
    fun api34AddsExplicitSelectionOnlyWhenReplaceTextCannotExpressIt() {
        val snapshot = EditorAiTestFixtures.fullSnapshot()
        val start = EditorAiTestFixtures.accepted(
            EditorAiTestFixtures.guardInput(
                snapshot,
                EditorAiTestFixtures.replacePatch(
                    snapshot,
                    text = "new",
                    selectionAfter = SelectionAfter.START,
                ),
            ),
        )
        val selected = EditorAiTestFixtures.accepted(
            EditorAiTestFixtures.guardInput(
                snapshot,
                EditorAiTestFixtures.replacePatch(
                    snapshot,
                    text = "new",
                    selectionAfter = SelectionAfter.SELECT_REPLACEMENT,
                ),
            ),
        )

        val startPlan = EditorPatchPlanner.plan(start, 36) as EditorPatchPlan.Replace
        val selectPlan = EditorPatchPlanner.plan(selected, 34) as EditorPatchPlan.Replace
        assertTrue(
            startPlan.bodyCommands.contains(EditorApplyCommand.SetSelection(0, 0)),
        )
        assertTrue(
            selectPlan.bodyCommands.contains(EditorApplyCommand.SetSelection(0, 3)),
        )
    }

    @Test
    fun api29To33UsesFinishSelectCommitSelectFallback() {
        for (sdk in 29..33) {
            val plan = EditorPatchPlanner.plan(
                EditorAiTestFixtures.accepted(),
                sdk,
            ) as EditorPatchPlan.Replace

            assertEquals(EditorApplyPath.API_29_33_BATCH_FALLBACK, plan.path)
            assertEquals(
                listOf(
                    EditorApplyCommand.BeginBatchEdit,
                    EditorApplyCommand.FinishComposingText,
                    EditorApplyCommand.SetSelection(0, 11),
                    EditorApplyCommand.CommitText("updated"),
                    EditorApplyCommand.SetSelection(7, 7),
                    EditorApplyCommand.EndBatchEdit,
                ),
                plan.orderedCommands,
            )
        }
    }

    @Test
    fun selectionPatchPreservesAbsoluteOffsetsAndBuildsCheckpoint() {
        val snapshot = EditorAiTestFixtures.fullSnapshot(
            selection = TextSelectionV1(6, 11),
            target = PatchTarget.SELECTION,
        )
        val guarded = EditorAiTestFixtures.accepted(
            EditorAiTestFixtures.guardInput(
                snapshot,
                EditorAiTestFixtures.replacePatch(snapshot, text = "Sense"),
            ),
        )

        val plan = EditorPatchPlanner.plan(guarded, 34) as EditorPatchPlan.Replace
        assertEquals(
            EditorApplyCommand.ReplaceText(6, 11, "Sense"),
            plan.bodyCommands.single(),
        )
        assertEquals("world", plan.checkpoint.originalTargetText)
        assertEquals("hello Sense", plan.expectedState.textWindow)
        assertEquals(TextSelectionV1(11, 11), plan.expectedState.selection)
    }

    @Test
    fun selectionOnlyWindowProducesLocalExpectedTextWithAbsoluteSelection() {
        val snapshot = EditorAiTestFixtures.selectionSnapshot()
        val guarded = EditorAiTestFixtures.accepted(
            EditorAiTestFixtures.guardInput(
                snapshot,
                EditorAiTestFixtures.replacePatch(
                    snapshot,
                    text = "Sense",
                    selectionAfter = SelectionAfter.SELECT_REPLACEMENT,
                ),
            ),
        )

        val plan = EditorPatchPlanner.plan(guarded, 33) as EditorPatchPlan.Replace
        assertEquals(6, plan.expectedState.textStartOffset)
        assertEquals("Sense", plan.expectedState.textWindow)
        assertEquals(TextSelectionV1(6, 11), plan.expectedState.selection)
    }

    @Test
    fun noChangeProducesNoInputConnectionCommands() {
        val snapshot = EditorAiTestFixtures.fullSnapshot()
        val guarded = EditorAiTestFixtures.accepted(
            EditorAiTestFixtures.guardInput(
                snapshot,
                EditorAiTestFixtures.noChangePatch(snapshot),
            ),
        )

        assertSame(EditorPatchPlan.NoChange, EditorPatchPlanner.plan(guarded, 34))
    }

    @Test(expected = IllegalArgumentException::class)
    fun apiBelowProjectMinimumIsRejected() {
        EditorPatchPlanner.plan(EditorAiTestFixtures.accepted(), 28)
    }

    @Test
    fun endBatchIsSeparatedAsMandatoryFinallyCommand() {
        val plan =
            EditorPatchPlanner.plan(
                EditorAiTestFixtures.accepted(),
                34,
            ) as EditorPatchPlan.Replace

        assertEquals(
            listOf(EditorApplyCommand.EndBatchEdit),
            plan.finallyCommands,
        )
        assertTrue(plan.bodyCommands.none { it is EditorApplyCommand.EndBatchEdit })
    }
}
