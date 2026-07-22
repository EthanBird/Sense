package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateSnapshot
import io.github.ethanbird.senseime.core.PinyinPrefixCandidate
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding

internal sealed interface ProgressiveCandidateChoice {
    val candidate: Candidate

    data class Whole(override val candidate: Candidate) : ProgressiveCandidateChoice

    data class Prefix(val value: PinyinPrefixCandidate) : ProgressiveCandidateChoice {
        override val candidate: Candidate
            get() = value.candidate
    }
}

/** Atomically binds displayed progressive choices to the core revision snapshot. */
internal class ProgressiveCandidateSnapshot private constructor(
    private val snapshot: CandidateSnapshot,
    private val choices: List<ProgressiveCandidateChoice>,
) {
    val revision: Long
        get() = snapshot.revision

    val candidates: List<Candidate>
        get() = snapshot.candidates

    fun select(
        currentRevision: Long,
        requestedRevision: Long,
        sourceIndex: Int,
    ): ProgressiveCandidateChoice? {
        val candidate = snapshot.select(currentRevision, requestedRevision, sourceIndex) ?: return null
        return choices.getOrNull(sourceIndex)?.takeIf { it.candidate == candidate }
    }

    companion object {
        val EMPTY = ProgressiveCandidateSnapshot(CandidateSnapshot.EMPTY, emptyList())

        fun from(decoding: ProgressivePinyinDecoding, limit: Int): ProgressiveCandidateSnapshot {
            if (limit <= 0) {
                return ProgressiveCandidateSnapshot(
                    CandidateSnapshot(decoding.revision, emptyList()),
                    emptyList(),
                )
            }
            val choices = ArrayList<ProgressiveCandidateChoice>(limit)
            val displayedTexts = HashSet<String>()

            // Full-pinyin phrases must not be displaced by the much larger set of
            // first-syllable characters. Keep a useful whole-candidate head, then
            // expose segmentation choices, followed by every remaining whole
            // candidate that fits in the caller's bounded presentation budget.
            decoding.wholeCandidates.take(WHOLE_CANDIDATE_HEAD_SIZE).forEach { candidate ->
                if (choices.size < limit && displayedTexts.add(candidate.text)) {
                    choices += ProgressiveCandidateChoice.Whole(candidate)
                }
            }
            decoding.prefixCandidates.forEach { prefix ->
                if (choices.size < limit && displayedTexts.add(prefix.candidate.text)) {
                    choices += ProgressiveCandidateChoice.Prefix(prefix)
                }
            }
            decoding.wholeCandidates.drop(WHOLE_CANDIDATE_HEAD_SIZE).forEach { candidate ->
                if (choices.size < limit && displayedTexts.add(candidate.text)) {
                    choices += ProgressiveCandidateChoice.Whole(candidate)
                }
            }
            return ProgressiveCandidateSnapshot(
                snapshot = CandidateSnapshot(decoding.revision, choices.map { it.candidate }),
                choices = choices,
            )
        }

        private const val WHOLE_CANDIDATE_HEAD_SIZE = 12
    }
}
