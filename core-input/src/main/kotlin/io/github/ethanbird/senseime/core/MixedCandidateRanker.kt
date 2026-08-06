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
        val queryLength = (english as? EnglishSuggestionBatch)?.queryLength
            ?: english.firstOrNull { it.matchKind == CandidateMatchKind.ENGLISH_EXACT }
                ?.text
                ?.length
        val boundedEnglish = boundShortEnglishCandidates(
            english = english,
            limit = limit,
            queryLength = queryLength,
            hasCanonicalExact = hasCanonicalExact,
            hasCanonicalComposition = hasCanonicalComposition,
        )
        // Rank a pool large enough to retain the bounded English evidence before deterministic
        // placement. Ranking directly to the visible limit could discard an exact word behind a
        // dense Chinese page before the "at most second" policy gets a chance to apply.
        val rankingLimit = if (limit > Int.MAX_VALUE - boundedEnglish.size) {
            Int.MAX_VALUE
        } else {
            limit + boundedEnglish.size
        }
        val ranked = CandidateRanker.rank(
            candidates = calibratedChinese + boundedEnglish,
            limit = rankingLimit,
            hasCanonicalExact = hasCanonicalExact,
            hasCanonicalComposition = hasCanonicalComposition,
        )
        val exactEnglish = boundedEnglish.firstOrNull {
            it.matchKind == CandidateMatchKind.ENGLISH_EXACT
        }
        val retained = if (
            exactEnglish != null && ranked.none { it.text == exactEnglish.text }
        ) {
            ranked + exactEnglish
        } else {
            ranked
        }
        return applyEnglishPlacementPolicy(retained, queryLength, limit)
    }

    /**
     * One Latin key is weak English-prefix evidence. Any concrete single-Han
     * pinyin candidate confirms a valid Chinese initial, so retain only a small
     * English doorway instead of letting corpus frequency decide the mode.
     * Exact English receives one additional doorway.
     */
    private fun boundShortEnglishCandidates(
        english: List<Candidate>,
        limit: Int,
        queryLength: Int?,
        hasCanonicalExact: Boolean,
        hasCanonicalComposition: Boolean,
    ): List<Candidate> {
        val budget = minOf(limit, when (queryLength) {
            1, 2 -> 1
            3 -> 2
            else -> return english
        })
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

    /**
     * Chinese mode owns the first slot. Broad one- and two-letter English recall is delayed behind
     * several Chinese choices, while a complete word of three or more letters gets one predictable
     * doorway at index one and never takes the Chinese head.
     */
    private fun applyEnglishPlacementPolicy(
        ranked: List<Candidate>,
        queryLength: Int?,
        limit: Int,
    ): List<Candidate> {
        if (queryLength == null || ranked.isEmpty()) return ranked
        val chinese = ranked.filterNot { it.isEnglish() }
        if (chinese.isEmpty()) return ranked
        val english = ranked.filter { it.isEnglish() }
        if (english.isEmpty()) return ranked

        if (queryLength <= 2) {
            val doorwayAfter = if (queryLength == 1) 4 else 3
            val insertion = minOf(doorwayAfter, chinese.size)
            return buildList(ranked.size) {
                addAll(chinese.take(insertion))
                addAll(english)
                addAll(chinese.drop(insertion))
            }.take(limit)
        }

        val exact = english.firstOrNull { it.matchKind == CandidateMatchKind.ENGLISH_EXACT }
        if (exact == null) {
            val insertion = minOf(2, chinese.size)
            return buildList(ranked.size) {
                addAll(chinese.take(insertion))
                addAll(english)
                addAll(chinese.drop(insertion))
            }.take(limit)
        }
        val firstChinese = chinese.first()
        return buildList(ranked.size) {
            add(firstChinese)
            add(exact)
            ranked.forEach { candidate ->
                if (candidate != firstChinese && candidate != exact) add(candidate)
            }
        }.take(limit)
    }

    private fun Candidate.isEnglish(): Boolean =
        matchKind == CandidateMatchKind.ENGLISH_EXACT ||
            matchKind == CandidateMatchKind.ENGLISH_PREFIX

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
}
