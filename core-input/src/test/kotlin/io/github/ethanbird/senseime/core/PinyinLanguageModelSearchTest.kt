package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class PinyinLanguageModelSearchTest {
    @Test
    fun aNewPersonalWordUsesTheProvenLexicalPathInsteadOfCorpusPopularity() {
        val base = fixture("wo" to listOf("我"), "shi" to listOf("是", "诗"), "ren" to listOf("人", "仁"))
        val store = MemoryUserLexicon(clock = { 1_000L })
        val segmenter = PinyinSyllableSegmenter(setOf("wo", "shi", "ren"))
        val legacy = AdaptivePinyinDecoder(base, store, segmenter)
        val language = AdaptivePinyinDecoder(base.withLanguageModel(prefer("我是人"), 4f), store, segmenter)
        val before = language.decode("woshiren", 6)
        val learned = requireNotNull(language.learn("shiren", Candidate("诗仁", canonicalPinyin = "shiren", canonicalInitials = "sr")))
        assertEquals("我诗仁", legacy.decode("woshiren", 6).first().text)
        assertEquals(legacy.decode("woshiren", 6), language.decode("woshiren", 6))
        assertTrue(language.forget(learned))
        assertEquals(before, language.decode("woshiren", 6))
    }

    @Test
    fun aForcedJointDoesNotDoubleCountTheSameDictionaryWordsLanguageScore() {
        val base = fixture("wo" to listOf("我"), "shi" to listOf("是"), "wo'shi" to listOf("我是"))
        val decoder = base.withLanguageModel(prefer("我是"))
        val direct = decoder.decode("woshi", 8).first { it.text == "我是" }
        val forced = decoder.decode("wo'shi", 8).first { it.text == "我是" }
        assertEquals(CandidateMatchKind.BASE_EXACT, forced.matchKind)
        assertEquals(direct.score, forced.score, 0.00001f)
    }

    @Test
    fun zeroWeightAndEmptyModelPreserveLegacyResultsIncludingCorrections() {
        val base = fixture("wo" to listOf("我"), "shi" to listOf("是", "诗"), "ren" to listOf("人", "仁"))
        for (query in listOf("wo", "shi", "woshiren", "woshiern", "wo'shi")) {
            for (prefix in listOf(false, true)) {
                val expected = base.decodeWithContext("我是", query, 12, prefix)
                assertEquals(expected, base.withLanguageModel(prefer("我诗人"), 0f).decodeWithContext("我是", query, 12, prefix))
                assertEquals(expected, base.withLanguageModel(CharacterLanguageModel.EMPTY).decodeWithContext("我是", query, 12, prefix))
            }
        }
        for (weight in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 4.1f)) {
            assertTrue(runCatching { base.withLanguageModel(prefer("我是"), weight) }.isFailure)
        }
    }

    @Test
    fun correctedSentenceSearchRetainsTheLanguagePreferredHomophone() {
        val base = fixture("wo" to listOf("我"), "shi" to listOf("是", "时", "事", "十", "使", "市", "式", "识", "诗"), "ren" to listOf("人"))
        val values = base.withLanguageModel(prefer("我诗人")).decode("woshiern", 6)
        assertEquals(values.toString(), "我诗人", values.first().text)
        assertEquals(CandidateMatchKind.CORRECTED, values.first().matchKind)
    }

    @Test
    fun progressiveInputUsesBothLeftContextCharactersAndKeepsPrefixCacheContextsSeparate() {
        val model = object : CharacterLanguageModel {
            override fun unigramLogProbability(next: Int) = -6f
            override fun logProbability(previous2: Int, previous1: Int, next: Int): Float = when {
                previous2 == '古'.code && previous1 == '代'.code && next == '诗'.code -> -0.1f
                previous2 == '时'.code && previous1 == '代'.code && next == '事'.code -> -0.1f
                else -> -10f
            }
        }
        val base = fixture("shi" to listOf("是", "事", "诗"), "ren" to listOf("人")).withLanguageModel(model)
        val adaptive = AdaptivePinyinDecoder(base, MemoryUserLexicon(), PinyinSyllableSegmenter(setOf("shi", "ren")))
        val input = "shi".fold(PinyinComposition()) { value, ch -> value.type(ch) }
        assertEquals("诗", adaptive.decodeProgressively(input, "古代", 1).wholeCandidates.first().text)
        assertEquals("事", adaptive.decodeProgressively(input, "时代", 1).wholeCandidates.first().text)
        assertEquals("是", adaptive.decodeProgressively(input, "古代。", 1).wholeCandidates.first().text)
        val partlyAccepted = PinyinComposition(acceptedSegments = listOf(AcceptedPinyinSegment("代", "dai")), remainingPinyin = "shi")
        assertEquals("诗", adaptive.decodeProgressively(partlyAccepted, "古", 1).wholeCandidates.first().text)
        for (prefix in listOf(false, true)) {
            assertEquals("诗", base.decodeWithContext("古代", "shi", 1, prefix).first().text)
            assertEquals("事", base.decodeWithContext("时代", "shi", 1, prefix).first().text)
            assertEquals("诗", base.decodeWithContext("古代", "shi", 1, prefix).first().text)
        }
    }

    @Test
    fun languageStateEntersTheLatticeBeforePerEdgeAndBeamPruning() {
        val base = fixture("wo" to listOf("我"), "shi" to listOf("是", "时", "事", "十", "使", "市", "式", "识", "诗"), "ren" to listOf("人"))
        assertFalse(base.decode("woshiren", 1).any { it.text == "我诗人" })
        val decoder = base.withLanguageModel(prefer("我诗人"), 1f)
        assertEquals("我诗人", decoder.decode("woshiren", 1).first().text)
    }

    @Test
    fun languageEvidenceRescuesAnExactHomophoneBeforeSmallOutputTruncation() {
        val base = fixture("shi" to listOf("是", "时", "事", "十", "使", "市", "式", "识", "诗"))
        val model = prefer("古诗")
        assertEquals("是", base.decodeAfter('古'.code, "shi", 1).first().text)
        val decoder = base.withLanguageModel(model, 1f)
        assertEquals("诗", decoder.decodeAfter('古'.code, "shi", 1).first().text)
        assertEquals(base.decode("shi", 8), decoder.withLanguageModel(CharacterLanguageModel.EMPTY).decode("shi", 8))
    }

    private fun prefer(text: String): CharacterLanguageModel = object : CharacterLanguageModel {
        override fun unigramLogProbability(next: Int) = -6f
        override fun logProbability(previous2: Int, previous1: Int, next: Int): Float =
            if (text.windowed(2).any { it[0].code == previous1 && it[1].code == next }) -0.1f else -10f
    }

    private fun fixture(vararg records: Pair<String, List<String>>): PinyinDecoder {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("SPLX"); out.writeShort(3); out.writeInt(records.size)
            for ((spelling, words) in records.sortedBy { it.first.replace("'", "") }) {
                val code = spelling.replace("'", "")
                out.writeByte(code.length); out.writeBytes(code); out.writeByte(words.size)
                words.forEachIndexed { index, text ->
                    val encoded = text.toByteArray(Charsets.UTF_8)
                    out.writeByte(encoded.size); out.write(encoded); out.writeInt(1000 - index)
                    val initials = if ('\'' in spelling) spelling.split('\'').joinToString("") { it.take(1) }
                        else code.take(1).repeat(text.codePointCount(0, text.length))
                    out.writeByte(initials.length); out.writeBytes(initials); out.writeByte(0)
                }
            }
        }
        return PinyinDecoder.fromBytes(bytes.toByteArray())
    }
}
