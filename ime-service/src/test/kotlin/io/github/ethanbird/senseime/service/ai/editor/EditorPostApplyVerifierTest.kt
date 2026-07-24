package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.SelectionAfter
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPostApplyVerifierTest {
    @Test
    fun selectionOnlyEndIsVerifiedBeforeTheCollapsedCursor() {
        val plan = selectionOnlyPlan(SelectionAfter.END)

        assertTrue(
            EditorPostApplyVerifier.verifySelectionOnly(
                plan,
                SelectionOnlyPostApplyObservation(
                    selection = TextSelectionV1(11, 11),
                    textBeforeCursor = "prefixSense",
                ),
            ),
        )
    }

    @Test
    fun selectionOnlyStartIsVerifiedAfterTheCollapsedCursor() {
        val plan = selectionOnlyPlan(SelectionAfter.START)

        assertTrue(
            EditorPostApplyVerifier.verifySelectionOnly(
                plan,
                SelectionOnlyPostApplyObservation(
                    selection = TextSelectionV1(6, 6),
                    textAfterCursor = "Sense suffix",
                ),
            ),
        )
    }

    @Test
    fun selectionOnlySelectedReplacementIsVerifiedDirectly() {
        val plan = selectionOnlyPlan(SelectionAfter.SELECT_REPLACEMENT)

        assertTrue(
            EditorPostApplyVerifier.verifySelectionOnly(
                plan,
                SelectionOnlyPostApplyObservation(
                    selection = TextSelectionV1(6, 11),
                    selectedText = "Sense",
                ),
            ),
        )
    }

    @Test
    fun selectionOnlyVerificationRejectsWrongPositionOrText() {
        val plan = selectionOnlyPlan(SelectionAfter.END)

        assertFalse(
            EditorPostApplyVerifier.verifySelectionOnly(
                plan,
                SelectionOnlyPostApplyObservation(
                    selection = TextSelectionV1(10, 10),
                    textBeforeCursor = "prefixSense",
                ),
            ),
        )
        assertFalse(
            EditorPostApplyVerifier.verifySelectionOnly(
                plan,
                SelectionOnlyPostApplyObservation(
                    selection = TextSelectionV1(11, 11),
                    textBeforeCursor = "prefixWrong",
                ),
            ),
        )
    }

    private fun selectionOnlyPlan(selectionAfter: SelectionAfter): EditorPatchPlan.Replace {
        val snapshot = EditorAiTestFixtures.selectionSnapshot()
        val guarded = EditorAiTestFixtures.accepted(
            EditorAiTestFixtures.guardInput(
                snapshot,
                EditorAiTestFixtures.replacePatch(
                    snapshot,
                    text = "Sense",
                    selectionAfter = selectionAfter,
                ),
            ),
        )
        return EditorPatchPlanner.plan(guarded, 34) as EditorPatchPlan.Replace
    }
}
