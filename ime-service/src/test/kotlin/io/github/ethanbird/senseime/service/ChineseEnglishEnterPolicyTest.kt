package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseEnglishEnterPolicyTest {
    @Test
    fun reusesTheVisibleExactEnglishCandidateAndItsRank() {
        val candidates = listOf(
            Candidate("功能", matchKind = CandidateMatchKind.BASE_EXACT),
            Candidate("function", matchKind = CandidateMatchKind.ENGLISH_EXACT),
        )

        val selection = ChineseEnglishEnterPolicy.select("function", candidates)

        assertEquals(candidates[1], selection?.candidate)
        assertEquals(1, selection?.candidateRank)
    }

    @Test
    fun createsALearnableExactCandidateForANewEnglishWord() {
        val selection = ChineseEnglishEnterPolicy.select("codex", emptyList())

        assertEquals("codex", selection?.candidate?.text)
        assertEquals(CandidateMatchKind.ENGLISH_EXACT, selection?.candidate?.matchKind)
    }

    @Test
    fun ignoresPinyinWithExplicitBoundaries() {
        assertNull(ChineseEnglishEnterPolicy.select("xi'an", emptyList()))
    }
}
