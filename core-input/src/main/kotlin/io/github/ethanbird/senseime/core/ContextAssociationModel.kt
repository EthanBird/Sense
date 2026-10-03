package io.github.ethanbird.senseime.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Offline continuation retrieval. An empty result may deliberately mean "stop suggesting". */
fun interface ContextAssociationModel {
    fun suggest(leftContext: String, limit: Int): List<AssociationSuggestion>
}

/** SNWP/1: sorted 1..3-Han contexts with up to eight pre-ranked word/suffix continuations.
 * Primitive indexes retain no dictionary-sized object graph. Only the selected row is decoded
 * at query time. Explicit empty rows suppress shorter-context backoff, including after STOP.
 */
class BinaryContextAssociationModel private constructor(
    private val bytes: ByteArray,
    private val keys: LongArray,
    private val offsets: IntArray,
) : ContextAssociationModel {
    override fun suggest(leftContext: String, limit: Int): List<AssociationSuggestion> {
        if (limit <= 0) return emptyList()
        var offset = leftContext.length
        var key = 0L
        var shift = 0
        var found = -1
        while (offset > 0 && shift < 63) {
            val cp = leftContext.codePointBefore(offset)
            if (!isHan(cp)) break
            key = key or (cp.toLong() shl shift)
            val index = keys.binarySearch(key)
            if (index >= 0) found = index
            offset -= Character.charCount(cp)
            shift += 21
        }
        if (found < 0) return emptyList()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        data.position(offsets[found])
        val count = (data.get().toInt() and 255).coerceAtMost(limit)
        return List(count) {
            val length = data.get().toInt() and 255
            val text = String(bytes, data.position(), length, Charsets.UTF_8)
            data.position(data.position() + length)
            AssociationSuggestion(text, 3f + data.float, AssociationSuggestionSource.CONTEXT_LANGUAGE_MODEL)
        }
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024

        fun fromBytes(input: ByteArray): BinaryContextAssociationModel {
            require(input.size in 10..MAX_BYTES) { "SNWP size out of bounds" }
            // Ownership is private; callers may reuse/mutate their loading buffer.
            val bytes = input.copyOf()
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            require(data.int == 0x534e5750 && data.short.toInt() == 1) { "Invalid SNWP header" }
            val count = data.int
            require(count in 1..((bytes.size - 10) / 9)) { "Invalid SNWP row count" }
            val keys = LongArray(count)
            val offsets = IntArray(count)
            // Validation must not allocate a String/decoder/HashSet for every stored successor.
            val wordStarts = IntArray(8)
            val wordLengths = IntArray(8)
            var previousKey = 0L
            for (row in 0 until count) {
                require(data.remaining() >= 9) { "Truncated SNWP row" }
                val key = data.long
                require(key > previousKey && validKey(key)) { "Invalid SNWP context" }
                keys[row] = key
                previousKey = key
                offsets[row] = data.position()
                val successors = data.get().toInt() and 255
                require(successors <= 8) { "SNWP successor budget" }
                var previousScore = Float.POSITIVE_INFINITY
                repeat(successors) { successor ->
                    require(data.hasRemaining()) { "Truncated SNWP word" }
                    val length = data.get().toInt() and 255
                    require(length in 1..32 && data.remaining() >= length + 4) { "Invalid SNWP word size" }
                    val start = data.position()
                    require(validWord(bytes, start, length)) { "Invalid SNWP UTF-8 or non-Han word" }
                    for (previous in 0 until successor) {
                        require(wordLengths[previous] != length || !sameBytes(bytes, wordStarts[previous], start, length)) {
                            "Duplicate SNWP word"
                        }
                    }
                    wordStarts[successor] = start
                    wordLengths[successor] = length
                    data.position(data.position() + length)
                    val score = data.float
                    require(score.isFinite() && score <= 0f && score <= previousScore) {
                        "Invalid SNWP score"
                    }
                    previousScore = score
                }
            }
            require(!data.hasRemaining()) { "Trailing SNWP bytes" }
            return BinaryContextAssociationModel(bytes, keys, offsets)
        }

        private fun validKey(key: Long): Boolean {
            if (key <= 0) return false
            var value = key
            var count = 0
            while (value != 0L) {
                if (!isHan((value and 0x1fffff).toInt())) return false
                value = value ushr 21
                count++
            }
            return count in 1..3
        }

        // These original, fully assigned Han ranges avoid an ICU/native script lookup for
        // virtually every corpus character on ART. Later additions still use the platform table.
        private fun isHan(cp: Int): Boolean = cp in 0x4e00..0x9fa5 || cp in 0x3400..0x4db5 ||
            (Character.isValidCodePoint(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)

        /** Strict UTF-8, restricted to Han scalars (all require 3 or 4 UTF-8 bytes). */
        private fun validWord(bytes: ByteArray, start: Int, length: Int): Boolean {
            val end = start + length
            var offset = start
            var count = 0
            while (offset < end) {
                val leading = bytes[offset++].toInt() and 255
                val continuation = when (leading) {
                    in 0xe0..0xef -> 2
                    in 0xf0..0xf4 -> 3
                    else -> return false
                }
                if (offset + continuation > end) return false
                var cp = leading and (if (continuation == 2) 15 else 7)
                repeat(continuation) {
                    val next = bytes[offset++].toInt() and 255
                    if (next !in 0x80..0xbf) return false
                    cp = (cp shl 6) or (next and 63)
                }
                if (cp < (if (continuation == 2) 0x800 else 0x10000) || !isHan(cp) || ++count > 8) return false
            }
            return count > 0
        }

        private fun sameBytes(bytes: ByteArray, first: Int, second: Int, length: Int): Boolean {
            for (index in 0 until length) if (bytes[first + index] != bytes[second + index]) return false
            return true
        }
    }
}
