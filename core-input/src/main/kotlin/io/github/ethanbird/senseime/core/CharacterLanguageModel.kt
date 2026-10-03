package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A true conditional log-probability interface; separate from dictionary boundary bonuses. */
interface CharacterLanguageModel {
    fun logProbability(previous2: Int, previous1: Int, next: Int): Float
    fun unigramLogProbability(next: Int): Float
    /** UNK is a class probability, not the emission probability of each unseen glyph. */
    fun containsCodePoint(codePoint: Int): Boolean = true

    companion object {
        // Outside Unicode's scalar range but inside the binary format's 21-bit token field.
        const val BOS = 0x110000
        const val EOS = 0x110001
        const val UNK = 0x110002
        val EMPTY: CharacterLanguageModel = object : CharacterLanguageModel {
            override fun logProbability(previous2: Int, previous1: Int, next: Int) = 0f
            override fun unigramLogProbability(next: Int) = 0f
        }
    }
}

/**
 * SCNG/1: sorted primitive arrays, no string maps and no allocations per transition.
 * Seen entries contain FULL interpolated log P; backoff weights apply only to missing entries.
 * The float32 file is produced and evaluated by tools/train_character_lm.py.
 */
class BinaryCharacterLanguageModel private constructor(
    private val vocabulary: IntArray,
    private val unigrams: FloatArray,
    private val bigramKeys: LongArray,
    private val bigrams: FloatArray,
    private val bigramContexts: IntArray,
    private val bigramBackoffs: FloatArray,
    private val trigramKeys: LongArray,
    private val trigrams: FloatArray,
    private val trigramContexts: LongArray,
    private val trigramBackoffs: FloatArray,
) : CharacterLanguageModel {
    val vocabularySize: Int get() = vocabulary.size
    val bigramCount: Int get() = bigramKeys.size
    val trigramCount: Int get() = trigramKeys.size
    /** Payload estimate, excluding small array/object headers and temporary loading bytes. */
    val estimatedRetainedBytes: Long get() = vocabulary.size * 8L + bigramKeys.size * 12L +
        bigramContexts.size * 8L + trigramKeys.size * 12L + trigramContexts.size * 12L

    private val unknownIndex = vocabulary.binarySearch(CharacterLanguageModel.UNK)

    override fun containsCodePoint(codePoint: Int): Boolean =
        codePoint in 0..0x10FFFF && vocabulary.binarySearch(codePoint) >= 0

    private fun token(codePoint: Int): Int = when {
        codePoint == CharacterLanguageModel.BOS -> codePoint
        vocabulary.binarySearch(codePoint) >= 0 -> codePoint
        else -> CharacterLanguageModel.UNK
    }

    override fun unigramLogProbability(next: Int): Float {
        require(next != CharacterLanguageModel.BOS) { "BOS is context only" }
        val index = vocabulary.binarySearch(next)
        return unigrams[if (index >= 0) index else unknownIndex]
    }

    override fun logProbability(previous2: Int, previous1: Int, next: Int): Float {
        require(next != CharacterLanguageModel.BOS) { "BOS is context only" }
        val a = token(previous2)
        val b = token(previous1)
        val w = token(next)
        val trigram = trigramKeys.binarySearch(pack3(a, b, w))
        if (trigram >= 0) return trigrams[trigram]
        val triContext = trigramContexts.binarySearch(pack2(a, b))
        val triBackoff = if (triContext >= 0) trigramBackoffs[triContext] else 0f
        val bigram = bigramKeys.binarySearch(pack2(b, w))
        if (bigram >= 0) return triBackoff + bigrams[bigram]
        val biContext = bigramContexts.binarySearch(b)
        val biBackoff = if (biContext >= 0) bigramBackoffs[biContext] else 0f
        return triBackoff + biBackoff + unigramLogProbability(w)
    }

    companion object {
        private const val HEADER_SIZE = 26
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val TOKEN_MASK = 0x1FFFFF
        private const val MAGIC = 0x53434E47 // SCNG
        private fun pack2(a: Int, b: Int) = (a.toLong() shl 21) or b.toLong()
        private fun pack3(a: Int, b: Int, c: Int) = (a.toLong() shl 42) or (b.toLong() shl 21) or c.toLong()

        fun load(input: InputStream): BinaryCharacterLanguageModel {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_BYTES) { "Ngram model exceeds size budget" }
                output.write(buffer, 0, count)
            }
            return fromBytes(output.toByteArray())
        }

        fun fromBytes(data: ByteArray): BinaryCharacterLanguageModel {
            require(data.size in HEADER_SIZE..MAX_BYTES) { "Ngram model size outside budget" }
            val bytes = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            require(bytes.int == MAGIC && bytes.short.toInt() == 1) { "Invalid ngram magic/version" }
            val counts = IntArray(5) { bytes.int }
            require(counts.all { it >= 0 } && counts[0] in 2..100_000) { "Invalid ngram counts" }
            val sizes = intArrayOf(8, 12, 8, 12, 12)
            require(HEADER_SIZE + counts.indices.sumOf { counts[it].toLong() * sizes[it] } == data.size.toLong()) {
                "Ngram table length mismatch"
            }
            fun score(): Float = bytes.float.also { require(it.isFinite() && it in -80f..0f) { "Invalid ngram log score" } }
            fun ints(count: Int): Pair<IntArray, FloatArray> {
                val keys = IntArray(count)
                val scores = FloatArray(count)
                repeat(count) { i ->
                    keys[i] = bytes.int
                    require(keys[i] >= 0 && (i == 0 || keys[i] > keys[i - 1])) { "Unsorted token table" }
                    scores[i] = score()
                }
                return keys to scores
            }
            fun longs(count: Int, pair: Boolean): Pair<LongArray, FloatArray> {
                val keys = LongArray(count)
                val scores = FloatArray(count)
                repeat(count) { i ->
                    keys[i] = bytes.long
                    require(keys[i] >= 0 && (!pair || keys[i] < (1L shl 42)) && (i == 0 || keys[i] > keys[i - 1])) { "Unsorted ngram table" }
                    scores[i] = score()
                }
                return keys to scores
            }
            val (vocabulary, unigrams) = ints(counts[0])
            val (biKeys, bigrams) = longs(counts[1], pair = true)
            val (biContexts, biBackoffs) = ints(counts[2])
            val (triKeys, trigrams) = longs(counts[3], pair = false)
            val (triContexts, triBackoffs) = longs(counts[4], pair = true)
            require(vocabulary.binarySearch(CharacterLanguageModel.EOS) >= 0 && vocabulary.binarySearch(CharacterLanguageModel.UNK) >= 0) { "Missing EOS/UNK" }
            require(vocabulary.all { it == CharacterLanguageModel.EOS || it == CharacterLanguageModel.UNK ||
                (it in 0..0x10FFFF && it !in 0xD800..0xDFFF) }) { "Invalid vocabulary scalar" }
            fun context(cp: Int) = cp == CharacterLanguageModel.BOS || vocabulary.binarySearch(cp) >= 0
            require(biContexts.all(::context)) { "Invalid bigram context" }
            require(triContexts.all { context((it ushr 21).toInt()) && context((it and TOKEN_MASK.toLong()).toInt()) }) { "Invalid trigram context" }
            require(biKeys.all {
                val b = (it ushr 21).toInt()
                context(b) && vocabulary.binarySearch((it and TOKEN_MASK.toLong()).toInt()) >= 0 && biContexts.binarySearch(b) >= 0
            }) { "Invalid bigram reference" }
            require(triKeys.all {
                val a = (it ushr 42).toInt()
                val b = ((it ushr 21) and TOKEN_MASK.toLong()).toInt()
                context(a) && context(b) && vocabulary.binarySearch((it and TOKEN_MASK.toLong()).toInt()) >= 0 && triContexts.binarySearch(pack2(a, b)) >= 0
            }) { "Invalid trigram reference" }
            return BinaryCharacterLanguageModel(vocabulary, unigrams, biKeys, bigrams, biContexts, biBackoffs, triKeys, trigrams, triContexts, triBackoffs)
        }
    }
}
