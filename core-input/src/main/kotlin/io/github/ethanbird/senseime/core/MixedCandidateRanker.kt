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
        val singleHanEvidence = chinese.filter { it.isSingleHanPinyinEvidence() }
        val singleHanPrior = singleHanModePrior(
            chinese = singleHanEvidence,
            english = english,
            hasCanonicalExact = hasCanonicalExact,
            hasCanonicalComposition = hasCanonicalComposition,
        )
        val calibratedChinese = chinese.map { candidate ->
            if (candidate.isSingleHanPinyinEvidence()) {
                candidate.copy(score = candidate.score + singleHanPrior)
            } else {
                candidate
            }
        }
        val boundedEnglish = boundBroadEnglishPrefixes(
            english = english,
            limit = limit,
            hasCanonicalExact = hasCanonicalExact,
            hasCanonicalComposition = hasCanonicalComposition,
            hasSingleHanPinyinEvidence = singleHanEvidence.isNotEmpty(),
        )
        return CandidateRanker.rank(
            candidates = calibratedChinese + boundedEnglish,
            limit = limit,
            hasCanonicalExact = hasCanonicalExact,
            hasCanonicalComposition = hasCanonicalComposition,
        )
    }

    /**
     * One Latin key is weak English-prefix evidence. Any concrete single-Han
     * pinyin candidate confirms a valid Chinese initial, so retain only a small
     * English doorway instead of letting corpus frequency decide the mode.
     * Exact English receives one additional doorway.
     */
    private fun boundBroadEnglishPrefixes(
        english: List<Candidate>,
        limit: Int,
        hasCanonicalExact: Boolean,
        hasCanonicalComposition: Boolean,
        hasSingleHanPinyinEvidence: Boolean,
    ): List<Candidate> {
        val queryLength = (english as? EnglishSuggestionBatch)?.queryLength ?: return english
        if (queryLength != 1 || !hasSingleHanPinyinEvidence) return english

        val hasEnglishExact =
            english.any { it.matchKind == CandidateMatchKind.ENGLISH_EXACT }
        val budget = minOf(
            limit,
            if (hasEnglishExact) {
                STRONG_PINYIN_ENGLISH_EXACT_BUDGET
            } else {
                STRONG_PINYIN_ENGLISH_PREFIX_BUDGET
            },
        )
        if (english.size <= budget) return english
        return english
            .sortedWith(
                compareByDescending<Candidate> {
                    it.score + CandidateRanker.sourcePrior(
                        it.matchKind,
                        hasCanonicalExact,
                        hasCanonicalComposition,
                    )
                }.thenBy { it.text },
            )
            .take(budget)
    }

    private fun singleHanModePrior(
        chinese: List<Candidate>,
        english: List<Candidate>,
        hasCanonicalExact: Boolean,
        hasCanonicalComposition: Boolean,
    ): Float {
        val queryLength = (english as? EnglishSuggestionBatch)?.queryLength ?: return 0f
        if (queryLength != 1 || chinese.isEmpty()) return 0f
        val bestChineseTotal = chinese.maxOf { candidate ->
            candidate.score + CandidateRanker.sourcePrior(
                candidate.matchKind,
                hasCanonicalExact,
                hasCanonicalComposition,
            )
        }
        val bestEnglishTotal = english.maxOfOrNull { candidate ->
            candidate.score + CandidateRanker.sourcePrior(
                candidate.matchKind,
                hasCanonicalExact,
                hasCanonicalComposition,
            )
        } ?: return SINGLE_HAN_PREFIX_BILINGUAL_PRIOR
        return maxOf(
            SINGLE_HAN_PREFIX_BILINGUAL_PRIOR,
            bestEnglishTotal - bestChineseTotal + SINGLE_HAN_HEAD_MARGIN,
        )
    }

    /**
     * A single pinyin initial is an explicit Chinese-mode signal, while English
     * single-letter input is only a broad completion prefix. Calibrate that
     * evidence in score space instead of reserving a source slot.
     */
    private fun Candidate.isSingleHanPinyinEvidence(): Boolean =
            canonicalInitials?.length == 1 &&
            isSingleHanChineseEvidence()

    private fun Candidate.isSingleHanChineseEvidence(): Boolean =
        matchKind != CandidateMatchKind.ENGLISH_EXACT &&
            matchKind != CandidateMatchKind.ENGLISH_PREFIX &&
            text.codePointCount(0, text.length) == 1 &&
            Character.UnicodeScript.of(text.codePointAt(0)) == Character.UnicodeScript.HAN

    private const val SINGLE_HAN_PREFIX_BILINGUAL_PRIOR = 4.5f
    private const val SINGLE_HAN_HEAD_MARGIN = 0.25f
    private const val STRONG_PINYIN_ENGLISH_PREFIX_BUDGET = 2
    private const val STRONG_PINYIN_ENGLISH_EXACT_BUDGET = 3
}
