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
    private val bigramModel: CharacterBigramModel,
    private val syllableRecordIndicesByInitial: Array<IntArray>,
) : ContextualInputDecoder {

    override fun decode(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val query = normalize(composing)
        if (query.isEmpty()) return emptyList()
        val outputLimit = minOf(limit, MAX_DECODE_CANDIDATES)
        val hybrid = readHybridCandidates(query, outputLimit)

        val exact = findExact(query)
        if (exact >= 0) {
            val exactCandidates = readCandidates(exact, outputLimit, CandidateMatchKind.BASE_EXACT, query)
            val composedCandidates = if (hasCompleteMultiSegmentComposition(query)) {
                composeCandidates(
                    query,
                    outputLimit,
                    segmentCandidatesPerKey = segmentCandidateLimit(outputLimit),
                    beamWidth = segmentBeamWidth(outputLimit),
                )
            } else {
                emptyList()
            }
            val canonicalFullPinyin = mergeCandidates(
                exactCandidates + composedCandidates,
                outputLimit,
            )
            // A private hybrid alias must never outrank a valid full-pinyin
            // spelling merely because the underlying phrase has a larger raw
            // dictionary weight (`hang` must remain 行/航…, not 韩国).
            return concatenateCandidateGroups(
                listOf(canonicalFullPinyin, hybrid),
                outputLimit,
            )
        }

        val initials = readInitialsCandidates(query, outputLimit)
        val composed = composeCandidates(
            query,
            outputLimit,
            segmentCandidatesPerKey = segmentCandidateLimit(outputLimit),
            beamWidth = segmentBeamWidth(outputLimit),
        )
        val statisticalPrefix = readStatisticalPrefixCandidates(query, outputLimit)
        val dynamicPrefix = readPrefixCandidates(query, outputLimit)
        val corrected = readCorrectedCandidates(query, outputLimit)
        // If the raw query already has a complete pinyin segmentation, a
        // correction that inserts another letter is only an alternative. Keep
        // it behind the normal ranker so a newly added `...haoo` phrase cannot
        // turn exact `...henhao` into `...很好哦`. Same-length substitutions
        // and transpositions still compete normally; that preserves typo
        // recovery when a weak accidental segmentation also exists.
        val (completionCorrections, competitiveCorrections) = if (
            composed.isNotEmpty() && hasStrongMultiSegmentComposition(query)
        ) {
            corrected.partition { isCompletionCorrection(it, query) }
        } else {
            emptyList<Candidate>() to corrected
        }
        val primary = mergeCandidates(
            hybrid + initials + composed + statisticalPrefix + dynamicPrefix + competitiveCorrections,
            outputLimit,
        )
        return if (completionCorrections.isEmpty()) {
            primary
        } else {
            concatenateCandidateGroups(
                listOf(primary, mergeCandidates(completionCorrections, outputLimit)),
                outputLimit,
            )
        }
    }

    override fun decodeAfter(previousCodePoint: Int, composing: String, limit: Int): List<Candidate> {
        if (limit <= 0 || !Character.isValidCodePoint(previousCodePoint)) return emptyList()
        val query = normalize(composing)
        val hasCanonicalExact = findExact(query) >= 0
        val hasCanonicalComposition =
            !hasCanonicalExact && hasStrongMultiSegmentComposition(query)
        return decode(composing, limit)
            .map { candidate ->
                candidate.copy(
                    score = candidate.score + bigramModel.score(previousCodePoint, candidate.text.codePointAt(0)),
                )
            }
            .sortedWith(
                compareBy<Candidate> {
                    when {
                        hasCanonicalExact -> exactQuerySourceGroup(it.matchKind)
                        hasCanonicalComposition && isCompletionCorrection(it, query) -> 1
                        else -> 0
                    }
                }
                    .thenByDescending { it.score }
                    .thenBy { matchPriority(it.matchKind) }
                    .thenBy { it.text.length },
            )
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
        val lastCodePoint: Int,
        val lastSegmentWasSingleCodePoint: Boolean,
    )

    private data class CompositionIdentity(
        val text: String,
        val segments: Int,
        val lastSegmentWasSingleCodePoint: Boolean,
    )

    /** Builds a sentence by balancing word likelihood with a penalty for each split. */
    private fun composeCandidates(
        query: String,
        limit: Int,
        segmentCandidatesPerKey: Int,
        beamWidth: Int,
    ): List<Candidate> {
        val beams = arrayOfNulls<MutableList<CompositionPath>>(query.length + 1)
        beams[0] = mutableListOf(CompositionPath("", "", 0, 0f, NO_CODE_POINT, false))
        query.indices.forEach { start ->
            val paths = beams[start]?.also { pruneBeam(it, beamWidth) } ?: return@forEach
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 1)..maxEnd) {
                val record = findExact(query, start, end)
                if (record < 0) continue
                val options = readCandidates(record, segmentCandidatesPerKey)
                val target = beams[end] ?: mutableListOf<CompositionPath>().also { beams[end] = it }
                paths.forEach { path ->
                    options.forEach { option ->
                        val firstCodePoint = option.text.codePointAt(0)
                        val optionIsSingleCodePoint = option.text.codePointCount(0, option.text.length) == 1
                        val boundaryScore = if (
                            path.lastCodePoint == NO_CODE_POINT ||
                            !path.lastSegmentWasSingleCodePoint && !optionIsSingleCodePoint
                        ) {
                            0f
                        } else {
                            bigramModel.score(path.lastCodePoint, firstCodePoint)
                        }
                        addToBeam(
                            target,
                            CompositionPath(
                                text = path.text + option.text,
                                initials = path.initials + option.canonicalInitials.orEmpty(),
                                segments = path.segments + 1,
                                score = path.score + option.score + boundaryScore,
                                lastCodePoint = option.text.codePointBefore(option.text.length),
                                lastSegmentWasSingleCodePoint = optionIsSingleCodePoint,
                            ),
                            beamWidth,
                        )
                    }
                }
            }
        }

        val completed = beams[query.length] ?: mutableListOf()
        pruneBeam(completed, beamWidth)
        return completed
            .filter { it.segments > 1 }
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

    private fun addToBeam(
        beam: MutableList<CompositionPath>,
        value: CompositionPath,
        beamWidth: Int,
    ) {
        beam += value
        if (beam.size >= beamWidth * BEAM_PRUNE_MULTIPLIER) pruneBeam(beam, beamWidth)
    }

    /** Batch pruning avoids sorting a several-hundred-entry beam for every path expansion. */
    private fun pruneBeam(beam: MutableList<CompositionPath>, beamWidth: Int) {
        if (beam.isEmpty()) return
        val best = HashMap<CompositionIdentity, CompositionPath>(beam.size)
        beam.forEach { path ->
            val key = CompositionIdentity(path.text, path.segments, path.lastSegmentWasSingleCodePoint)
            val previous = best[key]
            if (previous == null || path.score > previous.score) best[key] = path
        }
        if (best.size == beam.size && beam.size <= beamWidth) return
        val retained = best.values.sortedWith(compositionComparator).take(beamWidth)
        beam.clear()
        beam.addAll(retained)
    }

    private fun compositionScore(path: CompositionPath): Float =
        path.score - path.segments * SEGMENT_SCORE_PENALTY

    private val compositionComparator =
        compareByDescending<CompositionPath>(::compositionScore)
            .thenBy { it.segments }
            .thenBy { it.text }

    private fun matchPriority(kind: CandidateMatchKind): Int = when (kind) {
        CandidateMatchKind.BASE_EXACT -> 0
        CandidateMatchKind.BASE_COMPOSED -> 1
        CandidateMatchKind.BASE_HYBRID -> 1
        CandidateMatchKind.BASE_INITIALS -> 1
        CandidateMatchKind.BASE_PREFIX -> 2
        CandidateMatchKind.CORRECTED -> 4
        CandidateMatchKind.ENGLISH_EXACT -> 5
        CandidateMatchKind.ENGLISH_PREFIX -> 6
        CandidateMatchKind.USER_FULL,
        CandidateMatchKind.USER_INITIALS
        -> 0
    }

    private fun exactQuerySourceGroup(kind: CandidateMatchKind): Int = when (kind) {
        CandidateMatchKind.BASE_EXACT,
        CandidateMatchKind.BASE_COMPOSED,
        -> 0

        CandidateMatchKind.BASE_HYBRID -> 1
        else -> 2
    }

    private fun isCompletionCorrection(candidate: Candidate, query: String): Boolean =
        candidate.matchKind == CandidateMatchKind.CORRECTED &&
            candidate.canonicalPinyin?.length?.let { it > query.length } == true

    /**
     * Small UI/benchmark requests keep a compact search budget. The IME's
     * 255-candidate production decode receives the wider budget needed for
     * deep alternatives such as `hua -> 滑` inside a composed phrase.
     */
    private fun segmentCandidateLimit(limit: Int): Int =
        if (limit >= WIDE_COMPOSITION_LIMIT) MAX_SEGMENT_CANDIDATES_PER_KEY else MIN_SEGMENT_CANDIDATES_PER_KEY

    private fun segmentBeamWidth(limit: Int): Int =
        if (limit >= WIDE_COMPOSITION_LIMIT) {
            MAX_SEGMENT_BEAM_WIDTH
        } else {
            maxOf(MIN_SEGMENT_BEAM_WIDTH, limit)
        }

    private fun mergeCandidates(candidates: List<Candidate>, limit: Int): List<Candidate> {
        val bestByText = LinkedHashMap<String, Candidate>()
        candidates.forEach { candidate ->
            val previous = bestByText[candidate.text]
            bestByText[candidate.text] = if (previous == null) {
                candidate
            } else {
                mergeDuplicate(previous, candidate)
            }
        }
        return bestByText.values
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { matchPriority(it.matchKind) }
                    .thenBy { it.text.length }
                    .thenBy { it.text },
            )
            .take(limit)
    }

    private fun concatenateCandidateGroups(
        groups: List<List<Candidate>>,
        limit: Int,
    ): List<Candidate> {
        val result = ArrayList<Candidate>(limit)
        val seen = HashSet<String>()
        groups.forEach { group ->
            group.forEach { candidate ->
                if (result.size < limit && seen.add(candidate.text)) result += candidate
            }
        }
        return result
    }

    /**
     * Statistical prefix records do not encode their originating full pinyin.
     * If the same result is found by the normal prefix scan, retain that safe
     * source metadata even when the statistical score wins the display rank.
     */
    private fun mergeDuplicate(previous: Candidate, candidate: Candidate): Candidate {
        val best = if (
            candidate.score > previous.score ||
            candidate.score == previous.score && matchPriority(candidate.matchKind) < matchPriority(previous.matchKind)
        ) {
            candidate
        } else {
            previous
        }
        if (best.matchKind != CandidateMatchKind.BASE_PREFIX || best.canonicalPinyin != null) return best
        val sourcedPrefix = sequenceOf(previous, candidate).firstOrNull {
            it.matchKind == CandidateMatchKind.BASE_PREFIX && it.canonicalPinyin != null
        } ?: return best
        return best.copy(
            canonicalPinyin = sourcedPrefix.canonicalPinyin,
            canonicalInitials = best.canonicalInitials ?: sourcedPrefix.canonicalInitials,
        )
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

    private fun readInitialsCandidates(query: String, limit: Int): List<Candidate> {
        if (query.length < MIN_INITIALS_LENGTH) return emptyList()
        val record = findExact(INITIALS_NAMESPACE + query)
        if (record < 0) return emptyList()
        return readCandidates(record, limit, CandidateMatchKind.BASE_INITIALS)
            .map {
                it.copy(
                    score = it.score + initialsLengthBonus(it.text, query),
                    canonicalPinyin = null,
                    canonicalInitials = query,
                    matchKind = CandidateMatchKind.BASE_INITIALS,
                )
            }
    }

    private fun initialsLengthBonus(text: String, query: String): Float {
        val textLength = text.codePointCount(0, text.length)
        if (textLength != query.length) return 0f
        return if (textLength == 4) {
            FOUR_CHARACTER_INITIALS_BONUS
        } else {
            EXACT_INITIALS_LENGTH_BONUS
        }
    }

    private fun readHybridCandidates(query: String, limit: Int): List<Candidate> {
        if (query.length < MIN_HYBRID_LENGTH) return emptyList()
        val prefix = HYBRID_NAMESPACE + query + HYBRID_SEPARATOR
        val values = ArrayList<Candidate>(minOf(limit, 32))
        var index = lowerBound(prefix)
        var scanned = 0
        while (
            index < recordOffsets.size &&
            scanned < HYBRID_SCAN_LIMIT &&
            codeStartsWith(index, prefix)
        ) {
            val code = readCode(index)
            val canonical = code.substring(prefix.length)
            readCandidates(
                index,
                limit,
                CandidateMatchKind.BASE_HYBRID,
                canonical,
            ).forEach(values::add)
            index += 1
            scanned += 1
        }
        return mergeCandidates(values, limit)
    }

    private fun readPrefixCandidates(query: String, limit: Int): List<Candidate> {
        val values = HashMap<String, Candidate>()
        fun readRecord(index: Int) {
            val codeLength = unsigned(data[recordOffsets[index]])
            val canonicalPinyin = readCode(index)
            val completionPenalty = (codeLength - query.length).coerceAtLeast(0) * 0.08f
            readCandidates(index, PREFIX_CANDIDATES_PER_KEY).forEach { candidate ->
                val score = candidate.score - completionPenalty
                if (score > (values[candidate.text]?.score ?: Float.NEGATIVE_INFINITY)) {
                    values[candidate.text] = candidate.copy(
                        score = score,
                        canonicalPinyin = canonicalPinyin,
                        matchKind = CandidateMatchKind.BASE_PREFIX,
                    )
                }
            }
        }

        syllableRecordIndicesByInitial[query.first() - 'a'].forEach { index ->
            if (codeStartsWith(index, query)) readRecord(index)
        }
        var index = lowerBound(query)
        var scanned = 0
        while (index < recordOffsets.size && scanned < PREFIX_SCAN_LIMIT && codeStartsWith(index, query)) {
            readRecord(index)
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

                hasCompleteComposition(correction.code) -> composeCandidates(
                    correction.code,
                    limit,
                    segmentCandidatesPerKey = CORRECTION_SEGMENT_CANDIDATES_PER_KEY,
                    beamWidth = CORRECTION_BEAM_WIDTH,
                ).map {
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

    /** True when the whole query has an exact path containing at least two records. */
    private fun hasCompleteMultiSegmentComposition(query: String): Boolean {
        val maximumSegments = IntArray(query.length + 1) { -1 }
        maximumSegments[0] = 0
        query.indices.forEach { start ->
            if (maximumSegments[start] < 0) return@forEach
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 1)..maxEnd) {
                if (findExact(query, start, end) >= 0) {
                    maximumSegments[end] = maxOf(maximumSegments[end], maximumSegments[start] + 1)
                }
            }
        }
        return maximumSegments.last() >= 2
    }

    /**
     * A canonical composition used to suppress completion-style corrections
     * must not depend on one-letter fallback records such as `n`. Without this
     * guard, `fun` can be treated as `fu + n`, and a transposition typo ending
     * in `...ern` can look like a valid sentence.
     */
    private fun hasStrongMultiSegmentComposition(query: String): Boolean {
        val maximumSegments = IntArray(query.length + 1) { -1 }
        maximumSegments[0] = 0
        query.indices.forEach { start ->
            if (maximumSegments[start] < 0) return@forEach
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 2)..maxEnd) {
                if (findExact(query, start, end) >= 0) {
                    maximumSegments[end] = maxOf(maximumSegments[end], maximumSegments[start] + 1)
                }
            }
        }
        return maximumSegments.last() >= 2
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

    private fun readCode(index: Int): String {
        val offset = recordOffsets[index]
        val codeLength = unsigned(data[offset])
        return data.decodeToString(offset + 1, offset + 1 + codeLength)
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
        private const val MAX_DECODE_CANDIDATES = 255
        private const val PREFIX_SCAN_LIMIT = 96
        private const val PREFIX_CANDIDATES_PER_KEY = 2
        private const val MAX_SEGMENT_CODE_LENGTH = 24
        private const val MIN_SEGMENT_CANDIDATES_PER_KEY = 8
        private const val MAX_SEGMENT_CANDIDATES_PER_KEY = 16
        private const val MIN_SEGMENT_BEAM_WIDTH = 24
        private const val MAX_SEGMENT_BEAM_WIDTH = 96
        private const val WIDE_COMPOSITION_LIMIT = 64
        private const val BEAM_PRUNE_MULTIPLIER = 4
        private const val MAX_PINYIN_SYLLABLE_CODE_LENGTH = 6
        private const val CORRECTION_SEGMENT_CANDIDATES_PER_KEY = 2
        private const val CORRECTION_BEAM_WIDTH = 10
        private const val SEGMENT_SCORE_PENALTY = 20f
        private const val MAX_CORRECTION_QUERY_LENGTH = 48
        private const val CORRECTION_PENALTY = 4f
        private const val FUZZY_COST = 0.25f
        private const val NEIGHBOR_COST = 0.35f
        private const val REPEATED_KEY_COST = 0.3f
        private const val TRANSPOSITION_COST = 0.45f
        private const val SUBSTITUTION_COST = 0.9f
        private const val INSERTION_DELETION_COST = 1f
        private const val NO_CODE_POINT = -1
        private const val FALLBACK_SOURCE_TIER = 1
        // CC-CEDICT fallback entries use weight 1. Penalizing only fallback
        // entries keeps a zero-weight primary entry ahead without rewarding
        // sentences merely for splitting into more primary-source segments.
        private const val FALLBACK_SOURCE_PENALTY = 1f
        private val FUZZY_SUBSTITUTIONS = setOf('n' to 'l', 'f' to 'h')
        private const val PREFIX_NAMESPACE = "{"
        private const val INITIALS_NAMESPACE = "~"
        private const val HYBRID_NAMESPACE = "}"
        private const val HYBRID_SEPARATOR = "|"
        private const val MIN_INITIALS_LENGTH = 2
        private const val EXACT_INITIALS_LENGTH_BONUS = 3f
        private const val FOUR_CHARACTER_INITIALS_BONUS = 10f
        private const val MIN_HYBRID_LENGTH = 3
        private const val HYBRID_SCAN_LIMIT = 128
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

        fun load(
            input: InputStream,
            bigramModel: CharacterBigramModel = CharacterBigramModel.EMPTY,
        ): PinyinDecoder = fromBytes(input.readBytes(), bigramModel)

        fun fromBytes(
            data: ByteArray,
            bigramModel: CharacterBigramModel = CharacterBigramModel.EMPTY,
        ): PinyinDecoder {
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
            val syllableRecords = Array(26) { ArrayList<Int>() }
            var cursor = HEADER_SIZE
            repeat(count) { index ->
                require(cursor < data.size) { "Pinyin lexicon record $index is truncated" }
                offsets[index] = cursor
                val codeLength = data[cursor++].toInt() and 0xFF
                require(codeLength > 0 && cursor + codeLength < data.size) { "Pinyin code $index is invalid" }
                val firstCodeByte = data[cursor].toInt() and 0xFF
                cursor += codeLength
                val candidateCount = data[cursor++].toInt() and 0xFF
                require(candidateCount > 0) { "Pinyin code $index has no candidates" }
                var hasSingleSyllableCandidate = false
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
                    if (initialsLength == 1) hasSingleSyllableCandidate = true
                    cursor += initialsLength
                    require(cursor < data.size) { "Pinyin candidate source tier is missing" }
                    val sourceTier = data[cursor++].toInt() and 0xFF
                    require(sourceTier in 0..1) { "Pinyin candidate source tier is invalid" }
                }
                if (
                    codeLength <= MAX_PINYIN_SYLLABLE_CODE_LENGTH &&
                    firstCodeByte in 'a'.code..'z'.code &&
                    hasSingleSyllableCandidate
                ) {
                    syllableRecords[firstCodeByte - 'a'.code] += index
                }
            }
            require(cursor == data.size) { "Pinyin lexicon has trailing bytes" }
            return PinyinDecoder(
                data,
                offsets,
                bigramModel,
                Array(syllableRecords.size) { syllableRecords[it].toIntArray() },
            )
        }
    }
}
