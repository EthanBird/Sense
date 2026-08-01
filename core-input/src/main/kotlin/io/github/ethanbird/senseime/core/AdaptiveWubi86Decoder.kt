package io.github.ethanbird.senseime.core

/**
 * Applies Wubi-only user evidence to candidates that still exist in the read-only base lexicon.
 * Exact candidates remain structurally ahead of completions, including after personalization.
 */
class Wubi86CandidatePersonalizer(
    private val userLexicon: WubiUserLexicon,
) {
    fun rank(prefix: String, candidates: List<Candidate>, limit: Int): List<Candidate> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val normalizedPrefix = normalizeWubiCode(prefix)
            ?: return candidates.takeDistinct(limit)
        val learnedByKey = userLexicon.lookup(normalizedPrefix, MAX_PERSONALIZATION_MATCHES)
            .associateBy { PreferenceKey(it.canonicalCode, it.text) }
        if (learnedByKey.isEmpty()) return candidates.takeDistinct(limit)

        val groupTopScores = FloatArray(GROUP_COUNT) { Float.NEGATIVE_INFINITY }
        candidates.forEach { candidate ->
            if (candidate.text.isEmpty() || !candidate.score.isFinite()) return@forEach
            val group = structuralGroup(candidate)
            groupTopScores[group] = maxOf(groupTopScores[group], candidate.score)
        }
        val bestByText = LinkedHashMap<String, RankedWubiCandidate>()
        candidates.forEachIndexed { index, candidate ->
            if (candidate.text.isEmpty() || !candidate.score.isFinite()) return@forEachIndexed
            val canonicalCode = normalizeWubiCode(candidate.canonicalCode.orEmpty())
            val learned = canonicalCode
                ?.takeIf { it.startsWith(normalizedPrefix) }
                ?.let { learnedByKey[PreferenceKey(it, candidate.text)] }
            val group = structuralGroup(candidate)
            val calibratedBase = (
                candidate.score - groupTopScores[group]
                ).coerceAtLeast(-MAX_STATIC_SCORE_GAP)
            val ranked = RankedWubiCandidate(
                candidate = candidate,
                structuralGroup = group,
                effectiveScore = calibratedBase + (learned?.rankingBoost ?: 0f),
                sourceIndex = index,
            )
            val previous = bestByText[candidate.text]
            if (previous == null || ORDER.compare(ranked, previous) < 0) {
                bestByText[candidate.text] = ranked
            }
        }
        return bestByText.values
            .sortedWith(ORDER)
            .take(limit)
            .map(RankedWubiCandidate::candidate)
    }

    fun recordSelection(
        prefix: String,
        candidate: Candidate,
        evidence: WubiLearningEvidence,
    ): LearnedWubiCandidate? {
        val key = candidate.preferenceKey(prefix) ?: return null
        return userLexicon.record(key.canonicalCode, key.text, evidence)
    }

    fun demote(
        prefix: String,
        candidate: Candidate,
        feedback: WubiNegativeFeedback = WubiNegativeFeedback.MANUAL_DEMOTION,
    ): LearnedWubiCandidate? {
        val key = candidate.preferenceKey(prefix) ?: return null
        return userLexicon.demote(key.canonicalCode, key.text, feedback)
    }

    fun forget(prefix: String, candidate: Candidate): Boolean {
        val key = candidate.preferenceKey(prefix) ?: return false
        return userLexicon.forget(key.canonicalCode, key.text)
    }

    private fun Candidate.preferenceKey(prefix: String): PreferenceKey? {
        if (matchKind != CandidateMatchKind.WUBI_EXACT && matchKind != CandidateMatchKind.WUBI_COMPLETION) {
            return null
        }
        if (text.isEmpty()) return null
        val normalizedPrefix = normalizeWubiCode(prefix) ?: return null
        val canonical = normalizeWubiCode(
            canonicalCode ?: if (matchKind == CandidateMatchKind.WUBI_EXACT) normalizedPrefix else return null,
        ) ?: return null
        if (!canonical.startsWith(normalizedPrefix)) return null
        return PreferenceKey(canonical, text)
    }

    private fun structuralGroup(candidate: Candidate): Int = when (candidate.matchKind) {
        CandidateMatchKind.WUBI_EXACT -> EXACT_GROUP
        CandidateMatchKind.WUBI_COMPLETION -> COMPLETION_GROUP
        else -> OTHER_GROUP
    }

    private data class PreferenceKey(val canonicalCode: String, val text: String)

    private data class RankedWubiCandidate(
        val candidate: Candidate,
        val structuralGroup: Int,
        val effectiveScore: Float,
        val sourceIndex: Int,
    )

    private companion object {
        const val EXACT_GROUP = 0
        const val COMPLETION_GROUP = 1
        const val OTHER_GROUP = 2
        const val GROUP_COUNT = 3
        const val MAX_PERSONALIZATION_MATCHES = 128

        /**
         * Production weights can span the entire unsigned-32-bit domain. Compressing each
         * structural group's prior to two log-score points lets one deliberate selection matter,
         * while preserving the base order for candidates without evidence.
         */
        const val MAX_STATIC_SCORE_GAP = 2f

        val ORDER = compareBy<RankedWubiCandidate> { it.structuralGroup }
            .thenByDescending { it.effectiveScore }
            .thenBy { it.sourceIndex }
            .thenBy { it.candidate.text }
    }
}

/** A drop-in [InputDecoder] wrapper; persistence remains owned by the injected user lexicon. */
class AdaptiveWubi86Decoder(
    private val baseDecoder: InputDecoder,
    private val personalizer: Wubi86CandidatePersonalizer,
    private val preferredCandidatePoolSize: Int = DEFAULT_CANDIDATE_POOL_SIZE,
) : RankedCandidateDecoder {
    constructor(
        baseDecoder: InputDecoder,
        userLexicon: WubiUserLexicon,
        preferredCandidatePoolSize: Int = DEFAULT_CANDIDATE_POOL_SIZE,
    ) : this(
        baseDecoder,
        Wubi86CandidatePersonalizer(userLexicon),
        preferredCandidatePoolSize,
    )

    init {
        require(preferredCandidatePoolSize > 0)
    }

    override fun decode(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val expandedLimit = when {
            limit >= preferredCandidatePoolSize -> limit
            limit > Int.MAX_VALUE / CANDIDATE_POOL_MULTIPLIER -> preferredCandidatePoolSize
            else -> maxOf(
                limit,
                minOf(
                    preferredCandidatePoolSize,
                    maxOf(MINIMUM_CANDIDATE_POOL_SIZE, limit * CANDIDATE_POOL_MULTIPLIER),
                ),
            )
        }
        return personalizer.rank(
            prefix = composing,
            candidates = baseDecoder.decode(composing, expandedLimit),
            limit = limit,
        )
    }

    fun learn(
        composing: String,
        candidate: Candidate,
        evidence: WubiLearningEvidence = WubiLearningEvidence.EXPLICIT_SELECTION,
    ): LearnedWubiCandidate? = personalizer.recordSelection(composing, candidate, evidence)

    fun demote(
        composing: String,
        candidate: Candidate,
        feedback: WubiNegativeFeedback = WubiNegativeFeedback.MANUAL_DEMOTION,
    ): LearnedWubiCandidate? = personalizer.demote(composing, candidate, feedback)

    fun forget(composing: String, candidate: Candidate): Boolean =
        personalizer.forget(composing, candidate)

    private companion object {
        const val DEFAULT_CANDIDATE_POOL_SIZE = 128
        const val MINIMUM_CANDIDATE_POOL_SIZE = 16
        const val CANDIDATE_POOL_MULTIPLIER = 4
    }
}

private fun List<Candidate>.takeDistinct(limit: Int): List<Candidate> {
    if (limit <= 0) return emptyList()
    val seen = HashSet<String>(minOf(size, limit) * 2 + 1)
    val result = ArrayList<Candidate>(minOf(size, limit))
    forEach { candidate ->
        if (
            result.size < limit &&
            candidate.text.isNotEmpty() &&
            candidate.score.isFinite() &&
            seen.add(candidate.text)
        ) {
            result += candidate
        }
    }
    return result
}
