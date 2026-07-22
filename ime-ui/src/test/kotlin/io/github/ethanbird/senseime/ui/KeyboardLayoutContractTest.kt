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

    @Test
    fun collapsedStripReservesAnOverflowControlOnlyWhenNeeded() {
        val overflowing = KeyboardLayoutContract.collapsedCandidateStrip(
            viewWidth = 180f,
            measuredTextWidths = List(6) { 34f },
            padding = 6f,
            textInset = 8f,
            gap = 3f,
            minimumWidth = 44f,
            overflowControlWidth = 40f,
        )
        assertTrue(overflowing.hasOverflow)
        assertTrue(overflowing.slots.all { it.right <= 131f })
        assertEquals(6f, overflowing.slots.first().left)

        val fitting = KeyboardLayoutContract.collapsedCandidateStrip(
            viewWidth = 180f,
            measuredTextWidths = listOf(20f, 20f),
            padding = 6f,
            textInset = 8f,
            gap = 3f,
            minimumWidth = 44f,
            overflowControlWidth = 40f,
        )
        assertFalse(fitting.hasOverflow)
    }

    @Test
    fun expandedGridPagesEveryCandidateWithGlobalIndices() {
        val pages = KeyboardLayoutContract.pagedCandidateGrid(
            viewWidth = 220f,
            contentTop = 50f,
            contentBottom = 150f,
            measuredTextWidths = listOf(40f, 70f, 30f, 180f, 45f, 45f, 45f),
            horizontalPadding = 6f,
            textInset = 9f,
            horizontalGap = 4f,
            verticalGap = 4f,
            minimumWidth = 48f,
            rowHeight = 40f,
        )

        assertTrue(pages.size > 1)
        assertEquals((0..6).toList(), pages.flatMap { page -> page.slots.map { it.sourceIndex } })
        pages.forEach { page ->
            val rows = page.slots.groupBy { it.top }
            rows.values.forEach { row -> assertEquals(6f, row.first().left) }
            assertTrue(page.slots.all { it.right <= 214f && it.bottom <= 150f })
        }
    }

    @Test
    fun oversizedCandidateIsClampedAndDoesNotStallPaging() {
        val pages = KeyboardLayoutContract.pagedCandidateGrid(
            viewWidth = 120f,
            contentTop = 45f,
            contentBottom = 90f,
            measuredTextWidths = listOf(500f, 20f),
            horizontalPadding = 6f,
            textInset = 9f,
            horizontalGap = 3f,
            verticalGap = 3f,
            minimumWidth = 44f,
            rowHeight = 40f,
        )

        assertEquals(2, pages.size)
        assertEquals(108f, pages.first().slots.single().right - pages.first().slots.single().left)
        assertEquals(listOf(0, 1), pages.flatMap { it.slots }.map { it.sourceIndex })
    }

    @Test
    fun numericPadUsesThreeByThreeDigitsAndScreenshotSideRails() {
        val pad = KeyboardLayoutContract.numericPad(chineseMode = true)

        assertEquals(
            (1..9).map { it.toString() },
            pad.filter { it.row in 0..2 && it.column in 1..3 }.map { it.label },
        )
        assertEquals(listOf(".", "/", "+", "−"), pad.filter { it.column == 0 && it.row < 4 }.map { it.label })
        assertEquals(
            listOf(KeyCodes.DELETE, 0, 0),
            pad.filter { it.column == 4 && it.row < 3 }.map { it.code },
        )
        assertEquals(
            listOf(KeyCodes.SYMBOLS, KeyCodes.LETTERS, '0'.code, KeyCodes.SPACE, KeyCodes.ENTER),
            pad.filter { it.row == 4 }.map { it.code },
        )
    }

    @Test
    fun numericPadGeometryHasNoOverlapsAndKeepsBottomRowSeparate() {
        val slots = KeyboardLayoutContract.numericPadLayout(
            viewWidth = 360f,
            contentTop = 92f,
            contentBottom = 300f,
            horizontalPadding = 6f,
            gap = 5f,
            chineseMode = true,
        )

        assertTrue(slots.all { it.right > it.left && it.bottom > it.top })
        slots.forEachIndexed { leftIndex, left ->
            slots.drop(leftIndex + 1).forEach { right ->
                val overlaps = left.left < right.right && left.right > right.left &&
                    left.top < right.bottom && left.bottom > right.top
                assertFalse("${left.key.label} overlaps ${right.key.label}", overlaps)
            }
        }
        val bottomRow = slots.filter { it.key.row == 4 }
        assertEquals(5, bottomRow.size)
        assertEquals(300f, bottomRow.maxOf { it.bottom })
    }
}
