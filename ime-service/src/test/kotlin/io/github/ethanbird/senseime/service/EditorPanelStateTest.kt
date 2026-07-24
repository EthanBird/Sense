package io.github.ethanbird.senseime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPanelStateTest {
    @Test
    fun hostSelectionAndShiftSelectionModeAreIndependent() {
        val started = EditorSelectionState()
            .withHostSelection(true)

        assertTrue(started.hasSelection)
        assertFalse(started.selectionMode)

        val extending = started.toggleSelectionMode()
            .withHostSelection(false)

        assertFalse(extending.hasSelection)
        assertTrue(extending.selectionMode)
        assertFalse(extending.resetSelectionMode().selectionMode)
    }

    @Test
    fun acceptedCopyAndCutProduceOnlyTheirOwnFeedback() {
        val copied = EditorContextActionPolicy.resolve(EditorContextCommand.COPY, accepted = true)
        assertEquals(EditorFeedback.COPIED, copied.feedback)
        assertFalse(copied.leaveEditor)
        assertFalse(copied.resetSelectionMode)

        val cut = EditorContextActionPolicy.resolve(EditorContextCommand.CUT, accepted = true)
        assertEquals(EditorFeedback.CUT, cut.feedback)
        assertFalse(cut.leaveEditor)
        assertTrue(cut.resetSelectionMode)
    }

    @Test
    fun acceptedPasteLeavesEditorWithoutClaimingCopyFeedback() {
        val outcome = EditorContextActionPolicy.resolve(
            EditorContextCommand.PASTE,
            accepted = true,
        )

        assertNull(outcome.feedback)
        assertTrue(outcome.leaveEditor)
        assertTrue(outcome.resetSelectionMode)
    }

    @Test
    fun rejectedContextActionsHaveNoSuccessEffects() {
        EditorContextCommand.entries.forEach { command ->
            assertEquals(
                EditorContextOutcome(),
                EditorContextActionPolicy.resolve(command, accepted = false),
            )
        }
    }
}
