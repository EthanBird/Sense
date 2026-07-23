package io.github.ethanbird.senseime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorCompositionSelectionPolicyTest {
    @Test
    fun inactiveSessionIgnoresMissingComposingRange() {
        assertFalse(
            EditorCompositionSelectionPolicy.shouldCancelLocalComposition(
                hasActiveComposition = false,
                newSelectionStart = 4,
                newSelectionEnd = 4,
                candidatesStart = -1,
                candidatesEnd = -1,
            ),
        )
    }

    @Test
    fun collapsedCaretAtComposingEndKeepsTheSession() {
        assertFalse(
            EditorCompositionSelectionPolicy.shouldCancelLocalComposition(
                hasActiveComposition = true,
                newSelectionStart = 12,
                newSelectionEnd = 12,
                candidatesStart = 8,
                candidatesEnd = 12,
            ),
        )
    }

    @Test
    fun movedSelectionOrCanceledHostSpanClearsTheSession() {
        assertTrue(
            EditorCompositionSelectionPolicy.shouldCancelLocalComposition(
                hasActiveComposition = true,
                newSelectionStart = 6,
                newSelectionEnd = 6,
                candidatesStart = 8,
                candidatesEnd = 12,
            ),
        )
        assertTrue(
            EditorCompositionSelectionPolicy.shouldCancelLocalComposition(
                hasActiveComposition = true,
                newSelectionStart = 12,
                newSelectionEnd = 12,
                candidatesStart = -1,
                candidatesEnd = -1,
            ),
        )
    }
}
