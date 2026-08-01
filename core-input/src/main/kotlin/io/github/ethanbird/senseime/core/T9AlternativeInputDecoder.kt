package io.github.ethanbird.senseime.core

/** Result of the complete T9 path, lexical decode, merge and presentation pipeline. */
data class T9AlternativeDecoding(
    val composingLabel: String,
    val candidates: List<Candidate>,
    /** Number of distinct decoder queries executed after path-query deduplication. */
    val decodedQueryCount: Int,
    /** Wider second-pass decodes; each targets one probe winner. */
    val expandedQueryCount: Int,
    val decodeInvocationCount: Int,
    val availablePathCount: Int,
    /** Dynamic canonical choices derived from the same path beam used for this decode. */
    val pinyinChoices: List<T9PinyinChoice> = emptyList(),
)

/**
 * Complete bounded T9 decoder shared by the Android service and host performance gate.
 *
 * The numeric DAG can expose several spellings for one key stream. Decoding is staged: a
 * representative first beam is evaluated, and the remaining beam is visited only when it is
 * still needed for lexical evidence or candidate fill. Every generated spelling is canonical,
 * so decoders with [CanonicalChineseOnlyInputDecoder] skip typo correction. A continuation probe
 * gives latest-only workers a cooperative supersession point between expensive path decodes.
 */
object T9AlternativeInputDecoder {
    fun decode(
        composition: T9Composition,
        pathSource: T9PinyinPathSource,
        pinyinDecoder: InputDecoder,
        leftContext: String,
        limit: Int,
        shouldContinue: () -> Boolean = ALWAYS_CONTINUE,
    ): T9AlternativeDecoding {
        if (limit <= 0 || composition.rawDigits.isEmpty() || !shouldContinue()) {
            return T9AlternativeDecoding(
                composingLabel = composition.rawDigits,
                candidates = emptyList(),
                decodedQueryCount = 0,
                expandedQueryCount = 0,
                decodeInvocationCount = 0,
                availablePathCount = 0,
            )
        }
        val paths = pathSource.paths(composition, MAX_T9_PATHS)
        if (paths.isEmpty()) {
            return T9AlternativeDecoding(
                composingLabel = composition.rawDigits,
                candidates = emptyList(),
                decodedQueryCount = 0,
                expandedQueryCount = 0,
                decodeInvocationCount = 0,
                availablePathCount = 0,
            )
        }
        val uniquePlans = deduplicatedPlans(composition, paths)
        val queryBudget = maximumQueryBudget(composition, uniquePlans.size)
        val plans = diversityFirstPlans(
            plans = uniquePlans,
            firstStageCount = minOf(INITIAL_QUERY_BEAM, queryBudget),
        )
        val ranked = LinkedHashMap<String, RankedCandidate>()
        val usesLexicalProbe = pinyinDecoder is CanonicalChineseLexicalProbeDecoder
        val lexicallyProbedPlans = ArrayList<QueryPlan>(queryBudget)
        val previousCodePoint = leftContext
            .takeIf(String::isNotEmpty)
            ?.codePointBefore(leftContext.length)
        val expansionLimit = minOf(limit, MAX_CANDIDATES_PER_QUERY)
        var probedQueries = 0
        var stageEnd = minOf(initialStageEnd(plans.size), queryBudget)

        while (probedQueries < queryBudget) {
            while (probedQueries < stageEnd) {
                if (!shouldContinue()) {
                    return result(composition, paths, ranked, probedQueries, 0, limit)
                }
                val plan = plans[probedQueries]
                val decoded = if (usesLexicalProbe && plan.requiresBoundaryAwareProbe) {
                    // The cheap lexical seam intentionally skips apostrophe-constrained
                    // compositions. Run the same one-candidate bounded stage through the full
                    // canonical seam so a valid third-or-later constrained path remains visible.
                    decodeCanonicalChinese(
                        decoder = pinyinDecoder,
                        query = plan.query,
                        previousCodePoint = previousCodePoint,
                        limit = PROBE_CANDIDATE_LIMIT,
                    )
                } else {
                    if (usesLexicalProbe) lexicallyProbedPlans += plan
                    probeCanonicalChinese(
                        decoder = pinyinDecoder,
                        query = plan.query,
                        previousCodePoint = previousCodePoint,
                        limit = PROBE_CANDIDATE_LIMIT,
                    )
                }
                merge(plan, decoded, ranked)
                probedQueries += 1
            }
            if (hasSufficientEvidence(composition, ranked, limit)) break
            if (stageEnd >= queryBudget) break
            stageEnd = nextStageEnd(stageEnd, queryBudget)
        }

        var expandedQueries = 0
        if (usesLexicalProbe && ranked.isEmpty() && lexicallyProbedPlans.isNotEmpty()) {
            val fallbackBeam = maximumStructuralFallbackBeam(composition)
            for (plan in lexicallyProbedPlans.take(fallbackBeam)) {
                if (!shouldContinue()) {
                    return result(
                        composition,
                        paths,
                        ranked,
                        probedQueries,
                        expandedQueries,
                        limit,
                    )
                }
                merge(
                    plan = plan,
                    decoded = decodeCanonicalChinese(
                        decoder = pinyinDecoder,
                        query = plan.query,
                        previousCodePoint = previousCodePoint,
                        limit = expansionLimit,
                    ),
                    ranked = ranked,
                )
                expandedQueries += 1
                if (ranked.isNotEmpty()) break
            }
        } else if (
            expansionLimit > PROBE_CANDIDATE_LIMIT &&
            ranked.isNotEmpty() &&
            probedQueries > 1 &&
            composition.rawDigits.length <= NORMAL_COMPOSITION_DIGITS &&
            limit > ranked.size
        ) {
            val expansionPlans = selectExpansionPlans(
                probedPlans = plans.subList(0, probedQueries),
                ranked = ranked,
            )
            for (plan in expansionPlans) {
                if (!shouldContinue()) {
                    return result(
                        composition,
                        paths,
                        ranked,
                        probedQueries,
                        expandedQueries,
                        limit,
                    )
                }
                merge(
                    plan = plan,
                    decoded = decodeCanonicalChinese(
                        decoder = pinyinDecoder,
                        query = plan.query,
                        previousCodePoint = previousCodePoint,
                        limit = expansionLimit,
                    ),
                    ranked = ranked,
                )
                expandedQueries += 1
            }
        }
        return result(
            composition,
            paths,
            ranked,
            probedQueries,
            expandedQueries,
            limit,
        )
    }

    private fun merge(
        plan: QueryPlan,
        decoded: List<Candidate>,
        ranked: MutableMap<String, RankedCandidate>,
    ) {
        val structuralPenalty =
            plan.path.structuralPenalty + plan.pathIndex * PATH_ORDER_PENALTY
        decoded.forEachIndexed { candidateIndex, candidate ->
            val value = RankedCandidate(
                candidate = candidate.copy(
                    score = candidate.score + evidencePrior(candidate.matchKind) -
                        structuralPenalty - candidateIndex * CANDIDATE_ORDER_PENALTY,
                    pinyinInputAlias = plan.query,
                ),
                path = plan.path,
                pathIndex = plan.pathIndex,
                candidateIndex = candidateIndex,
            )
            val previous = ranked[candidate.text]
            if (previous == null || CANDIDATE_ORDER.compare(value, previous) < 0) {
                ranked[candidate.text] = value
            }
        }
    }

    private fun selectExpansionPlans(
        probedPlans: List<QueryPlan>,
        ranked: Map<String, RankedCandidate>,
    ): List<QueryPlan> {
        val byPathIndex = probedPlans.associateBy(QueryPlan::pathIndex)
        val selected = LinkedHashSet<QueryPlan>(EXPANSION_QUERY_BEAM)
        ranked.values.sortedWith(CANDIDATE_ORDER).forEach { candidate ->
            if (selected.size < EXPANSION_QUERY_BEAM) {
                byPathIndex[candidate.pathIndex]?.let(selected::add)
            }
        }
        probedPlans.forEach { plan ->
            if (selected.size < EXPANSION_QUERY_BEAM) selected += plan
        }
        return selected.toList()
    }

    private fun deduplicatedPlans(
        composition: T9Composition,
        paths: List<T9PinyinPath>,
    ): List<QueryPlan> {
        val constrainedBoundaries = constrainedBoundaries(composition)
        val byQuery = LinkedHashMap<String, QueryPlan>(paths.size)
        paths.forEachIndexed { pathIndex, path ->
            val query = decoderQuery(path, constrainedBoundaries)
            byQuery.putIfAbsent(
                query,
                QueryPlan(
                    query = query,
                    path = path,
                    pathIndex = pathIndex,
                    requiresBoundaryAwareProbe = '\'' in query,
                ),
            )
        }
        return byQuery.values.toList()
    }

    /**
     * Decoder separators represent user constraints, not the path searcher's inferred joints.
     * Keeping an ordinary path continuous leaves exact/hybrid/initials recall available, while
     * explicit `1` separators and locked-edge boundaries remain hard constraints downstream.
     */
    private fun constrainedBoundaries(composition: T9Composition): BooleanArray? {
        if (composition.forcedJoints.isEmpty() && composition.lockedEdges.isEmpty()) return null
        val boundaries = BooleanArray(composition.rawDigits.length + 1)
        composition.forcedJoints.forEach { joint ->
            if (joint in 1 until composition.rawDigits.length) boundaries[joint] = true
        }
        composition.lockedEdges.forEach { edge ->
            if (edge.digitStart in 1 until composition.rawDigits.length) {
                boundaries[edge.digitStart] = true
            }
            if (edge.digitEnd in 1 until composition.rawDigits.length) {
                boundaries[edge.digitEnd] = true
            }
        }
        return boundaries
    }

    private fun decoderQuery(
        path: T9PinyinPath,
        constrainedBoundaries: BooleanArray?,
    ): String {
        if (constrainedBoundaries == null) return path.canonical
        return buildString(path.canonical.length + path.segments.size) {
            path.segments.forEach { segment ->
                segment.spelling.forEachIndexed { spellingOffset, character ->
                    val digitOffset = segment.digitStart + spellingOffset
                    if (
                        isNotEmpty() &&
                        digitOffset in constrainedBoundaries.indices &&
                        constrainedBoundaries[digitOffset]
                    ) {
                        append('\'')
                    }
                    append(character)
                }
            }
        }
    }

    /**
     * Keeps the trie winner first, then greedily covers different segment spellings/shapes in
     * the first stage. Unselected plans remain behind that stage in stable source order, so lack
     * of lexical evidence can continue into the rest of the deduplicated beam.
     * Numeric collisions tend to cluster only one changed initial at a time; taking the first K
     * therefore wastes probes on near duplicates. With at most 32 paths this bounded O(K²) pass
     * is cheaper than one lexical lookup and substantially improves a small probe beam's recall.
     */
    private fun diversityFirstPlans(
        plans: List<QueryPlan>,
        firstStageCount: Int,
    ): List<QueryPlan> {
        if (firstStageCount >= plans.size || firstStageCount <= 0) return plans
        val remaining = plans.toMutableList()
        val selected = ArrayList<QueryPlan>(plans.size)
        selected += remaining.removeAt(0)
        while (selected.size < firstStageCount && remaining.isNotEmpty()) {
            var bestIndex = 0
            var bestDistance = Int.MIN_VALUE
            remaining.forEachIndexed { index, candidate ->
                val distance = selected.minOf { retained ->
                    pathDiversity(candidate.path, retained.path)
                }
                if (distance > bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            }
            selected += remaining.removeAt(bestIndex)
        }
        selected += remaining
        return selected
    }

    private fun pathDiversity(left: T9PinyinPath, right: T9PinyinPath): Int {
        val shared = minOf(left.segments.size, right.segments.size)
        var distance = kotlin.math.abs(left.segments.size - right.segments.size) * 3
        for (index in 0 until shared) {
            val leftSegment = left.segments[index]
            val rightSegment = right.segments[index]
            if (leftSegment.spelling != rightSegment.spelling) distance += 2
            if (leftSegment.kind != rightSegment.kind) distance += 1
            if (
                leftSegment.digitStart != rightSegment.digitStart ||
                leftSegment.digitEnd != rightSegment.digitEnd
            ) {
                distance += 1
            }
        }
        return distance
    }

    private fun initialStageEnd(planCount: Int): Int =
        minOf(planCount, INITIAL_QUERY_BEAM)

    private fun nextStageEnd(current: Int, planCount: Int): Int = when {
        current < INITIAL_QUERY_BEAM -> minOf(INITIAL_QUERY_BEAM, planCount)
        else -> minOf(current + CONTINUATION_QUERY_STEP, planCount)
    }

    /**
     * Inputs through 32 digits retain the complete deduplicated beam and rely on staged evidence
     * stopping. Beyond that normal boundary, the cheap one-candidate lexical probe budget halves
     * every 16 digits. This preserves the same 31/32 behavior while bounding pathological pastes.
     */
    private fun maximumQueryBudget(composition: T9Composition, planCount: Int): Int =
        when {
            composition.rawDigits.length <= NORMAL_COMPOSITION_DIGITS -> planCount
            composition.rawDigits.length <= NORMAL_COMPOSITION_DIGITS + 16 ->
                minOf(planCount, EXTREME_ENTRY_QUERY_BEAM)
            composition.rawDigits.length <= NORMAL_COMPOSITION_DIGITS + 32 ->
                minOf(planCount, EXTREME_ENTRY_QUERY_BEAM / 2)
            composition.rawDigits.length <= NORMAL_COMPOSITION_DIGITS + 48 ->
                minOf(planCount, EXTREME_ENTRY_QUERY_BEAM / 4)
            else -> minOf(planCount, EXTREME_QUERY_BEAM)
        }

    /** Long paste-like streams retain lexical recall without running the sentence DAG. */
    private fun maximumStructuralFallbackBeam(composition: T9Composition): Int = when {
        composition.rawDigits.length <= NORMAL_COMPOSITION_DIGITS ->
            STRUCTURAL_FALLBACK_QUERY_BEAM
        composition.rawDigits.length <= NORMAL_COMPOSITION_DIGITS + 32 -> 1
        else -> 0
    }

    private fun hasSufficientEvidence(
        composition: T9Composition,
        ranked: Map<String, RankedCandidate>,
        limit: Int,
    ): Boolean {
        if (composition.rawDigits.length >= LONG_COMPOSITION_DIGITS) {
            return ranked.values.any { it.candidate.matchKind.hasLexicalPhraseEvidence }
        }
        return ranked.size >= minOf(limit, MIN_CANDIDATE_FILL)
    }

    private val CandidateMatchKind.hasLexicalPhraseEvidence: Boolean
        get() = when (this) {
            CandidateMatchKind.USER_FULL,
            CandidateMatchKind.USER_INITIALS,
            CandidateMatchKind.BASE_EXACT,
            CandidateMatchKind.BASE_HYBRID,
            CandidateMatchKind.BASE_INITIALS,
            -> true

            else -> false
        }

    private fun result(
        composition: T9Composition,
        paths: List<T9PinyinPath>,
        ranked: Map<String, RankedCandidate>,
        decodedQueries: Int,
        expandedQueries: Int,
        limit: Int = Int.MAX_VALUE,
    ): T9AlternativeDecoding {
        val ordered = ranked.values.sortedWith(CANDIDATE_ORDER).take(limit)
        val resolvedPath = ordered.firstOrNull()?.path ?: paths.first()
        return T9AlternativeDecoding(
            composingLabel = resolvedPath.formatted,
            candidates = ordered.map(RankedCandidate::candidate),
            decodedQueryCount = decodedQueries,
            expandedQueryCount = expandedQueries,
            decodeInvocationCount = decodedQueries + expandedQueries,
            availablePathCount = paths.size,
            pinyinChoices = buildT9PinyinChoices(
                composition = composition,
                paths = paths,
                maxChoices = T9SyllableIndex.DEFAULT_MAX_CHOICES,
            ),
        )
    }

    private fun decodeCanonicalChinese(
        decoder: InputDecoder,
        query: String,
        previousCodePoint: Int?,
        limit: Int,
    ): List<Candidate> = when {
        decoder is CanonicalChineseOnlyInputDecoder && previousCodePoint != null ->
            decoder.decodeCanonicalChineseOnlyAfter(previousCodePoint, query, limit)

        decoder is CanonicalChineseOnlyInputDecoder ->
            decoder.decodeCanonicalChineseOnly(query, limit)

        decoder is ChineseOnlyInputDecoder && previousCodePoint != null ->
            decoder.decodeChineseOnlyAfter(previousCodePoint, query, limit)

        decoder is ChineseOnlyInputDecoder -> decoder.decodeChineseOnly(query, limit)
        decoder is ContextualInputDecoder && previousCodePoint != null ->
            decoder.decodeAfter(previousCodePoint, query, limit)

        else -> decoder.decode(query, limit)
    }

    private fun probeCanonicalChinese(
        decoder: InputDecoder,
        query: String,
        previousCodePoint: Int?,
        limit: Int,
    ): List<Candidate> = when {
        decoder is CanonicalChineseLexicalProbeDecoder && previousCodePoint != null ->
            decoder.probeCanonicalChineseOnlyAfter(previousCodePoint, query, limit)

        decoder is CanonicalChineseLexicalProbeDecoder ->
            decoder.probeCanonicalChineseOnly(query, limit)

        else -> decodeCanonicalChinese(decoder, query, previousCodePoint, limit)
    }

    private val T9PinyinPath.structuralPenalty: Float
        get() = segments.fold(0f) { penalty, segment ->
            penalty + when (segment.kind) {
                T9PinyinSegmentKind.SYLLABLE -> 0f
                T9PinyinSegmentKind.INITIAL -> INITIAL_SEGMENT_PENALTY
                T9PinyinSegmentKind.INCOMPLETE -> INCOMPLETE_SEGMENT_PENALTY
            }
        }

    /** Bounded prior for lexical evidence under numeric ambiguity; no phrase is slot-pinned. */
    private fun evidencePrior(kind: CandidateMatchKind): Float = when (kind) {
        CandidateMatchKind.USER_FULL,
        CandidateMatchKind.USER_INITIALS,
        -> 5.5f

        CandidateMatchKind.BASE_HYBRID -> 5.25f
        CandidateMatchKind.BASE_INITIALS -> 4.5f
        CandidateMatchKind.BASE_EXACT -> 2.25f
        CandidateMatchKind.BASE_COMPOSED -> 0f
        CandidateMatchKind.BASE_PREFIX -> -0.5f
        CandidateMatchKind.CORRECTED -> -1f
        CandidateMatchKind.ENGLISH_EXACT,
        CandidateMatchKind.ENGLISH_PREFIX,
        CandidateMatchKind.WUBI_EXACT,
        CandidateMatchKind.WUBI_COMPLETION,
        -> -4f
    }

    private data class QueryPlan(
        val query: String,
        val path: T9PinyinPath,
        val pathIndex: Int,
        val requiresBoundaryAwareProbe: Boolean,
    )

    private data class RankedCandidate(
        val candidate: Candidate,
        val path: T9PinyinPath,
        val pathIndex: Int,
        val candidateIndex: Int,
    )

    private val CANDIDATE_ORDER =
        compareByDescending<RankedCandidate> { it.candidate.score }
            .thenBy { it.path.structuralPenalty }
            .thenBy { it.pathIndex }
            .thenBy { it.candidateIndex }
            .thenBy { it.candidate.text }

    private val ALWAYS_CONTINUE: () -> Boolean = { true }
    private const val MAX_T9_PATHS = 32
    private const val INITIAL_QUERY_BEAM = 8
    private const val CONTINUATION_QUERY_STEP = 1
    private const val LONG_COMPOSITION_DIGITS = 7
    private const val NORMAL_COMPOSITION_DIGITS = 32
    private const val EXTREME_ENTRY_QUERY_BEAM = 8
    private const val EXTREME_QUERY_BEAM = 1
    private const val PROBE_CANDIDATE_LIMIT = 1
    private const val EXPANSION_QUERY_BEAM = 2
    private const val STRUCTURAL_FALLBACK_QUERY_BEAM = 2
    private const val MAX_CANDIDATES_PER_QUERY = 8
    private const val MIN_CANDIDATE_FILL = 16
    private const val INITIAL_SEGMENT_PENALTY = 0.22f
    private const val INCOMPLETE_SEGMENT_PENALTY = 0.7f
    private const val PATH_ORDER_PENALTY = 0.025f
    private const val CANDIDATE_ORDER_PENALTY = 0.002f
}
