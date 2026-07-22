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
        assertNull(candidate.canonicalPinyin)
    }

    @Test
    fun exactPinyinIsNotPollutedByTheStatisticalPrefixIndex() {
        val values = decoder.decode("xian")
        assertEquals(listOf("先", "西安"), values.map { it.text })
        assertEquals(listOf("x", "xa"), values.map { it.canonicalInitials })
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
