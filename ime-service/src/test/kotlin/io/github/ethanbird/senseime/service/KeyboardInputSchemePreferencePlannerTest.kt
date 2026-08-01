package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import io.github.ethanbird.senseime.ui.KeyboardInputSchemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class KeyboardInputSchemePreferencePlannerTest {
    @Test
    fun mapsEveryKeyboardChoiceToItsConfigurationScheme() {
        val current = ImePreferencesV1(
            chineseInputScheme = ChineseInputScheme.WUBI_86,
            wubiAutoCommitMode = WubiAutoCommitMode.UNIQUE_AT_4,
        )

        val cases = listOf(
            KeyboardInputSchemeChoice.PINYIN_T9 to ChineseInputScheme.PINYIN_T9,
            KeyboardInputSchemeChoice.PINYIN_QWERTY to ChineseInputScheme.PINYIN_QWERTY,
            KeyboardInputSchemeChoice.WUBI_86 to ChineseInputScheme.WUBI_86,
        )

        cases.forEach { (choice, expectedScheme) ->
            val source = current.copy(
                chineseInputScheme = ChineseInputScheme.entries.first { it != expectedScheme },
            )
            val intent = KeyboardInputSchemePreferencePlanner.plan(source, choice)
                as KeyboardInputSchemePersistenceIntent.Persist

            assertEquals(expectedScheme, intent.preferences.chineseInputScheme)
        }
    }

    @Test
    fun changedSchemeCopiesPreferencesAndPreservesWubiPolicy() {
        val current = ImePreferencesV1(
            chineseInputScheme = ChineseInputScheme.PINYIN_QWERTY,
            wubiAutoCommitMode = WubiAutoCommitMode.OFF,
        )

        val intent = KeyboardInputSchemePreferencePlanner.plan(
            current = current,
            choice = KeyboardInputSchemeChoice.PINYIN_T9,
        ) as KeyboardInputSchemePersistenceIntent.Persist

        assertNotSame(current, intent.preferences)
        assertEquals(ChineseInputScheme.PINYIN_T9, intent.preferences.chineseInputScheme)
        assertEquals(WubiAutoCommitMode.OFF, intent.preferences.wubiAutoCommitMode)
    }

    @Test
    fun selectingTheActiveSchemeIsIdempotent() {
        val current = ImePreferencesV1(
            chineseInputScheme = ChineseInputScheme.WUBI_86,
            wubiAutoCommitMode = WubiAutoCommitMode.RIME_STYLE,
        )

        val first = KeyboardInputSchemePreferencePlanner.plan(
            current,
            KeyboardInputSchemeChoice.WUBI_86,
        )
        val second = KeyboardInputSchemePreferencePlanner.plan(
            current,
            KeyboardInputSchemeChoice.WUBI_86,
        )

        assertSame(KeyboardInputSchemePersistenceIntent.Unchanged, first)
        assertSame(first, second)
    }
}
