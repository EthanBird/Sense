package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.AssociationObservation
import io.github.ethanbird.senseime.core.CommitSequenceTracker
import io.github.ethanbird.senseime.core.CommittedTextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliableCommitSelectionFenceTest {
    @Test
    fun delayedOwnCommitSelectionKeepsTheNextAssociationBoundary() {
        var now = 1_000L
        val connection = Any()
        val fence = ReliableCommitSelectionFence(clock = { now })
        val tracker = CommitSequenceTracker()

        fence.expect(editorSessionId = 7L, connectionIdentity = connection, cursor = 1)
        tracker.record(CommittedTextUnit("程", "cheng", 7L, now, 0, 1))
        now += 20L
        val ownCallback = fence.acknowledge(7L, connection, 1, 1)
        if (!ownCallback) tracker.breakSequence()

        val outcome = tracker.record(CommittedTextUnit("彻", "che", 7L, now + 20L, 1, 2))
        assertTrue(ownCallback)
        assertEquals(AssociationObservation("程", "彻"), outcome.association)
    }

    @Test
    fun differentConnectionCursorOrExpiredCallbackDoesNotMatch() {
        var now = 100L
        val connection = Any()
        val fence = ReliableCommitSelectionFence(clock = { now }, maximumAgeMillis = 50L)

        fence.expect(1L, connection, 4)
        assertFalse(fence.acknowledge(1L, Any(), 4, 4))

        fence.expect(1L, connection, 4)
        assertFalse(fence.acknowledge(1L, connection, 3, 3))

        fence.expect(1L, connection, 4)
        now += 51L
        assertFalse(fence.acknowledge(1L, connection, 4, 4))
    }
}
