package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.WubiComposition

/**
 * Resolves the fifth direct-Wubi key without dropping it while the four-code decode is in flight.
 *
 * The current four-code transaction is committed first and the caller then replays the overflow
 * key against a fresh composition. Candidate order is supplied by the adaptive Wubi decoder, but
 * completion candidates are never accepted for a four-code overflow transaction.
 */
internal class WubiOverflowCoordinator {
    private var pending: PendingOverflow? = null

    fun onCharacter(
        composition: WubiComposition,
        character: Char,
        presentationRevision: Long,
        mode: WubiAutoCommitMode,
        decoding: AlternativeDecoding?,
    ): WubiOverflowAction {
        if (
            composition.isReverseLookup ||
            !composition.isAtMaximumLength ||
            !WubiComposition.isCodeCharacter(character.lowercaseChar())
        ) {
            return WubiOverflowAction.Continue
        }
        if (mode == WubiAutoCommitMode.OFF) return WubiOverflowAction.Reject
        if (decoding == null) {
            pending = PendingOverflow(presentationRevision, mode)
            return WubiOverflowAction.Await(presentationRevision)
        }
        pending = null
        return resolve(mode, decoding.candidates)
    }

    fun onDecoded(
        presentationRevision: Long,
        candidates: List<Candidate>,
    ): WubiOverflowAction {
        val transaction = pending
            ?.takeIf { it.presentationRevision == presentationRevision }
            ?: return WubiOverflowAction.Continue
        pending = null
        return resolve(transaction.mode, candidates)
    }

    /**
     * Resolves a worker timeout without weakening the selected policy.
     *
     * Rime style has an explicit raw-code fallback when no exact result is available. UNIQUE_AT_4
     * has no evidence of uniqueness at timeout, so it must keep the four-code composition and
     * reject only the overflow key.
     */
    fun onTimeout(presentationRevision: Long): WubiOverflowAction {
        val transaction = pending
            ?.takeIf { it.presentationRevision == presentationRevision }
            ?: return WubiOverflowAction.Continue
        pending = null
        return when (transaction.mode) {
            WubiAutoCommitMode.RIME_STYLE -> WubiOverflowAction.Commit(null)
            WubiAutoCommitMode.UNIQUE_AT_4,
            WubiAutoCommitMode.OFF,
            -> WubiOverflowAction.Reject
        }
    }

    fun clear() {
        pending = null
    }

    private fun resolve(
        mode: WubiAutoCommitMode,
        candidates: List<Candidate>,
    ): WubiOverflowAction {
        val exact = candidates.filter { it.matchKind == CandidateMatchKind.WUBI_EXACT }
        return when (mode) {
            WubiAutoCommitMode.RIME_STYLE -> WubiOverflowAction.Commit(exact.firstOrNull())
            WubiAutoCommitMode.UNIQUE_AT_4 -> if (exact.size == 1) {
                WubiOverflowAction.Commit(exact.single())
            } else {
                WubiOverflowAction.Reject
            }
            WubiAutoCommitMode.OFF -> WubiOverflowAction.Reject
        }
    }

    private data class PendingOverflow(
        val presentationRevision: Long,
        val mode: WubiAutoCommitMode,
    )
}

internal sealed interface WubiOverflowAction {
    data object Continue : WubiOverflowAction
    /** Keeps the four-code transaction active and requires an explicit commit/selection. */
    data object Reject : WubiOverflowAction
    data class Await(val presentationRevision: Long) : WubiOverflowAction
    data class Commit(val candidate: Candidate?) : WubiOverflowAction
}
