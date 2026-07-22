package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressivePinyinTest {
    private val segmenter = PinyinSyllableSegmenter(
        setOf("a", "an", "pei", "pi", "pin", "xi", "xian"),
    )

    @Test
    fun continuousPinyinFindsOnlyPrefixesWithACompleteRemainder() {
        assertEquals(listOf("pi", "pei"), segmenter.segment("pipei"))
        assertEquals(listOf(2), segmenter.selectablePrefixLengths("pipei").toList())
        assertEquals(listOf("xian"), segmenter.segment("xian"))
        assertEquals(listOf(2), segmenter.selectablePrefixLengths("xian").toList())
        assertTrue(segmenter.selectablePrefixLengths("pzz").isEmpty())
        assertFalse(segmenter.isComplete("pzz"))
        assertFalse(segmenter.isComplete("a".repeat(97)))
    }

    @Test
    fun progressiveDecodeExposesPiCharactersAndKeepsWholeCandidate() {
        val decoder = AdaptivePinyinDecoder(fixtureDecoder(), MemoryUserLexicon(), segmenter)
        val state = "pipei".fold(PinyinComposition()) { value, character -> value.type(character) }
        val result = decoder.decodeProgressively(state, 8)

        assertEquals(state.revision, result.revision)
        assertEquals("匹配", result.wholeCandidates.first().text)
        assertEquals(listOf("匹", "批"), result.prefixCandidates.map { it.candidate.text })
        assertTrue(result.prefixCandidates.all { it.consumedPinyin == "pi" && it.remainingPinyin == "pei" })
    }

    @Test
    fun selectedPrefixRemainsReversibleComposingText() {
        val decoder = AdaptivePinyinDecoder(fixtureDecoder(), MemoryUserLexicon(), segmenter)
        val typed = "pipei".fold(PinyinComposition()) { value, character -> value.type(character) }
        val decoded = decoder.decodeProgressively(typed, 8)
        val selected = decoded.prefixCandidates.first { it.candidate.text == "匹" }
        val partial = typed.acceptPrefix(decoded.revision, selected)

        assertEquals("匹pei", partial.visibleText)
        assertEquals("匹pei", partial.confirmRaw())
        assertEquals("匹配", partial.confirmPrimary(Candidate("配")))
        assertFalse(partial.isComplete)
        assertEquals("匹pe", partial.backspace().visibleText)

        val emptyTail = partial.copy(remainingPinyin = "")
        assertEquals("pi", emptyTail.backspace().visibleText)
        assertEquals(typed, typed.acceptPrefix(decoded.revision - 1, selected))
    }

    @Test
    fun acceptedPrefixProvidesContextForTheRemainingCandidate() {
        val decoder = AdaptivePinyinDecoder(fixtureDecoder(), MemoryUserLexicon(), segmenter)
        val typed = "pipei".fold(PinyinComposition()) { value, character -> value.type(character) }
        val decoded = decoder.decodeProgressively(typed, 8)
        val partial = typed.acceptPrefix(
            decoded.revision,
            decoded.prefixCandidates.first { it.candidate.text == "匹" },
        )

        assertEquals("配", decoder.decodeProgressively(partial, 8).wholeCandidates.first().text)
    }

    @Test
    fun prefixCandidateCountIsHardBounded() {
        val many = object : InputDecoder {
            override fun decode(composing: String, limit: Int): List<Candidate> =
                if (composing == "pi") {
                    List(100) { Candidate(text = ('一'.code + it).toChar().toString(), canonicalInitials = "p") }
                } else {
                    emptyList()
                }
        }
        val decoder = AdaptivePinyinDecoder(many, MemoryUserLexicon(), segmenter)
        val state = "pipei".fold(PinyinComposition()) { value, character -> value.type(character) }

        assertEquals(32, decoder.decodeProgressively(state, 100).prefixCandidates.size)
    }

    private fun fixtureDecoder(): InputDecoder = object : ContextualInputDecoder {
        override fun decode(composing: String, limit: Int): List<Candidate> = when (composing) {
            "pipei" -> listOf(Candidate("匹配", canonicalPinyin = "pipei", canonicalInitials = "pp"))
            "pi" -> listOf(
                Candidate("匹", canonicalPinyin = "pi", canonicalInitials = "p"),
                Candidate("批", canonicalPinyin = "pi", canonicalInitials = "p"),
                Candidate("皮鞋", canonicalPinyin = "pi", canonicalInitials = "px"),
            )
            "pei" -> listOf(Candidate("陪", canonicalPinyin = "pei", canonicalInitials = "p"))
            else -> emptyList()
        }.take(limit)

        override fun decodeAfter(previousCodePoint: Int, composing: String, limit: Int): List<Candidate> =
            if (previousCodePoint == '匹'.code && composing == "pei") {
                listOf(
                    Candidate("配", score = 2f, canonicalPinyin = "pei", canonicalInitials = "p"),
                    Candidate("陪", score = 1f, canonicalPinyin = "pei", canonicalInitials = "p"),
                ).take(limit)
            } else {
                decode(composing, limit)
            }
    }
}
