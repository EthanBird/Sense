package io.github.ethanbird.senseime.ui

import android.graphics.RectF

/** Current four-row QWERTY primary-layout adapter. */
internal object QwertyKeyboardLayout : KeyboardLetterLayout {
    override fun appendKeys(
        request: KeyboardLetterLayoutRequest,
        metrics: KeyboardMetrics,
        output: MutableList<Key>,
    ) {
        val top = request.chromeBottom + metrics.dp(7f)
        val bottom = request.viewHeight - metrics.systemBarHeight - metrics.dp(7f)
        val rowHeight = (bottom - top - metrics.keyGap * 3f) / 4f
        if (
            rowHeight <= 0f ||
            request.viewWidth <= metrics.horizontalPadding * 2f
        ) {
            return
        }

        appendLetterRow(
            characters = "qwertyuiop",
            y = top,
            rowHeight = rowHeight,
            extraInset = 0f,
            request = request,
            metrics = metrics,
            output = output,
        )
        appendLetterRow(
            characters = "asdfghjkl",
            y = top + rowHeight + metrics.keyGap,
            rowHeight = rowHeight,
            extraInset = metrics.dp(18f),
            request = request,
            metrics = metrics,
            output = output,
        )
        appendWeightedRowKeys(
            items = KeyboardLayoutContract.thirdLetterRow(
                shifted = request.shifted,
                chineseMode = request.chineseMode,
            ),
            viewWidth = request.viewWidth,
            y = top + 2f * (rowHeight + metrics.keyGap),
            rowHeight = rowHeight,
            swipeMode = request.swipeMode,
            metrics = metrics,
            output = output,
        )
        appendWeightedRowKeys(
            items = KeyboardLayoutContract.functionRow(request.chineseMode),
            viewWidth = request.viewWidth,
            y = top + 3f * (rowHeight + metrics.keyGap),
            rowHeight = rowHeight,
            swipeMode = request.swipeMode,
            metrics = metrics,
            output = output,
        )
    }

    private fun appendLetterRow(
        characters: String,
        y: Float,
        rowHeight: Float,
        extraInset: Float,
        request: KeyboardLetterLayoutRequest,
        metrics: KeyboardMetrics,
        output: MutableList<Key>,
    ) {
        val left = metrics.horizontalPadding + extraInset
        val right = request.viewWidth - metrics.horizontalPadding - extraInset
        val itemWidth =
            (right - left - metrics.keyGap * (characters.length - 1)) / characters.length
        if (itemWidth <= 0f) return

        characters.forEachIndexed { index, character ->
            val x = left + index * (itemWidth + metrics.keyGap)
            output += Key(
                label = KeyboardLayoutContract.letterLabel(
                    character = character,
                    chineseMode = request.chineseMode,
                    shifted = request.shifted,
                ),
                action = KeyAction.EmitKey(character.code),
                bounds = RectF(x, y, x + itemWidth, y + rowHeight),
                hint = SwipeCharacterMap.forKey(character.code, request.swipeMode),
            )
        }
    }
}
