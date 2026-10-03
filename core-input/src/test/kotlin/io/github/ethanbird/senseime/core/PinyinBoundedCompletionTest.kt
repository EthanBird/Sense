package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PinyinBoundedCompletionTest {
    private val neutral = object : CharacterLanguageModel {
        override fun unigramLogProbability(next: Int) = -6f
        override fun logProbability(previous2: Int, previous1: Int, next: Int) = -6f
    }

    @Test fun lastSyllableCompletionSurvivesAnOverflowingLexicographicPrefixRange() {
        val base = crowdedDictionary()
        assertEquals("北极", base.withLanguageModel(neutral, .5f).decodeWithContext("", "beiji", 1, true).first().text)
        for (limit in listOf(12, 255)) {
            val candidates = base.withLanguageModel(neutral, .5f).decodeWithContext("", "beiji", limit, true)
            assertEquals("Complete literal input retains its stronger source", "北极", candidates.first().text)
            val target = candidates.firstOrNull { it.text == "北京" }
            assertNotNull("A word beyond the 96-key scan must remain selectable", target)
            assertEquals("beijing", target!!.canonicalPinyin)
            assertEquals("bj", target.canonicalInitials)
            assertEquals(CandidateMatchKind.BASE_PREFIX, target.matchKind)
        }
    }

    @Test fun productionWordIsPresentBeforeAndAfterTheFinalNAndG() {
        val decoder = production()
        for (query in listOf("beiji", "beijin", "beijing")) {
            val candidates = decoder.decodeWithContext("", query, 255, true)
            assertTrue("$query: ${candidates.take(8)}", candidates.any { it.text == "北京" })
        }
    }

    @Test fun modelDisabledAndExplicitSyllableBoundariesRetainTheirPreviousRecall() {
        val base = crowdedDictionary()
        val legacy = base.decodeWithContext("", "beiji", 255, true)
        assertFalse(legacy.any { it.text == "北京" })
        assertEquals(legacy, base.withLanguageModel(neutral, 0f).decodeWithContext("", "beiji", 255, true))
        val separated = base.withLanguageModel(neutral, .5f).decodeWithContext("", "bei'ji", 255, true)
        assertEquals("北极", separated.first().text)
        assertFalse(separated.any { it.text == "北京" })
    }

    @Test fun allReachableLastSyllableBoundariesAreKeptWithoutInventingAnotherSyllable() {
        val s = PinyinSyllableSegmenter(setOf("xi", "xia", "an", "ang", "na", "nang", "bei", "ji", "jing"))
        assertEquals(setOf("xiang", "xiana", "xianang"), s.finalSyllableCompletions("xian").toSet())
        assertEquals(listOf("beijing"), s.finalSyllableCompletions("beiji"))
        // The typed final n may also start a third syllable after bei'ji.
        assertEquals(setOf("beijing", "beijina", "beijinang"), s.finalSyllableCompletions("beijin").toSet())
        assertTrue(s.finalSyllableCompletions("beijing").isEmpty())
    }

    @Test fun invalidAndAtomicInputsDoNotCreateAWholeWordCompletionProbe() {
        val s = PinyinSyllableSegmenter(setOf("bei", "jing"))
        for (query in listOf("", "b", "bei", "bzji", "bei'ji", "bei1ji", "a".repeat(97)))
            assertTrue(query, s.finalSyllableCompletions(query).isEmpty())
    }

    @Test fun lastSyllableReachabilityRemainsCancellableAndReusable() {
        val s = PinyinSyllableSegmenter(setOf("bei", "ji", "jing"))
        var active = true
        assertThrows(DecodeSupersededException::class.java) {
            DecodeWorkScope.whileCurrent({ active }) {
                active = false
                s.finalSyllableCompletions("beiji")
            }
        }
        assertEquals(listOf("beijing"), s.finalSyllableCompletions("beiji"))
    }

    @Test fun explicitCompletionChoiceKeepsItsCanonicalWordAndAliasAcrossReuseAndRestore() {
        val base = crowdedDictionary().withLanguageModel(neutral, .5f)
        val store = MemoryUserLexicon(clock = { 1_000L })
        fun adaptive(memory: UserLexicon) = AdaptivePinyinDecoder(base, memory,
            PinyinSyllableSegmenter(setOf("bei", "ji", "jing")))
        val decoder = adaptive(store)
        val choice = decoder.decode("beiji", 255).first { it.text == "北京" }
        val learned = requireNotNull(decoder.learn("beiji", choice,
            UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, 1)))
        assertEquals("beijing", learned.fullPinyin)
        assertTrue("beiji" in learned.aliases)
        repeat(2) {
            val first = decoder.decode("beiji", 255).first()
            assertEquals("北京", first.text)
            decoder.learn("beiji", first, UserLearningEvidence(UserSelectionKind.DEFAULT_ACCEPT, 0))
        }
        val restored = adaptive(MemoryUserLexicon(store.lookup("beiji", 255), clock = { 1_000L }))
        assertEquals("北京", restored.decode("beiji", 255).first().text)
        assertEquals("beijing", restored.decode("beiji", 255).first().canonicalPinyin)
    }

    private data class Word(val text: String, val initials: String, val frequency: Int)

    private fun crowdedDictionary(): PinyinDecoder {
        val syllables = listOf("a", "ai", "an", "ang", "ao", "ba", "bai", "ban", "bang", "bao", "bei")
        val rows = linkedMapOf<String, Word>()
        for (s in syllables) rows[s] = Word("甲", s.take(1), 1)
        rows["bei"] = Word("北", "b", 10)
        rows["ji"] = Word("极", "j", 10)
        rows["jia"] = Word("甲", "j", 1)
        rows["jing"] = Word("京", "j", 10)
        rows["beiji"] = Word("北极", "bj", 100_000)
        repeat(110) { n ->
            val a = syllables[n / syllables.size]; val b = syllables[n % syllables.size]
            rows["beijia$a$b"] = Word("北甲甲甲", "bj${a.first()}${b.first()}", 1)
        }
        rows["beijing"] = Word("北京", "bj", 50_000)
        assertEquals(112, rows.keys.count { it.startsWith("beiji") })
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("SPLX"); out.writeShort(3); out.writeInt(rows.size)
            for ((code, word) in rows.toSortedMap()) {
                out.writeByte(code.length); out.writeBytes(code); out.writeByte(1)
                val text = word.text.toByteArray(Charsets.UTF_8)
                out.writeByte(text.size); out.write(text); out.writeInt(word.frequency)
                out.writeByte(word.initials.length); out.writeBytes(word.initials); out.writeByte(0)
            }
        }
        return PinyinDecoder.fromBytes(bytes.toByteArray())
    }

    private fun production(): PinyinDecoder =
        asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) }
            .withLanguageModel(asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load), .5f, -4f)

    private fun asset(name: String) = listOf(File("../ime-service/src/main/assets/$name"),
        File("ime-service/src/main/assets/$name")).first { it.isFile }
}
