package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class LayeredMixedRecallTest {
    private data class Word(val code: String, val text: String, val initials: String, val tier: Int = 0)
    private val s = listOf("sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai",
        "shan", "shang", "shao", "she", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun", "suo")
    private val base = (s + listOf("yi", "ca", "cang", "pa", "pin")).map { Word(it, "字", it.take(1)) } +
        Word("yishushoucangpin", "艺术收藏品", "ysscp")
    private val model = object : CharacterLanguageModel {
        override fun unigramLogProbability(next: Int) = -6f
        override fun logProbability(previous2: Int, previous1: Int, next: Int) = -6f
    }
    private fun decoder(words: List<Word>): PinyinDecoder {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val records = words.groupBy { it.code }.toSortedMap()
            out.writeBytes("SPLX"); out.writeShort(4); out.writeInt(records.size)
            for ((code, values) in records) {
                out.writeByte(code.length); out.writeBytes(code); out.writeByte(values.size)
                for (word in values) {
                    val text = word.text.toByteArray(); out.writeByte(text.size); out.write(text)
                    out.writeInt(if (word.tier == 2) 1 else 100)
                    out.writeByte(word.initials.length); out.writeBytes(word.initials); out.writeByte(word.tier)
                }
            }
        }
        return PinyinDecoder.fromBytes(bytes.toByteArray()).withLanguageModel(model, .5f)
    }

    @Test fun supplementalPrefixesDoNotEraseABaseMixedWordBeforeLanguageScoring() {
        val expected = decoder(base).decode("yisscp", 255).single { it.text == "艺术收藏品" }
        val filler = s.flatMap { a -> s.map { b -> Word("yi${a}${b}capa", "补充测试词", "ysscp", 2) } }
        val actual = decoder(base + filler).decode("yisscp", 255)
        assertTrue("A fixed mixed-search budget must reserve base recall", actual.any { it.text == expected.text })
        assertEquals(expected.score, actual.first { it.text == expected.text }.score, 0f)
    }

    @Test fun nonCrowdedSupplementalMixedWordsRemainAvailable() {
        val actual = decoder(base + Word("yishushucangpin", "一树书藏品", "ysscp", 2)).decode("yisscp", 255)
        assertTrue(actual.any { it.text == "一树书藏品" })
        assertTrue(actual.any { it.text == "艺术收藏品" })
    }
}
