package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommitSequenceTrackerTest {
    @Test
    fun twoAdjacentHanCommitsProduceAssociationWithoutInventingAWord() {
        val tracker = CommitSequenceTracker()
        assertEquals(
            CommitSequenceOutcome(null),
            tracker.record(CommittedTextUnit("程", "cheng", 7L, 1_000L, 4, 5)),
        )

        val outcome = tracker.record(CommittedTextUnit("彻", "che", 7L, 1_200L, 5, 6))

        assertEquals(AssociationObservation("程", "彻"), outcome.association)
        assertEquals("彻", tracker.context(7L))
    }

    @Test
    fun editorChangeTimeoutAndNonHanCommitBreakTheSequence() {
        val tracker = CommitSequenceTracker(maximumGapMillis = 500L)
        tracker.record(CommittedTextUnit("智能", "zhineng", 1L, 1_000L, 0, 2))
        assertNull(
            tracker.record(CommittedTextUnit("体", "ti", 2L, 1_100L, 2, 3)).association,
        )

        tracker.record(CommittedTextUnit("智能", "zhineng", 2L, 2_000L, 0, 2))
        assertNull(
            tracker.record(CommittedTextUnit("体", "ti", 2L, 2_501L, 2, 3)).association,
        )

        tracker.record(CommittedTextUnit("智能", "zhineng", 2L, 3_000L, 0, 2))
        tracker.record(CommittedTextUnit("，", null, 2L, 3_100L, 2, 3))
        assertNull(
            tracker.record(CommittedTextUnit("体", "ti", 2L, 3_200L, 3, 4)).association,
        )
    }

    @Test
    fun nonAdjacentHostRangesDoNotProduceLearning() {
        val tracker = CommitSequenceTracker()
        tracker.record(CommittedTextUnit("智能", "zhineng", 9L, 1_000L, 10, 12))

        val outcome = tracker.record(CommittedTextUnit("体", "ti", 9L, 1_100L, 20, 21))

        assertNull(outcome.association)
    }

    @Test
    fun threeSingleCharacterCommitsProduceOnlyAdjacentAssociations() {
        val tracker = CommitSequenceTracker()
        tracker.record(CommittedTextUnit("智", "zhi", 3L, 1_000L, 0, 1))
        assertEquals(
            AssociationObservation("智", "能"),
            tracker.record(CommittedTextUnit("能", "neng", 3L, 1_100L, 1, 2)).association,
        )

        assertEquals(
            AssociationObservation("能", "体"),
            tracker.record(CommittedTextUnit("体", "ti", 3L, 1_200L, 2, 3)).association,
        )
    }
}
