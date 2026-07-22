package io.github.ethanbird.senseime.core

import java.io.InputStream

/** Scores a boundary between two Unicode code points without allocating. */
fun interface CharacterBigramModel {
    fun score(previousCodePoint: Int, nextCodePoint: Int): Float

    companion object {
        val EMPTY = CharacterBigramModel { _, _ -> 0f }
    }
}

/** Compact sorted bigram table backed by primitive arrays and binary search. */
class BinaryCharacterBigramModel private constructor(
    private val keys: LongArray,
    private val scores: FloatArray,
) : CharacterBigramModel {
    val size: Int
        get() = keys.size

    override fun score(previousCodePoint: Int, nextCodePoint: Int): Float {
        val target = pack(previousCodePoint, nextCodePoint)
        var low = 0
        var high = keys.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            when {
                keys[middle] < target -> low = middle + 1
                keys[middle] > target -> high = middle - 1
                else -> return scores[middle]
            }
        }
        return 0f
    }

    companion object {
        private const val HEADER_SIZE = 10
        private const val RECORD_SIZE = 12
        private const val VERSION = 1
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'B'.code.toByte(), 'G'.code.toByte(), 'M'.code.toByte())

        fun load(input: InputStream): BinaryCharacterBigramModel = fromBytes(input.readBytes())

        fun fromBytes(data: ByteArray): BinaryCharacterBigramModel {
            require(data.size >= HEADER_SIZE) { "Bigram model header is truncated" }
            require(MAGIC.indices.all { data[it] == MAGIC[it] }) { "Bigram model magic is invalid" }
            val version = readUnsignedShort(data, 4)
            require(version == VERSION) { "Unsupported bigram model version: $version" }
            val count = readInt(data, 6)
            require(count in 1..1_000_000) { "Bigram record count is invalid: $count" }
            require(data.size == HEADER_SIZE + count * RECORD_SIZE) { "Bigram model length is invalid" }

            val keys = LongArray(count)
            val scores = FloatArray(count)
            var cursor = HEADER_SIZE
            repeat(count) { index ->
                val previous = readInt(data, cursor)
                val next = readInt(data, cursor + 4)
                val score = Float.fromBits(readInt(data, cursor + 8))
                require(isUnicodeScalar(previous) && isUnicodeScalar(next)) {
                    "Bigram code point is invalid"
                }
                require(score.isFinite() && score in MIN_SCORE..MAX_SCORE) { "Bigram score is invalid" }
                val key = pack(previous, next)
                require(index == 0 || key > keys[index - 1]) { "Bigram records are not strictly sorted" }
                keys[index] = key
                scores[index] = score
                cursor += RECORD_SIZE
            }
            return BinaryCharacterBigramModel(keys, scores)
        }

        private fun pack(previousCodePoint: Int, nextCodePoint: Int): Long =
            (previousCodePoint.toLong() shl 32) or (nextCodePoint.toLong() and 0xFFFF_FFFFL)

        private fun isUnicodeScalar(codePoint: Int): Boolean =
            Character.isValidCodePoint(codePoint) && codePoint !in 0xD800..0xDFFF

        private fun readUnsignedShort(data: ByteArray, offset: Int): Int =
            (unsigned(data[offset]) shl 8) or unsigned(data[offset + 1])

        private fun readInt(data: ByteArray, offset: Int): Int =
            (unsigned(data[offset]) shl 24) or
                (unsigned(data[offset + 1]) shl 16) or
                (unsigned(data[offset + 2]) shl 8) or
                unsigned(data[offset + 3])

        private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

        private const val MIN_SCORE = 0.05f
        private const val MAX_SCORE = 3f
    }
}
