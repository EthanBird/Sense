package io.github.ethanbird.senseime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class SenseKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface KeyListener {
        fun onKey(code: Int)
    }

    enum class Panel {
        LETTERS,
        NUMBERS,
        SYMBOLS,
        EMOJI,
        CLIPBOARD,
    }

    private enum class KeyStyle {
        LETTER,
        ACTION,
        TOOL,
        SYSTEM,
        CARD,
    }

    private data class Key(
        val label: String,
        val code: Int,
        val bounds: RectF,
        val hint: String? = null,
        val style: KeyStyle = KeyStyle.LETTER,
        val text: String? = null,
    )

    var keyListener: KeyListener? = null
    var candidateListener: ((index: Int) -> Unit)? = null
    var textListener: ((text: String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keys = mutableListOf<Key>()
    private val candidateBounds = mutableListOf<RectF>()
    private val candidateTextAnchors = mutableListOf<Float>()
    private var candidates: List<String> = emptyList()
    private var clipboardItems: List<String> = emptyList()
    private var composing: String = ""
    private var activeKeyIndex = -1
    private var activeCandidateIndex = -1
    private var shifted = false
    private var chineseMode = true
    private var panel = Panel.LETTERS
    private var backgroundShader: Shader? = null

    private val candidateHeight = dp(45f)
    private val toolbarHeight = dp(42f)
    private val systemBarHeight = dp(52f)
    private val keyGap = dp(5f)
    private val horizontalPadding = dp(6f)
    private val keyRadius = dp(8f)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun updateComposing(text: String, values: List<String>) {
        composing = text
        candidates = values.take(6)
        rebuildCandidateBounds(width)
        invalidate()
    }

    fun setShifted(value: Boolean) {
        if (shifted == value) return
        shifted = value
        rebuildKeys(width, height)
        invalidate()
    }

    fun setChineseMode(value: Boolean) {
        if (chineseMode == value) return
        chineseMode = value
        rebuildKeys(width, height)
        invalidate()
    }

    fun setPanel(value: Panel) {
        if (panel == value) return
        panel = value
        rebuildKeys(width, height)
        invalidate()
    }

    fun showClipboard(values: List<String>) {
        clipboardItems = values
        panel = Panel.CLIPBOARD
        rebuildKeys(width, height)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(360f).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundShader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            color(0xFFF1F5FA.toInt(), 0xFF171819.toInt()),
            color(0xFFE6EDF6.toInt(), 0xFF111213.toInt()),
            Shader.TileMode.CLAMP,
        )
        rebuildCandidateBounds(w)
        rebuildKeys(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        drawCandidates(canvas)
        drawToolbar(canvas)
        if (panel == Panel.CLIPBOARD) drawClipboardHeader(canvas)
        drawKeys(canvas)
    }

    private fun drawBackground(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = backgroundShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = color(0x18000000, 0x2A000000)
        canvas.drawRect(0f, height - systemBarHeight, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawCandidates(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = color(0x22FFFFFF, 0x0FFFFFFF)
        canvas.drawRect(0f, 0f, width.toFloat(), candidateHeight, paint)

        if (candidates.isEmpty()) {
            paint.color = color(0xFF596579.toInt(), 0xFFB8BBC2.toInt())
            paint.textSize = sp(13f)
            paint.textAlign = Paint.Align.LEFT
            val label = when {
                composing.isNotBlank() -> composing
                chineseMode -> "中 · 先思输入法"
                else -> "英 · Sense"
            }
            drawCenteredText(canvas, label, dp(14f), candidateHeight / 2f)
            return
        }

        if (composing.isNotBlank()) {
            paint.color = color(0xFF667085.toInt(), 0xFF8F949E.toInt())
            paint.textSize = sp(11f)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(composing.take(12), dp(10f), dp(14f), paint)
        }

        candidateBounds.forEachIndexed { index, bounds ->
            val text = candidates.getOrNull(index) ?: return@forEachIndexed
            if (index == activeCandidateIndex) {
                paint.color = color(0x294F7CF5, 0x505E63D8)
                canvas.drawRoundRect(bounds, dp(7f), dp(7f), paint)
            }
            paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
            paint.textSize = sp(17f)
            paint.textAlign = Paint.Align.LEFT
            drawCenteredText(canvas, text, candidateTextAnchors[index], bounds.centerY() + dp(2f))
        }
    }

    private fun drawToolbar(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = color(0x14000000, 0x20000000)
        canvas.drawRect(0f, candidateHeight, width.toFloat(), candidateHeight + toolbarHeight, paint)
        paint.color = color(0x1F172033, 0x35FFFFFF)
        canvas.drawRect(0f, candidateHeight + toolbarHeight - max(1f, density), width.toFloat(), candidateHeight + toolbarHeight, paint)
    }

    private fun drawClipboardHeader(canvas: Canvas) {
        paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
        paint.textSize = sp(14f)
        paint.textAlign = Paint.Align.LEFT
        drawCenteredText(canvas, "剪贴板 · 点击内容即可上屏", dp(14f), candidateHeight + toolbarHeight + dp(18f))
    }

    private fun drawKeys(canvas: Canvas) {
        keys.forEachIndexed { index, key ->
            val pressed = index == activeKeyIndex
            when (key.style) {
                KeyStyle.TOOL -> drawToolKey(canvas, key, pressed)
                KeyStyle.SYSTEM -> drawSystemKey(canvas, key, pressed)
                KeyStyle.CARD -> drawCardKey(canvas, key, pressed)
                else -> drawKeyboardKey(canvas, key, pressed)
            }
        }
    }

    private fun drawKeyboardKey(canvas: Canvas, key: Key, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
        } else if (key.style == KeyStyle.ACTION) {
            color(0xFFCED6E1.toInt(), 0xFF242526.toInt())
        } else {
            color(0xEFFFFFFF.toInt(), 0xFF303132.toInt())
        }
        canvas.drawRoundRect(key.bounds, keyRadius, keyRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density * 0.55f)
        paint.color = color(0x55788798, 0x1FFFFFFF)
        canvas.drawRoundRect(key.bounds, keyRadius, keyRadius, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (pressed) Color.WHITE else color(0xFF111827.toInt(), 0xFFF6F7F9.toInt())
        paint.textSize = sp(if (key.label.length > 2) 13f else 20f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY() + if (key.hint == null) 0f else dp(3f))

        key.hint?.let { hint ->
            paint.color = color(0xFF7C8799.toInt(), 0xFF83868D.toInt())
            paint.textSize = sp(8.5f)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(hint, key.bounds.centerX(), key.bounds.top + dp(10f), paint)
        }
    }

    private fun drawToolKey(canvas: Canvas, key: Key, pressed: Boolean) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x254F7CF5, 0x405E63D8)
            canvas.drawRoundRect(key.bounds, dp(9f), dp(9f), paint)
        }
        paint.color = color(0xFF586477.toInt(), 0xFFAAAEB6.toInt())
        paint.textSize = sp(if (key.label.length > 1) 15f else 20f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
    }

    private fun drawSystemKey(canvas: Canvas, key: Key, pressed: Boolean) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x244F7CF5, 0x405E63D8)
            canvas.drawRoundRect(key.bounds, dp(12f), dp(12f), paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = color(0xFF39465B.toInt(), 0xFFE1E3E8.toInt())
        if (key.code == KeyCodes.SWITCH_INPUT_METHOD) {
            val body = RectF(
                key.bounds.centerX() - dp(15f),
                key.bounds.centerY() - dp(10f),
                key.bounds.centerX() + dp(15f),
                key.bounds.centerY() + dp(10f),
            )
            canvas.drawRoundRect(body, dp(4f), dp(4f), paint)
            repeat(3) { row ->
                repeat(4) { column ->
                    val x = body.left + dp(6f) + column * dp(6f)
                    val y = body.top + dp(6f) + row * dp(5f)
                    canvas.drawCircle(x, y, dp(0.8f), paint)
                }
            }
        } else {
            val back = RectF(
                key.bounds.centerX() - dp(13f),
                key.bounds.centerY() - dp(9f),
                key.bounds.centerX() + dp(11f),
                key.bounds.centerY() + dp(11f),
            )
            val front = RectF(back.left + dp(6f), back.top + dp(5f), back.right + dp(6f), back.bottom + dp(5f))
            canvas.drawRoundRect(back, dp(4f), dp(4f), paint)
            canvas.drawRoundRect(front, dp(4f), dp(4f), paint)
            canvas.drawLine(front.left + dp(5f), front.top + dp(6f), front.right - dp(5f), front.top + dp(6f), paint)
            canvas.drawLine(front.left + dp(5f), front.top + dp(11f), front.right - dp(5f), front.top + dp(11f), paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawCardKey(canvas: Canvas, key: Key, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0x335B7DF0, 0x556D61D8)
        } else {
            color(0xBFFFFFFF.toInt(), 0xFF292A2C.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(10f), dp(10f), paint)
        paint.color = color(0xFF263247.toInt(), 0xFFF0F1F4.toInt())
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.LEFT
        val x = key.bounds.left + dp(12f)
        val firstLine = key.label.take(18)
        drawCenteredText(canvas, firstLine, x, key.bounds.centerY() - if (key.label.length > 18) dp(8f) else 0f)
        if (key.label.length > 18) {
            paint.color = color(0xFF6B7484.toInt(), 0xFF9B9EA5.toInt())
            drawCenteredText(canvas, key.label.drop(18).take(18), x, key.bounds.centerY() + dp(10f))
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, centerY: Float) {
        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun rebuildCandidateBounds(viewWidth: Int) {
        candidateBounds.clear()
        candidateTextAnchors.clear()
        if (viewWidth <= 0 || candidates.isEmpty()) return
        paint.textSize = sp(17f)
        val top = if (composing.isBlank()) dp(3f) else dp(13f)
        val slots = KeyboardLayoutContract.leftAlignedCandidateSlots(
            viewWidth = viewWidth.toFloat(),
            measuredTextWidths = candidates.map(paint::measureText),
            padding = horizontalPadding,
            textInset = dp(9f),
            gap = dp(3f),
            minimumWidth = dp(44f),
        )
        slots.forEach { slot ->
            candidateTextAnchors += slot.textAnchor
            candidateBounds += RectF(
                slot.left,
                top,
                slot.right,
                candidateHeight - dp(3f),
            )
        }
    }

    private fun rebuildKeys(viewWidth: Int, viewHeight: Int) {
        keys.clear()
        if (viewWidth <= 0 || viewHeight <= 0) return
        layoutToolbar(viewWidth)
        when (panel) {
            Panel.LETTERS -> layoutLetters(viewWidth, viewHeight)
            Panel.NUMBERS -> layoutNumbers(viewWidth, viewHeight)
            Panel.SYMBOLS -> layoutSymbols(viewWidth, viewHeight)
            Panel.EMOJI -> layoutEmoji(viewWidth, viewHeight)
            Panel.CLIPBOARD -> layoutClipboard(viewWidth, viewHeight)
        }
        layoutSystemBar(viewWidth, viewHeight)
    }

    private fun layoutToolbar(viewWidth: Int) {
        val items = listOf(
            "⌘" to KeyCodes.SYMBOLS,
            "⌨" to KeyCodes.LETTERS,
            "☺" to KeyCodes.EMOJI,
            "↔" to KeyCodes.EDITOR,
            "🎙" to KeyCodes.VOICE,
            "⌄" to KeyCodes.HIDE,
        )
        val slot = viewWidth / items.size.toFloat()
        items.forEachIndexed { index, (label, code) ->
            keys += Key(
                label,
                code,
                RectF(index * slot + dp(5f), candidateHeight + dp(3f), (index + 1) * slot - dp(5f), candidateHeight + toolbarHeight - dp(3f)),
                style = KeyStyle.TOOL,
            )
        }
    }

    private fun layoutLetters(viewWidth: Int, viewHeight: Int) {
        val (top, rowHeight) = keyboardGrid(viewHeight)
        val numbers = "1234567890"
        layoutEqualRow(
            "qwertyuiop".mapIndexed { index, character ->
                KeySpec(if (shifted) character.uppercase() else character.toString(), character.code, numbers[index].toString())
            },
            top,
            rowHeight,
        )
        val punctuationHints = listOf("~", "!", "@", "#", "%", "“", "”", "*", "?")
        layoutEqualRow(
            "asdfghjkl".mapIndexed { index, character ->
                KeySpec(if (shifted) character.uppercase() else character.toString(), character.code, punctuationHints[index])
            },
            top + rowHeight + keyGap,
            rowHeight,
            dp(18f),
        )
        layoutWeightedRow(
            KeyboardLayoutContract.thirdLetterRow(shifted).map { it.toWeightedSpec() },
            top + 2 * (rowHeight + keyGap),
            rowHeight,
        )
        layoutBottomRow(top + 3 * (rowHeight + keyGap), rowHeight)
    }

    private fun layoutNumbers(viewWidth: Int, viewHeight: Int) {
        val (top, rowHeight) = keyboardGrid(viewHeight)
        layoutEqualRow("1234567890".map { KeySpec(it.toString(), it.code) }, top, rowHeight)
        layoutEqualRow(listOf("@", "#", "¥", "_", "&", "-", "+", "(", ")", "/").map { KeySpec(it, 0, text = it) }, top + rowHeight + keyGap, rowHeight)
        layoutWeightedRow(
            listOf(
                WeightedSpec("符", KeyCodes.SYMBOLS, 1.2f, KeyStyle.ACTION),
                WeightedSpec("*", 0, 1f, text = "*"),
                WeightedSpec("\"", 0, 1f, text = "\""),
                WeightedSpec("'", 0, 1f, text = "'"),
                WeightedSpec(":", 0, 1f, text = ":"),
                WeightedSpec(";", 0, 1f, text = ";"),
                WeightedSpec("!", 0, 1f, text = "!"),
                WeightedSpec("?", 0, 1f, text = "?"),
                WeightedSpec("⌫", KeyCodes.DELETE, 1.25f, KeyStyle.ACTION),
            ),
            top + 2 * (rowHeight + keyGap),
            rowHeight,
        )
        layoutBottomRow(top + 3 * (rowHeight + keyGap), rowHeight, returnLabel = "ABC")
    }

    private fun layoutSymbols(viewWidth: Int, viewHeight: Int) {
        val (top, rowHeight) = keyboardGrid(viewHeight)
        val rows = listOf(
            listOf("【", "】", "「", "」", "《", "》", "〈", "〉", "…", "—"),
            listOf("±", "×", "÷", "=", "≠", "≈", "≤", "≥", "℃", "‰"),
        )
        rows.forEachIndexed { index, values ->
            layoutEqualRow(values.map { KeySpec(it, 0, text = it) }, top + index * (rowHeight + keyGap), rowHeight)
        }
        layoutWeightedRow(
            buildList {
                add(WeightedSpec("123", KeyCodes.NUMBERS, 1.25f, KeyStyle.ACTION))
                listOf("·", "©", "®", "™", "✓", "→", "←").forEach { add(WeightedSpec(it, 0, 1f, text = it)) }
                add(WeightedSpec("⌫", KeyCodes.DELETE, 1.25f, KeyStyle.ACTION))
            },
            top + 2 * (rowHeight + keyGap),
            rowHeight,
        )
        layoutBottomRow(top + 3 * (rowHeight + keyGap), rowHeight, returnLabel = "ABC")
    }

    private fun layoutEmoji(viewWidth: Int, viewHeight: Int) {
        val (top, rowHeight) = keyboardGrid(viewHeight)
        val emojiRows = listOf(
            listOf("😀", "😂", "🥰", "😍", "😊", "😭", "😡", "👍"),
            listOf("👏", "🙏", "💪", "🎉", "❤️", "🔥", "✨", "🌙"),
            listOf("🌹", "🍀", "☕", "🎂", "🎁", "💡", "✅", "❗"),
        )
        emojiRows.forEachIndexed { rowIndex, values ->
            layoutEqualRow(values.map { KeySpec(it, 0, text = it) }, top + rowIndex * (rowHeight + keyGap), rowHeight)
        }
        layoutWeightedRow(
            listOf(
                WeightedSpec("ABC", KeyCodes.LETTERS, 1.2f, KeyStyle.ACTION),
                WeightedSpec("空格", KeyCodes.SPACE, 4f),
                WeightedSpec("⌫", KeyCodes.DELETE, 1.2f, KeyStyle.ACTION),
                WeightedSpec("↵", KeyCodes.ENTER, 1.2f, KeyStyle.ACTION),
            ),
            top + 3 * (rowHeight + keyGap),
            rowHeight,
        )
    }

    private fun layoutClipboard(viewWidth: Int, viewHeight: Int) {
        val top = candidateHeight + toolbarHeight + dp(31f)
        val bottom = viewHeight - systemBarHeight - dp(8f)
        if (clipboardItems.isEmpty()) {
            keys += Key(
                "系统剪贴板暂无文本",
                KeyCodes.LETTERS,
                RectF(horizontalPadding, top, viewWidth - horizontalPadding, bottom),
                style = KeyStyle.CARD,
            )
            return
        }
        val columns = 2
        val rows = 2
        val cardWidth = (viewWidth - horizontalPadding * 2 - keyGap) / columns
        val cardHeight = (bottom - top - keyGap) / rows
        clipboardItems.take(columns * rows).forEachIndexed { index, text ->
            val row = index / columns
            val column = index % columns
            val left = horizontalPadding + column * (cardWidth + keyGap)
            val cardTop = top + row * (cardHeight + keyGap)
            keys += Key(
                text.replace('\n', ' '),
                0,
                RectF(left, cardTop, left + cardWidth, cardTop + cardHeight),
                style = KeyStyle.CARD,
                text = text,
            )
        }
    }

    private fun layoutBottomRow(y: Float, rowHeight: Float, returnLabel: String? = null) {
        layoutWeightedRow(
            KeyboardLayoutContract.functionRow(chineseMode, returnToLetters = returnLabel != null).map { it.toWeightedSpec() },
            y,
            rowHeight,
        )
    }

    private fun layoutSystemBar(viewWidth: Int, viewHeight: Int) {
        val top = viewHeight - systemBarHeight
        KeyboardLayoutContract.systemBar.forEach { item ->
            val bounds = when (item.side) {
                KeyboardLayoutContract.Side.LEFT -> RectF(dp(13f), top + dp(5f), dp(73f), viewHeight - dp(5f))
                KeyboardLayoutContract.Side.RIGHT -> RectF(viewWidth - dp(73f), top + dp(5f), viewWidth - dp(13f), viewHeight - dp(5f))
            }
            keys += Key("", item.code, bounds, style = KeyStyle.SYSTEM)
        }
    }

    private fun keyboardGrid(viewHeight: Int): Pair<Float, Float> {
        val top = candidateHeight + toolbarHeight + dp(7f)
        val bottom = viewHeight - systemBarHeight - dp(7f)
        return top to (bottom - top - keyGap * 3) / 4f
    }

    private data class KeySpec(
        val label: String,
        val code: Int,
        val hint: String? = null,
        val style: KeyStyle = KeyStyle.LETTER,
        val text: String? = null,
    )

    private data class WeightedSpec(
        val label: String,
        val code: Int,
        val weight: Float,
        val style: KeyStyle = KeyStyle.LETTER,
        val text: String? = null,
    )

    private fun KeyboardLayoutContract.WeightedKey.toWeightedSpec(): WeightedSpec = WeightedSpec(
        label = label,
        code = code,
        weight = weight,
        style = if (action) KeyStyle.ACTION else KeyStyle.LETTER,
    )

    private fun layoutEqualRow(
        items: List<KeySpec>,
        y: Float,
        rowHeight: Float,
        extraInset: Float = 0f,
    ) {
        val left = horizontalPadding + extraInset
        val right = width - horizontalPadding - extraInset
        val itemWidth = (right - left - keyGap * (items.size - 1)) / items.size
        items.forEachIndexed { index, item ->
            val x = left + index * (itemWidth + keyGap)
            keys += Key(item.label, item.code, RectF(x, y, x + itemWidth, y + rowHeight), item.hint, item.style, item.text)
        }
    }

    private fun layoutWeightedRow(items: List<WeightedSpec>, y: Float, rowHeight: Float) {
        val totalWeight = items.sumOf { it.weight.toDouble() }.toFloat()
        val usable = width - horizontalPadding * 2 - keyGap * (items.size - 1)
        var x = horizontalPadding
        items.forEach { item ->
            val itemWidth = usable * item.weight / totalWeight
            keys += Key(item.label, item.code, RectF(x, y, x + itemWidth, y + rowHeight), style = item.style, text = item.text)
            x += itemWidth + keyGap
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeCandidateIndex = candidateBounds.indexOfFirst { it.contains(event.x, event.y) }
                activeKeyIndex = if (activeCandidateIndex < 0) keys.indexOfFirst { it.bounds.contains(event.x, event.y) } else -1
                if (activeCandidateIndex >= 0 || activeKeyIndex >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val nextCandidate = candidateBounds.indexOfFirst { it.contains(event.x, event.y) }
                val nextKey = if (nextCandidate < 0) keys.indexOfFirst { it.bounds.contains(event.x, event.y) } else -1
                if (nextCandidate != activeCandidateIndex || nextKey != activeKeyIndex) {
                    activeCandidateIndex = nextCandidate
                    activeKeyIndex = nextKey
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val candidate = activeCandidateIndex
                val keyIndex = activeKeyIndex
                activeCandidateIndex = -1
                activeKeyIndex = -1
                invalidate()
                if (candidate >= 0 && candidateBounds.getOrNull(candidate)?.contains(event.x, event.y) == true) {
                    candidateListener?.invoke(candidate)
                    performClick()
                } else if (keyIndex >= 0 && keys.getOrNull(keyIndex)?.bounds?.contains(event.x, event.y) == true) {
                    activateKey(keys[keyIndex])
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activeCandidateIndex = -1
                activeKeyIndex = -1
                invalidate()
                return true
            }
        }
        return true
    }

    private fun activateKey(key: Key) {
        key.text?.let {
            textListener?.invoke(it)
            if (panel == Panel.CLIPBOARD) setPanel(Panel.LETTERS)
            return
        }
        when (key.code) {
            KeyCodes.LETTERS -> setPanel(Panel.LETTERS)
            KeyCodes.NUMBERS -> setPanel(Panel.NUMBERS)
            KeyCodes.SYMBOLS -> setPanel(Panel.SYMBOLS)
            KeyCodes.EMOJI -> setPanel(Panel.EMOJI)
            else -> keyListener?.onKey(key.code)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun color(light: Int, dark: Int): Int = if (isNightMode()) dark else light

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * density * resources.configuration.fontScale
}
