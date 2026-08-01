package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.T9Composition
import io.github.ethanbird.senseime.core.WubiComposition

/** Outcome of an alternative-scheme edit transaction. */
internal enum class AlternativeEditResult {
    /** The active scheme does not own this character. */
    UNHANDLED,

    /** The character belongs to the scheme, but it does not alter the current state. */
    CONSUMED,

    /** The editor rejected the composing-text publication, so state was not advanced. */
    REJECTED,

    /** Editor publication and the matching immutable state transition both completed. */
    CHANGED,
}

/**
 * Main-thread owner of Chinese input-scheme state.
 *
 * Every edit is transactional: the proposed composing text is published to the editor first and
 * the in-memory composition advances only after that publication succeeds. Decoder results are
 * additionally bound to a scheme epoch, local revision, global presentation revision and decoder
 * generations through [AlternativeCandidateSession].
 */
internal class ChineseInputSchemeCoordinator(
    initialPreferences: ImePreferencesV1 = ImePreferencesV1.DEFAULT,
) {
    var preferences: ImePreferencesV1 = initialPreferences
        private set
    val scheme: ChineseInputScheme
        get() = preferences.chineseInputScheme

    var t9: T9Composition = T9Composition()
        private set
    var wubi: WubiComposition = WubiComposition()
        private set
    var leftContext: String = ""
        private set
    var presentationRevision: Long = 0L
        private set

    private var schemeEpoch = 0L
    private val candidateSession = AlternativeCandidateSession()
    private var pendingPreferences: ImePreferencesV1? = null

    val isAlternativeScheme: Boolean
        get() = scheme != ChineseInputScheme.PINYIN_QWERTY
    val rawCode: String
        get() = when (scheme) {
            ChineseInputScheme.PINYIN_T9 -> t9.rawDigits
            ChineseInputScheme.WUBI_86 -> wubi.visibleCode
            ChineseInputScheme.PINYIN_QWERTY -> ""
        }
    val hasComposition: Boolean
        get() = rawCode.isNotEmpty()
    val isWubiReverseLookup: Boolean
        get() = scheme == ChineseInputScheme.WUBI_86 && wubi.isReverseLookup
    val learnsPinyinOnCommit: Boolean
        get() = scheme == ChineseInputScheme.PINYIN_T9 || isWubiReverseLookup

    fun type(
        character: Char,
        captureLeftContext: () -> String,
        publish: (String) -> Boolean,
    ): AlternativeEditResult {
        val previousRaw = rawCode
        return when (scheme) {
            ChineseInputScheme.PINYIN_QWERTY -> AlternativeEditResult.UNHANDLED
            ChineseInputScheme.PINYIN_T9 -> {
                val previous = t9
                val expectedEpoch = schemeEpoch
                val next = when (character) {
                    '1' -> previous.forceJoint()
                    in '2'..'9' -> previous.typeDigit(character)
                    else -> return AlternativeEditResult.UNHANDLED
                }
                if (next == previous) return AlternativeEditResult.CONSUMED
                val nextLeftContext = leftContextForEdit(previousRaw, captureLeftContext)
                if (!publish(next.rawDigits)) return AlternativeEditResult.REJECTED
                if (
                    scheme != ChineseInputScheme.PINYIN_T9 ||
                    schemeEpoch != expectedEpoch ||
                    t9 != previous
                ) {
                    return AlternativeEditResult.REJECTED
                }
                t9 = next
                completeEdit(nextLeftContext)
                AlternativeEditResult.CHANGED
            }
            ChineseInputScheme.WUBI_86 -> {
                val normalized = character.lowercaseChar()
                if (normalized !in 'a'..'z') return AlternativeEditResult.UNHANDLED
                val previous = wubi
                val expectedEpoch = schemeEpoch
                val next = previous.type(normalized)
                if (next == previous) return AlternativeEditResult.CONSUMED
                val nextLeftContext = leftContextForEdit(previousRaw, captureLeftContext)
                if (!publish(next.visibleCode)) return AlternativeEditResult.REJECTED
                if (
                    scheme != ChineseInputScheme.WUBI_86 ||
                    schemeEpoch != expectedEpoch ||
                    wubi != previous
                ) {
                    return AlternativeEditResult.REJECTED
                }
                wubi = next
                completeEdit(nextLeftContext)
                AlternativeEditResult.CHANGED
            }
        }
    }

    fun backspace(publish: (String) -> Boolean): Boolean {
        when (scheme) {
            ChineseInputScheme.PINYIN_QWERTY -> return false
            ChineseInputScheme.PINYIN_T9 -> {
                val previous = t9
                val expectedEpoch = schemeEpoch
                val next = previous.backspace()
                if (next == previous || !publish(next.rawDigits)) return false
                if (
                    scheme != ChineseInputScheme.PINYIN_T9 ||
                    schemeEpoch != expectedEpoch ||
                    t9 != previous
                ) {
                    return false
                }
                t9 = next
            }
            ChineseInputScheme.WUBI_86 -> {
                val previous = wubi
                val expectedEpoch = schemeEpoch
                val next = previous.backspace()
                if (next == previous || !publish(next.visibleCode)) return false
                if (
                    scheme != ChineseInputScheme.WUBI_86 ||
                    schemeEpoch != expectedEpoch ||
                    wubi != previous
                ) {
                    return false
                }
                wubi = next
            }
        }
        if (rawCode.isEmpty()) leftContext = ""
        advancePresentationRevision()
        return true
    }

    fun shouldUniqueAtFourCommit(): Boolean =
        scheme == ChineseInputScheme.WUBI_86 &&
            preferences.wubiAutoCommitMode == WubiAutoCommitMode.UNIQUE_AT_4 &&
            !wubi.isReverseLookup &&
            wubi.isAtMaximumLength

    /** Keeps the newest cross-process settings snapshot until a composition boundary is reached. */
    fun acceptLoadedPreferences(
        value: ImePreferencesV1,
        compositionActive: Boolean,
    ): ImePreferencesV1? {
        if (compositionActive) {
            pendingPreferences = value
            return null
        }
        pendingPreferences = null
        return value
    }

    fun takePendingPreferences(compositionActive: Boolean): ImePreferencesV1? {
        if (compositionActive) return null
        return pendingPreferences.also { pendingPreferences = null }
    }

    /** Returns whether the active scheme changed and stale work was invalidated. */
    fun applyPreferences(value: ImePreferencesV1): Boolean {
        val changed = scheme != value.chineseInputScheme
        preferences = value
        if (changed) resetAlternativeState()
        return changed
    }

    fun reset() {
        resetAlternativeState()
    }

    fun clearAfterCommit() {
        resetAlternativeState()
    }

    fun key(): AlternativeCompositionKey = AlternativeCompositionKey(
        scheme = scheme,
        schemeEpoch = schemeEpoch,
        localRevision = when (scheme) {
            ChineseInputScheme.PINYIN_T9 -> t9.revision
            ChineseInputScheme.WUBI_86 -> wubi.revision
            ChineseInputScheme.PINYIN_QWERTY -> 0L
        },
        presentationRevision = presentationRevision,
        rawCode = rawCode,
    )

    fun begin(request: AlternativeDecodeRequest, forceDecode: Boolean): AlternativeDecodeLaunch {
        if (forceDecode) candidateSession.clear()
        return candidateSession.begin(request)
    }

    fun complete(
        request: AlternativeDecodeRequest,
        decoding: AlternativeDecoding,
        activePinyinGeneration: Long,
        activeWubiGeneration: Long,
    ): AlternativePresentation? = candidateSession.complete(
        request = request,
        decoding = decoding,
        activePinyinGeneration = activePinyinGeneration,
        activeWubiGeneration = activeWubiGeneration,
    )

    fun select(presentationRevision: Long, sourceIndex: Int): Candidate? =
        candidateSession.select(presentationRevision, sourceIndex)

    fun currentDecoding(): AlternativeDecoding? {
        val current = candidateSession.current
        return current.decoding?.takeIf {
            current.key == key() && !current.pending
        }
    }

    fun clearCandidates() {
        candidateSession.clear()
    }

    private fun leftContextForEdit(
        previousRaw: String,
        captureLeftContext: () -> String,
    ): String = if (previousRaw.isEmpty()) captureLeftContext() else leftContext

    private fun completeEdit(nextLeftContext: String) {
        leftContext = nextLeftContext
        advancePresentationRevision()
    }

    private fun resetAlternativeState() {
        t9 = T9Composition()
        wubi = WubiComposition()
        leftContext = ""
        schemeEpoch = nextGeneration(schemeEpoch)
        advancePresentationRevision()
        candidateSession.clear()
    }

    private fun advancePresentationRevision() {
        presentationRevision = nextGeneration(presentationRevision)
    }

    private fun nextGeneration(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L
}
