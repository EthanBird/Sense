package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun candidatesFlowFromTheLeftUsingMeasuredWidths() {
        val slots = KeyboardLayoutContract.leftAlignedCandidateSlots(
            viewWidth = 360f,
            measuredTextWidths = listOf(34f, 52f, 20f),
            padding = 6f,
            textInset = 9f,
            gap = 3f,
            minimumWidth = 44f,
        )

        assertEquals(6f, slots.first().left)
        assertEquals(15f, slots.first().textAnchor)
        assertTrue(slots.zipWithNext().all { (left, right) -> right.left > left.right })
        assertEquals(listOf(52f, 70f, 44f), slots.map { it.right - it.left })
    }

    @Test
    fun candidateFlowDoesNotCreateAnUntappableSliverAtTheRightEdge() {
        val slots = KeyboardLayoutContract.leftAlignedCandidateSlots(
            viewWidth = 120f,
            measuredTextWidths = listOf(30f, 30f, 30f),
            padding = 6f,
            textInset = 9f,
            gap = 3f,
            minimumWidth = 44f,
        )

        assertEquals(2, slots.size)
        assertTrue(slots.all { it.right - it.left >= 44f })
        assertTrue(slots.all { it.textAnchor <= it.right })
    }
}
