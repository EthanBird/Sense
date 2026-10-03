package io.github.ethanbird.senseime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorCompositionSelectionPolicyTest {
    @Test fun delayedAcknowledgmentOfOurCommitDoesNotCancelTheReplayedNextComposition() {
        val connection = Any()
        val fence = ReliableCommitSelectionFence(clock = { 100L })
        fence.expect(editorSessionId = 7, connectionIdentity = connection, cursor = 5)
        // 我喜欢北京 was committed; nihao has already been replayed locally. Android then
        // reports the older commit's caret=5, missing composing span before nihao's callbacks.
        val own = fence.acknowledge(7, connection, 5, 5)
        assertTrue(own)
        assertFalse(EditorCompositionSelectionPolicy.shouldCancelLocalComposition(
            hasActiveComposition = true, newSelectionStart = 5, newSelectionEnd = 5,
            candidatesStart = -1, candidatesEnd = -1, acknowledgesOwnCommit = own,
        ))
        // The fence is one-shot. A subsequent genuine host cancellation still clears input.
        assertTrue(EditorCompositionSelectionPolicy.shouldCancelLocalComposition(
            hasActiveComposition = true, newSelectionStart = 5, newSelectionEnd = 5,
            candidatesStart = -1, candidatesEnd = -1,
            acknowledgesOwnCommit = fence.acknowledge(7, connection, 5, 5),
        ))
    }
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
