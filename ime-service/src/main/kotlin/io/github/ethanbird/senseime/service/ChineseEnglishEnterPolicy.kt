package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind

internal data class ChineseEnglishEnterSelection(
    val candidate: Candidate,
    val candidateRank: Int,
)

/** Makes Enter and an explicit English candidate tap share the same commit/learning path. */
internal object ChineseEnglishEnterPolicy {
    fun select(rawInput: String, candidates: List<Candidate>): ChineseEnglishEnterSelection? {
        if (rawInput.length !in 1..32 || rawInput.any { it !in 'a'..'z' }) return null
        val exactIndex = candidates.indexOfFirst { candidate ->
            candidate.matchKind == CandidateMatchKind.ENGLISH_EXACT &&
                candidate.text.equals(rawInput, ignoreCase = true)
        }
        if (exactIndex >= 0) {
            return ChineseEnglishEnterSelection(candidates[exactIndex], exactIndex)
        }
        return ChineseEnglishEnterSelection(
            candidate = Candidate(
                text = rawInput,
                score = 0f,
                matchKind = CandidateMatchKind.ENGLISH_EXACT,
            ),
            candidateRank = 0,
        )
    }
}
