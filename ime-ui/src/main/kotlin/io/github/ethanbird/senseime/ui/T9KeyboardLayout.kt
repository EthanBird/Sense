package io.github.ethanbird.senseime.ui

import android.graphics.RectF

/** Three-by-three phone keypad primary-layout adapter. */
internal object T9KeyboardLayout : KeyboardLetterLayout {
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

        T9_ROWS.forEachIndexed { rowIndex, row ->
            appendT9Row(
                row = row,
                viewWidth = request.viewWidth,
                y = top + rowIndex * (rowHeight + metrics.keyGap),
                rowHeight = rowHeight,
                metrics = metrics,
                output = output,
            )
        }
        appendWeightedRowKeys(
            items = KeyboardLayoutContract.t9FunctionRow(request.chineseMode),
            viewWidth = request.viewWidth,
            y = top + 3f * (rowHeight + metrics.keyGap),
            rowHeight = rowHeight,
            swipeMode = request.swipeMode,
            metrics = metrics,
            output = output,
        )
    }

    private fun appendT9Row(
        row: Array<T9KeySpec>,
        viewWidth: Int,
        y: Float,
        rowHeight: Float,
        metrics: KeyboardMetrics,
        output: MutableList<Key>,
    ) {
        val usableWidth =
            viewWidth - metrics.horizontalPadding * 2f - metrics.keyGap * (row.size - 1)
        val keyWidth = usableWidth / row.size
        if (keyWidth <= 0f) return

        row.forEachIndexed { index, spec ->
            val x = metrics.horizontalPadding + index * (keyWidth + metrics.keyGap)
            output += Key(
                label = spec.digit.toString(),
                action = KeyAction.EmitKey(spec.digit.code),
                bounds = RectF(x, y, x + keyWidth, y + rowHeight),
                visualLegend = spec.legend,
            )
        }
    }

    private data class T9KeySpec(
        val digit: Char,
        val legend: String,
    )

    private val T9_ROWS = arrayOf(
        arrayOf(T9KeySpec('1', "'"), T9KeySpec('2', "ABC"), T9KeySpec('3', "DEF")),
        arrayOf(T9KeySpec('4', "GHI"), T9KeySpec('5', "JKL"), T9KeySpec('6', "MNO")),
        arrayOf(T9KeySpec('7', "PQRS"), T9KeySpec('8', "TUV"), T9KeySpec('9', "WXYZ")),
    )
}
