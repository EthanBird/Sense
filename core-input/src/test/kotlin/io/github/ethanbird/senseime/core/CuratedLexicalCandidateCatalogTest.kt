package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedLexicalCandidateCatalogTest {
    @Test
    fun intelligentAgentIsAnExactSecondCandidateAndNeverDuplicated() {
        val primary = listOf(
            Candidate("只能提", score = 9f),
            Candidate("智能题", score = 8f),
            Candidate("智能体", score = 1f, matchKind = CandidateMatchKind.BASE_COMPOSED),
        )

        val merged = CuratedLexicalCandidateCatalog.merge("zhi'neng'ti", primary, 8)

        assertEquals(listOf("只能提", "智能体", "智能题"), merged.map { it.text })
        assertEquals("zhinengti", merged[1].canonicalPinyin)
        assertEquals("znt", merged[1].canonicalInitials)
        assertEquals(CandidateMatchKind.BASE_EXACT, merged[1].matchKind)
    }

    @Test
    fun unrelatedOrZeroLimitQueriesPreserveNormalBehavior() {
        val primary = listOf(Candidate("程彻"))
        assertEquals(primary, CuratedLexicalCandidateCatalog.merge("chengche", primary, 8))
        assertTrue(CuratedLexicalCandidateCatalog.merge("zhinengti", primary, 0).isEmpty())
    }

    @Test
    fun initialsQueryGetsTheSameCuratedVocabularyWithInitialsSemantics() {
        val merged = CuratedLexicalCandidateCatalog.merge(
            composing = "znt",
            primary = listOf(Candidate("在那天", score = 9f)),
            limit = 8,
        )

        assertEquals("智能体", merged[1].text)
        assertEquals(CandidateMatchKind.BASE_INITIALS, merged[1].matchKind)
    }

    @Test
    fun learnedCandidateKeepsItsFrontRankAndUserEvidence() {
        val learned = Candidate(
            text = "智能体",
            score = 12f,
            canonicalPinyin = "zhinengti",
            canonicalInitials = "znt",
            matchKind = CandidateMatchKind.USER_FULL,
        )

        val alreadyFirst = CuratedLexicalCandidateCatalog.merge(
            "zhinengti",
            listOf(learned, Candidate("智能题")),
            8,
        )
        val promotedFromDeep = CuratedLexicalCandidateCatalog.merge(
            "zhinengti",
            listOf(Candidate("只能提"), Candidate("智能题"), Candidate("只能题"), learned),
            8,
        )

        assertEquals(learned, alreadyFirst.first())
        assertEquals(learned, promotedFromDeep[1])
        assertEquals(CandidateMatchKind.USER_FULL, promotedFromDeep[1].matchKind)
    }
}
