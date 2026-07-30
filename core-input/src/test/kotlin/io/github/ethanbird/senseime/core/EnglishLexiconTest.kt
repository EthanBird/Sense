package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
            Candidate("好哦", score = 16.35f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "ho"),
        )

        assertEquals(
            listOf("host", "hosts", "hostile", "好哦"),
            MixedCandidateRanker.merge(weakChinese, lexicon.suggest("host", 8), 4).map { it.text },
        )
    }

    @Test
    fun englishPrefixDoesNotDisplaceOneKeyChineseCandidate() {
        val chinese = listOf(
            Candidate("我", score = 13.37f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "w"),
            Candidate("为", score = 11.98f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "w"),
        )
        val english = EnglishLexicon.fromWords(listOf("was", "with", "were"))
            .suggest("w", 8)

        assertEquals("我", MixedCandidateRanker.merge(chinese, english, 8).first().text)
    }

    @Test
    fun corpusNoiseSingleLetterDoesNotDisplaceInitialPinyin() {
        val chinese = listOf(
            Candidate("在", score = 13.52f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "z"),
            Candidate("中", score = 11.8f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "z"),
        )
        val english = EnglishLexicon.fromWords(listOf("z", "zealand", "zone"))
            .suggest("z", 8)

        assertTrue(english.none { it.matchKind == CandidateMatchKind.ENGLISH_EXACT })
        assertEquals("在", MixedCandidateRanker.merge(chinese, english, 8).first().text)
    }

    @Test
    fun exactEnglishWordFindsItsScorePositionAmongStrongChineseCandidates() {
        val strongChinese = listOf(
            Candidate("妇女", score = 20f, matchKind = CandidateMatchKind.BASE_HYBRID),
            Candidate("👩🏻", score = 19f, matchKind = CandidateMatchKind.BASE_HYBRID),
            Candidate("服你", score = 18f, matchKind = CandidateMatchKind.BASE_HYBRID),
            Candidate("赋能", score = 17f, matchKind = CandidateMatchKind.BASE_HYBRID),
            Candidate("腐女", score = 16f, matchKind = CandidateMatchKind.BASE_HYBRID),
        )

        assertEquals(
            listOf("妇女", "👩🏻", "服你", "fun", "赋能", "腐女"),
            MixedCandidateRanker.merge(strongChinese, lexicon.suggest("fun", 8), 6).map { it.text },
        )
    }

    @Test
    fun bilingualCandidatesUseScoresInsteadOfAFixedEnglishSlot() {
        val chinese = listOf(
            Candidate("中文一", score = 20f, matchKind = CandidateMatchKind.BASE_HYBRID),
            Candidate("中文二", score = 16f, matchKind = CandidateMatchKind.BASE_HYBRID),
            Candidate("中文三", score = 15f, matchKind = CandidateMatchKind.BASE_HYBRID),
        )
        val english = listOf(
            Candidate("fun", score = 17f, matchKind = CandidateMatchKind.ENGLISH_EXACT),
        )

        assertEquals(
            listOf("中文一", "fun", "中文二", "中文三"),
            MixedCandidateRanker.merge(chinese, english, 8).map { it.text },
        )
    }

    @Test
    fun maximumIntegerLimitUsesTheBoundedPrefixBuffer() {
        val values = lexicon.suggest("host", Int.MAX_VALUE)

        assertEquals("host", values.first().text)
        assertTrue(values.size <= 96)
    }

    @Test
    fun emptyEnglishMergeReusesAnAlreadyBoundedChineseResult() {
        val chinese = listOf(Candidate("\u4F60"), Candidate("\u597D"))

        assertSame(chinese, MixedCandidateRanker.merge(chinese, emptyList(), 8))
    }
}
