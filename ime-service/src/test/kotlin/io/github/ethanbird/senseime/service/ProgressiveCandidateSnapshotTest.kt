package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.PinyinPrefixCandidate
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveCandidateSnapshotTest {
    @Test
    fun keepsWholePrimaryFirstThenExposesPrefixChoices() {
        val whole = Candidate("匹配")
        val secondWhole = Candidate("屁配")
        val prefix = PinyinPrefixCandidate(Candidate("匹"), "pi", "pei")
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(7, "pipei", listOf(whole, secondWhole), listOf(prefix)),
            limit = 32,
        )

        assertEquals(listOf("匹配", "匹", "屁配"), snapshot.candidates.map { it.text })
        assertTrue(snapshot.select(7, 7, 1) is ProgressiveCandidateChoice.Prefix)
        assertTrue(snapshot.select(7, 7, 2) is ProgressiveCandidateChoice.Whole)
    }

    @Test
    fun staleRevisionAndInvalidIndexCannotSelect() {
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(4, "pi", listOf(Candidate("匹")), emptyList()),
            limit = 32,
        )

        assertNull(snapshot.select(5, 4, 0))
        assertNull(snapshot.select(4, 3, 0))
        assertNull(snapshot.select(4, 4, 9))
    }
}
