package io.github.ethanbird.senseime.ui

import android.graphics.RectF

/**
 * Layout owner for the stable keyboard chrome and the primary input panels.
 *
 * The Android View supplies state and receives scene keys; this class owns the
 * geometry. In particular, no method reads View.width/View.height implicitly.
 */
internal class KeyboardPrimaryLayout(
    private val metrics: KeyboardMetrics,
    private val qwertyLayout: KeyboardLetterLayout = QwertyKeyboardLayout,
    private val t9Layout: KeyboardLetterLayout = T9KeyboardLayout,
) {
    fun appendToolbar(viewWidth: Int, output: MutableList<Key>) {
        val items = TOOLBAR_ITEMS
        val slot = viewWidth / items.size.toFloat()
        items.forEachIndexed { index, (icon, code) ->
            output += Key(
                label = "",
                action = if (icon == Icon.KEYBOARD) {
                    KeyAction.ShowPanel(
                        panel = KeyboardPanel.INPUT_SCHEMES,
                        keyCode = code,
                    )
                } else {
                    KeyAction.EmitKey(code)
                },
                bounds = RectF(
                    index * slot + metrics.dp(5f),
                    metrics.dp(3f),
                    (index + 1) * slot - metrics.dp(5f),
                    metrics.toolbarHeight - metrics.dp(3f),
                ),
                style = KeyStyle.TOOL,
                icon = icon,
            )
        }
    }

    fun appendToolbox(
        viewWidth: Int,
        viewHeight: Int,
        chromeBottom: Float,
        output: MutableList<Key>,
    ) {
        val top = chromeBottom + metrics.dp(38f)
        val bottom = viewHeight - metrics.systemBarHeight - metrics.dp(6f)
        if (bottom <= top) return

        KeyboardLayoutContract.toolboxLayout(
            viewWidth = viewWidth.toFloat(),
            contentTop = top,
            contentBottom = bottom,
            horizontalPadding = metrics.dp(10f),
            horizontalGap = metrics.dp(5f),
            verticalGap = metrics.dp(4f),
        ).forEach { slot ->
            output += Key(
                label = slot.item.label,
                action = KeyAction.EmitKey(slot.item.keyCode),
                bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                style = KeyStyle.TOOLBOX_CARD,
                icon = slot.item.icon(),
            )
        }
    }

    fun appendLetters(
        mode: PrimaryKeyboardMode,
        request: KeyboardLetterLayoutRequest,
        output: MutableList<Key>,
    ) {
        val layout = when (mode) {
            PrimaryKeyboardMode.QWERTY -> qwertyLayout
            PrimaryKeyboardMode.T9 -> t9Layout
        }
        layout.appendKeys(request, metrics, output)
    }

    fun appendNumbers(
        viewWidth: Int,
        viewHeight: Int,
        chromeBottom: Float,
        chineseMode: Boolean,
        output: MutableList<Key>,
    ) {
        val top = chromeBottom + metrics.dp(7f)
        val bottom = viewHeight - metrics.systemBarHeight - metrics.dp(7f)
        if (
            bottom - top <= metrics.keyGap * 4f ||
            viewWidth.toFloat() <= metrics.horizontalPadding * 2f + metrics.keyGap * 4f
        ) {
            return
        }

        KeyboardLayoutContract.numericPadLayout(
            viewWidth = viewWidth.toFloat(),
            contentTop = top,
            contentBottom = bottom,
            horizontalPadding = metrics.horizontalPadding,
            gap = metrics.keyGap,
            chineseMode = chineseMode,
        ).forEach { slot ->
            val item = slot.key
            val icon = actionIcon(item.code)
            output += Key(
                label = item.label,
                action = item.text?.let(KeyAction::CommitText)
                    ?: KeyAction.EmitKey(item.code),
                bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                style = when {
                    item.column == 0 && item.row < 4 -> KeyStyle.RAIL
                    item.code < 0 || icon != null -> KeyStyle.ACTION
                    else -> KeyStyle.LETTER
                },
                icon = icon,
            )
        }
    }

    fun appendSystemBar(
        viewWidth: Int,
        viewHeight: Int,
        output: MutableList<Key>,
    ) {
        val top = viewHeight - metrics.systemBarHeight
        KeyboardLayoutContract.systemBar.forEach { item ->
            val bounds = when (item.side) {
                KeyboardLayoutContract.Side.LEFT -> RectF(
                    metrics.dp(13f),
                    top + metrics.dp(5f),
                    metrics.dp(73f),
                    viewHeight - metrics.dp(5f),
                )

                KeyboardLayoutContract.Side.RIGHT -> RectF(
                    viewWidth - metrics.dp(73f),
                    top + metrics.dp(5f),
                    viewWidth - metrics.dp(13f),
                    viewHeight - metrics.dp(5f),
                )
            }
            output += Key(
                label = "",
                action = KeyAction.EmitKey(item.code),
                bounds = bounds,
                style = KeyStyle.SYSTEM,
            )
        }
    }

    fun appendWeightedRow(
        items: List<KeyboardLayoutContract.WeightedKey>,
        viewWidth: Int,
        y: Float,
        rowHeight: Float,
        swipeMode: SwipeCharacterMode,
        output: MutableList<Key>,
        backToLettersIcon: Icon? = null,
    ) {
        appendWeightedRowKeys(
            items = items,
            viewWidth = viewWidth,
            y = y,
            rowHeight = rowHeight,
            swipeMode = swipeMode,
            metrics = metrics,
            output = output,
            backToLettersIcon = backToLettersIcon,
        )
    }

    private fun KeyboardLayoutContract.ToolboxItem.icon(): Icon = when (this) {
        KeyboardLayoutContract.ToolboxItem.SYMBOLS -> Icon.SYMBOLS
        KeyboardLayoutContract.ToolboxItem.EDITOR -> Icon.EDITOR
        KeyboardLayoutContract.ToolboxItem.VOICE -> Icon.VOICE
        KeyboardLayoutContract.ToolboxItem.CLIPBOARD -> Icon.CLIPBOARD
        KeyboardLayoutContract.ToolboxItem.EMOJI -> Icon.EMOJI
        KeyboardLayoutContract.ToolboxItem.SETTINGS -> Icon.SETTINGS
    }

    private companion object {
        val TOOLBAR_ITEMS = listOf(
            Icon.TOOLS to KeyCodes.TOOLBOX,
            Icon.KEYBOARD to KeyCodes.LETTERS,
            Icon.EMOJI to KeyCodes.EMOJI,
            Icon.EDITOR to KeyCodes.EDITOR,
            Icon.VOICE to KeyCodes.VOICE,
            Icon.HIDE to KeyCodes.HIDE,
        )
    }
}

internal fun appendWeightedRowKeys(
    items: List<KeyboardLayoutContract.WeightedKey>,
    viewWidth: Int,
    y: Float,
    rowHeight: Float,
    swipeMode: SwipeCharacterMode,
    metrics: KeyboardMetrics,
    output: MutableList<Key>,
    backToLettersIcon: Icon? = null,
    visualLegendResolver: ((keyCode: Int, swipeOutput: String?) -> String?)? = null,
) {
    if (items.isEmpty()) return
    val totalWeight = items.sumOf { it.weight.toDouble() }.toFloat()
    val usable =
        viewWidth - metrics.horizontalPadding * 2f - metrics.keyGap * (items.size - 1)
    if (totalWeight <= 0f || usable <= 0f || rowHeight <= 0f) return

    var x = metrics.horizontalPadding
    items.forEach { item ->
        val itemWidth = usable * item.weight / totalWeight
        val swipeOutput = if (item.code > 0) {
            SwipeCharacterMap.forKey(item.code, swipeMode)
        } else {
            null
        }
        val icon = when (item.code) {
            KeyCodes.LETTERS -> backToLettersIcon
            else -> actionIcon(item.code)
        }
        val visualLegend = if (visualLegendResolver == null) {
            swipeOutput
        } else {
            visualLegendResolver(item.code, swipeOutput)
        }
        output += Key(
            label = if (icon == null) item.label else "",
            action = KeyAction.EmitKey(item.code),
            bounds = RectF(x, y, x + itemWidth, y + rowHeight),
            visualLegend = visualLegend,
            swipeOutput = swipeOutput,
            style = if (item.action) KeyStyle.ACTION else KeyStyle.LETTER,
            icon = icon,
        )
        x += itemWidth + metrics.keyGap
    }
}

internal fun actionIcon(code: Int): Icon? = when (code) {
    KeyCodes.SHIFT -> Icon.SHIFT
    KeyCodes.DELETE -> Icon.DELETE
    KeyCodes.SPACE -> Icon.SPACE
    KeyCodes.ENTER -> Icon.ENTER
    else -> null
}
