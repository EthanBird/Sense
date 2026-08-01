package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardInputSchemePanelLayoutTest {
    @Test
    fun `panel publishes three semantic choices and one explicit close action`() {
        val output = ArrayList<Key>()
        KeyboardInputSchemePanelLayout(KeyboardMetrics.fromDensity(1f)).appendKeys(
            viewWidth = 1080,
            viewHeight = 420,
            selectedChoice = KeyboardInputSchemeChoice.WUBI_86,
            output = output,
        )

        val choices = output.mapNotNull { key ->
            (key.action as? KeyAction.SelectInputScheme)?.let { action -> key to action.choice }
        }
        assertEquals(
            listOf(
                KeyboardInputSchemeChoice.PINYIN_T9,
                KeyboardInputSchemeChoice.PINYIN_QWERTY,
                KeyboardInputSchemeChoice.WUBI_86,
            ),
            choices.map { it.second },
        )
        assertEquals(listOf("9键拼音", "26键拼音", "五笔86"), choices.map { it.first.label })
        assertEquals(listOf(false, false, true), choices.map { it.first.selected })
        assertTrue(choices.all { it.first.style == KeyStyle.INPUT_SCHEME_OPTION })

        val close = output.single { it.action is KeyAction.ShowPanel }
        val closeAction = close.action as KeyAction.ShowPanel
        assertEquals(KeyboardPanel.LETTERS, closeAction.panel)
        assertEquals(KeyCodes.LETTERS, close.code)
        assertFalse(close.selected)
    }
}
