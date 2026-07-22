package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyboardLayoutContractTest {
    @Test
    fun backspaceIsImmediatelyAfterM() {
        val row = KeyboardLayoutContract.thirdLetterRow(shifted = false)

        assertEquals(
            listOf(
                KeyCodes.SHIFT,
                'z'.code,
                'x'.code,
                'c'.code,
                'v'.code,
                'b'.code,
                'n'.code,
                'm'.code,
                KeyCodes.DELETE,
            ),
            row.map { it.code },
        )
    }

    @Test
    fun functionRowEndsWithPeriodLanguageAndEnter() {
        val row = KeyboardLayoutContract.functionRow(chineseMode = true)

        assertEquals(
            listOf(
                KeyCodes.SYMBOLS,
                KeyCodes.NUMBERS,
                KeyCodes.COMMA,
                KeyCodes.SPACE,
                KeyCodes.PERIOD,
                KeyCodes.LANGUAGE,
                KeyCodes.ENTER,
            ),
            row.map { it.code },
        )
        assertEquals(listOf("空格", "。", "中/英", "↵"), row.takeLast(4).map { it.label })
    }

    @Test
    fun bottomSystemBarHasOnlyTwoEdgeActions() {
        val keys = KeyboardLayoutContract.systemBar

        assertEquals(2, keys.size)
        assertEquals(KeyCodes.SWITCH_INPUT_METHOD, keys.first().code)
        assertEquals(KeyboardLayoutContract.Side.LEFT, keys.first().side)
        assertEquals(KeyCodes.CLIPBOARD, keys.last().code)
        assertEquals(KeyboardLayoutContract.Side.RIGHT, keys.last().side)
        assertFalse(keys.any { it.code == KeyCodes.SPACE })
    }
}
