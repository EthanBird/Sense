package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePinyinDecoderTest {
    private val segmenter = PinyinSyllableSegmenter(
        setOf("an", "fan", "fang", "gan", "ge", "hao", "ni", "ren", "shi", "wo", "xi", "xian", "yi"),
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

    private fun emptyBase(): InputDecoder = object : InputDecoder {
        override fun decode(composing: String, limit: Int): List<Candidate> = emptyList()
    }
}
