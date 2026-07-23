package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinDecoderTest {
    private val decoder = PinyinDecoder.fromBytes(
        lexicon(
            "dang" to listOf(item("当", 3000, "d"), item("党", 2000, "d")),
            "ni" to listOf(item("你", 1000, "n"), item("呢", 400, "n")),
            "nihao" to listOf(item("你好", 5000, "nh"), item("你号", 20, "nh")),
            "ren" to listOf(item("人", 900, "r")),
            "shi" to listOf(item("是", 1000, "s")),
            "tian" to listOf(item("天", 2000, "t")),
            "wo" to listOf(item("我", 1000, "w")),
            "woshi" to listOf(item("我是", 1, "ws")),
            "xian" to listOf(item("先", 1000, "x"), item("西安", 900, "xa")),
            "xiansi" to listOf(item("先思", 300, "xs")),
            "yige" to listOf(item("一个", 1000, "yg")),
            "zhongguo" to listOf(item("中国", 2000, "zg")),
            "{w" to listOf(item("我", 850, "w"), item("为", 300, "w")),
            "{xian" to listOf(item("先思", 500, "xs")),
            "~ygz" to listOf(item("一个字", 840000, "ygz"), item("应该做", 353, "ygz")),
            "}fun|funv" to listOf(item("妇女", 9000, "fn")),
            "ken" to listOf(item("肯", 100, "k")),
            "}ken|keneng" to listOf(item("可能", 100000, "kn")),
            "}zhongwsrf|zhongwenshurufa" to listOf(item("中文输入法", 8000, "zwsrf")),
            "zhu" to listOf(item("主", 0, "z"), item("株", 1, "z", sourceTier = 1)),
        ),
    )

    @Test
    fun exactContinuousPinyinReturnsRankedChinese() {
        assertEquals(listOf("你好", "你号"), decoder.decode("nihao").map { it.text })
    }

    @Test
    fun apostrophesAndUppercaseAreNormalized() {
        assertEquals("你好", decoder.decode("Ni'Hao").first().text)
    }

    @Test
    fun incompletePinyinUsesPrefixCandidates() {
        assertEquals("你好", decoder.decode("nih").first().text)
    }

    @Test
    fun arbitraryPinyinCanComposeMultipleDictionaryWords() {
        assertEquals("我是一个人", decoder.decode("woshiyigeren").first().text)
    }

    @Test
    fun bigramViterbiScoreResolvesAnAmbiguousSentenceBoundary() {
        val model = CharacterBigramModel { previous, next ->
            if (previous == '我'.code && next == '是'.code) 2f else 0f
        }
        val contextual = PinyinDecoder.fromBytes(
            lexicon(
                "ren" to listOf(item("人", 1000, "r")),
                "shi" to listOf(item("时", 1200, "s"), item("是", 1000, "s")),
                "wo" to listOf(item("我", 1000, "w")),
            ),
            model,
        )

        assertEquals("我是人", contextual.decode("woshiren").first().text)
    }

    @Test
    fun bigramScoreDoesNotDoubleCountPairsInsideEachCandidate() {
        val model = CharacterBigramModel { previous, next ->
            if (previous == '世'.code && next == '界'.code) 2f else 0f
        }
        val contextual = PinyinDecoder.fromBytes(
            lexicon(
                "ren" to listOf(item("人", 1000, "r")),
                "shi" to listOf(item("时节", 1200, "sj"), item("世界", 1000, "sj")),
            ),
            model,
        )

        assertEquals("时节人", contextual.decode("shiren").first().text)
    }

    @Test
    fun widerSegmentBeamLetsContextRecoverTheThirdLocalCandidate() {
        val model = CharacterBigramModel { previous, next ->
            if (previous == '世'.code && next == '人'.code) 3f else 0f
        }
        val contextual = PinyinDecoder.fromBytes(
            lexicon(
                "ren" to listOf(item("人", 1000, "r")),
                "shi" to listOf(
                    item("时", 1400, "s"),
                    item("事", 1300, "s"),
                    item("世", 1000, "s"),
                    item("市", 900, "s"),
                ),
            ),
            model,
        )

        assertEquals("世人", contextual.decode("shiren").first().text)
    }

    @Test
    fun bigramSkipsACompoundBoundaryWhenBothSegmentsHaveMultipleCharacters() {
        val model = CharacterBigramModel { previous, next ->
            if (previous == '法'.code && next == '部'.code) 3f else 0f
        }
        val contextual = PinyinDecoder.fromBytes(
            lexicon(
                "budui" to listOf(item("不对", 1200, "bd"), item("部队", 1000, "bd")),
                "shuofa" to listOf(item("说法", 1000, "sf")),
            ),
            model,
        )

        assertEquals("说法不对", contextual.decode("shuofabudui").first().text)
    }

    @Test
    fun beamKeepsSingleAndMultiCharacterBoundaryStatesForTheSameText() {
        val model = CharacterBigramModel { previous, next ->
            if (previous == '丙'.code && next == '丁'.code) 3f else 0f
        }
        val contextual = PinyinDecoder.fromBytes(
            lexicon(
                "a" to listOf(item("甲", 2_000_000_000, "j")),
                "ab" to listOf(item("甲乙", 2_000_000_000, "jy")),
                "bcd" to listOf(item("乙丙", 2000, "yb")),
                "cd" to listOf(item("丙", 1000, "b")),
                "e" to listOf(
                    item("己庚", 2_000_000_000, "jg"),
                    item("丁戊", 700_000_000, "dw"),
                ),
            ),
            model,
        )

        assertEquals("甲乙丙丁戊", contextual.decode("abcde").first().text)
    }

    @Test
    fun compositionKeepsTheBestScoringPathForDuplicateText() {
        val candidate = decoder.decode("woshiyigeren").first()
        assertEquals("我是一个人", candidate.text)
        assertEquals("wsygr", candidate.canonicalInitials)
    }

    @Test
    fun primarySourceOutranksAWeightedFallbackEntry() {
        val candidates = decoder.decode("zhu")
        assertEquals(listOf("主", "株"), candidates.map { it.text })
        assertTrue(candidates[0].score > candidates[1].score)
    }

    @Test
    fun statisticalShortCodeReturnsMostFrequentCandidate() {
        val candidate = decoder.decode("w").first()
        assertEquals("我", candidate.text)
        assertEquals(CandidateMatchKind.BASE_PREFIX, candidate.matchKind)
        assertEquals("wo", candidate.canonicalPinyin)
    }

    @Test
    fun exactInitialsIndexReturnsDefaultPhraseWithoutBreakingSingleLetterFrequency() {
        val initials = decoder.decode("ygz")
        assertEquals("一个字", initials.first().text)
        assertEquals(CandidateMatchKind.BASE_INITIALS, initials.first().matchKind)
        assertEquals("我", decoder.decode("w").first().text)
    }

    @Test
    fun fullPinyinFollowedByInitialsReturnsCanonicalHybridPhrase() {
        val candidate = decoder.decode("zhongwsrf").first()

        assertEquals("中文输入法", candidate.text)
        assertEquals("zhongwenshurufa", candidate.canonicalPinyin)
        assertEquals("zwsrf", candidate.canonicalInitials)
        assertEquals(CandidateMatchKind.BASE_HYBRID, candidate.matchKind)
    }

    @Test
    fun hybridAndInitialsSourcesCanCoexistWithoutEarlyReturn() {
        val values = decoder.decode("fun")

        assertEquals("妇女", values.first().text)
        assertEquals(CandidateMatchKind.BASE_HYBRID, values.first().matchKind)
    }

    @Test
    fun hybridAliasCannotDisplaceAValidFullPinyinCandidate() {
        val values = decoder.decode("ken")

        assertEquals("肯", values.first().text)
        assertEquals(CandidateMatchKind.BASE_EXACT, values.first().matchKind)
        assertTrue(values.any { it.text == "可能" && it.matchKind == CandidateMatchKind.BASE_HYBRID })
        assertEquals(
            CandidateMatchKind.BASE_EXACT,
            decoder.decodeAfter("上".codePointAt(0), "ken", 255).first().matchKind,
        )
    }

    @Test
    fun selectedCharacterContextCanRerankAnExactTail() {
        val contextual = PinyinDecoder.fromBytes(
            lexicon(
                "pei" to listOf(item("陪", 1000, "p"), item("配", 900, "p")),
            ),
            CharacterBigramModel { previous, next ->
                if (previous == '匹'.code && next == '配'.code) 2f else 0f
            },
        )

        assertEquals("陪", contextual.decode("pei").first().text)
        assertEquals("配", contextual.decodeAfter('匹'.code, "pei").first().text)
    }

    @Test
    fun exactPinyinIsNotPollutedByTheStatisticalPrefixIndex() {
        val values = decoder.decode("xian")
        assertEquals(listOf("先", "西安"), values.map { it.text })
        assertEquals(listOf("x", "xa"), values.map { it.canonicalInitials })
    }

    @Test
    fun exactPhraseAlsoExposesDeepSegmentedAlternatives() {
        val qualityDecoder = PinyinDecoder.fromBytes(
            lexicon(
                "hua" to listOf(
                    item("话", 58000, "h"),
                    item("花", 24000, "h"),
                    item("化", 12000, "h"),
                    item("画", 8000, "h"),
                    item("华", 7000, "h"),
                    item("划", 1600, "h"),
                    item("滑", 1400, "h"),
                ),
                "shang" to listOf(
                    item("上", 285000, "s"),
                    item("商", 5600, "s"),
                    item("伤", 5500, "s"),
                    item("尚", 3900, "s"),
                    item("赏", 950, "s"),
                ),
                "shanghua" to listOf(item("赏花", 117, "sh"), item("上画", 116, "sh")),
            ),
        )

        val values = qualityDecoder.decode("shanghua", 64)

        assertEquals("赏花", values.first().text)
        assertTrue("full pinyin must retain segmented alternatives", values.any { it.text == "上滑" })
        assertEquals(CandidateMatchKind.BASE_COMPOSED, values.first { it.text == "上滑" }.matchKind)
    }

    @Test
    fun exactSingleCharacterRecordDoesNotTerminateAValidMultiSegmentPath() {
        val qualityDecoder = PinyinDecoder.fromBytes(
            lexicon(
                "an" to listOf(item("安", 1000, "a")),
                "xi" to listOf(item("西", 1000, "x")),
                "xian" to listOf(item("先", 2000, "x")),
            ),
        )

        val values = qualityDecoder.decode("xian", 16)

        assertEquals("先", values.first().text)
        assertTrue(values.any { it.text == "西安" && it.matchKind == CandidateMatchKind.BASE_COMPOSED })
    }

    @Test
    fun exactCandidateRecordCanBeReadPastTheFirstThirtyTwoEntries() {
        val candidates = (1..39).map { index ->
            item(text = ('\u4E00'.code + index).toChar().toString(), weight = 1000 - index, initials = "h")
        } + item("滑", 1, "h")
        val qualityDecoder = PinyinDecoder.fromBytes(lexicon("hua" to candidates))

        val values = qualityDecoder.decode("hua", 255)

        assertEquals(40, values.size)
        assertEquals("滑", values[39].text)
    }

    @Test
    fun statisticalPrefixRetainsAFullPinyinSourceWhenOneIsKnown() {
        val qualityDecoder = PinyinDecoder.fromBytes(
            lexicon(
                "de" to listOf(item("的", 1000, "d"), item("得", 900, "d")),
                "{d" to listOf(item("的", 2000, "d"), item("地", 1800, "d")),
            ),
        )

        val candidate = qualityDecoder.decode("d", 64).first()

        assertEquals("的", candidate.text)
        assertEquals(CandidateMatchKind.BASE_PREFIX, candidate.matchKind)
        assertEquals("de", candidate.canonicalPinyin)
        assertEquals("d", candidate.canonicalInitials)
    }

    @Test
    fun adjacentTranspositionIsCorrected() {
        assertEquals("你好", decoder.decode("nihoa").first().text)
        assertEquals("当", decoder.decode("dagn").first().text)
        assertEquals("天", decoder.decode("tain").first().text)
        assertEquals("你好", decoder.decode("nijao").first().text)
    }

    @Test
    fun oneExtraLetterIsCorrected() {
        assertEquals("你好", decoder.decode("nnihao").first().text)
    }

    @Test
    fun oneTypoInsideALongComposedSentenceIsCorrected() {
        val candidate = decoder.decode("woshiyigezhongguoern").first()
        assertEquals("我是一个中国人", candidate.text)
        assertEquals("woshiyigezhongguoren", candidate.canonicalPinyin)
        assertEquals("wsygzgr", candidate.canonicalInitials)
        assertEquals(CandidateMatchKind.CORRECTED, candidate.matchKind)
    }

    @Test
    fun invalidBinaryIsRejected() {
        val failure = runCatching { PinyinDecoder.fromBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(failure.isFailure)
    }

    private data class FixtureCandidate(
        val text: String,
        val weight: Int,
        val initials: String,
        val sourceTier: Int,
    )

    private fun item(text: String, weight: Int, initials: String, sourceTier: Int = 0) =
        FixtureCandidate(text, weight, initials, sourceTier)

    private fun lexicon(vararg records: Pair<String, List<FixtureCandidate>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeBytes("SPLX")
            output.writeShort(3)
            output.writeInt(records.size)
            records.sortedBy { it.first }.forEach { (code, candidates) ->
                output.writeByte(code.length)
                output.writeBytes(code)
                output.writeByte(candidates.size)
                candidates.forEach { candidate ->
                    val encoded = candidate.text.toByteArray(Charsets.UTF_8)
                    output.writeByte(encoded.size)
                    output.write(encoded)
                    output.writeInt(candidate.weight)
                    output.writeByte(candidate.initials.length)
                    output.writeBytes(candidate.initials)
                    output.writeByte(candidate.sourceTier)
                }
            }
        }
        return bytes.toByteArray()
    }
}
