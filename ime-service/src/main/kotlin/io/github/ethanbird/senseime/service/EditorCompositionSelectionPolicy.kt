package io.github.ethanbird.senseime.service

/**
 * Detects when the editor has moved away from, replaced, or canceled the
 * composing span that the IME still owns.
 *
 * Android reports the composing range through candidatesStart/candidatesEnd.
 * While a composition is active, its caret must remain collapsed at the end of
 * that range. Any other selection means the host and the IME no longer share
 * the same editable span, so the local session must be cleared before another
 * key or backspace is handled.
 */
internal object EditorCompositionSelectionPolicy {
    fun shouldCancelLocalComposition(
        hasActiveComposition: Boolean,
        newSelectionStart: Int,
        newSelectionEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ): Boolean {
        if (!hasActiveComposition) return false
        if (candidatesStart < 0 || candidatesEnd < candidatesStart) return true
        return newSelectionStart != candidatesEnd || newSelectionEnd != candidatesEnd
    }
}
