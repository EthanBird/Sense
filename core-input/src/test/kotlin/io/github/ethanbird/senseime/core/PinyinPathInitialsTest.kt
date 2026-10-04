package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PinyinPathInitialsTest {
    private data class Word(val spelling: String, val text: String, val initials: String)
    private val neutral = object : CharacterLanguageModel {
        override fun unigramLogProbability(next: Int) = -6f
        override fun logProbability(previous2: Int, previous1: Int, next: Int) = -6f
    }

    @Test fun mixedLengthWordEdgesExportInitialsInForwardOrder() {
        val decoder = fixture(listOf(Word("wo", "我", "w"), Word("xihuan", "喜欢", "xh"),
            Word("beijing", "北京", "bj"), Word("woxihuan", "我喜欢", "wxh"),
            Word("xi", "西", "x"), Word("huan", "还", "h"), Word("bei", "北", "b"), Word("jing", "京", "j")))
        for (engine in listOf(decoder, decoder.withLanguageModel(neutral, .5f))) {
            for (query in listOf("woxihuanbeijing", "wo'xi'huan'bei'jing")) {
                val candidate = engine.decode(query, 255).firstOrNull { it.text == "我喜欢北京" }
                assertNotNull("query=$query", candidate)
                assertEquals("wxhbj", candidate!!.canonicalInitials)
            }
        }
    }

    @Test fun aDeepChainDoesNotReverseOrLoseRepeatedInitials() {
        val decoder = fixture(listOf(Word("wo", "我", "w")))
        for (engine in listOf(decoder, decoder.withLanguageModel(neutral, .5f))) {
            val candidate = engine.decode("wo".repeat(12), 255).firstOrNull { it.text == "我".repeat(12) }
            assertNotNull(candidate)
            assertEquals("w".repeat(12), candidate!!.canonicalInitials)
        }
    }

    @Test fun missingLexicalInitialsRemainRejectedByTheExistingAssetContract() {
        assertThrows(IllegalArgumentException::class.java) {
            fixture(listOf(Word("wo", "我", ""), Word("shi", "是", "s")))
        }
    }

    @Test fun supplementaryHanKeepsOneSyllableInitialPerWord() {
        val decoder = fixture(listOf(Word("qi", "\uD840\uDC00", "q"), Word("ren", "人", "r")))
            .withLanguageModel(neutral,.5f)
        val candidate = decoder.decode("qiren",255).first { it.text=="\uD840\uDC00人" }
        assertEquals("qr",candidate.canonicalInitials)
    }

    private fun fixture(words: List<Word>): PinyinDecoder {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("SPLX"); out.writeShort(3); out.writeInt(words.size)
            for (word in words.sortedBy { it.spelling }) {
                out.writeByte(word.spelling.length); out.writeBytes(word.spelling); out.writeByte(1)
                val text=word.text.toByteArray(Charsets.UTF_8)
                out.writeByte(text.size); out.write(text); out.writeInt(1000)
                out.writeByte(word.initials.length); out.writeBytes(word.initials); out.writeByte(0)
            }
        }
        return PinyinDecoder.fromBytes(bytes.toByteArray())
    }
}
