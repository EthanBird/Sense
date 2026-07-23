package io.github.ethanbird.senseime.core

/** Deterministic source interleaving for Chinese-mode bilingual candidates. */
object MixedCandidateRanker {
    fun merge(
        chinese: List<Candidate>,
        english: List<Candidate>,
        limit: Int,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        if (english.isEmpty()) return chinese.take(limit)

        val result = ArrayList<Candidate>(limit)
        val seen = HashSet<String>()
        fun add(candidate: Candidate) {
            if (result.size < limit && seen.add(candidate.text)) result += candidate
        }

        val hasStrongChinese = chinese.any { it.matchKind.isStrongChineseMatch() }
        val exactEnglish = english.firstOrNull {
            it.matchKind == CandidateMatchKind.ENGLISH_EXACT
        }
        if (hasStrongChinese) {
            chinese.take(STRONG_CHINESE_HEAD).forEach(::add)
            exactEnglish?.let(::add)
            chinese.drop(STRONG_CHINESE_HEAD).forEach(::add)
            english.forEach(::add)
        } else if (exactEnglish != null) {
            english.take(ENGLISH_HEAD).forEach(::add)
            chinese.forEach(::add)
            english.drop(ENGLISH_HEAD).forEach(::add)
        } else {
            // A partial English completion must not displace the primary
            // Chinese one-key experience (`w -> 我`, `d -> 的`).
            chinese.forEach(::add)
            english.forEach(::add)
        }
        return result
    }

    private fun CandidateMatchKind.isStrongChineseMatch(): Boolean = when (this) {
        CandidateMatchKind.BASE_EXACT,
        CandidateMatchKind.BASE_COMPOSED,
        CandidateMatchKind.BASE_HYBRID,
        CandidateMatchKind.BASE_INITIALS,
        CandidateMatchKind.USER_FULL,
        CandidateMatchKind.USER_INITIALS,
        -> true

        CandidateMatchKind.BASE_PREFIX,
        CandidateMatchKind.CORRECTED,
        CandidateMatchKind.ENGLISH_EXACT,
        CandidateMatchKind.ENGLISH_PREFIX,
        -> false
    }

    private const val STRONG_CHINESE_HEAD = 4
    private const val ENGLISH_HEAD = 3
}
