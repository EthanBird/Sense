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

            // Keep the sentence-level primary first for Space and discoverability,
            // then expose first-syllable choices before the long whole-candidate tail.
            decoding.wholeCandidates.firstOrNull()?.let { candidate ->
                choices += ProgressiveCandidateChoice.Whole(candidate)
                displayedTexts += candidate.text
            }
            decoding.prefixCandidates.forEach { prefix ->
                if (choices.size < limit && displayedTexts.add(prefix.candidate.text)) {
                    choices += ProgressiveCandidateChoice.Prefix(prefix)
                }
            }
            decoding.wholeCandidates.drop(1).forEach { candidate ->
                if (choices.size < limit && displayedTexts.add(candidate.text)) {
                    choices += ProgressiveCandidateChoice.Whole(candidate)
                }
            }
            return ProgressiveCandidateSnapshot(
                snapshot = CandidateSnapshot(decoding.revision, choices.map { it.candidate }),
                choices = choices,
            )
        }
    }
}
