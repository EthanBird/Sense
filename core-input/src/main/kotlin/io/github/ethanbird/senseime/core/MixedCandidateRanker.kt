package io.github.ethanbird.senseime.core

/**
 * Merges Chinese and English recall in the same calibrated score domain.
 *
 * Exact English is no longer assigned a magic fifth slot, and a fixed number
 * of English completions no longer jumps ahead of weak Chinese results.
 */
object MixedCandidateRanker {
    fun merge(
        chinese: List<Candidate>,
        english: List<Candidate>,
        limit: Int,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        if (english.isEmpty()) {
            return if (chinese.size <= limit) chinese else chinese.take(limit)
        }
        val hasCanonicalExact = chinese.any { it.matchKind == CandidateMatchKind.BASE_EXACT }
        val hasCanonicalComposition = !hasCanonicalExact &&
            chinese.any { it.matchKind == CandidateMatchKind.BASE_COMPOSED }
        val calibratedChinese = chinese.map { candidate ->
            if (candidate.isSingleHanPinyinPrefix()) {
                candidate.copy(score = candidate.score + SINGLE_HAN_PREFIX_BILINGUAL_PRIOR)
            } else {
                candidate
            }
        }
        return CandidateRanker.rank(
            candidates = calibratedChinese + english,
            limit = limit,
            hasCanonicalExact = hasCanonicalExact,
            hasCanonicalComposition = hasCanonicalComposition,
        )
    }

    /**
     * A single pinyin initial is an explicit Chinese-mode signal, while English
     * single-letter input is only a broad completion prefix. Calibrate that
     * evidence in score space instead of reserving a source slot.
     */
    private fun Candidate.isSingleHanPinyinPrefix(): Boolean =
        matchKind == CandidateMatchKind.BASE_PREFIX &&
            canonicalInitials?.length == 1 &&
            text.codePointCount(0, text.length) == 1 &&
            Character.UnicodeScript.of(text.codePointAt(0)) == Character.UnicodeScript.HAN

    private const val SINGLE_HAN_PREFIX_BILINGUAL_PRIOR = 4.5f
}
