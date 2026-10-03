package io.github.ethanbird.senseime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AssociationDisplayLifecycleTest {
    @Test
    fun holdingVisibleCandidatesInvalidatesExpiryAndReleaseStartsANewIdleTicket() {
        val lifecycle = AssociationDisplayLifecycle()
        val first = lifecycle.arm()
        assertTrue(lifecycle.reveal(first))
        assertTrue(lifecycle.holdInteraction())
        assertFalse(lifecycle.holdInteraction())
        assertFalse(lifecycle.expire(first))
        assertTrue(lifecycle.visible)
        val released = requireNotNull(lifecycle.releaseInteraction())
        assertFalse(lifecycle.expire(first))
        assertNull(lifecycle.releaseInteraction())
        assertTrue(lifecycle.expire(released))
    }

    @Test
    fun cancelAndNewCommitDoNotInheritAnOldGestureHold() {
        val lifecycle = AssociationDisplayLifecycle()
        val first = lifecycle.arm()
        assertFalse(lifecycle.holdInteraction())
        assertTrue(lifecycle.reveal(first))
        assertTrue(lifecycle.holdInteraction())
        lifecycle.cancel()
        assertNull(lifecycle.releaseInteraction())
        assertFalse(lifecycle.visible)
        val next = lifecycle.arm()
        assertTrue(lifecycle.reveal(next))
        assertNull(lifecycle.releaseInteraction())
        assertTrue(lifecycle.expire(next))
    }

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
