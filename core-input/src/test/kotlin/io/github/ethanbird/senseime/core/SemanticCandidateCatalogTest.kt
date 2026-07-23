package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticCandidateCatalogTest {
    @Test
    fun requiredPinyinAliasesResolveWithLowPriorityMetadata() {
        assertAlias("ji", listOf("🐔"), SemanticCandidateKind.EMOJI)
        assertAlias("yao", listOf("💊"), SemanticCandidateKind.EMOJI)
        assertAlias("suo", listOf("🔒"), SemanticCandidateKind.EMOJI)
        assertAlias("oumu", listOf("Ω"), SemanticCandidateKind.SYMBOL)
        assertAlias("pai", listOf("π"), SemanticCandidateKind.SYMBOL)
        assertAlias("zuo", listOf("←"), SemanticCandidateKind.SYMBOL)

        val you = SemanticCandidateCatalog.suggest("YOU")
        assertEquals(listOf("🈶", "→"), you.map { it.candidate.text })
        assertEquals(
            listOf(SemanticCandidateKind.EMOJI, SemanticCandidateKind.SYMBOL),
            you.map(SemanticCandidateSuggestion::kind),
        )
    }

    @Test
    fun matchingIsExactRatherThanAHighNoisePrefixSearch() {
        assertTrue(SemanticCandidateCatalog.suggest("j").isEmpty())
        assertTrue(SemanticCandidateCatalog.suggest("ji!").isEmpty())
        assertTrue(SemanticCandidateCatalog.suggest("").isEmpty())
        assertTrue(SemanticCandidateCatalog.suggest("ji", limit = 0).isEmpty())
    }

    @Test
    fun mixerPlacesSemanticCandidatesAfterSixPrimaryValues() {
        val primary = (1..10).map { Candidate("词$it", score = 100f - it) }
        val merged = SemanticCandidateMixer.merge(
            primary,
            SemanticCandidateCatalog.suggest("you"),
            limit = 12,
        )

        assertEquals(
            listOf("词1", "词2", "词3", "词4", "词5", "词6", "🈶", "→", "词7", "词8", "词9", "词10"),
            merged.map(Candidate::text),
        )
    }

    @Test
    fun mixerAppendsWhenPrimaryStripIsShortAndNeverDuplicates() {
        val primary = listOf(Candidate("我"), Candidate("🐔"))
        val merged = SemanticCandidateMixer.merge(
            primary,
            SemanticCandidateCatalog.suggest("ji"),
            limit = 8,
        )

        assertEquals(listOf("我", "🐔"), merged.map(Candidate::text))
    }

    private fun assertAlias(
        pinyin: String,
        expected: List<String>,
        kind: SemanticCandidateKind,
    ) {
        val suggestions = SemanticCandidateCatalog.suggest(pinyin)
        assertEquals(expected, suggestions.map { it.candidate.text })
        assertTrue(suggestions.all { it.kind == kind })
        assertTrue(suggestions.all { it.preferredInsertionIndex == 6 })
        assertTrue(suggestions.all { it.candidate.score < 0f })
        assertTrue(suggestions.all { it.candidate.matchKind == CandidateMatchKind.BASE_PREFIX })
    }
}
