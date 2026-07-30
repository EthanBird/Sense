package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding

/** One immutable UI/decode state, owned and mutated only by the IME main thread. */
internal data class CandidatePresentation(
    val composition: PinyinComposition,
    val decoderGeneration: Long,
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
 * Equality checks include accepted segments, pending pinyin, revision, and the
 * immutable decoder-publication generation. This prevents an older
 * worker/Handler result from replacing candidates for either a newer key event
 * or a hot-swapped decoder. Re-rendering an unchanged input view retains the
 * ready state instead of blanking the candidate strip and launching duplicate
 * work.
 */
internal class CandidateDecodeSession {
    var current = CandidatePresentation(
        composition = PinyinComposition(),
        decoderGeneration = 0L,
        snapshot = ProgressiveCandidateSnapshot.EMPTY,
        decoding = null,
        pending = false,
    )
        private set

    fun begin(
        composition: PinyinComposition,
        decoderGeneration: Long = 0L,
        forceDecode: Boolean = false,
    ): CandidateDecodeLaunch {
        if (
            !forceDecode &&
            composition == current.composition &&
            decoderGeneration == current.decoderGeneration
        ) {
            return CandidateDecodeLaunch(current, shouldDecode = false, stateChanged = false)
        }
        val shouldDecode = composition.remainingPinyin.isNotEmpty()
        val retainedVisualSnapshot = if (shouldDecode) {
            current.snapshot
        } else {
            ProgressiveCandidateSnapshot.EMPTY
        }
        current = CandidatePresentation(
            composition = composition,
            decoderGeneration = decoderGeneration,
            snapshot = retainedVisualSnapshot,
            decoding = null,
            pending = shouldDecode,
        )
        return CandidateDecodeLaunch(current, shouldDecode, stateChanged = true)
    }

    fun complete(
        requestedComposition: PinyinComposition,
        decoding: ProgressivePinyinDecoding,
        limit: Int,
        decoderGeneration: Long = 0L,
    ): CandidatePresentation? {
        if (
            requestedComposition != current.composition ||
            decoderGeneration != current.decoderGeneration ||
            decoding.revision != requestedComposition.revision ||
            decoding.remainingPinyin != requestedComposition.remainingPinyin
        ) {
            return null
        }
        current = CandidatePresentation(
            composition = requestedComposition,
            decoderGeneration = decoderGeneration,
            snapshot = ProgressiveCandidateSnapshot.from(decoding, limit),
            decoding = decoding,
            pending = false,
        )
        return current
    }

    fun currentDecoding(
        composition: PinyinComposition,
        decoderGeneration: Long = 0L,
    ): ProgressivePinyinDecoding? =
        current.decoding?.takeIf {
            current.composition == composition &&
                current.decoderGeneration == decoderGeneration &&
                it.revision == composition.revision &&
                it.remainingPinyin == composition.remainingPinyin
        }

    fun select(
        composition: PinyinComposition,
        requestedRevision: Long,
        sourceIndex: Int,
    ): ProgressiveCandidateChoice? {
        if (current.pending || current.composition != composition) return null
        return current.snapshot.select(composition.revision, requestedRevision, sourceIndex)
    }
}
