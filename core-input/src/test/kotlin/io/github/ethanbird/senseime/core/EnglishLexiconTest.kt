package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishLexiconTest {
    private val lexicon = EnglishLexicon.fromWords(
        listOf(
            "the",
            "hosted",
            "host",
            "hosting",
            "hosts",
            "hostile",
            "hostage",
            "fun",
            "function",
        ),
    )

    @Test
    fun exactPluralAndLexicalCompletionPrecedeDeferredInflections() {
        assertEquals(
            listOf("host", "hosts", "hostile", "hostage", "hosted", "hosting"),
            lexicon.suggest("host", 6).map { it.text },
        )
    }

    @Test
    fun lookupIsCaseInsensitiveAndMarksOnlyTheExactWordAsExact() {
        val values = lexicon.suggest("HOST", 4)

        assertEquals("host", values.first().text)
        assertEquals(CandidateMatchKind.ENGLISH_EXACT, values.first().matchKind)
        assertTrue(values.drop(1).all { it.matchKind == CandidateMatchKind.ENGLISH_PREFIX })
    }

    @Test
    fun weakChineseResultsFollowTheFirstThreeEnglishSuggestions() {
        val weakChinese = listOf(
            Candidate("好哦", matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "ho"),
        )

        assertEquals(
            listOf("host", "hosts", "hostile", "好哦"),
            MixedCandidateRanker.merge(weakChinese, lexicon.suggest("host", 8), 4).map { it.text },
        )
    }

    @Test
    fun englishPrefixDoesNotDisplaceOneKeyChineseCandidate() {
        val chinese = listOf(
            Candidate("我", matchKind = CandidateMatchKind.BASE_PREFIX),
            Candidate("为", matchKind = CandidateMatchKind.BASE_PREFIX),
        )
        val english = EnglishLexicon.fromWords(listOf("was", "with", "were"))
            .suggest("w", 8)

        assertEquals(
            listOf("我", "为", "was", "with", "were"),
            MixedCandidateRanker.merge(chinese, english, 8).map { it.text },
        )
    }

    @Test
    fun corpusNoiseSingleLetterDoesNotDisplaceInitialPinyin() {
        val chinese = listOf(
            Candidate("在", matchKind = CandidateMatchKind.BASE_PREFIX),
            Candidate("中", matchKind = CandidateMatchKind.BASE_PREFIX),
        )
        val english = EnglishLexicon.fromWords(listOf("z", "zealand", "zone"))
            .suggest("z", 8)

        assertTrue(english.none { it.matchKind == CandidateMatchKind.ENGLISH_EXACT })
        assertEquals(
            listOf("在", "中", "zealand", "zone"),
            MixedCandidateRanker.merge(chinese, english, 8).map { it.text },
        )
    }

    @Test
    fun exactEnglishWordIsFifthWhenStrongChineseCandidatesExist() {
        val strongChinese = listOf("妇女", "👩🏻", "服你", "赋能", "腐女").map {
            Candidate(it, matchKind = CandidateMatchKind.BASE_HYBRID)
        }

        assertEquals(
            listOf("妇女", "👩🏻", "服你", "赋能", "fun", "腐女"),
            MixedCandidateRanker.merge(strongChinese, lexicon.suggest("fun", 8), 6).map { it.text },
        )
    }
}
