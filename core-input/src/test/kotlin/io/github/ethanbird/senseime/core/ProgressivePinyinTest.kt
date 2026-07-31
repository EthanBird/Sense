package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun mixedSegmentationExposesDisplayJointsWithoutChangingRawComposition() {
        val mixed = PinyinSyllableSegmenter(
            setOf("hun", "shen", "shu", "wo", "xi", "xie", "zhong"),
        ).segmentMixed("hunshenxs")

        assertEquals("hunshenxs", mixed?.rawCode)
        assertEquals("hun'shen'x's", mixed?.formatted)
        assertEquals(
            listOf(
                MixedPinyinSegment("hun", abbreviated = false),
                MixedPinyinSegment("shen", abbreviated = false),
                MixedPinyinSegment("x", abbreviated = true),
                MixedPinyinSegment("s", abbreviated = true),
            ),
            mixed?.segments,
        )
    }

    @Test
    fun mixedDisplayKeepsAnIncompleteTailTogether() {
        val segmenter = PinyinSyllableSegmenter(
            setOf("hun", "shen", "shu", "wo", "zhong"),
        )

        assertEquals("hun'sh", segmenter.segmentMixed("hunsh")?.formatted)
        assertEquals("zhong'w", segmenter.segmentMixed("zhongw")?.formatted)
        assertEquals("zh'w", segmenter.segmentMixed("zhw")?.formatted)
    }

    @Test
    fun mixedDisplayPreservesAnExplicitSyllableJoint() {
        val segmenter = PinyinSyllableSegmenter(setOf("an", "xi", "xian"))

        assertEquals("xian", segmenter.segmentMixed("xian")?.formatted)
        assertEquals("xi'an", segmenter.segmentMixed("xi'an")?.formatted)
        assertEquals("xian", segmenter.segmentMixed("xi'an")?.rawCode)
    }

    @Test
    fun candidateMetadataSelectsTheMatchingAmbiguousSegmentation() {
        val segmenter = PinyinSyllableSegmenter(
            setOf("an", "fan", "fang", "gan", "hun", "shen", "shu", "xie"),
        )

        assertEquals(
            "fang'an",
            segmenter.segmentMixed("fangan", "fangan", "fa")?.formatted,
        )
        assertEquals(
            "fan'gan",
            segmenter.segmentMixed("fangan", "fangan", "fg")?.formatted,
        )
        assertEquals(
            "hun'shen'x's",
            segmenter.segmentMixed("hunshenxs", "hunshenxieshu", "hsxs")?.formatted,
        )
    }

    @Test
    fun normalizationReusesAlreadyCanonicalPinyin() {
        val canonical = String(charArrayOf('p', 'i', 'p', 'e', 'i'))

        assertSame(canonical, PinyinSyllableSegmenter.normalize(canonical))
        assertEquals("nihao", PinyinSyllableSegmenter.normalize("Ni'Hao"))
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
    fun progressiveMergeKeepsARepresentativeFromEverySelectablePrefixGroup() {
        val decoder = AdaptivePinyinDecoder(
            base = object : InputDecoder {
                override fun decode(composing: String, limit: Int): List<Candidate> = when (composing) {
                    "anan" -> listOf(
                        Candidate(
                            text = "\u5B89\u5B89",
                            canonicalPinyin = "anan",
                            canonicalInitials = "aa",
                        ),
                    )
                    "a" -> listOf(
                        Candidate("\u554A", score = 10f, canonicalPinyin = "a", canonicalInitials = "a"),
                        Candidate("\u963F", score = 9f, canonicalPinyin = "a", canonicalInitials = "a"),
                    )
                    "an" -> listOf(
                        Candidate("\u5B89", score = 1f, canonicalPinyin = "an", canonicalInitials = "a"),
                        Candidate("\u6309", score = 0f, canonicalPinyin = "an", canonicalInitials = "a"),
                    )
                    else -> emptyList()
                }.take(limit)
            },
            userLexicon = MemoryUserLexicon(),
            segmenter = PinyinSyllableSegmenter(setOf("a", "an", "na", "nan")),
        )

        val result = decoder.decodeProgressively(
            PinyinComposition(remainingPinyin = "anan"),
            limit = 2,
        )

        assertEquals(listOf("an", "a"), result.prefixCandidates.map { it.consumedPinyin })
        assertEquals(listOf("\u5B89", "\u554A"), result.prefixCandidates.map { it.candidate.text })
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
                    List(300) { Candidate(text = ('一'.code + it).toChar().toString(), canonicalInitials = "p") }
                } else {
                    emptyList()
                }
        }
        val decoder = AdaptivePinyinDecoder(many, MemoryUserLexicon(), segmenter)
        val state = "pipei".fold(PinyinComposition()) { value, character -> value.type(character) }

        assertEquals(100, decoder.decodeProgressively(state, 100).prefixCandidates.size)
        assertEquals(255, decoder.decodeProgressively(state, 1000).prefixCandidates.size)
    }

    @Test
    fun progressiveCandidatesCanExposeCharactersPastTheFirstPage() {
        val many = object : InputDecoder {
            override fun decode(composing: String, limit: Int): List<Candidate> = when (composing) {
                "pipei" -> listOf(Candidate("匹配", canonicalPinyin = "pipei", canonicalInitials = "pp"))
                "pi" -> List(80) { index ->
                    Candidate(
                        text = ('\u4E00'.code + index).toChar().toString(),
                        score = (80 - index).toFloat(),
                        canonicalPinyin = "pi",
                        canonicalInitials = "p",
                    )
                }
                else -> emptyList()
            }.take(limit)
        }
        val decoder = AdaptivePinyinDecoder(many, MemoryUserLexicon(), segmenter)
        val state = "pipei".fold(PinyinComposition()) { value, character -> value.type(character) }

        val values = decoder.decodeProgressively(state, 255).prefixCandidates

        assertEquals(80, values.size)
        assertTrue(values.any { it.candidate.text == ('\u4E00'.code + 79).toChar().toString() })
        assertTrue(values.none { it.remainingPinyin.isEmpty() })
    }

    @Test
    fun compositionStopsAtTheSharedNinetySixLetterBoundary() {
        val maximum = "a".repeat(PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH)
            .fold(PinyinComposition()) { composition, character -> composition.type(character) }

        val overLimit = maximum.type('a')

        assertEquals(PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH, maximum.remainingPinyin.length)
        assertEquals(maximum, overLimit)
    }

    @Test
    fun acceptedPrefixesStillCountTowardTheCompositionBoundary() {
        val accepted = PinyinComposition(
            acceptedSegments = listOf(
                AcceptedPinyinSegment(
                    text = "已",
                    consumedPinyin =
                        "a".repeat(PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH - 1),
                ),
            ),
            remainingPinyin = "b",
            revision = 9,
        )

        assertEquals(PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH, accepted.composingCodeLength)
        assertEquals(accepted, accepted.type('c'))
    }

    @Test
    fun progressiveDecodeRejectsAnOverlongDirectlyConstructedComposition() {
        var decodeCalls = 0
        val decoder = AdaptivePinyinDecoder(
            base = object : InputDecoder {
                override fun decode(composing: String, limit: Int): List<Candidate> {
                    decodeCalls += 1
                    return listOf(Candidate("\u4E00", canonicalPinyin = "a", canonicalInitials = "a"))
                }
            },
            userLexicon = MemoryUserLexicon(),
            segmenter = segmenter,
        )
        val overlong = PinyinComposition(
            remainingPinyin = "a".repeat(PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH + 1),
            revision = 17,
        )

        val result = decoder.decodeProgressively(overlong, 8)

        assertEquals(17, result.revision)
        assertTrue(result.wholeCandidates.isEmpty())
        assertTrue(result.prefixCandidates.isEmpty())
        assertEquals(0, decodeCalls)
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
