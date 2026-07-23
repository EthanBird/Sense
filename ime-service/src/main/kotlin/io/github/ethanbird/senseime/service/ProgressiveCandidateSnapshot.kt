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
            val displayedChoices = HashSet<String>()
            fun add(choice: ProgressiveCandidateChoice) {
                val identity = when (choice) {
                    is ProgressiveCandidateChoice.Whole -> "W\u0000${choice.candidate.text}"
                    is ProgressiveCandidateChoice.Prefix -> {
                        "P\u0000${choice.candidate.text}\u0000${choice.value.consumedPinyin}\u0000${choice.value.remainingPinyin}"
                    }
                }
                if (choices.size < limit && displayedChoices.add(identity)) choices += choice
            }

            // Full-pinyin phrases must not be displaced by the much larger set of
            // first-syllable characters. Keep a useful whole-candidate head, then
            // expose segmentation choices, followed by every remaining whole
            // candidate that fits in the caller's bounded presentation budget.
            val wholeHeadSize = if (
                decoding.wholeCandidates.firstOrNull()?.matchKind in ENGLISH_MATCH_KINDS
            ) {
                ENGLISH_WHOLE_CANDIDATE_HEAD_SIZE
            } else {
                WHOLE_CANDIDATE_HEAD_SIZE
            }
            decoding.wholeCandidates.take(wholeHeadSize).forEach { candidate ->
                add(ProgressiveCandidateChoice.Whole(candidate))
            }
            decoding.prefixCandidates.forEach { prefix ->
                add(ProgressiveCandidateChoice.Prefix(prefix))
            }
            decoding.wholeCandidates.drop(wholeHeadSize).forEach { candidate ->
                add(ProgressiveCandidateChoice.Whole(candidate))
            }
            return ProgressiveCandidateSnapshot(
                snapshot = CandidateSnapshot(decoding.revision, choices.map { it.candidate }),
                choices = choices,
            )
        }

        private const val WHOLE_CANDIDATE_HEAD_SIZE = 12
        private const val ENGLISH_WHOLE_CANDIDATE_HEAD_SIZE = 3
        private val ENGLISH_MATCH_KINDS = setOf(
            io.github.ethanbird.senseime.core.CandidateMatchKind.ENGLISH_EXACT,
            io.github.ethanbird.senseime.core.CandidateMatchKind.ENGLISH_PREFIX,
        )
    }
}
