package io.github.ethanbird.senseime.service

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorHistoryFeedbackPolicyTest {
    @Test
    fun `successful undo uses quiet haptic-only feedback`() {
        val feedback = EditorHistoryFeedbackPolicy.afterAttempt(accepted = true)

        assertEquals(EditorHistoryHaptic.CONFIRM, feedback.haptic)
    }

    @Test
    fun `rejected history command uses a distinct haptic-only result`() {
        val feedback = EditorHistoryFeedbackPolicy.afterAttempt(accepted = false)

        assertEquals(EditorHistoryHaptic.REJECT, feedback.haptic)
    }
}
