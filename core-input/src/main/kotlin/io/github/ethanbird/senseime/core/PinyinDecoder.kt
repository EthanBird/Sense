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
        if (exact >= 0) return readCandidates(exact, limit, CandidateMatchKind.BASE_EXACT, query)

        val composed = composeCandidates(query, limit)
        val statisticalPrefix = readStatisticalPrefixCandidates(query, limit)
        val dynamicPrefix = readPrefixCandidates(query, limit)
        val corrected = readCorrectedCandidates(query, limit)
        val bestByText = LinkedHashMap<String, Candidate>()
        (composed + statisticalPrefix + dynamicPrefix + corrected).forEach { candidate ->
            val previous = bestByText[candidate.text]
            if (previous == null || candidate.score > previous.score) bestByText[candidate.text] = candidate
        }
        return bestByText.values
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { matchPriority(it.matchKind) }.thenBy { it.text.length })
            .take(limit)
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
        val initials: String,
        val segments: Int,
        val score: Float,
    )

    /** Builds a sentence by balancing word likelihood with a penalty for each split. */
    private fun composeCandidates(query: String, limit: Int): List<Candidate> {
        val beams = arrayOfNulls<MutableList<CompositionPath>>(query.length + 1)
        beams[0] = mutableListOf(CompositionPath("", "", 0, 0f))
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
                            CompositionPath(
                                text = path.text + option.text,
                                initials = path.initials + option.canonicalInitials.orEmpty(),
                                segments = path.segments + 1,
                                score = path.score + option.score,
                            ),
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
            .map {
                Candidate(
                    text = it.text,
                    score = compositionScore(it),
                    canonicalPinyin = query,
                    matchKind = CandidateMatchKind.BASE_COMPOSED,
                    canonicalInitials = it.initials.ifEmpty { null },
                )
            }
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

    private fun compositionScore(path: CompositionPath): Float =
        path.score - path.segments * SEGMENT_SCORE_PENALTY

    private val compositionComparator =
        compareByDescending<CompositionPath>(::compositionScore)
            .thenBy { it.segments }
            .thenBy { it.text }

    private fun matchPriority(kind: CandidateMatchKind): Int = when (kind) {
        CandidateMatchKind.BASE_COMPOSED -> 0
        CandidateMatchKind.BASE_PREFIX -> 1
        CandidateMatchKind.CORRECTED -> 2
        else -> 3
    }

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

    private fun readStatisticalPrefixCandidates(query: String, limit: Int): List<Candidate> {
        val record = findExact(PREFIX_NAMESPACE + query)
        if (record < 0) return emptyList()
        return readCandidates(record, limit, CandidateMatchKind.BASE_PREFIX)
            .map { it.copy(canonicalPinyin = null, matchKind = CandidateMatchKind.BASE_PREFIX) }
    }

    private fun readPrefixCandidates(query: String, limit: Int): List<Candidate> {
        val values = HashMap<String, Candidate>()
        var index = lowerBound(query)
        var scanned = 0
        while (index < recordOffsets.size && scanned < PREFIX_SCAN_LIMIT && codeStartsWith(index, query)) {
            val codeLength = unsigned(data[recordOffsets[index]])
            val completionPenalty = (codeLength - query.length).coerceAtLeast(0) * 0.08f
            readCandidates(index, PREFIX_CANDIDATES_PER_KEY).forEach { candidate ->
                val score = candidate.score - completionPenalty
                if (score > (values[candidate.text]?.score ?: Float.NEGATIVE_INFINITY)) {
                    values[candidate.text] = candidate.copy(
                        score = score,
                        canonicalPinyin = null,
                        matchKind = CandidateMatchKind.BASE_PREFIX,
                    )
                }
            }
            index += 1
            scanned += 1
        }
        return values.values
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.text.length })
            .take(limit)
    }

    private data class CorrectionHit(
        val text: String,
        val score: Float,
        val cost: Float,
        val canonical: String,
        val initials: String?,
    )

    private data class CorrectionQuery(
        val code: String,
        val cost: Float,
    )

    private fun readCorrectedCandidates(query: String, limit: Int): List<Candidate> {
        if (query.length !in 2..MAX_CORRECTION_QUERY_LENGTH) return emptyList()
        val values = HashMap<String, CorrectionHit>()
        correctionQueries(query).forEach { correction ->
            val exact = findExact(correction.code)
            val candidates = when {
                exact >= 0 -> readCandidates(
                    exact,
                    limit,
                    CandidateMatchKind.CORRECTED,
                    correction.code,
                )

                hasCompleteComposition(correction.code) -> composeCandidates(correction.code, limit).map {
                    it.copy(matchKind = CandidateMatchKind.CORRECTED, canonicalPinyin = correction.code)
                }

                else -> emptyList()
            }
            candidates.forEach { candidate ->
                val value = CorrectionHit(
                    candidate.text,
                    candidate.score - correction.cost * CORRECTION_PENALTY,
                    correction.cost,
                    correction.code,
                    candidate.canonicalInitials,
                )
                val previous = values[candidate.text]
                if (previous == null || value.score > previous.score || value.score == previous.score && value.cost < previous.cost) {
                    values[candidate.text] = value
                }
            }
        }
        return values.values
            .sortedWith(compareByDescending<CorrectionHit> { it.score }.thenBy { it.cost }.thenBy { it.text.length })
            .take(limit)
            .map { Candidate(it.text, it.score, it.canonical, CandidateMatchKind.CORRECTED, it.initials) }
    }

    /** Generates all one-edit spellings and ranks mobile-keyboard/fuzzy variants first. */
    private fun correctionQueries(query: String): List<CorrectionQuery> {
        val values = HashMap<String, Float>()
        fun offer(code: String, cost: Float) {
            if (code != query && code.length <= MAX_CORRECTION_QUERY_LENGTH + 1) {
                values[code] = minOf(values[code] ?: Float.POSITIVE_INFINITY, cost)
            }
        }

        query.indices.forEach { index ->
            val repeatedKey = query.getOrNull(index - 1) == query[index] || query.getOrNull(index + 1) == query[index]
            val deletionCost = if (repeatedKey) REPEATED_KEY_COST else {
                insertionDeletionCost(query[index], query.getOrNull(index - 1))
            }
            offer(query.removeRange(index, index + 1), deletionCost)
            if (index < query.lastIndex && query[index] != query[index + 1]) {
                val swapped = query.toCharArray().also {
                    val first = it[index]
                    it[index] = it[index + 1]
                    it[index + 1] = first
                }
                offer(swapped.concatToString(), TRANSPOSITION_COST)
            }
            SUBSTITUTION_CANDIDATES[query[index]].orEmpty().forEach { expected ->
                offer(query.replaceRange(index, index + 1, expected.toString()), substitutionCost(expected, query[index]))
            }
        }
        for (index in 0..query.length) {
            ('a'..'z').forEach { expected ->
                offer(
                    query.substring(0, index) + expected + query.substring(index),
                    insertionDeletionCost(expected, query.getOrNull(index - 1)),
                )
            }
        }
        return values.entries
            .map { CorrectionQuery(it.key, it.value) }
            .sortedWith(compareBy<CorrectionQuery> { it.cost }.thenBy { it.code })
    }

    private fun hasCompleteComposition(query: String): Boolean {
        val reachable = BooleanArray(query.length + 1)
        reachable[0] = true
        query.indices.forEach { start ->
            if (!reachable[start]) return@forEach
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 1)..maxEnd) {
                if (findExact(query, start, end) >= 0) reachable[end] = true
            }
        }
        return reachable.last()
    }

    private fun substitutionCost(expected: Char, typed: Char): Float = when {
        expected == typed -> 0f
        expected to typed in FUZZY_SUBSTITUTIONS || typed to expected in FUZZY_SUBSTITUTIONS -> FUZZY_COST
        typed in KEY_NEIGHBORS[expected].orEmpty() -> NEIGHBOR_COST
        else -> SUBSTITUTION_COST
    }

    private fun insertionDeletionCost(character: Char, previous: Char?): Float = when {
        character == 'h' && previous != null && previous in "zcs" -> FUZZY_COST
        character == 'g' && previous == 'n' -> FUZZY_COST
        else -> INSERTION_DELETION_COST
    }

    private fun readCandidates(
        index: Int,
        limit: Int,
        matchKind: CandidateMatchKind = CandidateMatchKind.BASE_EXACT,
        canonicalPinyin: String? = null,
    ): List<Candidate> {
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
            val initialsLength = unsigned(data[cursor++])
            val initials = if (candidateIndex < limit && initialsLength > 0) {
                data.decodeToString(cursor, cursor + initialsLength)
            } else {
                null
            }
            cursor += initialsLength
            val sourceTier = unsigned(data[cursor++])
            if (candidateIndex < limit) {
                result += Candidate(
                    text,
                    ln(weight.toDouble() + 1.0).toFloat() -
                        if (sourceTier == FALLBACK_SOURCE_TIER) FALLBACK_SOURCE_PENALTY else 0f,
                    canonicalPinyin,
                    matchKind,
                    initials,
                )
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
        private const val VERSION = 3
        private const val PREFIX_SCAN_LIMIT = 96
        private const val PREFIX_CANDIDATES_PER_KEY = 2
        private const val MAX_SEGMENT_CODE_LENGTH = 24
        private const val SEGMENT_CANDIDATES_PER_KEY = 2
        private const val SEGMENT_BEAM_WIDTH = 10
        private const val SEGMENT_SCORE_PENALTY = 20f
        private const val MAX_CORRECTION_QUERY_LENGTH = 48
        private const val CORRECTION_PENALTY = 4f
        private const val FUZZY_COST = 0.25f
        private const val NEIGHBOR_COST = 0.35f
        private const val REPEATED_KEY_COST = 0.3f
        private const val TRANSPOSITION_COST = 0.45f
        private const val SUBSTITUTION_COST = 0.9f
        private const val INSERTION_DELETION_COST = 1f
        private const val FALLBACK_SOURCE_TIER = 1
        // CC-CEDICT fallback entries use weight 1. Penalizing only fallback
        // entries keeps a zero-weight primary entry ahead without rewarding
        // sentences merely for splitting into more primary-source segments.
        private const val FALLBACK_SOURCE_PENALTY = 1f
        private val FUZZY_SUBSTITUTIONS = setOf('n' to 'l', 'f' to 'h')
        private const val PREFIX_NAMESPACE = "{"
        private val KEY_NEIGHBORS = mapOf(
            'q' to "wa", 'w' to "qeas", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg",
            'y' to "tugh", 'u' to "yihj", 'i' to "uojk", 'o' to "ipkl", 'p' to "ol",
            'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgcv", 'g' to "ftyhbv",
            'h' to "gyujbn", 'j' to "huiknm", 'k' to "jiolm", 'l' to "kop",
            'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn",
            'n' to "bhjm", 'm' to "njk",
        )
        private val SUBSTITUTION_CANDIDATES: Map<Char, Set<Char>> = ('a'..'z').associateWith { typed ->
            buildSet {
                addAll(KEY_NEIGHBORS[typed].orEmpty().asIterable())
                KEY_NEIGHBORS.forEach { (expected, neighbors) -> if (typed in neighbors) add(expected) }
                FUZZY_SUBSTITUTIONS.forEach { (left, right) ->
                    if (typed == left) add(right)
                    if (typed == right) add(left)
                }
                remove(typed)
            }
        }
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
                    require(cursor < data.size) { "Pinyin initials length is missing" }
                    val initialsLength = data[cursor++].toInt() and 0xFF
                    require(initialsLength > 0 && cursor + initialsLength <= data.size) {
                        "Pinyin candidate initials are truncated"
                    }
                    cursor += initialsLength
                    require(cursor < data.size) { "Pinyin candidate source tier is missing" }
                    val sourceTier = data[cursor++].toInt() and 0xFF
                    require(sourceTier in 0..1) { "Pinyin candidate source tier is invalid" }
                }
            }
            require(cursor == data.size) { "Pinyin lexicon has trailing bytes" }
            return PinyinDecoder(data, offsets)
        }
    }
}
