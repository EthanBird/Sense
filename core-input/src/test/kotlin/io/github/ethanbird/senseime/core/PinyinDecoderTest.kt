package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinDecoderTest {
    private val decoder = PinyinDecoder.fromBytes(
        lexicon(
            "ni" to listOf("你" to 1000, "呢" to 400),
            "nihao" to listOf("你好" to 5000, "你号" to 20),
            "ren" to listOf("人" to 900),
            "shi" to listOf("是" to 1000),
            "wo" to listOf("我" to 1000),
            "xiansi" to listOf("先思" to 300),
            "yige" to listOf("一个" to 1000),
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
    fun invalidBinaryIsRejected() {
        val failure = runCatching { PinyinDecoder.fromBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(failure.isFailure)
    }

    private fun lexicon(vararg records: Pair<String, List<Pair<String, Int>>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeBytes("SPLX")
            output.writeShort(1)
            output.writeInt(records.size)
            records.sortedBy { it.first }.forEach { (code, candidates) ->
                output.writeByte(code.length)
                output.writeBytes(code)
                output.writeByte(candidates.size)
                candidates.forEach { (text, weight) ->
                    val encoded = text.toByteArray(Charsets.UTF_8)
                    output.writeByte(encoded.size)
                    output.write(encoded)
                    output.writeInt(weight)
                }
            }
        }
        return bytes.toByteArray()
    }
}
