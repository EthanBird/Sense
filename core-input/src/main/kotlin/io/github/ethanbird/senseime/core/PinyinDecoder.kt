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
    private val spellingGraph: PinyinSpellingGraph,
    private val correctionBudget: CorrectionSearchBudget,
) : ContextualInputDecoder, ProgressivePrefixProbeDecoder, RankedCandidateDecoder {

    override fun decode(composing: String, limit: Int): List<Candidate> =
        decodeInternal(composing, limit, NO_CODE_POINT, includeCorrections = true)

    override fun decodeAfter(previousCodePoint: Int, composing: String, limit: Int): List<Candidate> {
        if (!Character.isValidCodePoint(previousCodePoint)) return emptyList()
        return decodeInternal(composing, limit, previousCodePoint, includeCorrections = true)
    }

    override fun decodePrefixProbe(composing: String, limit: Int): List<Candidate> =
        decodeInternal(composing, limit, NO_CODE_POINT, includeCorrections = false)

    override fun decodePrefixProbeAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int,
    ): List<Candidate> {
        if (!Character.isValidCodePoint(previousCodePoint)) return emptyList()
        return decodeInternal(composing, limit, previousCodePoint, includeCorrections = false)
    }

    /**
     * Collects every viable source before applying one calibrated ranker.
     *
     * Canonical exact, composed, hybrid, initials and corrected spellings are
     * alternatives in one score domain; an exact dictionary hit no longer
     * terminates typo or segmentation recall.
     */
    private fun decodeInternal(
        composing: String,
        limit: Int,
        previousCodePoint: Int,
        includeCorrections: Boolean,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        val parsedQuery = parseQuery(composing)
        val query = parsedQuery.code
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }
        val outputLimit = minOf(limit, MAX_DECODE_CANDIDATES)
        val exactRecord = if (parsedQuery.hasForcedJoints) -1 else findExact(query)
        val hasCanonicalExact = exactRecord >= 0
        val hasCanonicalComposition = hasMultiSegmentComposition(
            query,
            parsedQuery.forcedJoints,
        )
        val candidates = ArrayList<Candidate>(outputLimit * 3)

        if (exactRecord >= 0) {
            candidates += readCandidates(
                exactRecord,
                outputLimit,
                CandidateMatchKind.BASE_EXACT,
                query,
            )
        }
        if (hasCanonicalComposition) {
            candidates += composeCandidates(
                query,
                outputLimit,
                segmentCandidatesPerKey = segmentCandidateLimit(outputLimit),
                beamWidth = segmentBeamWidth(outputLimit),
                forcedJoints = parsedQuery.forcedJoints,
            )
        }
        if (!parsedQuery.hasForcedJoints) {
            candidates += readHybridCandidates(query, outputLimit)
            candidates += readInitialsCandidates(query, outputLimit)
            if (!hasCanonicalExact) {
                candidates += readStatisticalPrefixCandidates(query, outputLimit)
                candidates += readPrefixCandidates(query, outputLimit)
            }
        }
        if (includeCorrections) {
            candidates += readSpellingGraphCorrections(
                rawInput = composing,
                normalizedQuery = query,
                limit = outputLimit,
                allowComposedCorrections = !hasCanonicalExact && !hasCanonicalComposition,
            )
        }

        val contextual = if (previousCodePoint == NO_CODE_POINT) {
            candidates
        } else {
            candidates.map { candidate ->
                candidate.copy(
                    score = candidate.score +
                        bigramModel.score(previousCodePoint, candidate.text.codePointAt(0))
                            .coerceIn(-CONTEXT_SCORE_CAP, CONTEXT_SCORE_CAP),
                )
            }
        }
        return CandidateRanker.rank(
            candidates = contextual,
            limit = outputLimit,
            hasCanonicalExact = hasCanonicalExact,
            hasCanonicalComposition = hasCanonicalComposition,
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
        forcedJoints: BooleanArray? = null,
        spellingSyllableEnds: List<Int>? = null,
    ): List<Candidate> {
        val beams = arrayOfNulls<MutableList<CompositionPath>>(query.length + 1)
        val syllableIndexByOffset = spellingSyllableEnds?.let { ends ->
            IntArray(query.length + 1) { -1 }.also { indices ->
                indices[0] = 0
                ends.forEachIndexed { index, end ->
                    if (end in 1..query.length) indices[end] = index + 1
                }
            }
        }
        beams[0] = mutableListOf(CompositionPath("", "", 0, 0f, NO_CODE_POINT, false))
        query.indices.forEach { start ->
            val paths = beams[start]?.also { pruneBeam(it, beamWidth) } ?: return@forEach
            val startSyllableIndex = syllableIndexByOffset?.get(start) ?: 0
            if (syllableIndexByOffset != null && startSyllableIndex < 0) return@forEach
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 1)..maxEnd) {
                if (!isCompositionEdgeAllowed(query, start, end, forcedJoints)) continue
                val edgeSyllableCount = syllableIndexByOffset?.let { indices ->
                    val endSyllableIndex = indices[end]
                    if (endSyllableIndex <= startSyllableIndex) return@let -1
                    endSyllableIndex - startSyllableIndex
                }
                if (edgeSyllableCount != null && edgeSyllableCount < 1) continue
                val record = findExact(query, start, end)
                if (record < 0) continue
                val options = readCandidates(record, segmentCandidatesPerKey)
                    .filter { option ->
                        edgeSyllableCount == null ||
                            option.canonicalInitials?.length == edgeSyllableCount
                    }
                val target = beams[end] ?: mutableListOf<CompositionPath>().also { beams[end] = it }
                paths.forEach { path ->
                    options.forEach { option ->
                        val firstCodePoint = option.text.codePointAt(0)
                        val optionIsSingleCodePoint = option.text.codePointCount(0, option.text.length) == 1
                        val boundaryScore = when {
                            path.lastCodePoint == NO_CODE_POINT -> 0f
                            !path.lastSegmentWasSingleCodePoint && !optionIsSingleCodePoint ->
                                bigramModel.score(path.lastCodePoint, firstCodePoint) * COMPOUND_BOUNDARY_SCALE

                            else -> bigramModel.score(path.lastCodePoint, firstCodePoint)
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
            .sortedWith(compositionComparator)
            .distinctBy { it.text }
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
        (
            path.score -
                (path.segments - 1).coerceAtLeast(0) * WORD_BOUNDARY_COST
        ) / path.segments.coerceAtLeast(1)

    private val compositionComparator =
        compareByDescending<CompositionPath>(::compositionScore)
            .thenBy { it.segments }
            .thenBy { it.text }

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

    private fun mergeCandidates(candidates: List<Candidate>, limit: Int): List<Candidate> =
        CandidateRanker.rank(candidates, limit, hasCanonicalExact = false)

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
        val values = LinkedHashMap<String, Candidate>(minOf(limit * 2, 64))
        val retainedCapacity = maxOf(limit * HYBRID_RETAINED_LIMIT_MULTIPLIER, 32)
        val pruneThreshold = maxOf(limit * HYBRID_PRUNE_LIMIT_MULTIPLIER, 64)
        fun add(candidate: Candidate) {
            val previous = values[candidate.text]
            if (previous == null || candidate.score > previous.score) {
                values[candidate.text] = candidate
            }
            if (values.size > pruneThreshold) {
                val retained = CandidateRanker.rank(
                    values.values,
                    retainedCapacity,
                    hasCanonicalExact = false,
                )
                values.clear()
                retained.forEach { values[it.text] = it }
            }
        }
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
                minOf(limit, HYBRID_CANDIDATES_PER_RECORD),
                CandidateMatchKind.BASE_HYBRID,
                canonical,
            ).forEach(::add)
            index += 1
            scanned += 1
        }
        return mergeCandidates(values.values.toList(), limit)
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

    /**
     * Resolves weighted spelling-graph routes through the same word lattice as
     * canonical input. This keeps a legal exact spelling and likely typo
     * alternatives together instead of making correction an early-return
     * fallback.
     */
    private fun readSpellingGraphCorrections(
        rawInput: String,
        normalizedQuery: String,
        limit: Int,
        allowComposedCorrections: Boolean,
    ): List<Candidate> {
        val values = ArrayList<Candidate>()
        val composedProbes = ArrayList<CorrectionCompositionProbe>()
        val spellingPathLimit =
            correctionBudget.spellingPathLimit(allowComposedCorrections, limit)
        val paths = spellingGraph.paths(rawInput, maxPaths = spellingPathLimit)
            .asSequence()
            .filter { it.cost > 0f && it.canonical != normalizedQuery }
            .toList()
        paths.forEach { path ->
            val exact = findExact(path.canonical)
            if (exact >= 0) {
                readCandidates(
                    exact,
                    minOf(limit, correctionBudget.exactCandidatesPerPath),
                    CandidateMatchKind.CORRECTED,
                    path.canonical,
                ).asSequence()
                    .filter { candidate ->
                        candidate.canonicalInitials?.length == path.syllableCount
                    }
                    .forEach { candidate -> values += candidate.withCorrectionPenalty(path) }
            }
            compositionScoreUpperBound(path.canonical)?.let { upperBound ->
                composedProbes += CorrectionCompositionProbe(
                    path = path,
                    upperBound = upperBound,
                    hasExactRecord = exact >= 0,
                )
            }
        }

        val composedBudget =
            correctionBudget.composedPathLimit(allowComposedCorrections, limit)
        val correctionProbeOrder =
            compareByDescending<CorrectionCompositionProbe> { it.hasExactRecord }
                .thenByDescending {
                    it.upperBound - it.path.cost * CORRECTION_PENALTY
                }
                .thenBy { it.path.cost }
                .thenBy { it.path.canonical }
        val orderedComposedProbes = composedProbes
            .sortedWith(correctionProbeOrder)
            .distinctBy { it.path.canonical }
        val selectedComposedProbes = LinkedHashSet<CorrectionCompositionProbe>(composedBudget)
        orderedComposedProbes.asSequence()
            .filter { isSingleCharacterTailExtension(normalizedQuery, it.path.canonical) }
            .take(minOf(TAIL_EXTENSION_PROBE_LIMIT, composedBudget))
            .forEach(selectedComposedProbes::add)
        val latestEditOffset = composedProbes.maxOfOrNull { it.path.firstEditOffset }
        if (latestEditOffset != null && selectedComposedProbes.size < composedBudget) {
            orderedComposedProbes.asSequence()
                .filter { it.path.firstEditOffset >= latestEditOffset - RECENT_EDIT_OFFSET_WINDOW }
                .firstOrNull()
                ?.let(selectedComposedProbes::add)
        }
        orderedComposedProbes.forEach { probe ->
            if (selectedComposedProbes.size < composedBudget) selectedComposedProbes += probe
        }
        selectedComposedProbes
            .forEach { probe ->
                composeCandidates(
                    probe.path.canonical,
                    minOf(limit, correctionBudget.composedCandidatesPerPath),
                    segmentCandidatesPerKey = correctionBudget.segmentCandidatesPerKey,
                    beamWidth = correctionBudget.segmentBeamWidth,
                    spellingSyllableEnds = probe.path.syllableEnds,
                ).asSequence()
                    .filter { candidate ->
                        candidate.canonicalInitials?.length == probe.path.syllableCount
                    }
                    .forEach { candidate ->
                        values += candidate.copy(
                            score = candidate.score - probe.path.cost * CORRECTION_PENALTY,
                            matchKind = CandidateMatchKind.CORRECTED,
                            canonicalPinyin = probe.path.canonical,
                        )
                    }
            }
        return CandidateRanker.rank(values, limit, hasCanonicalExact = false)
    }

    private fun isSingleCharacterTailExtension(typed: String, canonical: String): Boolean =
        (canonical.length == typed.length + 1 && canonical.startsWith(typed)) ||
            (typed.length == canonical.length + 1 && typed.startsWith(canonical))

    private data class CorrectionCompositionProbe(
        val path: PinyinSpellingPath,
        val upperBound: Float,
        val hasExactRecord: Boolean,
    )

    private fun Candidate.withCorrectionPenalty(path: PinyinSpellingPath): Candidate =
        copy(
            score = score - path.cost * CORRECTION_PENALTY,
            matchKind = CandidateMatchKind.CORRECTED,
            canonicalPinyin = path.canonical,
        )

    /**
     * Cheap lexical upper bound used before spending a full correction beam.
     *
     * It reads only the first candidate of each word-lattice edge and keeps one
     * score per segment count. This lets high-frequency corrected paths win the
     * bounded expansion budget instead of relying on canonical string order.
     */
    private fun compositionScoreUpperBound(query: String): Float? {
        val scores = Array(query.length + 1) {
            FloatArray(query.length + 1) { Float.NEGATIVE_INFINITY }
        }
        scores[0][0] = 0f
        query.indices.forEach { start ->
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 1)..maxEnd) {
                if (!isCompositionEdgeAllowed(query, start, end)) continue
                val record = findExact(query, start, end)
                if (record < 0) continue
                val topScore = readCandidates(record, 1).firstOrNull()?.score ?: continue
                for (segments in 0 until query.length) {
                    val previous = scores[start][segments]
                    if (!previous.isFinite()) continue
                    scores[end][segments + 1] = maxOf(
                        scores[end][segments + 1],
                        previous + topScore,
                    )
                }
            }
        }
        var best = Float.NEGATIVE_INFINITY
        for (segments in 2..query.length) {
            val total = scores[query.length][segments]
            if (!total.isFinite()) continue
            best = maxOf(
                best,
                (
                    total -
                        (segments - 1) * WORD_BOUNDARY_COST
                    ) / segments,
            )
        }
        return best.takeIf(Float::isFinite)
    }

    /**
     * A canonical composition used to suppress completion-style corrections
     * must not depend on one-letter fallback records such as `n`. Without this
     * guard, `fun` can be treated as `fu + n`, and a transposition typo ending
     * in `...ern` can look like a valid sentence.
     */
    private fun hasMultiSegmentComposition(
        query: String,
        forcedJoints: BooleanArray? = null,
    ): Boolean {
        val maximumSegments = IntArray(query.length + 1) { -1 }
        maximumSegments[0] = 0
        query.indices.forEach { start ->
            if (maximumSegments[start] < 0) return@forEach
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 1)..maxEnd) {
                if (!isCompositionEdgeAllowed(query, start, end, forcedJoints)) continue
                if (findExact(query, start, end) >= 0) {
                    maximumSegments[end] = maxOf(maximumSegments[end], maximumSegments[start] + 1)
                }
            }
        }
        return maximumSegments.last() >= 2
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

    private data class ParsedQuery(
        val code: String,
        val forcedJoints: BooleanArray,
    ) {
        val hasForcedJoints: Boolean
            get() = forcedJoints.any { it }
    }

    private fun parseQuery(value: String): ParsedQuery {
        val code = StringBuilder(value.length)
        val jointOffsets = ArrayList<Int>()
        var pendingJoint = false
        value.forEach { character ->
            when {
                character == '\'' -> pendingJoint = code.isNotEmpty()
                character.lowercaseChar() in 'a'..'z' -> {
                    if (pendingJoint && code.isNotEmpty()) jointOffsets += code.length
                    code.append(character.lowercaseChar())
                    pendingJoint = false
                }
            }
        }
        val joints = BooleanArray(code.length + 1)
        jointOffsets.forEach { joints[it] = true }
        return ParsedQuery(code.toString(), joints)
    }

    private fun crossesForcedJoint(
        forcedJoints: BooleanArray?,
        start: Int,
        end: Int,
    ): Boolean {
        if (forcedJoints == null) return false
        for (joint in (start + 1) until end) {
            if (forcedJoints.getOrElse(joint) { false }) return true
        }
        return false
    }

    private fun isCompositionEdgeAllowed(
        query: String,
        start: Int,
        end: Int,
        forcedJoints: BooleanArray? = null,
    ): Boolean =
        end > start &&
            (end - start > 1 || query[start] in SINGLE_LETTER_SYLLABLES) &&
            !crossesForcedJoint(forcedJoints, start, end)

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
        private const val WORD_BOUNDARY_COST = 0.65f
        private const val COMPOUND_BOUNDARY_SCALE = 0.04f
        private const val CONTEXT_SCORE_CAP = 3f
        private const val CORRECTION_PENALTY = 4f
        private const val RECENT_EDIT_OFFSET_WINDOW = 1
        private const val TAIL_EXTENSION_PROBE_LIMIT = 2
        private const val NO_CODE_POINT = -1
        private const val SINGLE_LETTER_SYLLABLES = "aeo"
        private const val FALLBACK_SOURCE_TIER = 1
        // CC-CEDICT fallback entries use weight 1. Penalizing only fallback
        // entries keeps a zero-weight primary entry ahead without rewarding
        // sentences merely for splitting into more primary-source segments.
        private const val FALLBACK_SOURCE_PENALTY = 1f
        private const val PREFIX_NAMESPACE = "{"
        private const val INITIALS_NAMESPACE = "~"
        private const val HYBRID_NAMESPACE = "}"
        private const val HYBRID_SEPARATOR = "|"
        private const val MIN_INITIALS_LENGTH = 2
        private const val EXACT_INITIALS_LENGTH_BONUS = 3f
        private const val FOUR_CHARACTER_INITIALS_BONUS = 10f
        private const val MIN_HYBRID_LENGTH = 3
        private const val HYBRID_SCAN_LIMIT = 128
        private const val HYBRID_CANDIDATES_PER_RECORD = 16
        private const val HYBRID_RETAINED_LIMIT_MULTIPLIER = 2
        private const val HYBRID_PRUNE_LIMIT_MULTIPLIER = 3
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(), 'X'.code.toByte())

        fun load(
            input: InputStream,
            bigramModel: CharacterBigramModel = CharacterBigramModel.EMPTY,
            correctionBudget: CorrectionSearchBudget = CorrectionSearchBudget.PRODUCTION,
        ): PinyinDecoder = fromBytes(input.readBytes(), bigramModel, correctionBudget)

        fun fromBytes(
            data: ByteArray,
            bigramModel: CharacterBigramModel = CharacterBigramModel.EMPTY,
            correctionBudget: CorrectionSearchBudget = CorrectionSearchBudget.PRODUCTION,
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
            val syllables = LinkedHashSet<String>()
            var cursor = HEADER_SIZE
            repeat(count) { index ->
                require(cursor < data.size) { "Pinyin lexicon record $index is truncated" }
                offsets[index] = cursor
                val codeLength = data[cursor++].toInt() and 0xFF
                require(codeLength > 0 && cursor + codeLength < data.size) { "Pinyin code $index is invalid" }
                val codeStart = cursor
                val firstCodeByte = data[cursor].toInt() and 0xFF
                val isCanonicalCode =
                    firstCodeByte in 'a'.code..'z'.code &&
                        codeLength <= MAX_SEGMENT_CODE_LENGTH
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
                val isSyllableRecord =
                    codeLength <= MAX_PINYIN_SYLLABLE_CODE_LENGTH &&
                    isCanonicalCode &&
                    hasSingleSyllableCandidate
                if (isSyllableRecord) {
                    // Production packs contain hundreds of thousands of canonical records but only
                    // a few hundred syllables. Decode a String only for graph inventory entries;
                    // the former eager conversion created one short-lived object per record.
                    val canonicalCode = data.decodeToString(codeStart, codeStart + codeLength)
                    syllableRecords[firstCodeByte - 'a'.code] += index
                    syllables += canonicalCode
                }
            }
            require(cursor == data.size) { "Pinyin lexicon has trailing bytes" }
            return PinyinDecoder(
                data,
                offsets,
                bigramModel,
                Array(syllableRecords.size) { syllableRecords[it].toIntArray() },
                PinyinSpellingGraph(syllables),
                correctionBudget,
            )
        }
    }
}
