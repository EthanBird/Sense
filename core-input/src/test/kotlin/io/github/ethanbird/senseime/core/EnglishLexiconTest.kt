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
    fun completeEnglishWordIsPromotedOnlyAsHighAsSecondPlace() {
        val weakChinese = listOf(
            Candidate("好哦", score = 16.35f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "ho"),
        )

        assertEquals(
            listOf("好哦", "host", "hosts", "hostile"),
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
    fun oneLetterPinyinKeepsFourChineseChoicesAndOnlyOneEnglishEntrance() {
        val chinese = listOf(
            Candidate("我", score = 13.4f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "w"),
            Candidate("为", score = 12.8f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "w"),
            Candidate("问", score = 12.4f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "w"),
            Candidate("无", score = 12.0f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "w"),
            Candidate("五", score = 11.6f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "w"),
            Candidate("完", score = 11.2f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "w"),
        )
        val english = EnglishLexicon.fromWords(
            listOf("was", "with", "were", "will", "would", "when", "where", "who"),
        ).suggest("w", 32)

        val merged = MixedCandidateRanker.merge(chinese, english, 8)

        assertEquals(listOf("我", "为", "问", "无"), merged.take(4).map { it.text })
        assertEquals(
            listOf("was"),
            merged.filter { it.matchKind == CandidateMatchKind.ENGLISH_PREFIX }.map { it.text },
        )
    }

    @Test
    fun lowFrequencyValidInitialActivatesChineseModeWithoutAScoreThreshold() {
        val chinese = listOf(
            Candidate(
                "\u6015",
                score = 6f,
                matchKind = CandidateMatchKind.BASE_PREFIX,
                canonicalInitials = "p",
            ),
        )
        val english = EnglishLexicon.fromWords(
            listOf("people", "please", "public", "problem", "place", "point"),
        ).suggest("p", 16)

        val merged = MixedCandidateRanker.merge(chinese, english, 16)

        assertEquals("\u6015", merged.first().text)
        assertEquals(
            1,
            merged.count { it.matchKind == CandidateMatchKind.ENGLISH_PREFIX },
        )
    }

    @Test
    fun oneConcretePinyinCandidateActivatesTheChineseInitialMode() {
        val shallowChinese = listOf(
            Candidate("我", score = 13.4f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "w"),
        )
        val english = EnglishLexicon.fromWords(
            listOf("was", "with", "were", "will", "would", "when"),
        ).suggest("w", 32)

        val merged = MixedCandidateRanker.merge(shallowChinese, english, 7)

        assertEquals(shallowChinese.single().text, merged.first().text)
        assertEquals(
            listOf("was"),
            merged.filter { it.matchKind == CandidateMatchKind.ENGLISH_PREFIX }.map { it.text },
        )
    }

    @Test
    fun exactOneLetterEnglishStillUsesOneBoundedEntrance() {
        val chinese = listOf(
            Candidate("啊", score = 13.6f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "a"),
            Candidate("阿", score = 13.0f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "a"),
            Candidate("爱", score = 12.6f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "a"),
            Candidate("安", score = 12.2f, matchKind = CandidateMatchKind.BASE_PREFIX, canonicalInitials = "a"),
        )
        val english = EnglishLexicon.fromWords(
            listOf("a", "about", "after", "again", "all", "also"),
        ).suggest("a", 32)

        val merged = MixedCandidateRanker.merge(chinese, english, 10)

        assertEquals(
            listOf("a"),
            merged
                .filter {
                    it.matchKind == CandidateMatchKind.ENGLISH_EXACT ||
                        it.matchKind == CandidateMatchKind.ENGLISH_PREFIX
                }
                .map { it.text },
        )
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
            listOf("妇女", "fun", "👩🏻", "服你", "赋能", "腐女"),
            MixedCandidateRanker.merge(strongChinese, lexicon.suggest("fun", 8), 6).map { it.text },
        )
    }

    @Test
    fun denseChinesePageStillKeepsTheCompleteEnglishWordAtSecondPlace() {
        val denseChinese = (0 until 24).map { index ->
            Candidate(
                text = "中文$index",
                score = 100f - index,
                matchKind = CandidateMatchKind.BASE_HYBRID,
            )
        }

        val merged = MixedCandidateRanker.merge(
            denseChinese,
            lexicon.suggest("host", 8),
            limit = 8,
        )

        assertEquals("中文0", merged[0].text)
        assertEquals("host", merged[1].text)
    }

    @Test
    fun completeEnglishWordUsesSecondPlaceDoorwayBehindChineseHead() {
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
    fun twoLetterPrefixCannotFillTheCandidatePage() {
        val chinese = listOf(
            Candidate("服务", score = 15f, matchKind = CandidateMatchKind.BASE_PREFIX),
            Candidate("富裕", score = 14f, matchKind = CandidateMatchKind.BASE_PREFIX),
            Candidate("复印", score = 13f, matchKind = CandidateMatchKind.BASE_PREFIX),
            Candidate("赋予", score = 12f, matchKind = CandidateMatchKind.BASE_PREFIX),
        )
        val english = EnglishLexicon.fromWords(
            listOf("function", "future", "full", "fund", "funny", "further"),
        ).suggest("fu", 16)

        val merged = MixedCandidateRanker.merge(chinese, english, 8)

        assertEquals(1, merged.count { it.matchKind == CandidateMatchKind.ENGLISH_PREFIX })
        assertTrue(merged.take(3).none { it.matchKind == CandidateMatchKind.ENGLISH_PREFIX })
    }

    @Test
    fun threeLetterPrefixStaysBehindTwoChineseCandidatesWhenItIsNotACompleteWord() {
        val chinese = listOf(
            Candidate("函数", score = 14f, matchKind = CandidateMatchKind.BASE_PREFIX),
            Candidate("范式", score = 13f, matchKind = CandidateMatchKind.BASE_PREFIX),
            Candidate("方式", score = 12f, matchKind = CandidateMatchKind.BASE_PREFIX),
        )
        val english = EnglishLexicon.fromWords(
            listOf("function", "functional", "fundamental"),
        ).suggest("fun", 8)

        val merged = MixedCandidateRanker.merge(chinese, english, 8)

        assertEquals(listOf("函数", "范式"), merged.take(2).map(Candidate::text))
        assertEquals(2, merged.count { it.matchKind == CandidateMatchKind.ENGLISH_PREFIX })
    }

    @Test
    fun maximumIntegerLimitUsesTheBoundedPrefixBuffer() {
        val values = lexicon.suggest("host", Int.MAX_VALUE)

        assertEquals("host", values.first().text)
        assertTrue(values.size <= 96)
    }

    @Test
    fun oneLetterEnglishRecallUsesASmallerBroadPrefixBudget() {
        val broadLexicon = EnglishLexicon.fromWords(
            (0 until 100).map { index ->
                "w${'a' + index / 26}${'a' + index % 26}"
            },
        )

        val values = broadLexicon.suggest("w", Int.MAX_VALUE)

        assertEquals(32, values.size)
    }

    @Test
    fun emptyEnglishMergeReusesAnAlreadyBoundedChineseResult() {
        val chinese = listOf(Candidate("\u4F60"), Candidate("\u597D"))

        assertSame(chinese, MixedCandidateRanker.merge(chinese, emptyList(), 8))
    }
}
