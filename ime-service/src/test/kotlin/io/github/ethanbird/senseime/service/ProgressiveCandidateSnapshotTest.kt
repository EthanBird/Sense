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
    fun keepsWholePhraseHeadThenExposesPrefixChoices() {
        val whole = Candidate("匹配")
        val secondWhole = Candidate("屁配")
        val prefix = PinyinPrefixCandidate(Candidate("匹"), "pi", "pei")
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(7, "pipei", listOf(whole, secondWhole), listOf(prefix)),
            limit = 32,
        )

        assertEquals(listOf("匹配", "屁配", "匹"), snapshot.candidates.map { it.text })
        assertTrue(snapshot.select(7, 7, 1) is ProgressiveCandidateChoice.Whole)
        assertTrue(snapshot.select(7, 7, 2) is ProgressiveCandidateChoice.Prefix)
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

    @Test
    fun keepsAUsefulWholePhraseHeadBeforePrefixCharacters() {
        val whole = (0 until 20).map { Candidate("whole-$it") }
        val prefixes = (0 until 20).map { index ->
            PinyinPrefixCandidate(Candidate("prefix-$index"), "pi", "pei")
        }
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(11, "pipei", whole, prefixes),
            limit = 255,
        )

        assertEquals((0 until 12).map { "whole-$it" }, snapshot.candidates.take(12).map { it.text })
        assertTrue(snapshot.select(11, 11, 11) is ProgressiveCandidateChoice.Whole)
        assertTrue(snapshot.select(11, 11, 12) is ProgressiveCandidateChoice.Prefix)
    }

    @Test
    fun exposesTheEntireBoundedCandidateSetForUiPaging() {
        val whole = (0 until 255).map { Candidate("candidate-$it") }
        val prefixes = (0 until 255).map { index ->
            PinyinPrefixCandidate(Candidate("prefix-$index"), "shang", "hua")
        }
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(19, "shanghua", whole, prefixes),
            limit = 510,
        )

        assertEquals(510, snapshot.candidates.size)
        assertEquals("candidate-254", snapshot.candidates.last().text)
        assertTrue(snapshot.select(19, 19, 509) is ProgressiveCandidateChoice.Whole)
    }
}
