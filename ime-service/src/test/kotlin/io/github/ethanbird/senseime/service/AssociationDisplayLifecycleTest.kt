package io.github.ethanbird.senseime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationDisplayLifecycleTest {
    @Test
    fun continuousTypingCancelsPendingRevealAndRejectsItsStaleCallback() {
        val lifecycle = AssociationDisplayLifecycle()
        val ticket = lifecycle.arm()

        assertTrue(lifecycle.waiting)
        assertFalse(lifecycle.visible)

        lifecycle.cancel()

        assertFalse(lifecycle.reveal(ticket))
        assertFalse(lifecycle.waiting)
        assertFalse(lifecycle.visible)
    }

    @Test
    fun visibleAssociationCanBeDismissedAndLaterCommitGetsANewTicket() {
        val lifecycle = AssociationDisplayLifecycle()
        val first = lifecycle.arm()
        assertTrue(lifecycle.reveal(first))
        assertTrue(lifecycle.visible)

        lifecycle.cancel()
        assertFalse(lifecycle.visible)

        val second = lifecycle.arm()
        assertTrue(second != first)
        assertTrue(lifecycle.reveal(second))
        assertTrue(lifecycle.visible)
    }

    @Test
    fun timeoutOnlyExpiresTheCurrentlyVisibleGeneration() {
        val lifecycle = AssociationDisplayLifecycle()
        val first = lifecycle.arm()
        assertTrue(lifecycle.reveal(first))

        val second = lifecycle.arm()
        assertFalse(lifecycle.expire(first))
        assertTrue(lifecycle.reveal(second))
        assertTrue(lifecycle.expire(second))
        assertFalse(lifecycle.visible)
    }
}
