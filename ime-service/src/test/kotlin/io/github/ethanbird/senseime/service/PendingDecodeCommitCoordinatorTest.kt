package io.github.ethanbird.senseime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDecodeCommitCoordinatorTest {
    @Test
    fun wubiReplayKeyIsIsolatedFromLaterDeferredControls() {
        val coordinator = PendingDecodeCommitCoordinator<String>(maximumDeferredInputs = 3)
        val overflow = PendingDecodeCommit.WubiOverflow(
            presentationRevision = 9L,
        )
        coordinator.start(overflow, triggerInput = "FIFTH:e")

        assertEquals(DeferredInputOffer.ACCEPTED, coordinator.defer("BACKSPACE"))
        assertEquals(DeferredInputOffer.ACCEPTED, coordinator.defer("LANGUAGE"))
        assertEquals(
            FinishedPendingDecodeCommit(
                intent = overflow,
                triggerInput = "FIFTH:e",
                followUpInputs = listOf("BACKSPACE", "LANGUAGE"),
            ),
            coordinator.finish(9L),
        )
        assertEquals(0, coordinator.deferredCount)
    }

    @Test
    fun staleCompletionDoesNotConsumeTheActiveIntent() {
        val coordinator = PendingDecodeCommitCoordinator<Int>(maximumDeferredInputs = 2)
        val intent = PendingDecodeCommit.Candidate(41L)
        coordinator.start(intent)

        assertNull(coordinator.finish(40L))
        assertEquals(intent, coordinator.intent)
        assertTrue(coordinator.isPending)
        assertEquals(intent, coordinator.finish(41L)?.intent)
        assertFalse(coordinator.isPending)
    }

    @Test
    fun capacitySignalNeverDropsAlreadyAcceptedInputs() {
        val coordinator = PendingDecodeCommitCoordinator<String>(maximumDeferredInputs = 2)
        coordinator.start(PendingDecodeCommit.Candidate(1L))

        assertEquals(DeferredInputOffer.ACCEPTED, coordinator.defer("first"))
        assertEquals(DeferredInputOffer.ACCEPTED, coordinator.defer("second"))
        assertEquals(DeferredInputOffer.CAPACITY_REACHED, coordinator.defer("third"))
        assertEquals(2, coordinator.deferredCount)
        assertEquals(
            listOf("first", "second"),
            coordinator.finish()?.followUpInputs,
        )
    }

    @Test
    fun clearAllSealsPendingWorkAndItsDeferredQueue() {
        val coordinator = PendingDecodeCommitCoordinator<String>(maximumDeferredInputs = 1)
        coordinator.start(PendingDecodeCommit.Candidate(7L))
        coordinator.defer("queued")

        coordinator.clearAll()

        assertFalse(coordinator.isPending)
        assertEquals(0, coordinator.deferredCount)
    }

    @Test
    fun clearingPendingTransactionCannotLeaveAnOrphanedFollowUpQueue() {
        val coordinator = PendingDecodeCommitCoordinator<String>(maximumDeferredInputs = 1)
        coordinator.start(PendingDecodeCommit.Candidate(7L), triggerInput = "trigger")
        coordinator.defer("queued")

        coordinator.clearAll()

        assertFalse(coordinator.isPending)
        assertEquals(0, coordinator.deferredCount)
        coordinator.start(PendingDecodeCommit.Candidate(8L))
        assertTrue(coordinator.isPending)
    }

    @Test
    fun detachedCompletionSurvivesReentrantCoordinatorClear() {
        val coordinator = PendingDecodeCommitCoordinator<String>(maximumDeferredInputs = 2)
        val intent = PendingDecodeCommit.Candidate(12L)
        coordinator.start(intent, triggerInput = "TRIGGER")
        coordinator.defer("FOLLOW_UP")

        val completion = coordinator.finish(12L)
        coordinator.clearAll() // Mirrors a synchronous onUpdateSelection reset during commitText.

        assertEquals(
            FinishedPendingDecodeCommit(
                intent = intent,
                triggerInput = "TRIGGER",
                followUpInputs = listOf("FOLLOW_UP"),
            ),
            completion,
        )
    }
}
