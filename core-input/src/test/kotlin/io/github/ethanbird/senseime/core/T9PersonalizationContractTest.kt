package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Personalization contract across T9 path decoding and a reconstructed user lexicon. */
class T9PersonalizationContractTest {
    @Test
    fun hybridT9SelectionIsRecalledImmediatelyAndAfterReload() {
        val journal = linkedMapOf<Pair<String, String>, LearnedPhrase>()
        val firstLexicon = journaledLexicon(journal)
        val firstDecoder = adaptiveDecoder(firstLexicon)
        val initial = decode(firstDecoder)
        val selected = initial.candidates.first { it.text == SELECTED_TEXT }

        requireNotNull(
            firstDecoder.learn(
                // Mirrors the service commit path: the candidate owns the canonical spelling,
                // while the T9 decoder owns the hybrid query that reached it.
                rawInput = requireNotNull(selected.canonicalPinyin),
                candidate = selected,
                evidence = UserLearningEvidence(
                    UserSelectionKind.EXPLICIT_SELECTION,
                    selectedRank = initial.candidates.indexOf(selected),
                ),
            ),
        )

        assertEquals(SELECTED_TEXT, decode(firstDecoder).candidates.first().text)
        val explicitlySegmented = compositionOf("486743697")
            .lockEdge(T9LockedEdge(0, 3, "hun"))
            .lockEdge(T9LockedEdge(3, 7, "shen"))
            .lockEdge(T9LockedEdge(7, 8, "x"))
            .lockEdge(T9LockedEdge(8, 9, "s"))
        assertEquals(
            SELECTED_TEXT,
            decode(
                decoder = firstDecoder,
                composition = explicitlySegmented,
                paths = listOf(PATHS.first()),
            ).candidates.first().text,
        )

        val reloaded = adaptiveDecoder(
            MemoryUserLexicon(initial = journal.values, clock = { CLOCK_MILLIS }),
        )
        val recalled = decode(reloaded).candidates.first()
        assertEquals(SELECTED_TEXT, recalled.text)
        assertEquals(CandidateMatchKind.USER_FULL, recalled.matchKind)
        assertTrue(journal.values.single().aliases.contains(HYBRID_QUERY))
    }

    @Test
    fun quickDeleteDemotionIsVisibleImmediatelyAndAfterReload() {
        val journal = linkedMapOf<Pair<String, String>, LearnedPhrase>()
        val firstDecoder = adaptiveDecoder(journaledLexicon(journal))
        val initial = decode(firstDecoder)
        val selected = initial.candidates.first { it.text == SELECTED_TEXT }
        val learned = requireNotNull(
            firstDecoder.learn(
                rawInput = requireNotNull(selected.canonicalPinyin),
                candidate = selected,
                evidence = UserLearningEvidence(
                    UserSelectionKind.EXPLICIT_SELECTION,
                    selectedRank = initial.candidates.indexOf(selected),
                ),
            ),
        )
        assertEquals(SELECTED_TEXT, decode(firstDecoder).candidates.first().text)

        requireNotNull(firstDecoder.demote(learned, UserNegativeFeedback.QUICK_DELETE))

        assertEquals(DEFAULT_TEXT, decode(firstDecoder).candidates.first().text)
        val reloaded = adaptiveDecoder(
            MemoryUserLexicon(initial = journal.values, clock = { CLOCK_MILLIS }),
        )
        assertEquals(DEFAULT_TEXT, decode(reloaded).candidates.first().text)
        assertTrue(journal.values.single().negativeEvidence > 0f)
    }

    private fun decode(
        decoder: AdaptivePinyinDecoder,
        composition: T9Composition = compositionOf("486743697"),
        paths: List<T9PinyinPath> = PATHS,
    ): T9AlternativeDecoding =
        T9AlternativeInputDecoder.decode(
            composition = composition,
            pathSource = T9PinyinPathSource { _, _ -> paths },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 8,
        )

    private fun adaptiveDecoder(userLexicon: UserLexicon): AdaptivePinyinDecoder =
        AdaptivePinyinDecoder(
            base = HybridFixtureDecoder,
            userLexicon = userLexicon,
            segmenter = PinyinSyllableSegmenter(setOf("hun", "shen", "xie", "shu", "huo")),
        )

    private fun journaledLexicon(
        journal: MutableMap<Pair<String, String>, LearnedPhrase>,
    ): MemoryUserLexicon = MemoryUserLexicon(
        clock = { CLOCK_MILLIS },
        onRecord = { learned -> journal[learned.fullPinyin to learned.text] = learned },
        onForget = { fullPinyin, text -> journal.remove(fullPinyin to text) },
    )

    private object HybridFixtureDecoder :
        CanonicalChineseOnlyInputDecoder,
        CanonicalChineseLexicalProbeDecoder,
        RankedCandidateDecoder {
        override fun decode(composing: String, limit: Int): List<Candidate> =
            decodeCanonicalChineseOnly(composing, limit)

        override fun decodeChineseOnly(composing: String, limit: Int): List<Candidate> =
            decodeCanonicalChineseOnly(composing, limit)

        override fun decodeCanonicalChineseOnly(composing: String, limit: Int): List<Candidate> =
            when (composing) {
                HYBRID_QUERY -> listOf(
                    candidate(DEFAULT_TEXT, score = 20f),
                    candidate(SELECTED_TEXT, score = 19f),
                )
                "huo" -> listOf(
                    Candidate(
                        text = "或",
                        score = 10f,
                        canonicalPinyin = "huo",
                        matchKind = CandidateMatchKind.BASE_EXACT,
                        canonicalInitials = "h",
                    ),
                )
                else -> emptyList()
            }.take(limit)

        override fun probeCanonicalChineseOnly(composing: String, limit: Int): List<Candidate> =
            decodeCanonicalChineseOnly(composing, limit)

        private fun candidate(text: String, score: Float) = Candidate(
            text = text,
            score = score,
            canonicalPinyin = CANONICAL_PINYIN,
            matchKind = CandidateMatchKind.BASE_HYBRID,
            canonicalInitials = "hsxs",
        )
    }

    private fun compositionOf(digits: String): T9Composition =
        digits.fold(T9Composition()) { state, digit -> state.typeDigit(digit) }

    private companion object {
        const val CLOCK_MILLIS = 1_000L
        const val HYBRID_QUERY = "hunshenxs"
        const val CANONICAL_PINYIN = "hunshenxieshu"
        const val DEFAULT_TEXT = "浑身写书"
        const val SELECTED_TEXT = "浑身解数"

        val PATHS = listOf(
            pathOf("hun", "shen", "x", "s"),
            pathOf("huo"),
        )

        fun pathOf(vararg spellings: String): T9PinyinPath {
            var digitStart = 0
            return T9PinyinPath(
                spellings.map { spelling ->
                    T9PinyinSegment(
                        spelling = spelling,
                        digitStart = digitStart,
                        digitEnd = digitStart + spelling.length,
                        kind = if (spelling.length == 1) {
                            T9PinyinSegmentKind.INITIAL
                        } else {
                            T9PinyinSegmentKind.SYLLABLE
                        },
                    ).also { digitStart += spelling.length }
                },
            )
        }
    }
}
