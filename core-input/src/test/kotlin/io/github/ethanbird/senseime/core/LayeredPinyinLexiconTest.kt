package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class LayeredPinyinLexiconTest {
    private data class Word(val code: String, val text: String, val weight: Int, val initials: String, val tier: Int = 0)
    private val base = listOf(Word("neng", "能", 100, "n"), Word("ti", "体", 200, "t"), Word("wo", "我", 900, "w"),
        Word("zhi", "智", 200, "z"), Word("zhineng", "智能", 500, "zn"))
    private fun bytes(version: Int, words: List<Word>) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            val records = words.groupBy(Word::code).toSortedMap()
            out.writeBytes("SPLX"); out.writeShort(version); out.writeInt(records.size)
            for ((code, values) in records) {
                out.writeByte(code.length); out.writeBytes(code); out.writeByte(values.size)
                for (word in values) {
                    val text = word.text.toByteArray(); out.writeByte(text.size); out.write(text); out.writeInt(word.weight)
                    out.writeByte(word.initials.length); out.writeBytes(word.initials); out.writeByte(word.tier)
                }
            }
        }
    }.toByteArray()

    @Test fun version4PreservesOldCandidatesAndMakesSupplementalExactWordsAvailable() {
        val old = PinyinDecoder.fromBytes(bytes(3, base))
        val current = PinyinDecoder.fromBytes(bytes(4, base + Word("zhinengti", "智能体", 1, "znt", 2)))
        assertEquals(old.decode("wo", 255), current.decode("wo", 255))
        val model = object : CharacterLanguageModel {
            override fun unigramLogProbability(next: Int) = -10f
            override fun logProbability(previous2: Int, previous1: Int, next: Int) = -10f
        }
        assertTrue(current.withLanguageModel(model, .5f).decode("zhinengti", 255).any { it.text == "智能体" && it.matchKind == CandidateMatchKind.BASE_EXACT })
        for (query in listOf("zhinengti", "wozhinengti", "zhin", "znt", "zhinengt")) {
            assertEquals("Legacy must see the original graph, not only filtered candidates", old.decode(query, 255), current.decode(query, 255))
            assertEquals(old.decode(query, 255), current.withLanguageModel(CharacterLanguageModel.EMPTY).decode(query, 255))
        }
        assertEquals(PinyinDecoder.fromBytes(bytes(3, base)).decode("wozhineng", 255),
            PinyinDecoder.fromBytes(bytes(4, base)).decode("wozhineng", 255))
    }

    @Test fun supplementalVocabularyDoesNotChangeTheBaseMassOrPersonalWordReferenceScore() {
        val old = PinyinDecoder.fromBytes(bytes(3, base))
        val current = PinyinDecoder.fromBytes(bytes(4, base + Word("zhinengti", "智能体", 100_000, "znt", 2)))
        for (field in listOf("unigramLogMass", "unigramMeanScore")) {
            val value = PinyinDecoder::class.java.getDeclaredField(field).apply { isAccessible = true }
            assertEquals(value.getFloat(old), value.getFloat(current), 0f)
        }
    }

    @Test fun aSupplementalEntryDoesNotReclassifyAPersonalNewWordAsACorpusKnownWord() {
        var calls = 0
        val model = object : CharacterLanguageModel {
            override fun logProbability(previous2: Int, previous1: Int, next: Int): Float { calls++; return -10f }
            override fun unigramLogProbability(next: Int) = -10f
        }
        val layered = PinyinDecoder.fromBytes(bytes(4, base + Word("zhinengti", "智能体", 1, "znt", 2)))
        val store = MemoryUserLexicon(clock = { 1_000L })
        val bound = layered.withLanguageModel(model, .5f, -4f).withUserLexicon(store)
        bound.decode("wozhinengti", 255)
        assertTrue(calls > 0)
        store.record("zhinengti", "znt", "智能体")
        calls = 0
        val personal = bound.decode("wozhinengti", 255)
        assertEquals("personal-only LM backoff must survive vocabulary updates", 0, calls)
        assertTrue(personal.any { it.text == "我智能体" })
        assertTrue(store.forget("zhinengti", "智能体"))
        calls = 0; bound.decode("wozhinengti", 255); assertTrue(calls > 0)
    }

    @Test fun theNewTierRequiresVersion4AndNeverDefinesAliasesOrSingleSyllables() {
        for (data in listOf(bytes(3, base + Word("zhinengti", "智能体", 1, "znt", 2)),
            bytes(4, base + Word("~znt", "智能体", 1, "znt", 2)),
            bytes(4, base + Word("ma", "码", 1, "m", 2)),
            bytes(4, base + Word("zhinengti", "智能体", 1, "znt", 3)))) {
            assertThrows(IllegalArgumentException::class.java) { PinyinDecoder.fromBytes(data) }
        }
    }
}
