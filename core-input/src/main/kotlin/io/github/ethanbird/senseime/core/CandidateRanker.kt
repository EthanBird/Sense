package io.github.ethanbird.senseime.core

/**
 * One deterministic score domain for candidates produced by every decoder path.
 *
 * Source priors preserve typed-evidence strength without assigning any source
 * an unreachable fixed slot. The Frost production pack and English popularity
 * list both use log-like scores, so canonical evidence needs only a bounded
 * prior rather than the former cross-domain offset.
 */
object CandidateRanker {
    fun rank(
        candidates: Iterable<Candidate>,
        limit: Int,
        hasCanonicalExact: Boolean,
        hasCanonicalComposition: Boolean = false,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        val bestByText = LinkedHashMap<String, ScoredCandidate>()
        val retainedCapacity = scaledCapacity(
            limit,
            RETAINED_LIMIT_MULTIPLIER,
            MIN_RETAINED_CANDIDATES,
        )
        val pruneThreshold = scaledCapacity(
            limit,
            PRUNE_LIMIT_MULTIPLIER,
            MIN_PRUNE_THRESHOLD,
        )
        candidates.forEach { candidate ->
            if (candidate.text.isEmpty() || !candidate.score.isFinite()) return@forEach
            val scored = ScoredCandidate(
                candidate = candidate,
                total = candidate.score + sourcePrior(
                    candidate.matchKind,
                    hasCanonicalExact,
                    hasCanonicalComposition,
                ),
            )
            val previous = bestByText[candidate.text]
            if (previous == null) {
                bestByText[candidate.text] = scored
            } else {
                val best = if (ORDER.compare(scored, previous) < 0) scored else previous
                val metadataSource = sequenceOf(previous.candidate, scored.candidate).firstOrNull {
                    it.canonicalPinyin != null
                }
                bestByText[candidate.text] = if (
                    best.candidate.canonicalPinyin == null &&
                    metadataSource?.canonicalPinyin != null
                ) {
                    best.copy(
                        candidate = best.candidate.copy(
                            canonicalPinyin = metadataSource.canonicalPinyin,
                            canonicalInitials = best.candidate.canonicalInitials
                                ?: metadataSource.canonicalInitials,
                        ),
                    )
                } else {
                    best
                }
            }
            if (bestByText.size > pruneThreshold) {
                retainBest(bestByText, retainedCapacity)
            }
        }
        return bestByText.values
            .sortedWith(ORDER)
            .take(limit)
            .map(ScoredCandidate::candidate)
    }

    /**
     * Bounds transient memory without changing top-K semantics.
     *
     * Scores only increase when a later duplicate arrives; a pruned text can
     * therefore re-enter if that stronger duplicate appears later.
     */
    private fun retainBest(
        candidates: LinkedHashMap<String, ScoredCandidate>,
        capacity: Int,
    ) {
        val retained = candidates.values.sortedWith(ORDER).take(capacity)
        candidates.clear()
        retained.forEach { scored -> candidates[scored.candidate.text] = scored }
    }

    private fun scaledCapacity(limit: Int, multiplier: Int, floor: Int): Int =
        maxOf(
            floor,
            if (limit > Int.MAX_VALUE / multiplier) Int.MAX_VALUE else limit * multiplier,
        )

    fun sourcePrior(
        kind: CandidateMatchKind,
        hasCanonicalExact: Boolean,
        hasCanonicalComposition: Boolean = false,
    ): Float = when (kind) {
        CandidateMatchKind.BASE_EXACT -> if (hasCanonicalExact) CANONICAL_EXACT_PRIOR else EXACT_PRIOR
        CandidateMatchKind.BASE_COMPOSED ->
            if (!hasCanonicalExact && hasCanonicalComposition) CANONICAL_COMPOSITION_PRIOR else COMPOSED_PRIOR
        CandidateMatchKind.BASE_HYBRID -> HYBRID_PRIOR
        CandidateMatchKind.BASE_INITIALS -> INITIALS_PRIOR
        CandidateMatchKind.BASE_PREFIX -> PREFIX_PRIOR
        CandidateMatchKind.CORRECTED -> when {
            hasCanonicalExact -> CORRECTION_WITH_EXACT_PRIOR
            hasCanonicalComposition -> CORRECTION_WITH_COMPOSITION_PRIOR
            else -> CORRECTION_PRIOR
        }
        CandidateMatchKind.ENGLISH_EXACT -> ENGLISH_EXACT_PRIOR
        CandidateMatchKind.ENGLISH_PREFIX -> ENGLISH_PREFIX_PRIOR
        CandidateMatchKind.WUBI_EXACT -> CANONICAL_EXACT_PRIOR
        CandidateMatchKind.WUBI_COMPLETION -> PREFIX_PRIOR
        CandidateMatchKind.USER_FULL,
        CandidateMatchKind.USER_INITIALS,
        -> USER_PRIOR
    }

    private data class ScoredCandidate(
        val candidate: Candidate,
        val total: Float,
    )

    private val ORDER =
        compareByDescending<ScoredCandidate> { it.total }
            .thenBy { tiePriority(it.candidate.matchKind) }
            .thenBy { it.candidate.text.codePointCount(0, it.candidate.text.length) }
            .thenBy { it.candidate.text }

    private fun tiePriority(kind: CandidateMatchKind): Int = when (kind) {
        CandidateMatchKind.BASE_EXACT -> 0
        CandidateMatchKind.BASE_COMPOSED -> 1
        CandidateMatchKind.USER_FULL -> 2
        CandidateMatchKind.USER_INITIALS -> 3
        CandidateMatchKind.BASE_HYBRID -> 4
        CandidateMatchKind.BASE_INITIALS -> 5
        CandidateMatchKind.CORRECTED -> 6
        CandidateMatchKind.BASE_PREFIX -> 7
        CandidateMatchKind.ENGLISH_EXACT -> 8
        CandidateMatchKind.ENGLISH_PREFIX -> 9
        CandidateMatchKind.WUBI_EXACT -> 0
        CandidateMatchKind.WUBI_COMPLETION -> 7
    }

    private const val CANONICAL_EXACT_PRIOR = 8f
    private const val CANONICAL_COMPOSITION_PRIOR = 6f
    private const val EXACT_PRIOR = 1.2f
    private const val COMPOSED_PRIOR = 0.55f
    private const val USER_PRIOR = 0.45f
    private const val HYBRID_PRIOR = 0.55f
    private const val INITIALS_PRIOR = 0.1f
    private const val CORRECTION_PRIOR = -0.25f
    private const val CORRECTION_WITH_EXACT_PRIOR = -13f
    private const val CORRECTION_WITH_COMPOSITION_PRIOR = -8f
    private const val PREFIX_PRIOR = -0.75f
    private const val ENGLISH_EXACT_PRIOR = 0.4f
    private const val ENGLISH_PREFIX_PRIOR = -0.9f
    private const val RETAINED_LIMIT_MULTIPLIER = 3
    private const val PRUNE_LIMIT_MULTIPLIER = 4
    private const val MIN_RETAINED_CANDIDATES = 48
    private const val MIN_PRUNE_THRESHOLD = 64
}
