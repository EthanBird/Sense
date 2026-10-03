package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class CharacterLanguageModelTest {
    @Test
    fun rejectsTruncationCountsBadKeysScoresAndReferencesBeforeUse() {
        val good = resource("tiny.scng")
        fun invalid(edit: (ByteBuffer) -> Unit) {
            val data = good.copyOf()
            edit(ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN))
            assertThrows(IllegalArgumentException::class.java) { BinaryCharacterLanguageModel.fromBytes(data) }
        }
        assertThrows(IllegalArgumentException::class.java) { BinaryCharacterLanguageModel.fromBytes(good.copyOf(20)) }
        assertThrows(IllegalArgumentException::class.java) { BinaryCharacterLanguageModel.fromBytes(good + 0.toByte()) }
        invalid { it.putInt(0, 0) }
        invalid { it.putInt(6, Int.MAX_VALUE) }
        invalid { it.putFloat(30, Float.NaN) }
        invalid { it.putInt(26, 0xD800) }
        invalid { it.putInt(34, it.getInt(26)) }
        val unigramCount = ByteBuffer.wrap(good).order(ByteOrder.BIG_ENDIAN).getInt(6)
        invalid { it.putLong(26 + unigramCount * 8, Long.MAX_VALUE) }
        // Keep key ordering but change the first bigram successor to a scalar outside vocabulary.
        invalid { val start = 26 + unigramCount * 8; it.putLong(start, (it.getLong(start) and (0x1FFFFFL shl 21)) or 1L) }
    }

    @Test
    fun loadingIsBoundedAndQueriesAreThreadSafe() {
        val model = ByteArrayInputStream(resource("tiny.scng")).use(BinaryCharacterLanguageModel::load)
        val expected = model.logProbability('喜'.code, '欢'.code, '吃'.code)
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((0 until 200).map { Callable {
                assertEquals(expected, model.logProbability('喜'.code, '欢'.code, '吃'.code), 0f)
            } }).forEach { it.get() }
        } finally { pool.shutdownNow() }
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(16 * 1024 * 1024 + 1)).use(BinaryCharacterLanguageModel::load)
        }
        assertEquals(0f, CharacterLanguageModel.EMPTY.logProbability(0, 0, '我'.code), 0f)
    }

    @Test
    fun readsPythonModelAndMatchesReferenceBackoffScores() {
        val model = BinaryCharacterLanguageModel.fromBytes(resource("tiny.scng"))
        val rows = resource("scores.tsv").toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }
        for (row in rows) {
            val f = row.split('\t')
            assertEquals(row, f[3].toFloat(), model.logProbability(f[0].toInt(), f[1].toInt(), f[2].toInt()), 0.00001f)
        }
        assertTrue(model.estimatedRetainedBytes < 4096)
        assertTrue(model.logProbability('喜'.code, '欢'.code, '吃'.code) > model.logProbability('喜'.code, '欢'.code, '茶'.code))
    }

    @Test
    fun largeVocabularyMembershipPreservesEveryScalarAndSentinelWithBoundedMemory() {
        val data = expandedVocabulary()
        val model = BinaryCharacterLanguageModel.fromBytes(data)
        val bytes = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val count = bytes.getInt(6)
        val known = (0 until count).associate { bytes.getInt(26 + it * 8) to bytes.getFloat(30 + it * 8) }
        for (cp in 0..CharacterLanguageModel.UNK) {
            val expected = cp <= 0x10FFFF && cp in known
            if (expected != model.containsCodePoint(cp)) fail("Membership differs at $cp")
        }
        assertFalse(model.containsCodePoint(-1))
        assertFalse(model.containsCodePoint(Int.MAX_VALUE))
        for (cp in listOf(-1, 0, 62, 63, 64, 65, 127, 128, 255, 256, 0xD800, 0xFFFF,
                         0x10000, 0x20000, 0x10FFFF, CharacterLanguageModel.EOS, CharacterLanguageModel.UNK, Int.MAX_VALUE)) {
            val expected = known[cp] ?: known.getValue(CharacterLanguageModel.UNK)
            assertEquals("Unigram $cp", expected.toRawBits(), model.unigramLogProbability(cp).toRawBits())
        }
        // Keep the bounded-memory contract for either the sorted-array implementation
        // or an optional future membership index; this test does not mandate an index.
        assertTrue(model.estimatedRetainedBytes <= data.size - 26L + 8_192L)
    }

    @Test
    fun largerVocabularyKeepsExistingNgramScoresBitwiseEqual() {
        val reference = BinaryCharacterLanguageModel.fromBytes(resource("tiny.scng"))
        val indexed = BinaryCharacterLanguageModel.fromBytes(expandedVocabulary())
        val cases = resource("scores.tsv").toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t').take(3).map(String::toInt) }.toList()
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((0 until 32).map { Callable {
                for ((a, b, c) in cases) {
                    assertEquals(reference.logProbability(a, b, c).toRawBits(), indexed.logProbability(a, b, c).toRawBits())
                }
            } }).forEach { it.get() }
        } finally { pool.shutdownNow() }
        assertThrows(IllegalArgumentException::class.java) {
            indexed.logProbability(CharacterLanguageModel.BOS, CharacterLanguageModel.BOS, CharacterLanguageModel.BOS)
        }
    }

    private fun expandedVocabulary(): ByteArray {
        val source = resource("tiny.scng")
        val original = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN)
        val count = original.getInt(6)
        val tokens = (0 until count).associate { original.getInt(26 + it * 8) to original.getFloat(30 + it * 8) }.toMutableMap()
        for (cp in (0x3000..0x3200).toList() + listOf(0, 63, 64, 127, 255, 256, 0xFFFF, 0x10000, 0x20000)) {
            tokens.putIfAbsent(cp, -8f)
        }
        val output = ByteBuffer.allocate(source.size + (tokens.size - count) * 8).order(ByteOrder.BIG_ENDIAN)
        output.put(source, 0, 26); output.putInt(6, tokens.size)
        for ((cp, score) in tokens.toSortedMap()) { output.putInt(cp); output.putFloat(score) }
        output.put(source, 26 + count * 8, source.size - 26 - count * 8)
        return output.array()
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/character-lm/$name")!!.use { it.readBytes() }
}
