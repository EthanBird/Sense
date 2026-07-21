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

    var keyListener: KeyListener? = null
    var candidateListener: ((index: Int) -> Unit)? = null

    private data class Key(
        val label: String,
        val code: Int,
        val bounds: RectF,
    )

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keys = mutableListOf<Key>()
    private val candidateBounds = mutableListOf<RectF>()
    private var candidates: List<String> = emptyList()
    private var composing: String = ""
    private var activeKeyIndex = -1
    private var activeCandidateIndex = -1
    private var shifted = false
    private var backgroundShader: Shader? = null

    private val candidateHeight = dp(46f)
    private val keyGap = dp(5f)
    private val horizontalPadding = dp(5f)
    private val keyRadius = dp(12f)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun updateComposing(text: String, values: List<String>) {
        composing = text
        candidates = values.take(5)
        rebuildCandidateBounds(width)
        invalidate()
    }

    fun setShifted(value: Boolean) {
        if (shifted == value) return
        shifted = value
        rebuildKeys(width, height)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(312f).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundShader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            color(0xFFE8EFF8.toInt(), 0xFF101722.toInt()),
            color(0xFFDDE8F8.toInt(), 0xFF172235.toInt()),
            Shader.TileMode.CLAMP,
        )
        rebuildCandidateBounds(w)
        rebuildKeys(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        drawCandidates(canvas)
        drawKeys(canvas)
    }

    private fun drawBackground(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = backgroundShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawCandidates(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = color(0xB8FFFFFF.toInt(), 0x1FFFFFFF)
        canvas.drawRoundRect(
            RectF(horizontalPadding, dp(4f), width - horizontalPadding, candidateHeight - dp(2f)),
            dp(14f),
            dp(14f),
            paint,
        )

        if (candidates.isEmpty()) {
            paint.color = color(0xFF667085.toInt(), 0xFFAAB4C3.toInt())
            paint.textSize = sp(13f)
            paint.textAlign = Paint.Align.LEFT
            drawCenteredText(canvas, if (composing.isBlank()) "Sense · 先思输入法" else composing, dp(18f), candidateHeight / 2f)
            return
        }

        candidateBounds.forEachIndexed { index, bounds ->
            val text = candidates.getOrNull(index) ?: return@forEachIndexed
            if (index == activeCandidateIndex) {
                paint.color = color(0x334F7CF5, 0x4482A7FF)
                canvas.drawRoundRect(bounds, dp(9f), dp(9f), paint)
            }
            paint.color = color(0xFF101828.toInt(), 0xFFF7FAFC.toInt())
            paint.textSize = sp(18f)
            paint.textAlign = Paint.Align.CENTER
            drawCenteredText(canvas, text, bounds.centerX(), bounds.centerY())
        }
    }

    private fun drawKeys(canvas: Canvas) {
        keys.forEachIndexed { index, key ->
            val pressed = index == activeKeyIndex
            paint.style = Paint.Style.FILL
            paint.color = if (pressed) {
                color(0xD14F7CF5.toInt(), 0xD182A7FF.toInt())
            } else {
                color(0x94FFFFFF.toInt(), 0x1CFFFFFF)
            }
            canvas.drawRoundRect(key.bounds, keyRadius, keyRadius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, density)
            paint.color = color(0xB8FFFFFF.toInt(), 0x2AFFFFFF)
            canvas.drawRoundRect(key.bounds, keyRadius, keyRadius, paint)

            paint.style = Paint.Style.FILL
            paint.color = if (pressed) Color.WHITE else color(0xFF101828.toInt(), 0xFFF7FAFC.toInt())
            paint.textSize = sp(if (key.label.length > 2) 12f else 17f)
            paint.textAlign = Paint.Align.CENTER
            drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, centerY: Float) {
        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun rebuildCandidateBounds(viewWidth: Int) {
        candidateBounds.clear()
        if (viewWidth <= 0 || candidates.isEmpty()) return
        val usable = viewWidth - horizontalPadding * 2
        val slotWidth = usable / candidates.size
        candidates.indices.forEach { index ->
            candidateBounds += RectF(
                horizontalPadding + slotWidth * index,
                dp(4f),
                horizontalPadding + slotWidth * (index + 1),
                candidateHeight - dp(2f),
            )
        }
    }

    private fun rebuildKeys(viewWidth: Int, viewHeight: Int) {
        keys.clear()
        if (viewWidth <= 0 || viewHeight <= 0) return

        val rows = listOf(
            "qwertyuiop".map { it.toString() to it.code },
            "asdfghjkl".map { it.toString() to it.code },
            "zxcvbnm".map { it.toString() to it.code },
        )
        val top = candidateHeight + dp(6f)
        val rowGap = dp(5f)
        val usableHeight = viewHeight - top - dp(6f)
        val rowHeight = (usableHeight - rowGap * 3) / 4f

        rows.forEachIndexed { rowIndex, row ->
            val inset = when (rowIndex) {
                1 -> dp(18f)
                2 -> dp(40f)
                else -> 0f
            }
            layoutEqualRow(
                items = row.map { (label, code) ->
                    (if (shifted) label.uppercase() else label) to code
                },
                y = top + rowIndex * (rowHeight + rowGap),
                rowHeight = rowHeight,
                extraInset = inset,
            )
        }

        layoutWeightedRow(
            items = listOf(
                Triple(if (shifted) "⇧" else "↑", KeyCodes.SHIFT, 1.15f),
                Triple("符", KeyCodes.SYMBOLS, 1f),
                Triple("空格", KeyCodes.SPACE, 3.2f),
                Triple("⌫", KeyCodes.DELETE, 1.15f),
                Triple("↵", KeyCodes.ENTER, 1.15f),
            ),
            y = top + 3 * (rowHeight + rowGap),
            rowHeight = rowHeight,
        )
    }

    private fun layoutEqualRow(
        items: List<Pair<String, Int>>,
        y: Float,
        rowHeight: Float,
        extraInset: Float,
    ) {
        val left = horizontalPadding + extraInset
        val right = width - horizontalPadding - extraInset
        val itemWidth = (right - left - keyGap * (items.size - 1)) / items.size
        items.forEachIndexed { index, (label, code) ->
            val x = left + index * (itemWidth + keyGap)
            keys += Key(label, code, RectF(x, y, x + itemWidth, y + rowHeight))
        }
    }

    private fun layoutWeightedRow(
        items: List<Triple<String, Int, Float>>,
        y: Float,
        rowHeight: Float,
    ) {
        val totalWeight = items.sumOf { it.third.toDouble() }.toFloat()
        val usable = width - horizontalPadding * 2 - keyGap * (items.size - 1)
        var x = horizontalPadding
        items.forEach { (label, code, weight) ->
            val itemWidth = usable * weight / totalWeight
            keys += Key(label, code, RectF(x, y, x + itemWidth, y + rowHeight))
            x += itemWidth + keyGap
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeCandidateIndex = candidateBounds.indexOfFirst { it.contains(event.x, event.y) }
                activeKeyIndex = if (activeCandidateIndex < 0) {
                    keys.indexOfFirst { it.bounds.contains(event.x, event.y) }
                } else {
                    -1
                }
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
                val key = activeKeyIndex
                activeCandidateIndex = -1
                activeKeyIndex = -1
                invalidate()
                if (candidate >= 0 && candidateBounds.getOrNull(candidate)?.contains(event.x, event.y) == true) {
                    candidateListener?.invoke(candidate)
                    performClick()
                } else if (key >= 0 && keys.getOrNull(key)?.bounds?.contains(event.x, event.y) == true) {
                    keyListener?.onKey(keys[key].code)
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
