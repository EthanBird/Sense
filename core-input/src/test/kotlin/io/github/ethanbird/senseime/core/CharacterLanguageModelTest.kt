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

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/character-lm/$name")!!.use { it.readBytes() }
}
