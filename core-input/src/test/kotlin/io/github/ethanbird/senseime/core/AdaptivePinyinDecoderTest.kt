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
    fun userLexiconEvictsTheWeakestEntryAtItsConfiguredCodeBudget() {
        var now = 1_000L
        val removed = mutableListOf<Pair<String, String>>()
        val lexicon = MemoryUserLexicon(
            clock = { now++ },
            onForget = { fullPinyin, text -> removed += fullPinyin to text },
            maximumRecords = 8,
            maximumRecordsPerFullPinyin = 1,
        )

        lexicon.record("ni", "n", "你", evidence = UserLearningEvidence.DEFAULT_ACCEPT)
        lexicon.record("ni", "n", "拟", evidence = UserLearningEvidence.EXPLICIT_SELECTION)

        assertEquals(listOf("拟"), lexicon.lookup("ni", 8).map { it.text })
        assertEquals(listOf("ni" to "你"), removed)
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
    fun oneSameStrengthEventDoesNotEraseARepeatedExplicitPreference() {
        var now = 1000L
        val lexicon = MemoryUserLexicon(clock = { now })
        repeat(20) { lexicon.record("di", "d", "地") }
        now = 1001L

        lexicon.record("de", "d", "的")

        assertEquals(listOf("地", "的"), lexicon.lookup("d", 2).map { it.text })
    }

    @Test
    fun defaultAcceptanceDoesNotOverrideABetterBaseCandidate() {
        val lexicon = MemoryUserLexicon(clock = { 1_000L })
        val decoder = scoredHomophoneDecoder(lexicon)
        val second = decoder.decode("ni", 8).first { it.text == "拟" }

        decoder.learn("ni", second, UserLearningEvidence.DEFAULT_ACCEPT)

        assertEquals(listOf("你", "拟"), decoder.decode("ni", 2).map { it.text })
    }

    @Test
    fun explicitDeepSelectionCarriesMoreEvidenceThanDefaultAcceptance() {
        val lexicon = MemoryUserLexicon(clock = { 1_000L })
        val decoder = scoredHomophoneDecoder(lexicon)
        val second = decoder.decode("ni", 8).first { it.text == "拟" }

        decoder.learn(
            "ni",
            second,
            UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, selectedRank = 7),
        )

        assertEquals("拟", decoder.decode("ni", 2).first().text)
    }

    @Test
    fun progressiveSelectionHasDistinctEvidenceBetweenDefaultAndExplicit() {
        val lexicon = MemoryUserLexicon(clock = { 1_000L })
        lexicon.record(
            "ni",
            "n",
            "你",
            evidence = UserLearningEvidence.DEFAULT_ACCEPT,
        )
        lexicon.record(
            "ni",
            "n",
            "尼",
            evidence = UserLearningEvidence.PROGRESSIVE_SELECTION,
        )
        lexicon.record(
            "ni",
            "n",
            "拟",
            evidence = UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, selectedRank = 7),
        )

        assertEquals(listOf("拟", "尼", "你"), lexicon.lookup("n", 3).map { it.text })
    }

    @Test
    fun oneExplicitSelectionDecaysInsteadOfPermanentlyPinningTheCandidate() {
        var now = 1_000L
        val lexicon = MemoryUserLexicon(clock = { now })
        val decoder = scoredHomophoneDecoder(lexicon)
        val second = decoder.decode("ni", 8).first { it.text == "拟" }
        decoder.learn(
            "ni",
            second,
            UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, selectedRank = 7),
        )
        assertEquals("拟", decoder.decode("ni", 2).first().text)

        now += 60L * 24L * 60L * 60L * 1_000L

        assertEquals("你", decoder.decode("ni", 2).first().text)
    }

    @Test
    fun quickDeleteDemotesTheLearnedPromotionAndSurvivesReload() {
        val now = 1_000L
        val journal = linkedMapOf<Pair<String, String>, LearnedPhrase>()
        val lexicon = MemoryUserLexicon(
            clock = { now },
            onRecord = { journal[it.fullPinyin to it.text] = it },
        )
        val decoder = scoredHomophoneDecoder(lexicon)
        val selected = decoder.decode("ni", 8).first { it.text == "拟" }
        val learned = requireNotNull(
            decoder.learn(
                "ni",
                selected,
                UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, selectedRank = 7),
            ),
        )
        assertEquals("拟", decoder.decode("ni", 2).first().text)

        decoder.demote(learned, UserNegativeFeedback.QUICK_DELETE)

        assertEquals("你", decoder.decode("ni", 2).first().text)
        assertEquals(
            "你",
            scoredHomophoneDecoder(MemoryUserLexicon(journal.values, clock = { now }))
                .decode("ni", 2)
                .first()
                .text,
        )
    }

    @Test
    fun negativeEvidenceCanMoveADislikedBaseDefaultBelowItsAlternative() {
        val lexicon = MemoryUserLexicon(clock = { 1_000L })
        val decoder = scoredHomophoneDecoder(lexicon)
        val primary = decoder.decode("ni", 2).first()
        val learned = requireNotNull(
            decoder.learn("ni", primary, UserLearningEvidence.DEFAULT_ACCEPT),
        )

        decoder.demote(learned, UserNegativeFeedback.MANUAL_DEMOTION)

        assertEquals(listOf("拟", "你"), decoder.decode("ni", 2).map { it.text })
    }

    @Test
    fun olderNegativeEvidenceDecaysBeforeANewDemotionIsRecorded() {
        var now = 1_000L
        val lexicon = MemoryUserLexicon(clock = { now })
        lexicon.record(
            "ni",
            "n",
            "拟",
            evidence = UserLearningEvidence.EXPLICIT_SELECTION,
        )
        lexicon.demote("ni", "拟", UserNegativeFeedback.QUICK_DELETE)
        now += 7L * 24L * 60L * 60L * 1_000L

        val demoted = requireNotNull(
            lexicon.demote("ni", "拟", UserNegativeFeedback.MANUAL_DEMOTION),
        )

        assertEquals(3.75f, demoted.negativeEvidence, 0.02f)
    }

    @Test
    fun forgettingRemovesEveryLookupAlias() {
        val removed = mutableListOf<Pair<String, String>>()
        val lexicon = MemoryUserLexicon(
            clock = { 1_000L },
            onForget = { fullPinyin, text -> removed += fullPinyin to text },
        )
        val learned = lexicon.record(
            "funv",
            "fn",
            "妇女",
            aliases = setOf("fun"),
        )

        assertTrue(lexicon.forget(learned.fullPinyin, learned.text))
        assertTrue(lexicon.lookup("funv", 5).isEmpty())
        assertTrue(lexicon.lookup("fn", 5).isEmpty())
        assertTrue(lexicon.lookup("fun", 5).isEmpty())
        assertEquals(listOf("funv" to "妇女"), removed)
    }

    @Test
    fun aliasesAreBoundedAndTheCurrentObservationReplacesAnOlderAlias() {
        val lexicon = MemoryUserLexicon(
            clock = { 1_000L },
            maximumAliasesPerRecord = 4,
        )
        listOf("aa", "bb", "cc", "dd", "ee").forEach { alias ->
            lexicon.record("funv", "fn", "妇女", aliases = setOf(alias))
        }

        val learned = lexicon.lookup("funv", 1).single()

        assertEquals(4, learned.aliases.size)
        assertTrue("ee" in learned.aliases)
        assertEquals("妇女", lexicon.lookup("ee", 1).single().text)
        assertTrue(lexicon.lookup("dd", 1).isEmpty())
    }

    @Test
    fun sharedShortCodeIndexStaysBoundedWithoutDeletingExactOrNegativeRecords() {
        val lexicon = MemoryUserLexicon(
            clock = { 1_000L },
            maximumRecords = 10_000,
            maximumRecordsPerLookupCode = 32,
        )
        repeat(10_000) { index ->
            lexicon.record(
                fullPinyin = stressFullCode(index),
                initials = "x",
                text = "词$index",
            )
        }

        assertEquals(32, lexicon.indexedCandidateCount("x"))
        assertEquals(8, lexicon.lookup("x", 8).size)
        assertEquals("词9999", lexicon.lookup("x", 1).single().text)

        val firstCode = stressFullCode(0)
        val demoted = requireNotNull(
            lexicon.demote(firstCode, "词0", UserNegativeFeedback.QUICK_DELETE),
        )
        assertTrue(demoted.negativeEvidence > 0f)
        assertEquals(demoted.negativeEvidence, lexicon.lookup(firstCode, 1).single().negativeEvidence)
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

    @Test
    fun progressiveDecodeUsesTheNearestCodePointFromAcceptedOrEditorContext() {
        var observedCodePoint = -1
        val contextualBase = object : ContextualInputDecoder {
            override fun decode(composing: String, limit: Int): List<Candidate> =
                listOf(Candidate("plain"))

            override fun decodeAfter(
                previousCodePoint: Int,
                composing: String,
                limit: Int,
            ): List<Candidate> {
                observedCodePoint = previousCodePoint
                return listOf(Candidate("context"))
            }
        }
        val decoder = AdaptivePinyinDecoder(
            contextualBase,
            MemoryUserLexicon(),
            segmenter,
        )

        decoder.decodeProgressively(
            composition = PinyinComposition(remainingPinyin = "ni"),
            leftContext = "编辑器上下文",
            limit = 8,
        )
        assertEquals("文".codePointAt(0), observedCodePoint)

        decoder.decodeProgressively(
            composition = PinyinComposition(
                acceptedSegments = listOf(AcceptedPinyinSegment("中国", "zhongguo")),
                remainingPinyin = "ni",
            ),
            leftContext = "旧",
            limit = 8,
        )
        assertEquals("国".codePointAt(0), observedCodePoint)
    }

    @Test
    fun progressivePrefixCandidatesUseTheSameEditorContextAsWholeCandidates() {
        val contextualBase = object : ContextualInputDecoder {
            override fun decode(composing: String, limit: Int): List<Candidate> = when (composing) {
                "ni" -> listOf(Candidate("你", canonicalPinyin = "ni", canonicalInitials = "n"))
                "nihao" -> listOf(Candidate("你好", canonicalPinyin = "nihao", canonicalInitials = "nh"))
                else -> emptyList()
            }

            override fun decodeAfter(
                previousCodePoint: Int,
                composing: String,
                limit: Int,
            ): List<Candidate> = when (composing) {
                "ni" -> listOf(Candidate("拟", canonicalPinyin = "ni", canonicalInitials = "n"))
                "nihao" -> listOf(Candidate("拟好", canonicalPinyin = "nihao", canonicalInitials = "nh"))
                else -> decode(composing, limit)
            }
        }
        val decoder = AdaptivePinyinDecoder(
            contextualBase,
            MemoryUserLexicon(),
            segmenter,
        )

        val result = decoder.decodeProgressively(
            composition = PinyinComposition(remainingPinyin = "nihao"),
            leftContext = "他",
            limit = 8,
        )

        assertEquals("拟好", result.wholeCandidates.first().text)
        assertEquals(
            "拟",
            result.prefixCandidates.first { it.consumedPinyin == "ni" }.candidate.text,
        )
    }

    @Test
    fun progressivePrefixSelectionUsesTheCorrectionFreeProbeSeam() {
        val ordinaryQueries = mutableListOf<String>()
        val prefixQueries = mutableListOf<String>()
        val base = object : ProgressivePrefixProbeDecoder {
            override fun decode(composing: String, limit: Int): List<Candidate> {
                ordinaryQueries += composing
                return listOf(
                    Candidate(
                        text = "你好",
                        canonicalPinyin = composing,
                        canonicalInitials = "nh",
                    ),
                )
            }

            override fun decodePrefixProbe(composing: String, limit: Int): List<Candidate> {
                prefixQueries += composing
                return listOf(
                    Candidate(
                        text = "你",
                        canonicalPinyin = composing,
                        canonicalInitials = "n",
                    ),
                )
            }
        }
        val decoder = AdaptivePinyinDecoder(base, MemoryUserLexicon(), segmenter)

        val result = decoder.decodeProgressively(
            PinyinComposition(remainingPinyin = "nihao"),
            limit = 8,
        )

        assertEquals(listOf("nihao"), ordinaryQueries)
        assertEquals(listOf("ni"), prefixQueries)
        assertEquals("你", result.prefixCandidates.single().candidate.text)
    }

    @Test
    fun supplementaryHanCandidateCanBeLearnedAsOneCharacter() {
        val supplementaryHan = String(Character.toChars(0x29F7E))
        val lexicon = MemoryUserLexicon(clock = { 1000L })
        val decoder = AdaptivePinyinDecoder(
            emptyBase(),
            lexicon,
            PinyinSyllableSegmenter(setOf("ji")),
        )

        val learned = decoder.learn(
            "ji",
            Candidate(
                text = supplementaryHan,
                canonicalPinyin = "ji",
                canonicalInitials = "j",
                matchKind = CandidateMatchKind.BASE_EXACT,
            ),
        )

        assertEquals(supplementaryHan, learned?.text)
        assertEquals(supplementaryHan, decoder.decode("ji").first().text)
    }

    @Test
    fun supplementaryHanCandidateCanBeSelectedAsAProgressivePrefix() {
        val supplementaryHan = String(Character.toChars(0x29F7E))
        val decoder = AdaptivePinyinDecoder(
            base = object : InputDecoder {
                override fun decode(composing: String, limit: Int): List<Candidate> = when (composing) {
                    "jixi" -> listOf(
                        Candidate(
                            text = supplementaryHan + "\u897F",
                            canonicalPinyin = "jixi",
                            canonicalInitials = "jx",
                        ),
                    )
                    "ji" -> listOf(
                        Candidate(
                            text = supplementaryHan,
                            canonicalPinyin = "ji",
                            canonicalInitials = "j",
                        ),
                    )
                    else -> emptyList()
                }.take(limit)
            },
            userLexicon = MemoryUserLexicon(),
            segmenter = PinyinSyllableSegmenter(setOf("ji", "xi")),
        )

        val result = decoder.decodeProgressively(
            PinyinComposition(remainingPinyin = "jixi"),
            limit = 8,
        )

        val prefix = result.prefixCandidates.single { it.consumedPinyin == "ji" }
        assertEquals(supplementaryHan, prefix.candidate.text)
        assertEquals("xi", prefix.remainingPinyin)
    }

    @Test
    fun genericBaseStillUsesSharedRankingWhenTheUserLexiconIsEmpty() {
        val unranked = listOf(
            Candidate(
                text = "\u62DF",
                score = 8f,
                canonicalPinyin = "ni",
                canonicalInitials = "n",
            ),
            Candidate(
                text = "\u4F60",
                score = 7f,
            ),
            Candidate(
                text = "\u4F60",
                score = 9f,
                canonicalPinyin = "ni",
                canonicalInitials = "n",
            ),
            Candidate(text = "", score = 100f),
        )
        val decoder = AdaptivePinyinDecoder(
            base = object : InputDecoder {
                override fun decode(composing: String, limit: Int): List<Candidate> = unranked
            },
            userLexicon = MemoryUserLexicon(),
            segmenter = segmenter,
        )

        val values = decoder.decode("ni", 2)

        assertEquals(listOf("\u4F60", "\u62DF"), values.map { it.text })
        assertEquals("ni", values.first().canonicalPinyin)
    }

    private fun emptyBase(): InputDecoder = object : InputDecoder {
        override fun decode(composing: String, limit: Int): List<Candidate> = emptyList()
    }

    private fun scoredHomophoneDecoder(userLexicon: MemoryUserLexicon): AdaptivePinyinDecoder =
        AdaptivePinyinDecoder(
            base = object : InputDecoder {
                override fun decode(composing: String, limit: Int): List<Candidate> =
                    if (composing == "ni") {
                        listOf(
                            Candidate(
                                text = "你",
                                score = 10f,
                                canonicalPinyin = "ni",
                                canonicalInitials = "n",
                            ),
                            Candidate(
                                text = "拟",
                                score = 9f,
                                canonicalPinyin = "ni",
                                canonicalInitials = "n",
                            ),
                        ).take(limit)
                    } else {
                        emptyList()
                    }
            },
            userLexicon = userLexicon,
            segmenter = segmenter,
        )

    private fun stressFullCode(value: Int): String = buildString(4) {
        append('q')
        append('a' + (value / (26 * 26)) % 26)
        append('a' + (value / 26) % 26)
        append('a' + value % 26)
    }
}
