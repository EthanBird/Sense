package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

/** The edge cap must use the same incremental evidence as the actual path. */
class PinyinEdgeSelectionTest {
    private val homophones = "是时事十使市式识诗史士师石食室示世".map(Char::toString)
    private val neutral = object : CharacterLanguageModel {
        override fun unigramLogProbability(next: Int) = -6f
        override fun logProbability(previous2: Int, previous1: Int, next: Int) = -6f
    }

    @Test fun internalBoundaryRescuesTheSeventeenthHomophoneBeforeProductionEdgeCap() {
        val decoder = fixture(listOf("wo" to listOf("我"), "shi" to homophones, "ren" to listOf("人"))) {
                previous, next -> if (previous == '我'.code && next == '世'.code) 3f else 0f
        }.withLanguageModel(neutral, .5f)
        for (limit in listOf(255, 64, 6, 1)) {
            val values = decoder.decodePrefixProbe("woshiren", limit)
            assertEquals("limit=$limit candidates=${values.take(5)}", "我世人", values.firstOrNull()?.text)
        }
    }

    @Test fun boundaryAttenuationDependsOnPreviousWordNotOnlyLastCharacters() {
        // Both prefixes end in 我去, but the path 我/去 has a full-strength
        // single-character boundary while 我去 has an attenuated compound one.
        val decoder = fixture(listOf("wo" to listOf("我"), "qu" to listOf("去"),
            "wo'qu" to listOf("我去"), "shi'ren" to homophones.map { it+"人" })) {
                previous, next -> if (previous == '去'.code && next == '世'.code) .1f else 0f
        }.withLanguageModel(neutral, .5f)
        val values = decoder.decodePrefixProbe("woqushiren", 255)
        // The weaker compound score leaves 世人 below the cap, while the full
        // single-character boundary must retain it. Reusing the first selection
        // for both states incorrectly removes this complete candidate entirely.
        assertTrue(values.toString(), values.any { it.text == "我去世人" })
        assertEquals("我去是人", values.firstOrNull()?.text)
    }

    @Test fun compoundBoundaryUsesTheSameAttenuationBeforeAndAfterPruning() {
        val decoder = fixture(listOf("wo'qu" to listOf("我去"), "shi'ren" to homophones.map { it+"人" })) {
                previous, next -> if (previous == '去'.code && next == '世'.code) .1f else 0f
        }.withLanguageModel(neutral, .5f)
        // .1 * .04 is smaller than the lexical gap to the seventeenth item.
        assertEquals("我去是人", decoder.decodePrefixProbe("woqushiren", 255).firstOrNull()?.text)
    }

    @Test fun emptyAndZeroWeightBindingsKeepTheLegacyPathExactly() {
        val decoder = fixture(listOf("wo" to listOf("我"), "shi" to homophones, "ren" to listOf("人"))) {
                previous, next -> if (previous == '我'.code && next == '世'.code) 3f else 0f
        }
        for (query in listOf("shi", "woshiren", "wo'shi'ren", "woshiern")) {
            assertEquals(decoder.decode(query,255), decoder.withLanguageModel(neutral,0f).decode(query,255))
            assertEquals(decoder.decode(query,255), decoder.withLanguageModel(CharacterLanguageModel.EMPTY).decode(query,255))
        }
    }

    private fun fixture(records: List<Pair<String,List<String>>>, score: (Int,Int)->Float): PinyinDecoder {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("SPLX"); out.writeShort(3); out.writeInt(records.size)
            for ((spelling,words) in records.sortedBy { it.first.replace("'", "") }) {
                val code=spelling.replace("'", "")
                out.writeByte(code.length);out.writeBytes(code);out.writeByte(words.size)
                for ((index,text) in words.withIndex()) {
                    val encoded=text.toByteArray(Charsets.UTF_8)
                    out.writeByte(encoded.size);out.write(encoded);out.writeInt(1000-index)
                    val initials=spelling.split('\'').joinToString("") { it.take(1) }
                    out.writeByte(initials.length);out.writeBytes(initials);out.writeByte(0)
                }
            }
        }
        return PinyinDecoder.fromBytes(bytes.toByteArray(),CharacterBigramModel(score))
    }
}
