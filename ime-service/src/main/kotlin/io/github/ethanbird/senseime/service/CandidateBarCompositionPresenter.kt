package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter

/**
 * Builds the explanatory spelling shown above the candidate list.
 *
 * This projection is deliberately owned by the candidate-bar boundary. Android's
 * editor composing span continues to receive [PinyinComposition.visibleText], so
 * inferred apostrophes never enter the reversible composition transaction. A
 * boundary is shown only after a ranked Chinese candidate supplies matching
 * canonical spelling metadata; pending and English-first states stay raw.
 */
internal object CandidateBarCompositionPresenter {
    fun text(
        composition: PinyinComposition,
        segmenter: PinyinSyllableSegmenter,
        decodingPending: Boolean,
        primaryCandidate: Candidate?,
    ): String {
        val remaining = composition.remainingPinyin
        if (remaining.isEmpty()) return composition.acceptedText
        val formattedRemaining = primaryCandidate
            ?.takeUnless { decodingPending }
            ?.takeIf(::hasChinesePinyinEvidence)
            ?.let { candidate ->
                segmenter.segmentMixed(
                    value = remaining,
                    canonicalPinyin = candidate.canonicalPinyin,
                    canonicalInitials = candidate.canonicalInitials,
                )
            }
            ?.takeIf { path -> path.rawCode == remaining }
            ?.formatted
            ?: remaining
        return composition.acceptedText + formattedRemaining
    }

    private fun hasChinesePinyinEvidence(candidate: Candidate): Boolean =
        candidate.matchKind !in ENGLISH_MATCH_KINDS &&
            !candidate.canonicalPinyin.isNullOrEmpty() &&
            !candidate.canonicalInitials.isNullOrEmpty()

    private val ENGLISH_MATCH_KINDS = setOf(
        CandidateMatchKind.ENGLISH_EXACT,
        CandidateMatchKind.ENGLISH_PREFIX,
    )
}
