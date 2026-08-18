package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9AlternativeInputDecoderTest {
    @Test
    fun curatedModernVocabularyIsAvailableThroughTheT9Path() {
        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("944636484"),
            pathSource = T9PinyinPathSource { _, _ ->
                listOf(
                    path(
                        "zhi" to T9PinyinSegmentKind.SYLLABLE,
                        "neng" to T9PinyinSegmentKind.SYLLABLE,
                        "ti" to T9PinyinSegmentKind.SYLLABLE,
                    ),
                )
            },
            pinyinDecoder = RecordingCanonicalDecoder(
                listOf(Candidate("智能", matchKind = CandidateMatchKind.BASE_COMPOSED)),
            ),
            leftContext = "",
            limit = 8,
        )

        assertTrue(result.candidates.indexOfFirst { it.text == "智能体" } in 0..1)
    }

    @Test
    fun inferredJointsStayOutOfDecoderQueryButRemainInPresentationLabel() {
        val decoder = RecordingCanonicalDecoder(
            listOf(Candidate(text = "\u6D51\u8EAB", matchKind = CandidateMatchKind.BASE_EXACT)),
        )

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("4867436"),
            pathSource = T9PinyinPathSource { _, _ ->
                listOf(
                    path(
                        "hun" to T9PinyinSegmentKind.SYLLABLE,
                        "shen" to T9PinyinSegmentKind.SYLLABLE,
                    ),
                )
            },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 8,
        )

        assertEquals(listOf("hunshen"), decoder.queries)
        assertTrue(result.composingLabel.endsWith("hun'shen"))
    }

    @Test
    fun forcedJointAndLockedEdgeBoundariesReachDecoderForEveryPathKind() {
        val forcedComposition = "7436".fold(compositionOf("486").forceJoint()) { state, digit ->
            state.typeDigit(digit)
        }
        val lockedComposition = compositionOf("4867436").lockEdge(
            T9LockedEdge(digitStart = 0, digitEnd = 3, spelling = "hun"),
        )
        val hybrid = path(
            "hun" to T9PinyinSegmentKind.SYLLABLE,
            "sh" to T9PinyinSegmentKind.INITIAL,
            "en" to T9PinyinSegmentKind.SYLLABLE,
        )

        listOf(forcedComposition, lockedComposition).forEach { composition ->
            val decoder = RecordingCanonicalDecoder(emptyList())
            T9AlternativeInputDecoder.decode(
                composition = composition,
                pathSource = T9PinyinPathSource { _, _ -> listOf(hybrid) },
                pinyinDecoder = decoder,
                leftContext = "",
                limit = 8,
            )

            assertEquals(listOf("hun'shen"), decoder.queries)
        }
    }

    @Test
    fun productionDecoderFindsForcedAndLockedCandidateBeyondStructuralFallbackBeam() {
        val forcedComposition = "426".fold(compositionOf("64").forceJoint()) { state, digit ->
            state.typeDigit(digit)
        }
        val lockedComposition = compositionOf("64426").lockEdge(
            T9LockedEdge(digitStart = 0, digitEnd = 2, spelling = "ni"),
        )
        val paths = listOf(
            path(
                "ni" to T9PinyinSegmentKind.SYLLABLE,
                "gam" to T9PinyinSegmentKind.SYLLABLE,
            ),
            path(
                "ni" to T9PinyinSegmentKind.SYLLABLE,
                "ian" to T9PinyinSegmentKind.SYLLABLE,
            ),
            path(
                "ni" to T9PinyinSegmentKind.SYLLABLE,
                "hao" to T9PinyinSegmentKind.SYLLABLE,
            ),
            path(
                "ni" to T9PinyinSegmentKind.SYLLABLE,
                "hbo" to T9PinyinSegmentKind.SYLLABLE,
            ),
        )
        val decoder = adaptiveProductionDecoderFixture()

        listOf(forcedComposition, lockedComposition).forEach { composition ->
            val result = T9AlternativeInputDecoder.decode(
                composition = composition,
                pathSource = T9PinyinPathSource { _, _ -> paths },
                pinyinDecoder = decoder,
                leftContext = "",
                limit = 8,
            )

            assertEquals("\u4F60\u597D", result.candidates.firstOrNull()?.text)
            assertTrue(result.decodedQueryCount >= 3)
            assertTrue(result.composingLabel.endsWith("ni'hao"))
        }
    }

    @Test
    fun deduplicatesEquivalentHybridDecoderQueries() {
        val first = path(
            "hun" to T9PinyinSegmentKind.SYLLABLE,
            "shen" to T9PinyinSegmentKind.SYLLABLE,
            "x" to T9PinyinSegmentKind.INITIAL,
            "s" to T9PinyinSegmentKind.INITIAL,
        )
        val second = path(
            "h" to T9PinyinSegmentKind.INITIAL,
            "un" to T9PinyinSegmentKind.SYLLABLE,
            "sh" to T9PinyinSegmentKind.INITIAL,
            "en" to T9PinyinSegmentKind.SYLLABLE,
            "x" to T9PinyinSegmentKind.INITIAL,
            "s" to T9PinyinSegmentKind.INITIAL,
        )
        val decoder = RecordingCanonicalDecoder()

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("486743697"),
            pathSource = T9PinyinPathSource { _, _ -> listOf(first, second) },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 8,
        )

        assertEquals(listOf("hunshenxs"), decoder.queries)
        assertEquals(1, result.decodedQueryCount)
        assertEquals(2, result.availablePathCount)
    }

    @Test
    fun canonicalPathUsesChineseOnlyNoCorrectionSeam() {
        val decoder = RecordingCanonicalDecoder()

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("486"),
            pathSource = T9PinyinPathSource { _, _ ->
                listOf(path("hun" to T9PinyinSegmentKind.SYLLABLE))
            },
            pinyinDecoder = decoder,
            leftContext = "中",
            limit = 8,
        )

        assertEquals(listOf("hun"), decoder.contextualQueries)
        assertEquals('中'.code, decoder.previousCodePoints.single())
        assertEquals(listOf("候选hun"), result.candidates.map(Candidate::text))
        assertEquals(0, decoder.fallbackCalls)
    }

    @Test
    fun longAmbiguousInputStopsAfterSmallBeamOnceLexicalEvidenceFillsIt() {
        val paths = (0 until 32).map { index ->
            if (index == 7) {
                path(
                    "hun" to T9PinyinSegmentKind.SYLLABLE,
                    "shen" to T9PinyinSegmentKind.SYLLABLE,
                    "x" to T9PinyinSegmentKind.INITIAL,
                    "s" to T9PinyinSegmentKind.INITIAL,
                )
            } else {
                path("query$index" to T9PinyinSegmentKind.INITIAL)
            }
        }
        val decoder = object : CanonicalChineseOnlyInputDecoder {
            var calls = 0

            override fun decode(composing: String, limit: Int): List<Candidate> =
                error("generic decode must stay outside T9")

            override fun decodeChineseOnly(composing: String, limit: Int): List<Candidate> =
                error("validated T9 paths must omit correction")

            override fun decodeCanonicalChineseOnly(
                composing: String,
                limit: Int,
            ): List<Candidate> {
                calls += 1
                return if (composing == "hunshenxs") {
                    listOf(
                        Candidate(
                            text = "浑身解数",
                            score = 10f,
                            matchKind = CandidateMatchKind.BASE_HYBRID,
                        ),
                    )
                } else {
                    listOf(
                        Candidate(
                            text = "普通候选",
                            matchKind = CandidateMatchKind.BASE_COMPOSED,
                        ),
                    )
                }
            }
        }

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("486743697"),
            pathSource = T9PinyinPathSource { _, _ -> paths },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 64,
        )

        assertEquals(10, decoder.calls)
        assertEquals(8, result.decodedQueryCount)
        assertEquals(2, result.expandedQueryCount)
        assertEquals(10, result.decodeInvocationCount)
        assertEquals("浑身解数", result.candidates.first().text)
        assertEquals("hun'shen'x's", result.composingLabel)
    }

    @Test
    fun canonicalExactPhraseStopsAfterTheDiverseFirstStage() {
        val paths = (0 until 32).map { index ->
            path("query${('a'.code + index).toChar()}" to T9PinyinSegmentKind.INITIAL)
        }
        var calls = 0
        val decoder = object : CanonicalChineseOnlyInputDecoder {
            override fun decode(composing: String, limit: Int): List<Candidate> = emptyList()

            override fun decodeChineseOnly(composing: String, limit: Int): List<Candidate> =
                emptyList()

            override fun decodeCanonicalChineseOnly(
                composing: String,
                limit: Int,
            ): List<Candidate> {
                calls += 1
                return listOf(
                    Candidate(
                        text = if (composing == "querya") "\u7CBE\u786E" else "\u666E\u901A",
                        matchKind = if (composing == "querya") {
                            CandidateMatchKind.BASE_EXACT
                        } else {
                            CandidateMatchKind.BASE_COMPOSED
                        },
                    ),
                )
            }
        }

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("486743697"),
            pathSource = T9PinyinPathSource { _, _ -> paths },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 1,
        )

        assertEquals(8, calls)
        assertEquals(8, result.decodedQueryCount)
        assertEquals("\u7CBE\u786E", result.candidates.single().text)
    }

    @Test
    fun diversityBeamContinuesOneProbeAtATimeWhenFirstStageHasNoLexicalEvidence() {
        val paths = (0 until 12).map { index ->
            path(
                (if (index == 8) "target" else "query${('a'.code + index).toChar()}") to
                    T9PinyinSegmentKind.INITIAL,
            )
        }
        val decoder = object : CanonicalChineseOnlyInputDecoder {
            val queries = mutableListOf<String>()

            override fun decode(composing: String, limit: Int): List<Candidate> = emptyList()

            override fun decodeChineseOnly(composing: String, limit: Int): List<Candidate> =
                emptyList()

            override fun decodeCanonicalChineseOnly(
                composing: String,
                limit: Int,
            ): List<Candidate> {
                queries += composing
                return if (composing == "target") {
                    listOf(
                        Candidate(
                            text = "\u76EE\u6807",
                            score = 10f,
                            matchKind = CandidateMatchKind.BASE_HYBRID,
                        ),
                    )
                } else {
                    listOf(
                        Candidate(
                            text = "\u666E\u901A",
                            matchKind = CandidateMatchKind.BASE_COMPOSED,
                        ),
                    )
                }
            }
        }

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("7426".repeat(7) + "742"),
            pathSource = T9PinyinPathSource { _, _ -> paths },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 1,
        )

        assertEquals(9, result.decodedQueryCount)
        assertEquals("target", decoder.queries.last())
        assertEquals("\u76EE\u6807", result.candidates.single().text)
    }

    @Test
    fun thirtyOneAndThirtyTwoDigitsShareTheCompleteStagedBeam() {
        val paths = (0 until 12).map { index ->
            path("query${('a'.code + index).toChar()}" to T9PinyinSegmentKind.INITIAL)
        }

        listOf(31, 32).forEach { digitCount ->
            val decoder = RecordingCanonicalDecoder(emptyList())
            val result = T9AlternativeInputDecoder.decode(
                composition = compositionOf("7".repeat(digitCount)),
                pathSource = T9PinyinPathSource { _, _ -> paths },
                pinyinDecoder = decoder,
                leftContext = "",
                limit = 8,
            )

            assertEquals(12, result.decodedQueryCount)
            assertEquals(12, decoder.queries.size)
        }
    }

    @Test
    fun cheapLexicalBeamFallsBackToOneCompleteStructuralWinner() {
        val paths = (0 until 12).map { index ->
            path("query${('a'.code + index).toChar()}" to T9PinyinSegmentKind.INITIAL)
        }
        val decoder = object : CanonicalChineseOnlyInputDecoder,
            CanonicalChineseLexicalProbeDecoder {
            val lexicalQueries = mutableListOf<String>()
            val completeQueries = mutableListOf<String>()

            override fun decode(composing: String, limit: Int): List<Candidate> = emptyList()

            override fun decodeChineseOnly(composing: String, limit: Int): List<Candidate> =
                emptyList()

            override fun probeCanonicalChineseOnly(
                composing: String,
                limit: Int,
            ): List<Candidate> {
                lexicalQueries += composing
                return emptyList()
            }

            override fun decodeCanonicalChineseOnly(
                composing: String,
                limit: Int,
            ): List<Candidate> {
                completeQueries += composing
                return listOf(
                    Candidate(
                        text = "\u7ED3\u6784\u5019\u9009",
                        matchKind = CandidateMatchKind.BASE_COMPOSED,
                    ),
                )
            }
        }

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("7426".repeat(8)),
            pathSource = T9PinyinPathSource { _, _ -> paths },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 8,
        )

        assertEquals(12, decoder.lexicalQueries.size)
        assertEquals(1, decoder.completeQueries.size)
        assertEquals(12, result.decodedQueryCount)
        assertEquals(1, result.expandedQueryCount)
        assertEquals("\u7ED3\u6784\u5019\u9009", result.candidates.single().text)
    }

    @Test
    fun extremeCompositionCapsLexicalQueriesButStillReturnsBoundedPaths() {
        val paths = (0 until 32).map { index ->
            path("query$index" to T9PinyinSegmentKind.INITIAL)
        }
        val decoder = RecordingCanonicalDecoder()

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("7426".repeat(24)),
            pathSource = T9PinyinPathSource { _, _ -> paths },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 64,
        )

        assertEquals(1, decoder.queries.size)
        assertEquals(1, result.decodedQueryCount)
        assertEquals(0, result.expandedQueryCount)
        assertEquals(32, result.availablePathCount)
    }

    @Test
    fun pasteBeyondSixtyFourDigitsUsesLexicalOnlyResourceEnvelope() {
        val paths = (0 until 32).map { index ->
            path("query${('a'.code + index).toChar()}" to T9PinyinSegmentKind.INITIAL)
        }
        var lexicalCalls = 0
        var completeCalls = 0
        val decoder = object : CanonicalChineseOnlyInputDecoder,
            CanonicalChineseLexicalProbeDecoder {
            override fun decode(composing: String, limit: Int): List<Candidate> = emptyList()

            override fun decodeChineseOnly(composing: String, limit: Int): List<Candidate> =
                emptyList()

            override fun probeCanonicalChineseOnly(
                composing: String,
                limit: Int,
            ): List<Candidate> {
                lexicalCalls += 1
                return emptyList()
            }

            override fun decodeCanonicalChineseOnly(
                composing: String,
                limit: Int,
            ): List<Candidate> {
                completeCalls += 1
                return emptyList()
            }
        }

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("7426".repeat(24)),
            pathSource = T9PinyinPathSource { _, _ -> paths },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 64,
        )

        assertEquals(1, lexicalCalls)
        assertEquals(0, completeCalls)
        assertEquals(1, result.decodedQueryCount)
        assertEquals(0, result.expandedQueryCount)
    }

    @Test
    fun continuationProbeStopsBeforeTheNextExpensivePath() {
        val paths = (0 until 10).map { index ->
            path("query$index" to T9PinyinSegmentKind.INITIAL)
        }
        val decoder = RecordingCanonicalDecoder(emptyList())
        var probes = 0

        val result = T9AlternativeInputDecoder.decode(
            composition = compositionOf("486"),
            pathSource = T9PinyinPathSource { _, _ -> paths },
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 8,
            shouldContinue = { ++probes <= 4 },
        )

        assertEquals(3, decoder.queries.size)
        assertEquals(3, result.decodedQueryCount)
        assertTrue(result.candidates.isEmpty())
    }

    private class RecordingCanonicalDecoder(
        private val values: List<Candidate>? = null,
    ) : CanonicalChineseOnlyInputDecoder {
        val queries = mutableListOf<String>()
        val contextualQueries = mutableListOf<String>()
        val previousCodePoints = mutableListOf<Int>()
        var fallbackCalls = 0

        override fun decode(composing: String, limit: Int): List<Candidate> {
            fallbackCalls += 1
            return emptyList()
        }

        override fun decodeChineseOnly(composing: String, limit: Int): List<Candidate> {
            fallbackCalls += 1
            return emptyList()
        }

        override fun decodeCanonicalChineseOnly(composing: String, limit: Int): List<Candidate> {
            queries += composing
            return values ?: listOf(Candidate("候选$composing"))
        }

        override fun decodeCanonicalChineseOnlyAfter(
            previousCodePoint: Int,
            composing: String,
            limit: Int,
        ): List<Candidate> {
            contextualQueries += composing
            previousCodePoints += previousCodePoint
            return values ?: listOf(Candidate("候选$composing"))
        }
    }

    private fun path(vararg spellings: Pair<String, T9PinyinSegmentKind>): T9PinyinPath {
        var digitOffset = 0
        return T9PinyinPath(
            spellings.map { (spelling, kind) ->
                T9PinyinSegment(
                    spelling = spelling,
                    digitStart = digitOffset,
                    digitEnd = digitOffset + spelling.length,
                    kind = kind,
                ).also { digitOffset += spelling.length }
            },
        )
    }

    private fun compositionOf(digits: String): T9Composition =
        digits.fold(T9Composition()) { state, digit -> state.typeDigit(digit) }

    private fun adaptiveProductionDecoderFixture(): AdaptivePinyinDecoder {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeBytes("SPLX")
            output.writeShort(3)
            output.writeInt(2)
            listOf(
                Triple("hao", "\u597D", "h"),
                Triple("ni", "\u4F60", "n"),
            ).forEach { (code, text, initials) ->
                output.writeByte(code.length)
                output.writeBytes(code)
                output.writeByte(1)
                val encoded = text.toByteArray(Charsets.UTF_8)
                output.writeByte(encoded.size)
                output.write(encoded)
                output.writeInt(10_000)
                output.writeByte(initials.length)
                output.writeBytes(initials)
                output.writeByte(0)
            }
        }
        return AdaptivePinyinDecoder(
            base = PinyinDecoder.fromBytes(bytes.toByteArray()),
            userLexicon = MemoryUserLexicon(),
            segmenter = PinyinSyllableSegmenter(setOf("hao", "ni")),
        )
    }
}
