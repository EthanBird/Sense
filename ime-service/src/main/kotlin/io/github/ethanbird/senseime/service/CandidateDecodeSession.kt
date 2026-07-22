package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding

/** One immutable UI/decode state, owned and mutated only by the IME main thread. */
internal data class CandidatePresentation(
    val composition: PinyinComposition,
    val snapshot: ProgressiveCandidateSnapshot,
    val decoding: ProgressivePinyinDecoding?,
    val pending: Boolean,
)

internal data class CandidateDecodeLaunch(
    val presentation: CandidatePresentation,
    val shouldDecode: Boolean,
    val stateChanged: Boolean,
)

/**
 * Atomically binds an asynchronous decode result to its complete composition.
 *
 * Equality checks include accepted segments, pending pinyin, and revision. This
 * prevents an older worker/Handler result from replacing the candidates for a
 * newer key event. Re-rendering an unchanged input view retains the ready state
 * instead of blanking the candidate strip and launching duplicate work.
 */
internal class CandidateDecodeSession {
    var current = CandidatePresentation(
        composition = PinyinComposition(),
        snapshot = ProgressiveCandidateSnapshot.EMPTY,
        decoding = null,
        pending = false,
    )
        private set

    fun begin(composition: PinyinComposition): CandidateDecodeLaunch {
        if (composition == current.composition) {
            return CandidateDecodeLaunch(current, shouldDecode = false, stateChanged = false)
        }
        val shouldDecode = composition.remainingPinyin.isNotEmpty()
        current = CandidatePresentation(
            composition = composition,
            snapshot = ProgressiveCandidateSnapshot.EMPTY,
            decoding = null,
            pending = shouldDecode,
        )
        return CandidateDecodeLaunch(current, shouldDecode, stateChanged = true)
    }

    fun complete(
        requestedComposition: PinyinComposition,
        decoding: ProgressivePinyinDecoding,
        limit: Int,
    ): CandidatePresentation? {
        if (
            requestedComposition != current.composition ||
            decoding.revision != requestedComposition.revision ||
            decoding.remainingPinyin != requestedComposition.remainingPinyin
        ) {
            return null
        }
        current = CandidatePresentation(
            composition = requestedComposition,
            snapshot = ProgressiveCandidateSnapshot.from(decoding, limit),
            decoding = decoding,
            pending = false,
        )
        return current
    }

    fun currentDecoding(composition: PinyinComposition): ProgressivePinyinDecoding? =
        current.decoding?.takeIf {
            current.composition == composition &&
                it.revision == composition.revision &&
                it.remainingPinyin == composition.remainingPinyin
        }

    fun select(
        composition: PinyinComposition,
        requestedRevision: Long,
        sourceIndex: Int,
    ): ProgressiveCandidateChoice? {
        if (current.composition != composition) return null
        return current.snapshot.select(composition.revision, requestedRevision, sourceIndex)
    }
}
