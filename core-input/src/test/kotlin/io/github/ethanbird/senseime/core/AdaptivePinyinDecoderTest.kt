package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePinyinDecoderTest {
    private val segmenter = PinyinSyllableSegmenter(
        setOf("an", "de", "di", "fan", "fang", "fu", "gan", "ge", "hao", "ni", "nv", "o", "ren", "shi", "wo", "xi", "xian", "yi"),
    )

    @Test
    fun characterCountDisambiguatesSyllables() {
        assertEquals("x", segmenter.initials("xian", expectedSyllables = 1))
        assertEquals("xa", segmenter.initials("xian", expectedSyllables = 2))
        assertEquals("wsygr", segmenter.initials("woshiyigeren", expectedSyllables = 5))
        assertNull(segmenter.initials("fangan", expectedSyllables = 2))
        assertNull(segmenter.initials("not-pinyin", expectedSyllables = 2))
    }

    @Test
    fun candidateBoundariesDisambiguateTheSameContinuousPinyin() {
        val lexicon = MemoryUserLexicon(clock = { 1000L })
        val decoder = AdaptivePinyinDecoder(emptyBase(), lexicon, segmenter)

        decoder.learn(
            "fangan",
            Candidate("方案", canonicalPinyin = "fangan", canonicalInitials = "fa"),
        )
        decoder.learn(
            "fangan",
            Candidate("反感", canonicalPinyin = "fangan", canonicalInitials = "fg"),
        )

        assertEquals("方案", decoder.decode("fa").first().text)
        assertEquals("反感", decoder.decode("fg").first().text)
    }

    @Test
    fun oneFullPinyinSelectionCanBeRecalledByInitials() {
        val lexicon = MemoryUserLexicon(clock = { 1000L })
        val decoder = AdaptivePinyinDecoder(emptyBase(), lexicon, segmenter)
        val selected = Candidate(
            "我是一个人",
            canonicalPinyin = "woshiyigeren",
            matchKind = CandidateMatchKind.BASE_COMPOSED,
        )

        decoder.learn("woshiyigeren", selected)

        val candidate = decoder.decode("wsygr").first()
        assertEquals("我是一个人", candidate.text)
        assertEquals("woshiyigeren", candidate.canonicalPinyin)
        assertEquals(CandidateMatchKind.USER_INITIALS, candidate.matchKind)
    }

    @Test
    fun repeatedUseIncrementsFrequencyWithoutDuplicates() {
        var now = 1000L
        val lexicon = MemoryUserLexicon(clock = { now })
        lexicon.record("nihao", "nh", "你好")
        now += 1
        lexicon.record("nihao", "nh", "你好")

        val values = lexicon.lookup("nh", 5)
        assertEquals(1, values.size)
        assertEquals(2, values.single().useCount)
        assertEquals(1001L, values.single().lastUsedAtMillis)
    }

    @Test
    fun incompletePrefixAndLatinFallbackAreNotLearned() {
        val lexicon = MemoryUserLexicon()
        val decoder = AdaptivePinyinDecoder(emptyBase(), lexicon, segmenter)

        assertNull(decoder.learn("niha", Candidate("你好", matchKind = CandidateMatchKind.BASE_PREFIX)))
        assertNull(decoder.learn("nh", Candidate("你好", matchKind = CandidateMatchKind.BASE_INITIALS)))
        assertNull(decoder.learn("hello", Candidate("hello")))
        assertTrue(lexicon.lookup("nh", 5).isEmpty())
    }

    @Test
    fun sourcedPrefixCanBeLearnedButStatisticalOnlyPrefixCannot() {
        val lexicon = MemoryUserLexicon(clock = { 1000L })
        val decoder = AdaptivePinyinDecoder(emptyBase(), lexicon, segmenter)

        val learned = decoder.learn(
            "d",
            Candidate(
                text = "的",
                canonicalPinyin = "de",
                canonicalInitials = "d",
                matchKind = CandidateMatchKind.BASE_PREFIX,
            ),
        )

        assertEquals("de", learned?.fullPinyin)
        assertEquals("的", decoder.decode("d").first().text)
        assertEquals(CandidateMatchKind.USER_INITIALS, decoder.decode("d").first().matchKind)
        assertNull(decoder.learn("d", Candidate("的", matchKind = CandidateMatchKind.BASE_PREFIX)))
        assertNull(
            decoder.learn(
                "d",
                Candidate("你", canonicalPinyin = "ni", canonicalInitials = "n", matchKind = CandidateMatchKind.BASE_PREFIX),
            ),
        )
    }

    @Test
    fun latestExplicitSelectionBeatsAnOlderMoreFrequentHomophone() {
        var now = 1000L
        val lexicon = MemoryUserLexicon(clock = { now })
        repeat(20) { lexicon.record("di", "d", "地") }
        now = 1001L

        lexicon.record("de", "d", "的")

        assertEquals(listOf("的", "地"), lexicon.lookup("d", 2).map { it.text })
    }

    @Test
    fun selectionsInTheSameClockMillisecondStillHaveStableRecencyOrder() {
        val lexicon = MemoryUserLexicon(clock = { 1000L })
        lexicon.record("di", "d", "地")
        lexicon.record("de", "d", "的")

        assertEquals(listOf("的", "地"), lexicon.lookup("d", 2).map { it.text })
        assertTrue(lexicon.lookup("d", 2)[0].lastUsedAtMillis > lexicon.lookup("d", 2)[1].lastUsedAtMillis)
    }

    @Test
    fun hybridSelectionLearnsItsCanonicalFullPinyin() {
        val lexicon = MemoryUserLexicon(clock = { 1000L })
        val decoder = AdaptivePinyinDecoder(emptyBase(), lexicon, segmenter)

        val learned = decoder.learn(
            "fun",
            Candidate(
                text = "妇女",
                canonicalPinyin = "funv",
                canonicalInitials = "fn",
                matchKind = CandidateMatchKind.BASE_HYBRID,
            ),
        )

        assertEquals("funv", learned?.fullPinyin)
        assertEquals(setOf("fun"), learned?.aliases)
        assertEquals("妇女", decoder.decode("fun").first().text)
        assertEquals(CandidateMatchKind.USER_FULL, decoder.decode("fun").first().matchKind)
        assertEquals("妇女", decoder.decode("fn").first().text)

        val reloaded = AdaptivePinyinDecoder(
            emptyBase(),
            MemoryUserLexicon(listOf(requireNotNull(learned))),
            segmenter,
        )
        assertEquals("妇女", reloaded.decode("fun").first().text)
    }

    @Test
    fun invalidTailStillOffersAChinesePrefixAfterThreeEnglishWords() {
        val base = object : InputDecoder {
            override fun decode(composing: String, limit: Int): List<Candidate> =
                if (composing == "ho") {
                    listOf(
                        Candidate(
                            text = "好哦",
                            canonicalPinyin = "haoo",
                            canonicalInitials = "ho",
                            matchKind = CandidateMatchKind.BASE_HYBRID,
                        ),
                    )
                } else {
                    emptyList()
                }
        }
        val english = EnglishLexicon.fromWords(listOf("hosted", "host", "hosts", "hostile"))
        val decoder = AdaptivePinyinDecoder(base, MemoryUserLexicon(), segmenter, english)
        val composition = PinyinComposition(remainingPinyin = "host", revision = 7)

        val result = decoder.decodeProgressively(composition, 16)
        val prefix = result.prefixCandidates.first { it.candidate.text == "好哦" }

        assertEquals(listOf("host", "hosts", "hostile"), result.wholeCandidates.take(3).map { it.text })
        assertEquals("ho", prefix.consumedPinyin)
        assertEquals("st", prefix.remainingPinyin)
        assertEquals("好哦st", composition.acceptPrefix(7, prefix).visibleText)
    }

    private fun emptyBase(): InputDecoder = object : InputDecoder {
        override fun decode(composing: String, limit: Int): List<Candidate> = emptyList()
    }
}
