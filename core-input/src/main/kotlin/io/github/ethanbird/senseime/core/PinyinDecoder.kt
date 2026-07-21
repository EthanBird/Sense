package io.github.ethanbird.senseime.core

import java.io.InputStream
import kotlin.math.ln

/**
 * Read-only decoder for Sense's compact pinyin lexicon.
 *
 * The binary remains in one byte array and an offset table. Candidate lookup is
 * a binary search, so the IME does not allocate tens of thousands of map entries.
 */
class PinyinDecoder private constructor(
    private val data: ByteArray,
    private val recordOffsets: IntArray,
) : InputDecoder {

    override fun decode(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val query = normalize(composing)
        if (query.isEmpty()) return emptyList()

        val exact = findExact(query)
        if (exact >= 0) return readCandidates(exact, limit)

        val composed = composeCandidates(query, limit)
        return if (composed.isNotEmpty()) composed else readPrefixCandidates(query, limit)
    }

    private fun findExact(query: String): Int {
        var low = 0
        var high = recordOffsets.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            when {
                compareCode(middle, query) < 0 -> low = middle + 1
                compareCode(middle, query) > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    private fun lowerBound(query: String): Int {
        var low = 0
        var high = recordOffsets.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (compareCode(middle, query) < 0) low = middle + 1 else high = middle
        }
        return low
    }

    private data class CompositionPath(
        val text: String,
        val segments: Int,
        val score: Float,
    )

    /** Builds a sentence from the longest available words when no exact phrase exists. */
    private fun composeCandidates(query: String, limit: Int): List<Candidate> {
        val beams = arrayOfNulls<MutableList<CompositionPath>>(query.length + 1)
        beams[0] = mutableListOf(CompositionPath("", 0, 0f))
        query.indices.forEach { start ->
            val paths = beams[start] ?: return@forEach
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 1)..maxEnd) {
                val record = findExact(query, start, end)
                if (record < 0) continue
                val options = readCandidates(record, SEGMENT_CANDIDATES_PER_KEY)
                val target = beams[end] ?: mutableListOf<CompositionPath>().also { beams[end] = it }
                paths.forEach { path ->
                    options.forEach { option ->
                        addToBeam(
                            target,
                            CompositionPath(path.text + option.text, path.segments + 1, path.score + option.score),
                        )
                    }
                }
            }
        }

        return beams[query.length]
            .orEmpty()
            .distinctBy { it.text }
            .sortedWith(compositionComparator)
            .take(limit)
            .map { Candidate(it.text, it.score - it.segments * SEGMENT_SCORE_PENALTY) }
    }

    private fun addToBeam(beam: MutableList<CompositionPath>, value: CompositionPath) {
        val existing = beam.indexOfFirst { it.text == value.text && it.segments == value.segments }
        if (existing >= 0) {
            if (beam[existing].score >= value.score) return
            beam.removeAt(existing)
        }
        beam += value
        beam.sortWith(compositionComparator)
        while (beam.size > SEGMENT_BEAM_WIDTH) beam.removeAt(beam.lastIndex)
    }

    private val compositionComparator =
        compareBy<CompositionPath> { it.segments }.thenByDescending { it.score }.thenBy { it.text }

    private fun findExact(query: String, start: Int, end: Int): Int {
        var low = 0
        var high = recordOffsets.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            when {
                compareCode(middle, query, start, end) < 0 -> low = middle + 1
                compareCode(middle, query, start, end) > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    private fun readPrefixCandidates(query: String, limit: Int): List<Candidate> {
        val values = HashMap<String, Float>()
        var index = lowerBound(query)
        var scanned = 0
        while (index < recordOffsets.size && scanned < PREFIX_SCAN_LIMIT && codeStartsWith(index, query)) {
            val codeLength = unsigned(data[recordOffsets[index]])
            val completionPenalty = (codeLength - query.length).coerceAtLeast(0) * 0.08f
            readCandidates(index, PREFIX_CANDIDATES_PER_KEY).forEach { candidate ->
                val score = candidate.score - completionPenalty
                if (score > (values[candidate.text] ?: Float.NEGATIVE_INFINITY)) {
                    values[candidate.text] = score
                }
            }
            index += 1
            scanned += 1
        }
        return values.entries
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key.length })
            .take(limit)
            .map { Candidate(it.key, it.value) }
    }

    private fun readCandidates(index: Int, limit: Int): List<Candidate> {
        var cursor = recordOffsets[index]
        val codeLength = unsigned(data[cursor++])
        cursor += codeLength
        val candidateCount = unsigned(data[cursor++])
        val result = ArrayList<Candidate>(minOf(limit, candidateCount))
        repeat(candidateCount) { candidateIndex ->
            val textLength = unsigned(data[cursor++])
            val text = if (candidateIndex < limit) {
                data.decodeToString(cursor, cursor + textLength)
            } else {
                ""
            }
            cursor += textLength
            val weight = readInt(cursor).toLong() and 0xFFFFFFFFL
            cursor += Int.SIZE_BYTES
            if (candidateIndex < limit) {
                result += Candidate(text, ln(weight.toDouble() + 1.0).toFloat())
            }
        }
        return result
    }

    private fun compareCode(index: Int, query: String): Int {
        return compareCode(index, query, 0, query.length)
    }

    private fun compareCode(index: Int, query: String, start: Int, end: Int): Int {
        val offset = recordOffsets[index]
        val codeLength = unsigned(data[offset])
        val queryLength = end - start
        val shared = minOf(codeLength, queryLength)
        repeat(shared) { characterIndex ->
            val difference = unsigned(data[offset + 1 + characterIndex]) - query[start + characterIndex].code
            if (difference != 0) return difference
        }
        return codeLength - queryLength
    }

    private fun codeStartsWith(index: Int, query: String): Boolean {
        val offset = recordOffsets[index]
        val codeLength = unsigned(data[offset])
        if (codeLength < query.length) return false
        return query.indices.all { unsigned(data[offset + 1 + it]) == query[it].code }
    }

    private fun readInt(offset: Int): Int =
        (unsigned(data[offset]) shl 24) or
            (unsigned(data[offset + 1]) shl 16) or
            (unsigned(data[offset + 2]) shl 8) or
            unsigned(data[offset + 3])

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

    private fun normalize(value: String): String = buildString(value.length) {
        value.forEach { character ->
            val lower = character.lowercaseChar()
            if (lower in 'a'..'z') append(lower)
        }
    }

    companion object {
        private const val HEADER_SIZE = 10
        private const val VERSION = 1
        private const val PREFIX_SCAN_LIMIT = 96
        private const val PREFIX_CANDIDATES_PER_KEY = 2
        private const val MAX_SEGMENT_CODE_LENGTH = 24
        private const val SEGMENT_CANDIDATES_PER_KEY = 2
        private const val SEGMENT_BEAM_WIDTH = 10
        private const val SEGMENT_SCORE_PENALTY = 20f
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(), 'X'.code.toByte())

        fun load(input: InputStream): PinyinDecoder = fromBytes(input.readBytes())

        fun fromBytes(data: ByteArray): PinyinDecoder {
            require(data.size >= HEADER_SIZE) { "Pinyin lexicon header is truncated" }
            require(MAGIC.indices.all { data[it] == MAGIC[it] }) { "Pinyin lexicon magic is invalid" }
            val version = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            require(version == VERSION) { "Unsupported pinyin lexicon version: $version" }
            val count = ((data[6].toInt() and 0xFF) shl 24) or
                ((data[7].toInt() and 0xFF) shl 16) or
                ((data[8].toInt() and 0xFF) shl 8) or
                (data[9].toInt() and 0xFF)
            require(count in 1..1_000_000) { "Pinyin lexicon record count is invalid: $count" }

            val offsets = IntArray(count)
            var cursor = HEADER_SIZE
            repeat(count) { index ->
                require(cursor < data.size) { "Pinyin lexicon record $index is truncated" }
                offsets[index] = cursor
                val codeLength = data[cursor++].toInt() and 0xFF
                require(codeLength > 0 && cursor + codeLength < data.size) { "Pinyin code $index is invalid" }
                cursor += codeLength
                val candidateCount = data[cursor++].toInt() and 0xFF
                require(candidateCount > 0) { "Pinyin code $index has no candidates" }
                repeat(candidateCount) {
                    require(cursor < data.size) { "Pinyin candidate length is missing" }
                    val textLength = data[cursor++].toInt() and 0xFF
                    require(textLength > 0 && cursor + textLength + Int.SIZE_BYTES <= data.size) {
                        "Pinyin candidate is truncated"
                    }
                    cursor += textLength + Int.SIZE_BYTES
                }
            }
            require(cursor == data.size) { "Pinyin lexicon has trailing bytes" }
            return PinyinDecoder(data, offsets)
        }
    }
}
