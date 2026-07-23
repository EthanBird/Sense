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
    fun chineseLetterLabelsAreUppercaseButCodesStayLowercase() {
        assertEquals("Q", KeyboardLayoutContract.letterLabel('q', chineseMode = true, shifted = false))
        assertEquals("q", KeyboardLayoutContract.letterLabel('q', chineseMode = false, shifted = false))
        assertEquals("Q", KeyboardLayoutContract.letterLabel('q', chineseMode = false, shifted = true))

        val chineseRow = KeyboardLayoutContract.thirdLetterRow(shifted = false, chineseMode = true)
        assertEquals(listOf("Z", "X", "C", "V", "B", "N", "M"), chineseRow.drop(1).dropLast(1).map { it.label })
        assertEquals("zxcvbnm".map { it.code }, chineseRow.drop(1).dropLast(1).map { it.code })
    }

    @Test
    fun activeCandidateAreaConsumesToolbarAndPortraitUsesTallerHeight() {
        assertEquals(45f, KeyboardLayoutContract.collapsedCandidateBottom(45f, 42f, takesToolbar = false))
        assertEquals(87f, KeyboardLayoutContract.collapsedCandidateBottom(45f, 42f, takesToolbar = true))
        assertEquals(
            42f,
            KeyboardLayoutContract.topChromeBottom(
                candidateHeight = 45f,
                toolbarHeight = 42f,
                candidatesTakeToolbar = false,
                editorPanelVisible = false,
            ),
        )
        assertEquals(
            87f,
            KeyboardLayoutContract.topChromeBottom(
                candidateHeight = 45f,
                toolbarHeight = 42f,
                candidatesTakeToolbar = true,
                editorPanelVisible = false,
            ),
        )
        assertTrue(
            KeyboardLayoutContract.preferredKeyboardHeightDp(isLandscape = false) >
                KeyboardLayoutContract.preferredKeyboardHeightDp(isLandscape = true),
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
    fun collapsedStripMeasuresOnlyTheVisiblePrefixOfALargeSnapshot() {
        var measurementCount = 0
        val layout = KeyboardLayoutContract.collapsedCandidateStrip(
            viewWidth = 360f,
            candidateCount = 510,
            measuredTextWidth = {
                measurementCount++
                34f
            },
            padding = 6f,
            textInset = 9f,
            gap = 3f,
            minimumWidth = 44f,
            overflowControlWidth = 44f,
        )

        assertTrue(layout.hasOverflow)
        assertTrue(layout.slots.isNotEmpty())
        assertTrue("measured $measurementCount of 510", measurementCount < 12)
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
    fun expandedGridKeeps120And255CandidatesReachableInBothDirections() {
        listOf(120, 255).forEach { candidateCount ->
            val pages = KeyboardLayoutContract.pagedCandidateGrid(
                viewWidth = 360f,
                contentTop = 50f,
                contentBottom = 270f,
                measuredTextWidths = List(candidateCount) { index -> 24f + index % 7 * 6f },
                horizontalPadding = 6f,
                textInset = 9f,
                horizontalGap = 3f,
                verticalGap = 4f,
                minimumWidth = 44f,
                rowHeight = 40f,
            )

            assertTrue(pages.size > 2)
            assertEquals(
                (0 until candidateCount).toList(),
                pages.flatMap { page -> page.slots.map { it.sourceIndex } },
            )

            var pageIndex = 0
            val forward = mutableListOf<Int>()
            while (true) {
                forward += pages[pageIndex].slots.map { it.sourceIndex }
                val next = KeyboardLayoutContract.adjacentCandidatePage(pageIndex, pages.size, delta = 1)
                if (next == pageIndex) break
                pageIndex = next
            }
            assertEquals((0 until candidateCount).toList(), forward)

            val backward = mutableListOf<Int>()
            while (true) {
                backward += pages[pageIndex].slots.asReversed().map { it.sourceIndex }
                val previous = KeyboardLayoutContract.adjacentCandidatePage(pageIndex, pages.size, delta = -1)
                if (previous == pageIndex) break
                pageIndex = previous
            }
            assertEquals((0 until candidateCount).reversed().toList(), backward)
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

    @Test
    fun emojiPageIndicatorHasItsOwnBandBetweenGridAndActionRow() {
        val geometry = KeyboardLayoutContract.emojiLayoutGeometry(
            contentTop = 91f,
            contentBottom = 302f,
            categoryHeight = 29f,
            actionHeight = 40f,
            gridGap = 3f,
            indicatorBandHeight = 14f,
        )

        assertTrue(geometry.gridBottom < geometry.indicatorY)
        assertTrue(geometry.indicatorY < geometry.actionTop)
        assertTrue(geometry.gridBottom <= geometry.indicatorTop)
        assertTrue(geometry.indicatorBottom <= geometry.actionTop)
        assertTrue(geometry.gridBottom <= geometry.indicatorY - 2f)
        assertTrue(geometry.indicatorY + 2f <= geometry.actionTop)
    }

    @Test
    fun scrollableEmojiGridUsesTheSpacePreviouslyReservedForPageDots() {
        val scrolling = KeyboardLayoutContract.scrollableEmojiLayoutGeometry(
            contentTop = 91f,
            contentBottom = 342f,
            categoryHeight = 29f,
            actionHeight = 40f,
            gridGap = 3f,
        )
        val paged = KeyboardLayoutContract.emojiLayoutGeometry(
            contentTop = 91f,
            contentBottom = 342f,
            categoryHeight = 29f,
            actionHeight = 40f,
            gridGap = 3f,
            indicatorBandHeight = 14f,
        )

        assertTrue(scrolling.gridBottom > paged.gridBottom)
        assertEquals(scrolling.gridBottom + 3f, scrolling.actionTop)
    }

    @Test
    fun clipboardCardsAreSingleColumnAndThreePerPage() {
        val slots = KeyboardLayoutContract.clipboardCardSlots(
            viewWidth = 360f,
            contentTop = 120f,
            contentBottom = 330f,
            itemCount = 8,
            pageStart = 3,
            horizontalPadding = 6f,
            gap = 5f,
        )

        assertEquals(listOf(3, 4, 5), slots.map { it.sourceIndex })
        assertTrue(slots.all { it.left == 6f && it.right == 354f })
        assertTrue(slots.zipWithNext().all { (before, after) -> before.bottom < after.top })
        assertTrue(slots.last().bottom <= 330f)
    }

    @Test
    fun clipboardPreviewFitsTwoLinesAndDoesNotSplitEmojiSurrogates() {
        val measure: (String) -> Float = { value ->
            Character.codePointCount(value, 0, value.length) * 10f
        }
        val preview = KeyboardLayoutContract.clipboardPreviewLines(
            text = "😀中文abcdef",
            maximumWidth = 30f,
            measureText = measure,
        )

        assertEquals("😀中文", preview.first)
        assertEquals("ab…", preview.second)
        assertTrue(measure(preview.first) <= 30f)
        assertTrue(measure(preview.second.orEmpty()) <= 30f)
        assertFalse(Character.isHighSurrogate(preview.first.last()))
        assertFalse(Character.isLowSurrogate(preview.second.orEmpty().first()))
    }

    @Test
    fun editorLayoutContainsEveryActionWithoutOverlaps() {
        val slots = KeyboardLayoutContract.editorLayout(
            viewWidth = 360f,
            contentTop = 52f,
            contentBottom = 340f,
            horizontalPadding = 6f,
            gap = 5f,
        )

        assertEquals(KeyboardLayoutContract.EditorKeyRole.entries.toSet(), slots.map { it.role }.toSet())
        assertTrue(slots.all { it.right > it.left && it.bottom > it.top })
        slots.forEachIndexed { leftIndex, left ->
            slots.drop(leftIndex + 1).forEach { right ->
                val overlaps = left.left < right.right && left.right > right.left &&
                    left.top < right.bottom && left.bottom > right.top
                assertFalse("${left.role} overlaps ${right.role}", overlaps)
            }
        }
    }
}
