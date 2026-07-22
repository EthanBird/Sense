package io.github.ethanbird.senseime.ui

/**
 * Stable ordering contract for the phone keyboard.
 *
 * Keeping these rows outside the Canvas view makes the physical layout testable:
 * M is always followed by backspace, and the system bar contains only its two
 * edge actions so its centre remains reserved.
 */
object KeyboardLayoutContract {
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

    fun thirdLetterRow(shifted: Boolean): List<WeightedKey> = buildList {
        add(WeightedKey("⇧", KeyCodes.SHIFT, 1.25f, action = true))
        "zxcvbnm".forEach { character ->
            add(WeightedKey(if (shifted) character.uppercase() else character.toString(), character.code, 1f))
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

    fun leftAlignedCandidateSlots(
        viewWidth: Float,
        measuredTextWidths: List<Float>,
        padding: Float,
        textInset: Float,
        gap: Float,
        minimumWidth: Float,
    ): List<CandidateSlot> {
        val result = ArrayList<CandidateSlot>(measuredTextWidths.size)
        var left = padding
        for (textWidth in measuredTextWidths) {
            if (viewWidth - padding - left < minimumWidth) break
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
    ): CandidateStripLayout {
        val fullWidth = leftAlignedCandidateSlots(
            viewWidth,
            measuredTextWidths,
            padding,
            textInset,
            gap,
            minimumWidth,
        )
        if (fullWidth.size == measuredTextWidths.size) return CandidateStripLayout(fullWidth, false)
        val reservedWidth = (viewWidth - overflowControlWidth - gap).coerceAtLeast(padding * 2 + minimumWidth)
        return CandidateStripLayout(
            leftAlignedCandidateSlots(
                reservedWidth,
                measuredTextWidths,
                padding,
                textInset,
                gap,
                minimumWidth,
            ),
            true,
        )
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
