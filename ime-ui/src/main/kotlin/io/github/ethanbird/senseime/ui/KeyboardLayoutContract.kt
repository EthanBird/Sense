package io.github.ethanbird.senseime.ui

/**
 * Stable ordering contract for the phone keyboard.
 *
 * Keeping these rows outside the Canvas view makes the physical layout testable:
 * M is always followed by backspace, and the system bar contains only its two
 * edge actions so its centre remains reserved.
 */
object KeyboardLayoutContract {
    /**
     * M6 accidentally kept the removed toolbar row in the total height. That
     * made the idle letter rows 42dp taller, then shrank them as soon as the
     * candidate strip took over the toolbar. Keep the M6 composing-state key
     * size while removing that dead row from both orientations.
     */
    const val PORTRAIT_KEYBOARD_HEIGHT_DP = 358f
    const val LANDSCAPE_KEYBOARD_HEIGHT_DP = 258f

    data class CandidateSlot(
        val left: Float,
        val right: Float,
        val textAnchor: Float,
    )

    data class IndexedCandidateSlot(
        val sourceIndex: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val textAnchor: Float,
    )

    data class CandidateStripLayout(
        val slots: List<CandidateSlot>,
        val hasOverflow: Boolean,
    )

    data class CandidatePage(
        val slots: List<IndexedCandidateSlot>,
    )

    data class WeightedKey(
        val label: String,
        val code: Int,
        val weight: Float,
        val action: Boolean = false,
    )

    enum class Side {
        LEFT,
        RIGHT,
    }

    data class SystemKey(
        val code: Int,
        val side: Side,
    )

    data class NumericKey(
        val label: String,
        val code: Int = 0,
        val text: String? = null,
        val column: Int,
        val row: Int,
        val columnSpan: Int = 1,
        val rowSpan: Int = 1,
    )

    data class NumericSlot(
        val key: NumericKey,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    data class EmojiLayoutGeometry(
        val categoryTop: Float,
        val categoryBottom: Float,
        val gridTop: Float,
        val gridBottom: Float,
        val indicatorTop: Float,
        val indicatorY: Float,
        val indicatorBottom: Float,
        val actionTop: Float,
        val actionBottom: Float,
    )

    data class ScrollableEmojiLayoutGeometry(
        val categoryTop: Float,
        val categoryBottom: Float,
        val gridTop: Float,
        val gridBottom: Float,
        val actionTop: Float,
        val actionBottom: Float,
    )

    data class ClipboardCardSlot(
        val sourceIndex: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    enum class EditorKeyRole {
        UP,
        LEFT,
        TOGGLE_SELECTION,
        RIGHT,
        DOWN,
        DELETE,
        COPY,
        CUT,
        PASTE,
        HOME,
        SELECT_ALL,
        END,
    }

    data class EditorKeySlot(
        val role: EditorKeyRole,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    fun preferredKeyboardHeightDp(isLandscape: Boolean): Float =
        if (isLandscape) LANDSCAPE_KEYBOARD_HEIGHT_DP else PORTRAIT_KEYBOARD_HEIGHT_DP

    fun collapsedCandidateBottom(
        candidateHeight: Float,
        toolbarHeight: Float,
        takesToolbar: Boolean,
    ): Float {
        require(candidateHeight > 0f)
        require(toolbarHeight >= 0f)
        return maxOf(candidateHeight, toolbarHeight)
    }

    fun topChromeBottom(
        candidateHeight: Float,
        toolbarHeight: Float,
        candidatesTakeToolbar: Boolean,
        editorPanelVisible: Boolean,
    ): Float {
        require(candidateHeight > 0f)
        require(toolbarHeight > 0f)
        return maxOf(candidateHeight, toolbarHeight)
    }

    fun letterLabel(character: Char, chineseMode: Boolean, shifted: Boolean): String =
        if (chineseMode || shifted) character.uppercase() else character.toString()

    fun thirdLetterRow(shifted: Boolean, chineseMode: Boolean = false): List<WeightedKey> = buildList {
        add(WeightedKey("⇧", KeyCodes.SHIFT, 1.25f, action = true))
        "zxcvbnm".forEach { character ->
            add(WeightedKey(letterLabel(character, chineseMode, shifted), character.code, 1f))
        }
        add(WeightedKey("⌫", KeyCodes.DELETE, 1.25f, action = true))
    }

    fun functionRow(chineseMode: Boolean, returnToLetters: Boolean = false): List<WeightedKey> = listOf(
        WeightedKey(if (returnToLetters) "ABC" else "符", if (returnToLetters) KeyCodes.LETTERS else KeyCodes.SYMBOLS, 0.9f, action = true),
        WeightedKey("123", KeyCodes.NUMBERS, 1.05f, action = true),
        WeightedKey(if (chineseMode) "，" else ",", KeyCodes.COMMA, 0.8f),
        WeightedKey("空格", KeyCodes.SPACE, 2.7f),
        WeightedKey(if (chineseMode) "。" else ".", KeyCodes.PERIOD, 0.8f),
        WeightedKey("中/英", KeyCodes.LANGUAGE, 1f, action = true),
        WeightedKey("↵", KeyCodes.ENTER, 1.2f, action = true),
    )

    val systemBar: List<SystemKey> = listOf(
        SystemKey(KeyCodes.SWITCH_INPUT_METHOD, Side.LEFT),
        SystemKey(KeyCodes.CLIPBOARD, Side.RIGHT),
    )

    /**
     * Five-column numeric pad matching the compact mobile 3x3 convention.
     * Column 0 is the operator rail, 1..3 are the number grid, and 4 is the
     * editing rail. The last row contains mode/back/zero/space/enter.
     */
    fun numericPad(chineseMode: Boolean): List<NumericKey> = buildList {
        listOf(".", "/", "+", "−").forEachIndexed { row, text ->
            add(NumericKey(text, text = if (text == "−") "-" else text, column = 0, row = row))
        }
        (1..9).forEach { value ->
            val index = value - 1
            add(NumericKey(value.toString(), value.toString().single().code, text = value.toString(), column = 1 + index % 3, row = index / 3))
        }
        add(NumericKey("", KeyCodes.DELETE, column = 4, row = 0))
        add(NumericKey(".", text = if (chineseMode) "。" else ".", column = 4, row = 1))
        add(NumericKey("@", text = "@", column = 4, row = 2))
        add(NumericKey("符", KeyCodes.SYMBOLS, column = 0, row = 4))
        add(NumericKey("返回", KeyCodes.LETTERS, column = 1, row = 4))
        add(NumericKey("0", '0'.code, text = "0", column = 2, row = 4))
        add(NumericKey("", KeyCodes.SPACE, column = 3, row = 4))
        add(NumericKey("", KeyCodes.ENTER, column = 4, row = 4))
    }

    fun numericPadLayout(
        viewWidth: Float,
        contentTop: Float,
        contentBottom: Float,
        horizontalPadding: Float,
        gap: Float,
        chineseMode: Boolean,
    ): List<NumericSlot> {
        require(viewWidth > horizontalPadding * 2 + gap * 4)
        require(contentBottom > contentTop)
        val bottomRowHeight = (contentBottom - contentTop) * 0.245f
        val bottomTop = contentBottom - bottomRowHeight
        val mainBottom = bottomTop - gap
        val centreRowHeight = (mainBottom - contentTop - gap * 2) / 3f
        val railRowHeight = (mainBottom - contentTop - gap * 3) / 4f
        val weights = floatArrayOf(0.82f, 1.08f, 1.08f, 1.08f, 0.9f)
        val usableWidth = viewWidth - horizontalPadding * 2 - gap * 4
        val totalWeight = weights.sum()
        val lefts = FloatArray(5)
        val rights = FloatArray(5)
        var x = horizontalPadding
        repeat(5) { column ->
            lefts[column] = x
            rights[column] = x + usableWidth * weights[column] / totalWeight
            x = rights[column] + gap
        }
        return numericPad(chineseMode).map { key ->
            val top = when {
                key.row == 4 -> bottomTop
                key.column == 0 -> contentTop + key.row * (railRowHeight + gap)
                else -> contentTop + key.row * (centreRowHeight + gap)
            }
            val height = when {
                key.row == 4 -> bottomRowHeight
                key.column == 0 -> railRowHeight
                else -> centreRowHeight
            }
            NumericSlot(key, lefts[key.column], top, rights[key.column], top + height)
        }
    }

    fun emojiLayoutGeometry(
        contentTop: Float,
        contentBottom: Float,
        categoryHeight: Float,
        actionHeight: Float,
        gridGap: Float,
        indicatorBandHeight: Float,
    ): EmojiLayoutGeometry {
        require(categoryHeight > 0f)
        require(actionHeight > 0f)
        require(gridGap >= 0f)
        require(indicatorBandHeight > 0f)
        val categoryBottom = contentTop + categoryHeight
        val actionTop = contentBottom - actionHeight
        val indicatorTop = actionTop - indicatorBandHeight
        val gridTop = categoryBottom + gridGap
        val gridBottom = indicatorTop - gridGap
        require(gridBottom > gridTop)
        return EmojiLayoutGeometry(
            categoryTop = contentTop,
            categoryBottom = categoryBottom,
            gridTop = gridTop,
            gridBottom = gridBottom,
            indicatorTop = indicatorTop,
            indicatorY = (indicatorTop + actionTop) / 2f,
            indicatorBottom = actionTop,
            actionTop = actionTop,
            actionBottom = contentBottom,
        )
    }

    fun scrollableEmojiLayoutGeometry(
        contentTop: Float,
        contentBottom: Float,
        categoryHeight: Float,
        actionHeight: Float,
        gridGap: Float,
    ): ScrollableEmojiLayoutGeometry {
        require(categoryHeight > 0f)
        require(actionHeight > 0f)
        require(gridGap >= 0f)
        val categoryBottom = contentTop + categoryHeight
        val actionTop = contentBottom - actionHeight
        val gridTop = categoryBottom + gridGap
        val gridBottom = actionTop - gridGap
        require(gridBottom > gridTop)
        return ScrollableEmojiLayoutGeometry(
            categoryTop = contentTop,
            categoryBottom = categoryBottom,
            gridTop = gridTop,
            gridBottom = gridBottom,
            actionTop = actionTop,
            actionBottom = contentBottom,
        )
    }

    fun clipboardCardSlots(
        viewWidth: Float,
        contentTop: Float,
        contentBottom: Float,
        itemCount: Int,
        pageStart: Int,
        horizontalPadding: Float,
        gap: Float,
        itemsPerPage: Int = 3,
    ): List<ClipboardCardSlot> {
        require(viewWidth > horizontalPadding * 2)
        require(contentBottom > contentTop)
        require(itemCount >= 0)
        require(pageStart >= 0)
        require(gap >= 0f)
        require(itemsPerPage > 0)
        val visibleCount = (itemCount - pageStart).coerceIn(0, itemsPerPage)
        if (visibleCount == 0) return emptyList()
        val cardHeight = (contentBottom - contentTop - gap * (itemsPerPage - 1)) / itemsPerPage
        require(cardHeight > 0f)
        return List(visibleCount) { pageIndex ->
            val top = contentTop + pageIndex * (cardHeight + gap)
            ClipboardCardSlot(
                sourceIndex = pageStart + pageIndex,
                left = horizontalPadding,
                top = top,
                right = viewWidth - horizontalPadding,
                bottom = top + cardHeight,
            )
        }
    }

    fun clipboardPreviewLines(
        text: String,
        maximumWidth: Float,
        measureText: (String) -> Float,
    ): Pair<String, String?> {
        require(maximumWidth > 0f)
        val normalized = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (normalized.isEmpty()) return "" to null
        val firstEnd = fittingCodePointPrefixEnd(normalized, maximumWidth, measureText)
        if (firstEnd >= normalized.length) return normalized to null
        val first = normalized.substring(0, firstEnd).trimEnd()
        val remainder = normalized.substring(firstEnd).trimStart()
        if (measureText(remainder) <= maximumWidth) return first to remainder

        val ellipsis = "…"
        val secondEnd = fittingCodePointPrefixEnd(
            remainder,
            (maximumWidth - measureText(ellipsis)).coerceAtLeast(0f),
            measureText,
        )
        return first to (remainder.substring(0, secondEnd).trimEnd() + ellipsis)
    }

    private fun fittingCodePointPrefixEnd(
        text: String,
        maximumWidth: Float,
        measureText: (String) -> Float,
    ): Int {
        var end = 0
        while (end < text.length) {
            val next = end + Character.charCount(Character.codePointAt(text, end))
            if (measureText(text.substring(0, next)) > maximumWidth) break
            end = next
        }
        return end
    }

    fun editorLayout(
        viewWidth: Float,
        contentTop: Float,
        contentBottom: Float,
        horizontalPadding: Float,
        gap: Float,
    ): List<EditorKeySlot> {
        require(viewWidth > horizontalPadding * 2)
        require(contentBottom > contentTop)
        require(gap >= 0f)
        val usableWidth = viewWidth - horizontalPadding * 2
        val railWidth = usableWidth * 0.17f
        val mainRight = viewWidth - horizontalPadding - railWidth - gap
        val mainWidth = mainRight - horizontalPadding
        val bottomHeight = (contentBottom - contentTop) * 0.26f
        val bottomTop = contentBottom - bottomHeight
        val padBottom = bottomTop - gap
        val padHeight = padBottom - contentTop
        require(mainWidth > gap * 2)
        require(padHeight > gap * 2)

        val cellWidth = (mainWidth - gap * 2) / 3f
        val cellHeight = (padHeight - gap * 2) / 3f
        fun gridSlot(role: EditorKeyRole, column: Int, row: Int) = EditorKeySlot(
            role = role,
            left = horizontalPadding + column * (cellWidth + gap),
            top = contentTop + row * (cellHeight + gap),
            right = horizontalPadding + column * (cellWidth + gap) + cellWidth,
            bottom = contentTop + row * (cellHeight + gap) + cellHeight,
        )

        val railLeft = viewWidth - horizontalPadding - railWidth
        val railCellHeight = (contentBottom - contentTop - gap * 3) / 4f
        val railRoles = listOf(
            EditorKeyRole.DELETE,
            EditorKeyRole.COPY,
            EditorKeyRole.CUT,
            EditorKeyRole.PASTE,
        )
        val bottomCellWidth = (mainWidth - gap * 2) / 3f
        val bottomRoles = listOf(
            EditorKeyRole.HOME,
            EditorKeyRole.SELECT_ALL,
            EditorKeyRole.END,
        )

        return buildList {
            add(gridSlot(EditorKeyRole.UP, 1, 0))
            add(gridSlot(EditorKeyRole.LEFT, 0, 1))
            add(gridSlot(EditorKeyRole.TOGGLE_SELECTION, 1, 1))
            add(gridSlot(EditorKeyRole.RIGHT, 2, 1))
            add(gridSlot(EditorKeyRole.DOWN, 1, 2))
            railRoles.forEachIndexed { index, role ->
                val top = contentTop + index * (railCellHeight + gap)
                add(EditorKeySlot(role, railLeft, top, railLeft + railWidth, top + railCellHeight))
            }
            bottomRoles.forEachIndexed { index, role ->
                val left = horizontalPadding + index * (bottomCellWidth + gap)
                add(EditorKeySlot(role, left, bottomTop, left + bottomCellWidth, contentBottom))
            }
        }
    }

    fun leftAlignedCandidateSlots(
        viewWidth: Float,
        measuredTextWidths: List<Float>,
        padding: Float,
        textInset: Float,
        gap: Float,
        minimumWidth: Float,
    ): List<CandidateSlot> = leftAlignedCandidateSlots(
        viewWidth = viewWidth,
        candidateCount = measuredTextWidths.size,
        measuredTextWidth = measuredTextWidths::get,
        padding = padding,
        textInset = textInset,
        gap = gap,
        minimumWidth = minimumWidth,
    )

    private fun leftAlignedCandidateSlots(
        viewWidth: Float,
        candidateCount: Int,
        measuredTextWidth: (Int) -> Float,
        padding: Float,
        textInset: Float,
        gap: Float,
        minimumWidth: Float,
    ): List<CandidateSlot> {
        val result = ArrayList<CandidateSlot>(minOf(candidateCount, 8))
        var left = padding
        for (sourceIndex in 0 until candidateCount) {
            if (viewWidth - padding - left < minimumWidth) break
            val textWidth = measuredTextWidth(sourceIndex)
            val width = maxOf(minimumWidth, textWidth + textInset * 2)
            val right = minOf(viewWidth - padding, left + width)
            result += CandidateSlot(left, right, left + textInset)
            left = right + gap
        }
        return result
    }

    fun collapsedCandidateStrip(
        viewWidth: Float,
        measuredTextWidths: List<Float>,
        padding: Float,
        textInset: Float,
        gap: Float,
        minimumWidth: Float,
        overflowControlWidth: Float,
    ): CandidateStripLayout = collapsedCandidateStrip(
        viewWidth = viewWidth,
        candidateCount = measuredTextWidths.size,
        measuredTextWidth = measuredTextWidths::get,
        padding = padding,
        textInset = textInset,
        gap = gap,
        minimumWidth = minimumWidth,
        overflowControlWidth = overflowControlWidth,
    )

    fun collapsedCandidateStrip(
        viewWidth: Float,
        candidateCount: Int,
        measuredTextWidth: (Int) -> Float,
        padding: Float,
        textInset: Float,
        gap: Float,
        minimumWidth: Float,
        overflowControlWidth: Float,
    ): CandidateStripLayout {
        require(candidateCount >= 0)
        val measuredPrefix = ArrayList<Float>(minOf(candidateCount, 8))
        val cachedTextWidth: (Int) -> Float = { sourceIndex ->
            while (measuredPrefix.size <= sourceIndex) {
                measuredPrefix += measuredTextWidth(measuredPrefix.size)
            }
            measuredPrefix[sourceIndex]
        }
        val fullWidth = leftAlignedCandidateSlots(
            viewWidth,
            candidateCount,
            cachedTextWidth,
            padding,
            textInset,
            gap,
            minimumWidth,
        )
        if (fullWidth.size == candidateCount) return CandidateStripLayout(fullWidth, false)
        val reservedWidth = (viewWidth - overflowControlWidth - gap).coerceAtLeast(padding * 2 + minimumWidth)
        return CandidateStripLayout(
            leftAlignedCandidateSlots(
                reservedWidth,
                candidateCount,
                cachedTextWidth,
                padding,
                textInset,
                gap,
                minimumWidth,
            ),
            true,
        )
    }

    fun adjacentCandidatePage(currentPage: Int, pageCount: Int, delta: Int): Int {
        if (pageCount <= 0) return 0
        return (currentPage.coerceIn(0, pageCount - 1) + delta).coerceIn(0, pageCount - 1)
    }

    fun pagedCandidateGrid(
        viewWidth: Float,
        contentTop: Float,
        contentBottom: Float,
        measuredTextWidths: List<Float>,
        horizontalPadding: Float,
        textInset: Float,
        horizontalGap: Float,
        verticalGap: Float,
        minimumWidth: Float,
        rowHeight: Float,
    ): List<CandidatePage> {
        if (measuredTextWidths.isEmpty()) return emptyList()
        require(viewWidth > horizontalPadding * 2)
        require(contentBottom - contentTop >= rowHeight)
        val rightLimit = viewWidth - horizontalPadding
        val maximumWidth = rightLimit - horizontalPadding
        val pages = mutableListOf<CandidatePage>()
        var slots = mutableListOf<IndexedCandidateSlot>()
        var left = horizontalPadding
        var top = contentTop

        measuredTextWidths.forEachIndexed { sourceIndex, textWidth ->
            val width = maxOf(minimumWidth, textWidth + textInset * 2).coerceAtMost(maximumWidth)
            if (left > horizontalPadding && left + width > rightLimit) {
                left = horizontalPadding
                top += rowHeight + verticalGap
            }
            if (top + rowHeight > contentBottom) {
                pages += CandidatePage(slots)
                slots = mutableListOf()
                left = horizontalPadding
                top = contentTop
            }
            slots += IndexedCandidateSlot(
                sourceIndex = sourceIndex,
                left = left,
                top = top,
                right = left + width,
                bottom = top + rowHeight,
                textAnchor = left + textInset,
            )
            left += width + horizontalGap
        }
        if (slots.isNotEmpty()) pages += CandidatePage(slots)
        return pages
    }
}
