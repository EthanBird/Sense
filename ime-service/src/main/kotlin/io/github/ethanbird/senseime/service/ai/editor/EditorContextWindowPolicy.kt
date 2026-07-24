package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol

/**
 * Narrows a host-provided partial editor read into one bounded editing unit.
 *
 * A collapsed-cursor context is never treated as the whole document. Sense first authorizes only
 * the current paragraph and, when that paragraph is long, a UTF-16-safe window around the cursor.
 * A real selection remains untouched because selection authority is handled separately.
 */
object EditorContextWindowPolicy {
    const val DEFAULT_MAX_CONTEXT_CHARS = SenseAiProtocol.DEFAULT_MAX_OUTPUT_CHARS

    fun constrain(
        context: ExtractedEditorText,
        maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS,
    ): ExtractedEditorText {
        require(maxContextChars > 0)
        if (context.completeDocument) return context
        if (!context.hasValidCollapsedSelection()) return context

        val cursor = context.selectionStartInText
        val paragraphStart = context.text.lastParagraphBoundaryBefore(cursor)
        val paragraphEnd = context.text.firstParagraphBoundaryAtOrAfter(cursor)
        var windowStart = paragraphStart
        var windowEnd = paragraphEnd

        if (windowEnd - windowStart > maxContextChars) {
            val leftBudget = maxContextChars / 2
            windowStart = (cursor - leftBudget)
                .coerceIn(paragraphStart, paragraphEnd - maxContextChars)
            windowEnd = windowStart + maxContextChars
        }

        // Moving inward keeps both the retained substring and the untouched host text valid.
        if (!context.text.isUtf16Boundary(windowStart)) windowStart += 1
        if (!context.text.isUtf16Boundary(windowEnd)) windowEnd -= 1
        if (windowStart > cursor || windowEnd < cursor || windowEnd < windowStart) return context

        val absoluteStart = context.startOffset.toLong() + windowStart
        if (absoluteStart !in 0..Int.MAX_VALUE.toLong()) return context
        val localCursor = cursor - windowStart
        return context.copy(
            text = context.text.substring(windowStart, windowEnd),
            startOffset = absoluteStart.toInt(),
            selectionStartInText = localCursor,
            selectionEndInText = localCursor,
            completeDocument = false,
        )
    }

    private fun ExtractedEditorText.hasValidCollapsedSelection(): Boolean =
        startOffset >= 0 &&
            selectionStartInText == selectionEndInText &&
            selectionStartInText in 0..text.length &&
            text.isUtf16Boundary(selectionStartInText) &&
            startOffset.toLong() + text.length <= Int.MAX_VALUE.toLong()

    private fun String.lastParagraphBoundaryBefore(cursor: Int): Int {
        var index = cursor - 1
        while (index >= 0) {
            if (this[index].isParagraphSeparator()) return index + 1
            index -= 1
        }
        return 0
    }

    private fun String.firstParagraphBoundaryAtOrAfter(cursor: Int): Int {
        var index = cursor
        while (index < length) {
            if (this[index].isParagraphSeparator()) return index
            index += 1
        }
        return length
    }

    private fun Char.isParagraphSeparator(): Boolean =
        this == '\n' ||
            this == '\r' ||
            this == '\u0085' ||
            this == '\u2028' ||
            this == '\u2029'

    private fun String.isUtf16Boundary(index: Int): Boolean =
        index <= 0 ||
            index >= length ||
            !(this[index - 1].isHighSurrogate() && this[index].isLowSurrogate())
}
