package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
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

            // A small prefix sample keeps progressive character selection close
            // to the strip head. The complete whole-candidate ranking follows
            // before the bulk prefix tail, so hundreds of first-syllable
            // characters cannot push a valid whole phrase several pages away.
            val wholeHeadSize = if (
                decoding.wholeCandidates.firstOrNull()?.matchKind in ENGLISH_MATCH_KINDS
            ) {
                ENGLISH_WHOLE_CANDIDATE_HEAD_SIZE
            } else {
                WHOLE_CANDIDATE_HEAD_SIZE
            }
            val orderedPrefixes = prioritizePrefixPaths(decoding.prefixCandidates)
            val presentedPrefixes = if (decoding.hasExactSingleHanSyllable()) {
                orderedPrefixes.take(EXACT_SINGLE_SYLLABLE_PREFIX_LIMIT)
            } else {
                orderedPrefixes
            }
            decoding.wholeCandidates.take(wholeHeadSize).forEach { candidate ->
                add(ProgressiveCandidateChoice.Whole(candidate))
            }
            presentedPrefixes.take(REPRESENTATIVE_PREFIX_COUNT).forEach { prefix ->
                add(ProgressiveCandidateChoice.Prefix(prefix))
            }
            decoding.wholeCandidates.drop(wholeHeadSize).forEach { candidate ->
                add(ProgressiveCandidateChoice.Whole(candidate))
            }
            presentedPrefixes.drop(REPRESENTATIVE_PREFIX_COUNT).forEach { prefix ->
                add(ProgressiveCandidateChoice.Prefix(prefix))
            }
            return ProgressiveCandidateSnapshot(
                snapshot = CandidateSnapshot(decoding.revision, choices.map { it.candidate }),
                choices = choices,
            )
        }

        /**
         * Reserves one early choice for every distinct consumed-pinyin path,
         * then fills the representative block in decoder rank order.
         */
        private fun prioritizePrefixPaths(
            prefixes: List<PinyinPrefixCandidate>,
        ): List<PinyinPrefixCandidate> {
            if (prefixes.size <= 1) return prefixes
            val selected = BooleanArray(prefixes.size)
            val consumedPaths = HashSet<String>()
            val result = ArrayList<PinyinPrefixCandidate>(prefixes.size)
            prefixes.forEachIndexed { index, prefix ->
                if (
                    result.size < REPRESENTATIVE_PREFIX_COUNT &&
                    consumedPaths.add(prefix.consumedPinyin)
                ) {
                    selected[index] = true
                    result += prefix
                }
            }
            prefixes.forEachIndexed { index, prefix ->
                if (
                    result.size < REPRESENTATIVE_PREFIX_COUNT &&
                    !selected[index]
                ) {
                    selected[index] = true
                    result += prefix
                }
            }
            prefixes.forEachIndexed { index, prefix ->
                if (!selected[index]) result += prefix
            }
            return result
        }

        private fun ProgressivePinyinDecoding.hasExactSingleHanSyllable(): Boolean =
            wholeCandidates.any { candidate ->
                candidate.matchKind in CHINESE_EXACT_MATCH_KINDS &&
                    candidate.canonicalPinyin == remainingPinyin &&
                    candidate.text.codePointCount(0, candidate.text.length) == 1 &&
                    Character.UnicodeScript.of(candidate.text.codePointAt(0)) ==
                    Character.UnicodeScript.HAN
            }

        private const val WHOLE_CANDIDATE_HEAD_SIZE = 12
        private const val ENGLISH_WHOLE_CANDIDATE_HEAD_SIZE = 3
        private const val REPRESENTATIVE_PREFIX_COUNT = 4
        private const val EXACT_SINGLE_SYLLABLE_PREFIX_LIMIT = 4
        private val ENGLISH_MATCH_KINDS = setOf(
            CandidateMatchKind.ENGLISH_EXACT,
            CandidateMatchKind.ENGLISH_PREFIX,
        )
        private val CHINESE_EXACT_MATCH_KINDS = setOf(
            CandidateMatchKind.BASE_EXACT,
            CandidateMatchKind.USER_FULL,
        )
    }
}
