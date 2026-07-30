package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.UserLearningEvidence
import io.github.ethanbird.senseime.core.UserSelectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveLearningQueueTest {
    @Test
    fun rollbackRemovesOnlyTheMostRecentPrefixSelection() {
        val queue = ProgressiveLearningQueue()
        queue.add(learning("xi", "西"))
        queue.add(learning("an", "安"))

        assertEquals("安", queue.rollbackLast()?.candidate?.text)
        assertEquals(listOf("西"), queue.snapshotForCommit().map { it.candidate.text })
    }

    @Test
    fun stagedCommitSurvivesSynchronousLiveQueueReset() {
        val queue = ProgressiveLearningQueue()
        queue.add(learning("xi", "西"))
        queue.add(learning("an", "安"))
        val staged = queue.snapshotForCommit()

        // Mirrors resetComposition running from a synchronous editor callback.
        queue.clear()

        assertEquals(listOf("西", "安"), staged.map { it.candidate.text })
        assertTrue(queue.snapshotForCommit().isEmpty())
    }

    @Test
    fun stagedCommitCannotObserveLaterPrefixSelections() {
        val queue = ProgressiveLearningQueue()
        queue.add(learning("xi", "西"))
        val staged = queue.snapshotForCommit()

        queue.add(learning("an", "安"))

        assertEquals(listOf("西"), staged.map { it.candidate.text })
        assertEquals(listOf("西", "安"), queue.snapshotForCommit().map { it.candidate.text })
    }

    private fun learning(rawInput: String, text: String) = ProgressiveLearning(
        rawInput = rawInput,
        candidate = Candidate(text),
        evidence = UserLearningEvidence(
            kind = UserSelectionKind.PROGRESSIVE_SELECTION,
            selectedRank = 0,
        ),
    )
}
