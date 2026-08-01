package io.github.ethanbird.senseime.ui

import android.graphics.RectF

/** Single-scene keyboard scheme picker modelled after a compact IME bottom sheet. */
internal class KeyboardInputSchemePanelLayout(
    private val metrics: KeyboardMetrics,
) {
    fun appendKeys(
        viewWidth: Int,
        viewHeight: Int,
        selectedChoice: KeyboardInputSchemeChoice,
        output: MutableList<Key>,
    ) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val headerHeight = metrics.candidateHeight
        val closeWidth = metrics.dp(58f)
        output += Key(
            label = "",
            action = KeyAction.ShowPanel(
                panel = KeyboardPanel.LETTERS,
                keyCode = KeyCodes.LETTERS,
            ),
            bounds = RectF(
                viewWidth - closeWidth - metrics.dp(4f),
                metrics.dp(3f),
                viewWidth - metrics.dp(4f),
                headerHeight - metrics.dp(3f),
            ),
            style = KeyStyle.TOOL,
            icon = Icon.BACK,
        )

        val horizontalPadding = metrics.dp(18f)
        val gap = metrics.dp(10f)
        val contentTop = headerHeight + metrics.dp(13f)
        val contentBottom = viewHeight - metrics.dp(14f)
        val cardHeight = minOf(metrics.dp(112f), contentBottom - contentTop)
        val cardWidth =
            (viewWidth - horizontalPadding * 2f - gap * (OPTIONS.size - 1)) / OPTIONS.size
        if (cardHeight <= 0f || cardWidth <= 0f) return

        OPTIONS.forEachIndexed { index, option ->
            val left = horizontalPadding + index * (cardWidth + gap)
            output += Key(
                label = option.label,
                action = KeyAction.SelectInputScheme(option.choice),
                bounds = RectF(left, contentTop, left + cardWidth, contentTop + cardHeight),
                visualLegend = option.glyph,
                style = KeyStyle.INPUT_SCHEME_OPTION,
                selected = option.choice == selectedChoice,
            )
        }
    }

    private data class Option(
        val choice: KeyboardInputSchemeChoice,
        val glyph: String,
        val label: String,
    )

    private companion object {
        val OPTIONS = listOf(
            Option(KeyboardInputSchemeChoice.PINYIN_T9, "拼₉", "9键拼音"),
            Option(KeyboardInputSchemeChoice.PINYIN_QWERTY, "拼₂₆", "26键拼音"),
            Option(KeyboardInputSchemeChoice.WUBI_86, "五", "五笔86"),
        )
    }
}
