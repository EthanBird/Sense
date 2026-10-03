package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test

class FullSpellingBoundaryTest {
    private val segmenter = PinyinSyllableSegmenter(listOf("xi", "an", "xian", "a", "n", "chang", "chan", "gan", "ganan", "nan"))

    @Test
    fun jointConstrainsSyllablesInsteadOfForcingWordSplits() {
        val joints = BooleanArray(5).also { it[2] = true }
        assertTrue(segmenter.matchesFullSpelling("xian", 0, 4, "xa", joints))
        assertFalse(segmenter.matchesFullSpelling("xian", 0, 4, "x", joints))
        assertTrue(segmenter.matchesFullSpelling("xian", 0, 4, "x", null))
    }

    @Test
    fun canonicalInitialsConstrainAmbiguousSegmentationsAndSubranges() {
        val joints = BooleanArray(10).also { it[6] = true }
        assertTrue(segmenter.matchesFullSpelling("zxianxian", 1, 5, "xa", joints))
        assertTrue(segmenter.matchesFullSpelling("changanan", 0, 9, "caa", null))
        assertTrue(segmenter.matchesFullSpelling("changanan", 0, 9, "cg", null))
        assertFalse(segmenter.matchesFullSpelling("changanan", 0, 9, "cn", null))
        assertFalse(segmenter.matchesFullSpelling("xian", 0, 4, null, null))
        assertFalse(segmenter.matchesFullSpelling("xian", 0, 4, "xx", null))
    }
}
