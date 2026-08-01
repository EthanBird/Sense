package io.github.ethanbird.senseime.ui

import android.graphics.RectF

/**
 * Five-column nine-key layout matching the visual hierarchy of mainstream Chinese IMEs.
 *
 * The centre three-by-three grid owns pinyin input. Punctuation and editing live on narrow rails,
 * keeping the letter groups visually dominant without changing the digit key codes consumed by
 * the T9 decoder.
 */
internal object T9KeyboardLayout : KeyboardLetterLayout {
    override fun appendKeys(
        request: KeyboardLetterLayoutRequest,
        metrics: KeyboardMetrics,
        output: MutableList<Key>,
    ) {
        val top = request.chromeBottom + metrics.dp(7f)
        val bottom = request.viewHeight - metrics.systemBarHeight - metrics.dp(7f)
        val availableHeight = bottom - top
        if (
            availableHeight <= metrics.keyGap * 3f ||
            request.viewWidth <= metrics.horizontalPadding * 2f + metrics.keyGap * 4f
        ) {
            return
        }

        val bottomRowHeight = availableHeight * 0.23f
        val bottomRowTop = bottom - bottomRowHeight
        val mainBottom = bottomRowTop - metrics.keyGap
        val mainHeight = mainBottom - top
        val centreRowHeight = (mainHeight - metrics.keyGap * 2f) / 3f
        if (centreRowHeight <= 0f) return

        val columns = weightedColumns(request.viewWidth, metrics)
        T9_KEYS.forEachIndexed { index, spec ->
            val row = index / 3
            val column = index % 3 + 1
            val y = top + row * (centreRowHeight + metrics.keyGap)
            output += Key(
                label = spec.label,
                action = KeyAction.EmitKey(spec.digit.code),
                bounds = RectF(
                    columns[column].left,
                    y,
                    columns[column].right,
                    y + centreRowHeight,
                ),
                style = KeyStyle.T9_PRIMARY,
            )
        }

        val pinyinChoices = request.t9PinyinChoices.take(MAX_LEFT_RAIL_CHOICES)
        val punctuation = if (request.chineseMode) {
            listOf(
                "，" to KeyAction.EmitKey(KeyCodes.COMMA),
                "。" to KeyAction.EmitKey(KeyCodes.PERIOD),
                "？" to KeyAction.CommitText("？"),
                "！" to KeyAction.CommitText("！"),
            )
        } else {
            listOf(
                "," to KeyAction.EmitKey(KeyCodes.COMMA),
                "." to KeyAction.EmitKey(KeyCodes.PERIOD),
                "?" to KeyAction.CommitText("?"),
                "!" to KeyAction.CommitText("!"),
            )
        }
        val leftRail = if (!request.t9CompositionActive) {
            punctuation.map { (label, action) ->
                LeftRailKey(label = label, action = action)
            }
        } else {
            pinyinChoices.mapIndexed { index, choice ->
                LeftRailKey(
                    label = choice.canonical,
                    visualLegend = choice.preview.takeUnless { it == choice.canonical },
                    action = KeyAction.SelectT9PinyinChoice(
                        revision = request.t9PinyinChoiceRevision,
                        index = index,
                    ),
                )
            }
        }
        val leftRailRowHeight = (mainHeight - metrics.keyGap * 3f) / 4f
        leftRail.forEachIndexed { row, item ->
            val y = top + row * (leftRailRowHeight + metrics.keyGap)
            output += Key(
                label = item.label,
                action = item.action,
                bounds = RectF(
                    columns[0].left,
                    y,
                    columns[0].right,
                    y + leftRailRowHeight,
                ),
                visualLegend = item.visualLegend,
                style = KeyStyle.T9_LEFT_RAIL,
            )
        }

        val rightRailHeight = (mainHeight - metrics.keyGap * 2f) / 3f
        val rightRail = listOf(
            RightRailKey(label = "", keyCode = KeyCodes.DELETE, icon = Icon.DELETE),
            RightRailKey(label = "重输", keyCode = KeyCodes.T9_REINPUT),
            RightRailKey(label = "0", keyCode = '0'.code),
        )
        rightRail.forEachIndexed { row, item ->
            val y = top + row * (rightRailHeight + metrics.keyGap)
            output += Key(
                label = item.label,
                action = KeyAction.EmitKey(item.keyCode),
                bounds = RectF(
                    columns[4].left,
                    y,
                    columns[4].right,
                    y + rightRailHeight,
                ),
                style = KeyStyle.T9_RAIL,
                icon = item.icon,
            )
        }

        appendBottomRow(
            request = request,
            metrics = metrics,
            top = bottomRowTop,
            height = bottomRowHeight,
            output = output,
        )
    }

    private fun appendBottomRow(
        request: KeyboardLetterLayoutRequest,
        metrics: KeyboardMetrics,
        top: Float,
        height: Float,
        output: MutableList<Key>,
    ) {
        val items = listOf(
            KeyboardLayoutContract.WeightedKey("符", KeyCodes.SYMBOLS, 0.9f, action = true),
            KeyboardLayoutContract.WeightedKey("123", KeyCodes.NUMBERS, 0.95f, action = true),
            KeyboardLayoutContract.WeightedKey("空格", KeyCodes.SPACE, 2.15f),
            KeyboardLayoutContract.WeightedKey("中/英", KeyCodes.LANGUAGE, 1f, action = true),
            KeyboardLayoutContract.WeightedKey("↵", KeyCodes.ENTER, 1f, action = true),
        )
        appendWeightedRowKeys(
            items = items,
            viewWidth = request.viewWidth,
            y = top,
            rowHeight = height,
            swipeMode = request.swipeMode,
            metrics = metrics,
            output = output,
        )
    }

    private fun weightedColumns(
        viewWidth: Int,
        metrics: KeyboardMetrics,
    ): Array<Column> {
        val weights = floatArrayOf(0.72f, 1f, 1f, 1f, 0.82f)
        val usableWidth =
            viewWidth - metrics.horizontalPadding * 2f - metrics.keyGap * (weights.size - 1)
        val totalWeight = weights.sum()
        var x = metrics.horizontalPadding
        return Array(weights.size) { index ->
            val right = x + usableWidth * weights[index] / totalWeight
            Column(x, right).also { x = right + metrics.keyGap }
        }
    }

    private data class Column(
        val left: Float,
        val right: Float,
    )

    private data class T9KeySpec(
        val digit: Char,
        val label: String,
    )

    private data class LeftRailKey(
        val label: String,
        val action: KeyAction,
        val visualLegend: String? = null,
    )

    private data class RightRailKey(
        val label: String,
        val keyCode: Int,
        val icon: Icon? = null,
    )

    private val T9_KEYS = listOf(
        T9KeySpec('1', "分词"),
        T9KeySpec('2', "ABC"),
        T9KeySpec('3', "DEF"),
        T9KeySpec('4', "GHI"),
        T9KeySpec('5', "JKL"),
        T9KeySpec('6', "MNO"),
        T9KeySpec('7', "PQRS"),
        T9KeySpec('8', "TUV"),
        T9KeySpec('9', "WXYZ"),
    )

    private const val MAX_LEFT_RAIL_CHOICES = 4
}
