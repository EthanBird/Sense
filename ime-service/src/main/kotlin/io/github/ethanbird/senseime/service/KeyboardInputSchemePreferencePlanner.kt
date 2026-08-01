package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.ui.KeyboardInputSchemeChoice

/** Pure intent produced when the keyboard surface requests a Chinese input-scheme change. */
internal sealed interface KeyboardInputSchemePersistenceIntent {
    /** The requested scheme is already active; neither apply nor disk write is needed. */
    data object Unchanged : KeyboardInputSchemePersistenceIntent

    /** Apply this in-memory snapshot; storage re-plans the same choice under its file lock. */
    data class Persist(
        val preferences: ImePreferencesV1,
    ) : KeyboardInputSchemePersistenceIntent
}

/**
 * Maps the presentation-layer choice to a complete, immutable preference update.
 *
 * Keeping the decision here makes the service callback a small executor of an already-tested
 * intent. A scheme change copies the existing value so unrelated Wubi policy survives, while an
 * identical selection remains idempotent and avoids redundant cross-process storage traffic.
 */
internal object KeyboardInputSchemePreferencePlanner {
    fun plan(
        current: ImePreferencesV1,
        choice: KeyboardInputSchemeChoice,
    ): KeyboardInputSchemePersistenceIntent {
        val scheme = choice.toChineseInputScheme()
        if (scheme == current.chineseInputScheme) {
            return KeyboardInputSchemePersistenceIntent.Unchanged
        }
        return KeyboardInputSchemePersistenceIntent.Persist(
            current.copy(chineseInputScheme = scheme),
        )
    }

    private fun KeyboardInputSchemeChoice.toChineseInputScheme(): ChineseInputScheme = when (this) {
        KeyboardInputSchemeChoice.PINYIN_T9 -> ChineseInputScheme.PINYIN_T9
        KeyboardInputSchemeChoice.PINYIN_QWERTY -> ChineseInputScheme.PINYIN_QWERTY
        KeyboardInputSchemeChoice.WUBI_86 -> ChineseInputScheme.WUBI_86
    }
}
