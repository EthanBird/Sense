package io.github.ethanbird.senseime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.SparseArray
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

    enum class ClipboardAction {
        CLEAR,
        DELETE,
        REFRESH,
    }

    private enum class KeyStyle {
        LETTER,
        ACTION,
        TOOL,
        SYSTEM,
        CARD,
        EMOJI,
        CATEGORY,
        RAIL,
    }

    private enum class Icon {
        TOOLS,
        KEYBOARD,
        EMOJI,
        EDITOR,
        VOICE,
        HIDE,
        DELETE,
        ENTER,
        SHIFT,
        SPACE,
        BACK,
        CLEAR,
        REFRESH,
    }

    private data class Key(
        val label: String,
        val code: Int,
        val bounds: RectF,
        val hint: String? = null,
        val style: KeyStyle = KeyStyle.LETTER,
        val text: String? = null,
        val icon: Icon? = null,
        val clipboardAction: ClipboardAction? = null,
        val clipboardIndex: Int = -1,
        val secondaryLabel: String? = null,
    )

    private data class VisibleCandidate(
        val sourceIndex: Int,
        val bounds: RectF,
        val textAnchor: Float,
    )

    private data class EmojiGroup(
        val icon: String,
        val values: List<String>,
    )

    private enum class CandidateControl {
        EXPAND,
        COLLAPSE,
        PREVIOUS_PAGE,
        NEXT_PAGE,
    }

    private data class CandidateControlSlot(
        val control: CandidateControl,
        val bounds: RectF,
        val enabled: Boolean = true,
    )

    private sealed interface FrozenTouchTarget {
        val bounds: RectF

        data class CandidateValue(
            val revision: Long,
            val sourceIndex: Int,
            override val bounds: RectF,
        ) : FrozenTouchTarget

        data class CandidateControlValue(
            val value: CandidateControl,
            override val bounds: RectF,
        ) : FrozenTouchTarget

        data class KeyValue(
            val key: Key,
            override val bounds: RectF = key.bounds,
        ) : FrozenTouchTarget
    }

    var keyListener: KeyListener? = null
    var candidateListener: ((revision: Long, sourceIndex: Int) -> Unit)? = null
    var textListener: ((text: String) -> Unit)? = null
    var clipboardActionListener: ((action: ClipboardAction, index: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontMetrics = Paint.FontMetrics()
    private val iconPath = Path()
    private val keys = mutableListOf<Key>()
    private val visibleCandidates = mutableListOf<VisibleCandidate>()
    private val candidateControls = mutableListOf<CandidateControlSlot>()
    private var candidatePages: List<KeyboardLayoutContract.CandidatePage> = emptyList()
    private var candidates: List<String> = emptyList()
    private var clipboardItems: List<String> = emptyList()
    private var composing: String = ""
    private var candidateRevision: Long = 0L
    private var candidatePageIndex = 0
    private var candidatePageLabel = "1 / 1"
    private var candidatesExpanded = false
    private val touchReducer = TouchInputReducer<FrozenTouchTarget>(
        swipeThreshold = dp(22f),
        maximumHorizontalDrift = dp(34f),
    )
    private val keyEventQueue = KeyEventQueue(initialCapacity = 64)
    private val pressedTargets = SparseArray<FrozenTouchTarget>(4)
    private var keyDispatchPosted = false
    private val backspaceRepeatSession = BackspaceRepeatSession()
    private var emojiGroupIndex = 0
    private var emojiPageIndex = 0
    private var clipboardPageIndex = 0
    private var clipboardPageLabel = ""
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
    private val candidateTextInset = dp(9f)
    private val candidateGap = dp(3f)
    private val candidateMinimumWidth = dp(44f)
    private val candidateControlWidth = dp(44f)
    private val expandedCandidateRowHeight = dp(42f)
    private val expandedCandidatePagerHeight = dp(38f)
    private val keyDispatchRunnable = Runnable {
        keyDispatchPosted = false
        while (true) {
            val code = keyEventQueue.poll() ?: break
            keyListener?.onKey(code)
        }
    }
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            val pointerId = backspaceRepeatSession.activePointerId() ?: return
            if (!touchReducer.isPressed(pointerId)) return
            val target = touchReducer.target(pointerId) as? FrozenTouchTarget.KeyValue ?: return
            if (target.key.code != KeyCodes.DELETE) return
            enqueueKey(KeyCodes.DELETE)
            val held = backspaceRepeatSession.heldMillis(SystemClock.uptimeMillis())
            postDelayed(this, BackspaceRepeatPolicy.intervalMillis(held))
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun updateComposing(revision: Long, text: String, values: List<String>) {
        val nextCandidates = values.toList()
        val shouldReset = revision != candidateRevision || text != composing || nextCandidates != candidates
        val wasExpanded = candidatesExpanded
        if (shouldReset) {
            candidatesExpanded = false
            candidatePageIndex = 0
        }
        candidateRevision = revision
        composing = text
        candidates = nextCandidates
        rebuildCandidateLayout(width, height)
        if (wasExpanded != candidatesExpanded) rebuildKeys(width, height)
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
        collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

    fun setPanel(value: Panel) {
        val wasExpanded = candidatesExpanded
        collapseCandidates()
        if (panel == value && !wasExpanded) return
        panel = value
        if (value == Panel.EMOJI) emojiPageIndex = 0
        rebuildKeys(width, height)
        invalidate()
    }

    fun showClipboard(values: List<String>) {
        clipboardItems = values
        clipboardPageIndex = 0
        panel = Panel.CLIPBOARD
        collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(360f).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelAllTouches()
        backgroundShader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            color(0xFFF1F5FA.toInt(), 0xFF171819.toInt()),
            color(0xFFE6EDF6.toInt(), 0xFF111213.toInt()),
            Shader.TileMode.CLAMP,
        )
        rebuildCandidateLayout(w, h)
        rebuildKeys(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        drawCandidates(canvas)
        if (!candidatesExpanded) {
            drawToolbar(canvas)
            if (panel == Panel.CLIPBOARD) drawClipboardHeader(canvas)
        }
        drawKeys(canvas)
        if (!candidatesExpanded && panel == Panel.EMOJI) drawEmojiPageIndicator(canvas)
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
        if (candidatesExpanded) {
            drawExpandedCandidates(canvas)
            return
        }
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
            canvas.drawText(composing, 0, minOf(12, composing.length), dp(10f), dp(14f), paint)
        }

        drawVisibleCandidates(canvas)
        candidateControls.forEach { drawCandidateControl(canvas, it) }
    }

    private fun drawExpandedCandidates(canvas: Canvas) {
        val systemBarTop = height - systemBarHeight
        paint.style = Paint.Style.FILL
        paint.color = color(0xF2EDF3FA.toInt(), 0xF2161718.toInt())
        canvas.drawRect(0f, 0f, width.toFloat(), systemBarTop, paint)

        paint.color = color(0x16000000, 0x24FFFFFF)
        canvas.drawRect(0f, candidateHeight - max(1f, density), width.toFloat(), candidateHeight, paint)

        paint.color = color(0xFF596579.toInt(), 0xFFB8BBC2.toInt())
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.LEFT
        val headerRight = width - candidateControlWidth
        val saveCount = canvas.save()
        canvas.clipRect(dp(14f), 0f, headerRight, candidateHeight)
        drawCenteredText(
            canvas,
            if (composing.isBlank()) "候选" else composing,
            dp(14f),
            candidateHeight / 2f,
        )
        canvas.restoreToCount(saveCount)

        drawVisibleCandidates(canvas)

        val pagerTop = systemBarTop - expandedCandidatePagerHeight
        paint.color = color(0x16000000, 0x24FFFFFF)
        canvas.drawRect(0f, pagerTop, width.toFloat(), pagerTop + max(1f, density), paint)
        paint.color = color(0xFF667085.toInt(), 0xFF9B9EA5.toInt())
        paint.textSize = sp(12f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(
            canvas,
            candidatePageLabel,
            width / 2f,
            pagerTop + expandedCandidatePagerHeight / 2f,
        )
        candidateControls.forEach { drawCandidateControl(canvas, it) }
    }

    private fun drawVisibleCandidates(canvas: Canvas) {
        visibleCandidates.forEach { candidate ->
            val text = candidates.getOrNull(candidate.sourceIndex) ?: return@forEach
            if (isCandidatePressed(candidate)) {
                paint.style = Paint.Style.FILL
                paint.color = color(0x294F7CF5, 0x505E63D8)
                canvas.drawRoundRect(candidate.bounds, dp(7f), dp(7f), paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
            paint.textSize = sp(17f)
            paint.textAlign = Paint.Align.LEFT
            val saveCount = canvas.save()
            canvas.clipRect(candidate.bounds)
            drawCenteredText(canvas, text, candidate.textAnchor, candidate.bounds.centerY() + dp(2f))
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawCandidateControl(canvas: Canvas, slot: CandidateControlSlot) {
        val pressed = isCandidateControlPressed(slot)
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x254F7CF5, 0x405E63D8)
            canvas.drawRoundRect(slot.bounds, dp(9f), dp(9f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (slot.enabled) {
            color(0xFF586477.toInt(), 0xFFAAAEB6.toInt())
        } else {
            color(0x55586477, 0x55AAAEB6)
        }
        paint.textSize = sp(20f)
        paint.textAlign = Paint.Align.CENTER
        val label = when (slot.control) {
            CandidateControl.EXPAND -> "⌄"
            CandidateControl.COLLAPSE -> "⌃"
            CandidateControl.PREVIOUS_PAGE -> "‹"
            CandidateControl.NEXT_PAGE -> "›"
        }
        drawCenteredText(canvas, label, slot.bounds.centerX(), slot.bounds.centerY())
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
        paint.textSize = sp(13.5f)
        paint.textAlign = Paint.Align.LEFT
        drawCenteredText(canvas, "剪贴板", dp(14f), candidateHeight + toolbarHeight + dp(19f))
        if (clipboardPageLabel.isNotEmpty()) {
            paint.color = color(0xFF748094.toInt(), 0xFF92969E.toInt())
            paint.textSize = sp(10f)
            drawCenteredText(canvas, clipboardPageLabel, dp(62f), candidateHeight + toolbarHeight + dp(19f))
        }
    }

    private fun drawKeys(canvas: Canvas) {
        keys.forEach { key ->
            val pressed = isKeyPressed(key)
            when (key.style) {
                KeyStyle.TOOL -> drawToolKey(canvas, key, pressed)
                KeyStyle.SYSTEM -> drawSystemKey(canvas, key, pressed)
                KeyStyle.CARD -> drawCardKey(canvas, key, pressed)
                KeyStyle.EMOJI -> drawEmojiKey(canvas, key, pressed)
                KeyStyle.CATEGORY -> drawCategoryKey(canvas, key, pressed)
                KeyStyle.RAIL -> drawRailKey(canvas, key, pressed)
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

        paint.style = Paint.Style.FILL
        paint.color = if (pressed) Color.WHITE else color(0xFF111827.toInt(), 0xFFF6F7F9.toInt())
        if (key.icon != null) {
            drawIcon(canvas, key.icon, key.bounds, paint.color)
        } else {
            paint.textSize = sp(if (key.label.length > 2) 13f else 20f)
            paint.textAlign = Paint.Align.CENTER
            drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY() + if (key.hint == null) 0f else dp(3f))
        }

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
        val iconColor = color(0xFF586477.toInt(), 0xFFB6BAC2.toInt())
        if (key.icon != null) {
            drawIcon(canvas, key.icon, key.bounds, iconColor)
        } else {
            paint.color = iconColor
            paint.textSize = sp(if (key.label.length > 1) 14f else 19f)
            paint.textAlign = Paint.Align.CENTER
            drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
        }
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
            val left = key.bounds.centerX() - dp(15f)
            val top = key.bounds.centerY() - dp(10f)
            val right = key.bounds.centerX() + dp(15f)
            val bottom = key.bounds.centerY() + dp(10f)
            canvas.drawRoundRect(left, top, right, bottom, dp(4f), dp(4f), paint)
            repeat(3) { row ->
                repeat(4) { column ->
                    val x = left + dp(6f) + column * dp(6f)
                    val y = top + dp(6f) + row * dp(5f)
                    canvas.drawCircle(x, y, dp(0.8f), paint)
                }
            }
        } else {
            val backLeft = key.bounds.centerX() - dp(13f)
            val backTop = key.bounds.centerY() - dp(9f)
            val backRight = key.bounds.centerX() + dp(11f)
            val backBottom = key.bounds.centerY() + dp(11f)
            val frontLeft = backLeft + dp(6f)
            val frontTop = backTop + dp(5f)
            val frontRight = backRight + dp(6f)
            val frontBottom = backBottom + dp(5f)
            canvas.drawRoundRect(backLeft, backTop, backRight, backBottom, dp(4f), dp(4f), paint)
            canvas.drawRoundRect(frontLeft, frontTop, frontRight, frontBottom, dp(4f), dp(4f), paint)
            canvas.drawLine(frontLeft + dp(5f), frontTop + dp(6f), frontRight - dp(5f), frontTop + dp(6f), paint)
            canvas.drawLine(frontLeft + dp(5f), frontTop + dp(11f), frontRight - dp(5f), frontTop + dp(11f), paint)
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
        canvas.drawRoundRect(key.bounds, dp(11f), dp(11f), paint)
        paint.color = color(0xFF263247.toInt(), 0xFFF0F1F4.toInt())
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.LEFT
        val x = key.bounds.left + dp(11f)
        drawCenteredText(canvas, key.label, x, key.bounds.centerY() - if (key.secondaryLabel != null) dp(8f) else 0f)
        key.secondaryLabel?.let { secondLine ->
            paint.color = color(0xFF6B7484.toInt(), 0xFF9B9EA5.toInt())
            drawCenteredText(canvas, secondLine, x, key.bounds.centerY() + dp(10f))
        }
    }

    private fun drawEmojiKey(canvas: Canvas, key: Key, pressed: Boolean) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x255B7DF0, 0x456D61D8)
            canvas.drawCircle(key.bounds.centerX(), key.bounds.centerY(), minOf(key.bounds.width(), key.bounds.height()) * 0.42f, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF172033.toInt(), 0xFFF5F5F7.toInt())
        paint.textSize = sp(25f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
    }

    private fun drawCategoryKey(canvas: Canvas, key: Key, pressed: Boolean) {
        val selected = key.clipboardIndex == emojiGroupIndex
        if (selected || pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x224F7CF5, 0x385E63D8)
            canvas.drawRoundRect(key.bounds, dp(10f), dp(10f), paint)
        }
        paint.color = if (selected) color(0xFF4F6FE8.toInt(), 0xFFC0B8FF.toInt()) else color(0xFF647084.toInt(), 0xFFA4A8B0.toInt())
        paint.textSize = sp(16f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
    }

    private fun drawRailKey(canvas: Canvas, key: Key, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
        } else {
            color(0xE6FFFFFF.toInt(), 0xFF303132.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(5f), dp(5f), paint)
        paint.color = if (pressed) Color.WHITE else color(0xFF1C2433.toInt(), 0xFFF3F4F7.toInt())
        paint.textSize = sp(17f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
    }

    private fun drawIcon(canvas: Canvas, icon: Icon, bounds: RectF, tint: Int) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val unit = minOf(bounds.width(), bounds.height()) / 24f
        paint.shader = null
        paint.color = tint
        paint.strokeWidth = max(dp(1.65f), unit * 1.45f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        iconPath.reset()
        when (icon) {
            Icon.TOOLS -> {
                repeat(2) { row ->
                    repeat(2) { column ->
                        val x = cx + (column * 2 - 1) * unit * 4f
                        val y = cy + (row * 2 - 1) * unit * 4f
                        canvas.drawRoundRect(x - unit * 2f, y - unit * 2f, x + unit * 2f, y + unit * 2f, unit, unit, paint)
                    }
                }
            }
            Icon.KEYBOARD -> {
                canvas.drawRoundRect(cx - unit * 8f, cy - unit * 6f, cx + unit * 8f, cy + unit * 6f, unit * 2f, unit * 2f, paint)
                repeat(3) { row ->
                    repeat(5) { column ->
                        canvas.drawCircle(cx - unit * 5.5f + column * unit * 2.75f, cy - unit * 3.5f + row * unit * 3f, unit * 0.45f, paint)
                    }
                }
                canvas.drawLine(cx - unit * 4f, cy + unit * 3.8f, cx + unit * 4f, cy + unit * 3.8f, paint)
            }
            Icon.EMOJI -> {
                canvas.drawCircle(cx, cy, unit * 8f, paint)
                canvas.drawCircle(cx - unit * 2.8f, cy - unit * 2f, unit * 0.7f, paint)
                canvas.drawCircle(cx + unit * 2.8f, cy - unit * 2f, unit * 0.7f, paint)
                iconPath.moveTo(cx - unit * 3.5f, cy + unit * 2f)
                iconPath.quadTo(cx, cy + unit * 5.5f, cx + unit * 3.5f, cy + unit * 2f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.EDITOR -> {
                canvas.drawLine(cx, cy - unit * 7f, cx, cy + unit * 7f, paint)
                canvas.drawLine(cx - unit * 3f, cy - unit * 7f, cx + unit * 3f, cy - unit * 7f, paint)
                canvas.drawLine(cx - unit * 3f, cy + unit * 7f, cx + unit * 3f, cy + unit * 7f, paint)
                iconPath.moveTo(cx - unit * 5f, cy - unit * 2.5f)
                iconPath.lineTo(cx - unit * 7.5f, cy)
                iconPath.lineTo(cx - unit * 5f, cy + unit * 2.5f)
                iconPath.moveTo(cx + unit * 5f, cy - unit * 2.5f)
                iconPath.lineTo(cx + unit * 7.5f, cy)
                iconPath.lineTo(cx + unit * 5f, cy + unit * 2.5f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.VOICE -> {
                canvas.drawRoundRect(cx - unit * 3.2f, cy - unit * 7.5f, cx + unit * 3.2f, cy + unit * 2f, unit * 3.2f, unit * 3.2f, paint)
                iconPath.moveTo(cx - unit * 6f, cy)
                iconPath.quadTo(cx - unit * 5.5f, cy + unit * 6f, cx, cy + unit * 6f)
                iconPath.quadTo(cx + unit * 5.5f, cy + unit * 6f, cx + unit * 6f, cy)
                canvas.drawPath(iconPath, paint)
                canvas.drawLine(cx, cy + unit * 6f, cx, cy + unit * 9f, paint)
            }
            Icon.HIDE -> {
                iconPath.moveTo(cx - unit * 7f, cy - unit * 2f)
                iconPath.lineTo(cx, cy + unit * 5f)
                iconPath.lineTo(cx + unit * 7f, cy - unit * 2f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.DELETE -> {
                iconPath.moveTo(cx - unit * 8f, cy)
                iconPath.lineTo(cx - unit * 3f, cy - unit * 6f)
                iconPath.lineTo(cx + unit * 8f, cy - unit * 6f)
                iconPath.lineTo(cx + unit * 8f, cy + unit * 6f)
                iconPath.lineTo(cx - unit * 3f, cy + unit * 6f)
                iconPath.close()
                canvas.drawPath(iconPath, paint)
                canvas.drawLine(cx + unit, cy - unit * 2.8f, cx + unit * 5f, cy + unit * 2.8f, paint)
                canvas.drawLine(cx + unit * 5f, cy - unit * 2.8f, cx + unit, cy + unit * 2.8f, paint)
            }
            Icon.ENTER -> {
                iconPath.moveTo(cx + unit * 7f, cy - unit * 6f)
                iconPath.lineTo(cx + unit * 7f, cy + unit * 2f)
                iconPath.quadTo(cx + unit * 7f, cy + unit * 6f, cx + unit * 3f, cy + unit * 6f)
                iconPath.lineTo(cx - unit * 7f, cy + unit * 6f)
                iconPath.moveTo(cx - unit * 3f, cy + unit * 2f)
                iconPath.lineTo(cx - unit * 7f, cy + unit * 6f)
                iconPath.lineTo(cx - unit * 3f, cy + unit * 10f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.SHIFT -> {
                iconPath.moveTo(cx - unit * 7f, cy)
                iconPath.lineTo(cx, cy - unit * 7f)
                iconPath.lineTo(cx + unit * 7f, cy)
                iconPath.lineTo(cx + unit * 3.5f, cy)
                iconPath.lineTo(cx + unit * 3.5f, cy + unit * 7f)
                iconPath.lineTo(cx - unit * 3.5f, cy + unit * 7f)
                iconPath.lineTo(cx - unit * 3.5f, cy)
                iconPath.close()
                canvas.drawPath(iconPath, paint)
            }
            Icon.SPACE -> {
                iconPath.moveTo(cx - unit * 8f, cy + unit * 1f)
                iconPath.lineTo(cx - unit * 8f, cy + unit * 5f)
                iconPath.lineTo(cx + unit * 8f, cy + unit * 5f)
                iconPath.lineTo(cx + unit * 8f, cy + unit * 1f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.BACK -> {
                iconPath.moveTo(cx - unit * 7f, cy)
                iconPath.lineTo(cx - unit * 2f, cy - unit * 5f)
                iconPath.moveTo(cx - unit * 7f, cy)
                iconPath.lineTo(cx - unit * 2f, cy + unit * 5f)
                iconPath.moveTo(cx - unit * 7f, cy)
                iconPath.lineTo(cx + unit * 7f, cy)
                canvas.drawPath(iconPath, paint)
            }
            Icon.CLEAR -> {
                canvas.drawRoundRect(cx - unit * 5f, cy - unit * 4f, cx + unit * 5f, cy + unit * 7f, unit, unit, paint)
                canvas.drawLine(cx - unit * 7f, cy - unit * 6f, cx + unit * 7f, cy - unit * 6f, paint)
                canvas.drawLine(cx - unit * 2.5f, cy - unit * 8f, cx + unit * 2.5f, cy - unit * 8f, paint)
            }
            Icon.REFRESH -> {
                iconPath.moveTo(cx + unit * 6f, cy - unit * 5f)
                iconPath.lineTo(cx + unit * 6f, cy - unit * 0.5f)
                iconPath.lineTo(cx + unit * 2f, cy - unit * 2f)
                iconPath.moveTo(cx + unit * 5f, cy - unit * 3f)
                iconPath.cubicTo(cx + unit, cy - unit * 8f, cx - unit * 7f, cy - unit * 5f, cx - unit * 7f, cy + unit)
                iconPath.cubicTo(cx - unit * 7f, cy + unit * 7f, cx + unit * 3f, cy + unit * 9f, cx + unit * 7f, cy + unit * 3f)
                canvas.drawPath(iconPath, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, centerY: Float) {
        paint.getFontMetrics(fontMetrics)
        val baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun drawEmojiPageIndicator(canvas: Canvas) {
        val pageCount = ((EMOJI_GROUPS[emojiGroupIndex].values.size + EMOJI_ITEMS_PER_PAGE - 1) / EMOJI_ITEMS_PER_PAGE).coerceAtLeast(1)
        if (pageCount <= 1) return
        val gap = dp(7f)
        val startX = width / 2f - (pageCount - 1) * gap / 2f
        val y = height - systemBarHeight - dp(45f)
        paint.style = Paint.Style.FILL
        repeat(pageCount) { page ->
            paint.color = if (page == emojiPageIndex) color(0xFF536FE5.toInt(), 0xFFC1B9FF.toInt()) else color(0x55708090, 0x668E929A)
            canvas.drawCircle(startX + page * gap, y, if (page == emojiPageIndex) dp(2f) else dp(1.35f), paint)
        }
    }

    private fun rebuildCandidateLayout(viewWidth: Int, viewHeight: Int) {
        visibleCandidates.clear()
        candidateControls.clear()
        candidatePages = emptyList()
        if (viewWidth <= 0 || candidates.isEmpty()) {
            candidatesExpanded = false
            candidatePageIndex = 0
            candidatePageLabel = "1 / 1"
            return
        }
        paint.textSize = sp(17f)
        val measuredWidths = candidates.map(paint::measureText)
        val collapsed = KeyboardLayoutContract.collapsedCandidateStrip(
            viewWidth = viewWidth.toFloat(),
            measuredTextWidths = measuredWidths,
            padding = horizontalPadding,
            textInset = candidateTextInset,
            gap = candidateGap,
            minimumWidth = candidateMinimumWidth,
            overflowControlWidth = candidateControlWidth,
        )

        if (!collapsed.hasOverflow) {
            candidatesExpanded = false
            candidatePageIndex = 0
        }

        val systemBarTop = viewHeight - systemBarHeight
        val gridTop = candidateHeight + dp(5f)
        val pagerTop = systemBarTop - expandedCandidatePagerHeight
        val gridBottom = pagerTop - dp(4f)
        val canExpand = collapsed.hasOverflow &&
            viewHeight > 0 &&
            gridBottom - gridTop >= expandedCandidateRowHeight

        if (candidatesExpanded && canExpand) {
            candidatePages = KeyboardLayoutContract.pagedCandidateGrid(
                viewWidth = viewWidth.toFloat(),
                contentTop = gridTop,
                contentBottom = gridBottom,
                measuredTextWidths = measuredWidths,
                horizontalPadding = horizontalPadding,
                textInset = candidateTextInset,
                horizontalGap = candidateGap,
                verticalGap = dp(4f),
                minimumWidth = candidateMinimumWidth,
                rowHeight = expandedCandidateRowHeight,
            )
            if (candidatePages.isEmpty()) {
                candidatesExpanded = false
                candidatePageIndex = 0
            } else {
                candidatePageIndex = candidatePageIndex.coerceIn(0, candidatePages.lastIndex)
                candidatePageLabel = "${candidatePageIndex + 1} / ${candidatePages.size}"
                candidatePages[candidatePageIndex].slots.forEach { slot ->
                    visibleCandidates += VisibleCandidate(
                        sourceIndex = slot.sourceIndex,
                        bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                        textAnchor = slot.textAnchor,
                    )
                }
                candidateControls += CandidateControlSlot(
                    CandidateControl.COLLAPSE,
                    RectF(viewWidth - candidateControlWidth, 0f, viewWidth.toFloat(), candidateHeight),
                )
                val pagerButtonWidth = dp(68f)
                candidateControls += CandidateControlSlot(
                    CandidateControl.PREVIOUS_PAGE,
                    RectF(horizontalPadding, pagerTop, horizontalPadding + pagerButtonWidth, systemBarTop),
                    enabled = candidatePageIndex > 0,
                )
                candidateControls += CandidateControlSlot(
                    CandidateControl.NEXT_PAGE,
                    RectF(viewWidth - horizontalPadding - pagerButtonWidth, pagerTop, viewWidth - horizontalPadding, systemBarTop),
                    enabled = candidatePageIndex < candidatePages.lastIndex,
                )
                return
            }
        } else if (candidatesExpanded) {
            candidatesExpanded = false
            candidatePageIndex = 0
        }

        val top = if (composing.isBlank()) dp(3f) else dp(13f)
        collapsed.slots.forEachIndexed { sourceIndex, slot ->
            visibleCandidates += VisibleCandidate(
                sourceIndex = sourceIndex,
                bounds = RectF(slot.left, top, slot.right, candidateHeight - dp(3f)),
                textAnchor = slot.textAnchor,
            )
        }
        if (collapsed.hasOverflow && canExpand) {
            candidateControls += CandidateControlSlot(
                CandidateControl.EXPAND,
                RectF(viewWidth - candidateControlWidth, 0f, viewWidth.toFloat(), candidateHeight),
            )
        }
    }

    private fun rebuildKeys(viewWidth: Int, viewHeight: Int) {
        keys.clear()
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (!candidatesExpanded) {
            layoutToolbar(viewWidth)
            when (panel) {
                Panel.LETTERS -> layoutLetters(viewWidth, viewHeight)
                Panel.NUMBERS -> layoutNumbers(viewWidth, viewHeight)
                Panel.SYMBOLS -> layoutSymbols(viewWidth, viewHeight)
                Panel.EMOJI -> layoutEmoji(viewWidth, viewHeight)
                Panel.CLIPBOARD -> layoutClipboard(viewWidth, viewHeight)
            }
        }
        layoutSystemBar(viewWidth, viewHeight)
    }

    private fun layoutToolbar(viewWidth: Int) {
        val items = listOf(
            Icon.TOOLS to KeyCodes.SYMBOLS,
            Icon.KEYBOARD to KeyCodes.LETTERS,
            Icon.EMOJI to KeyCodes.EMOJI,
            Icon.EDITOR to KeyCodes.EDITOR,
            Icon.VOICE to KeyCodes.VOICE,
            Icon.HIDE to KeyCodes.HIDE,
        )
        val slot = viewWidth / items.size.toFloat()
        items.forEachIndexed { index, (icon, code) ->
            keys += Key(
                "",
                code,
                RectF(index * slot + dp(5f), candidateHeight + dp(3f), (index + 1) * slot - dp(5f), candidateHeight + toolbarHeight - dp(3f)),
                style = KeyStyle.TOOL,
                icon = icon,
            )
        }
    }

    private fun layoutLetters(viewWidth: Int, viewHeight: Int) {
        val (top, rowHeight) = keyboardGrid(viewHeight)
        layoutEqualRow(
            "qwertyuiop".map { character ->
                KeySpec(if (shifted) character.uppercase() else character.toString(), character.code, SwipeCharacterMap.forKey(character.code))
            },
            top,
            rowHeight,
        )
        layoutEqualRow(
            "asdfghjkl".map { character ->
                KeySpec(if (shifted) character.uppercase() else character.toString(), character.code, SwipeCharacterMap.forKey(character.code))
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
        val top = candidateHeight + toolbarHeight + dp(7f)
        val bottom = viewHeight - systemBarHeight - dp(7f)
        KeyboardLayoutContract.numericPadLayout(
            viewWidth = viewWidth.toFloat(),
            contentTop = top,
            contentBottom = bottom,
            horizontalPadding = horizontalPadding,
            gap = keyGap,
            chineseMode = chineseMode,
        ).forEach { slot ->
            val item = slot.key
            val icon = when (item.code) {
                KeyCodes.DELETE -> Icon.DELETE
                KeyCodes.SPACE -> Icon.SPACE
                KeyCodes.ENTER -> Icon.ENTER
                else -> null
            }
            keys += Key(
                label = item.label,
                code = item.code,
                bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                style = when {
                    item.column == 0 && item.row < 4 -> KeyStyle.RAIL
                    item.code < 0 || icon != null -> KeyStyle.ACTION
                    else -> KeyStyle.LETTER
                },
                text = item.text,
                icon = icon,
            )
        }
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
                add(WeightedSpec("", KeyCodes.DELETE, 1.25f, KeyStyle.ACTION, icon = Icon.DELETE))
            },
            top + 2 * (rowHeight + keyGap),
            rowHeight,
        )
        layoutBottomRow(top + 3 * (rowHeight + keyGap), rowHeight, returnLabel = "ABC")
    }

    private fun layoutEmoji(viewWidth: Int, viewHeight: Int) {
        val top = candidateHeight + toolbarHeight + dp(4f)
        val bottom = viewHeight - systemBarHeight - dp(6f)
        val categoryHeight = dp(29f)
        val categorySlot = (viewWidth - horizontalPadding * 2) / EMOJI_GROUPS.size
        EMOJI_GROUPS.forEachIndexed { index, group ->
            keys += Key(
                group.icon,
                0,
                RectF(
                    horizontalPadding + index * categorySlot + dp(2f),
                    top,
                    horizontalPadding + (index + 1) * categorySlot - dp(2f),
                    top + categoryHeight,
                ),
                style = KeyStyle.CATEGORY,
                clipboardIndex = index,
            )
        }
        val actionHeight = dp(40f)
        val actionTop = bottom - actionHeight
        val gridTop = top + categoryHeight + dp(3f)
        val gridBottom = actionTop - dp(3f)
        val rows = 3
        val columns = 7
        val itemWidth = (viewWidth - horizontalPadding * 2) / columns
        val itemHeight = (gridBottom - gridTop) / rows
        val values = EMOJI_GROUPS[emojiGroupIndex].values
        val pageCount = ((values.size + EMOJI_ITEMS_PER_PAGE - 1) / EMOJI_ITEMS_PER_PAGE).coerceAtLeast(1)
        emojiPageIndex = emojiPageIndex.coerceIn(0, pageCount - 1)
        values.drop(emojiPageIndex * EMOJI_ITEMS_PER_PAGE).take(EMOJI_ITEMS_PER_PAGE).forEachIndexed { index, text ->
            val row = index / columns
            val column = index % columns
            keys += Key(
                text,
                0,
                RectF(
                    horizontalPadding + column * itemWidth,
                    gridTop + row * itemHeight,
                    horizontalPadding + (column + 1) * itemWidth,
                    gridTop + (row + 1) * itemHeight,
                ),
                style = KeyStyle.EMOJI,
                text = text,
            )
        }
        layoutWeightedRow(
            listOf(
                WeightedSpec("", KeyCodes.LETTERS, 1.05f, KeyStyle.ACTION, icon = Icon.BACK),
                WeightedSpec("", KeyCodes.SPACE, 3.3f, KeyStyle.LETTER, icon = Icon.SPACE),
                WeightedSpec("", KeyCodes.DELETE, 1.05f, KeyStyle.ACTION, icon = Icon.DELETE),
                WeightedSpec("", KeyCodes.ENTER, 1.05f, KeyStyle.ACTION, icon = Icon.ENTER),
            ),
            actionTop,
            actionHeight,
        )
    }

    private fun layoutClipboard(viewWidth: Int, viewHeight: Int) {
        val headerTop = candidateHeight + toolbarHeight
        val headerHeight = dp(36f)
        val headerIconWidth = dp(39f)
        keys += Key("", 0, RectF(viewWidth - headerIconWidth * 3, headerTop, viewWidth - headerIconWidth * 2, headerTop + headerHeight), style = KeyStyle.TOOL, icon = Icon.REFRESH, clipboardAction = ClipboardAction.REFRESH)
        keys += Key("", 0, RectF(viewWidth - headerIconWidth * 2, headerTop, viewWidth - headerIconWidth, headerTop + headerHeight), style = KeyStyle.TOOL, icon = Icon.CLEAR, clipboardAction = ClipboardAction.CLEAR)
        keys += Key("", KeyCodes.LETTERS, RectF(viewWidth - headerIconWidth, headerTop, viewWidth.toFloat(), headerTop + headerHeight), style = KeyStyle.TOOL, icon = Icon.BACK)
        val top = headerTop + headerHeight
        val bottom = viewHeight - systemBarHeight - dp(8f)
        if (clipboardItems.isEmpty()) {
            clipboardPageLabel = ""
            keys += Key(
                "暂无剪贴板文本  ·  复制文字后点刷新",
                0,
                RectF(horizontalPadding, top, viewWidth - horizontalPadding, bottom),
                style = KeyStyle.CARD,
            )
            return
        }
        val columns = 2
        val rows = 3
        val cardWidth = (viewWidth - horizontalPadding * 2 - keyGap) / columns
        val cardHeight = (bottom - top - keyGap) / rows
        val pageCount = ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) / CLIPBOARD_ITEMS_PER_PAGE).coerceAtLeast(1)
        clipboardPageIndex = clipboardPageIndex.coerceIn(0, pageCount - 1)
        clipboardPageLabel = if (pageCount > 1) "${clipboardPageIndex + 1}/$pageCount" else ""
        val pageStart = clipboardPageIndex * CLIPBOARD_ITEMS_PER_PAGE
        clipboardItems.drop(pageStart).take(CLIPBOARD_ITEMS_PER_PAGE).forEachIndexed { index, text ->
            val row = index / columns
            val column = index % columns
            val left = horizontalPadding + column * (cardWidth + keyGap)
            val cardTop = top + row * (cardHeight + keyGap)
            val globalIndex = pageStart + index
            keys += Key(
                text.replace('\n', ' ').take(16),
                0,
                RectF(left, cardTop, left + cardWidth, cardTop + cardHeight),
                style = KeyStyle.CARD,
                text = text,
                secondaryLabel = text.replace('\n', ' ').drop(16).take(16).takeIf { it.isNotEmpty() },
            )
            keys += Key(
                "",
                0,
                RectF(left + cardWidth - dp(31f), cardTop + dp(2f), left + cardWidth - dp(2f), cardTop + dp(31f)),
                style = KeyStyle.TOOL,
                icon = Icon.CLEAR,
                clipboardAction = ClipboardAction.DELETE,
                clipboardIndex = globalIndex,
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
        val icon: Icon? = null,
    )

    private data class WeightedSpec(
        val label: String,
        val code: Int,
        val weight: Float,
        val style: KeyStyle = KeyStyle.LETTER,
        val text: String? = null,
        val hint: String? = null,
        val icon: Icon? = null,
    )

    private fun KeyboardLayoutContract.WeightedKey.toWeightedSpec(): WeightedSpec = WeightedSpec(
        label = if (code == KeyCodes.SHIFT || code == KeyCodes.DELETE || code == KeyCodes.SPACE || code == KeyCodes.ENTER) "" else label,
        code = code,
        weight = weight,
        style = if (action) KeyStyle.ACTION else KeyStyle.LETTER,
        hint = if (code > 0) SwipeCharacterMap.forKey(code) else null,
        icon = when (code) {
            KeyCodes.SHIFT -> Icon.SHIFT
            KeyCodes.DELETE -> Icon.DELETE
            KeyCodes.SPACE -> Icon.SPACE
            KeyCodes.ENTER -> Icon.ENTER
            else -> null
        },
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
            keys += Key(item.label, item.code, RectF(x, y, x + itemWidth, y + rowHeight), item.hint, item.style, item.text, item.icon)
        }
    }

    private fun layoutWeightedRow(items: List<WeightedSpec>, y: Float, rowHeight: Float) {
        val totalWeight = items.sumOf { it.weight.toDouble() }.toFloat()
        val usable = width - horizontalPadding * 2 - keyGap * (items.size - 1)
        var x = horizontalPadding
        items.forEach { item ->
            val itemWidth = usable * item.weight / totalWeight
            keys += Key(item.label, item.code, RectF(x, y, x + itemWidth, y + rowHeight), hint = item.hint, style = item.style, text = item.text, icon = item.icon)
            x += itemWidth + keyGap
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event, event.actionIndex)

            MotionEvent.ACTION_MOVE -> {
                repeat(event.pointerCount) { pointerIndex ->
                    val pointerId = event.getPointerId(pointerIndex)
                    val target = touchReducer.target(pointerId) ?: return@repeat
                    val canceled = touchReducer.onMove(
                        pointerId,
                        isInsideGestureTarget(target, event.getX(pointerIndex), event.getY(pointerIndex)),
                    )
                    if (canceled) {
                        pressedTargets.remove(pointerId)
                        if (backspaceRepeatSession.owns(pointerId)) stopBackspaceRepeat(pointerId)
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event, event.actionIndex)

            MotionEvent.ACTION_CANCEL -> {
                cancelAllTouches()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handlePointerDown(event: MotionEvent, pointerIndex: Int): Boolean {
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val target = touchTargetAt(x, y) ?: return true
        val pointerId = event.getPointerId(pointerIndex)
        touchReducer.onDown(pointerId, target, x, y)
        pressedTargets.put(pointerId, target)
        val key = (target as? FrozenTouchTarget.KeyValue)?.key
        if (key?.code == KeyCodes.DELETE) {
            enqueueKey(KeyCodes.DELETE)
            startBackspaceRepeat(pointerId)
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
        return true
    }

    private fun handlePointerUp(event: MotionEvent, pointerIndex: Int): Boolean {
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val target = touchReducer.target(pointerId)
        val activation = touchReducer.onUp(pointerId, x, y, target?.let { isInsideGestureTarget(it, x, y) } == true)
        pressedTargets.remove(pointerId)
        stopBackspaceRepeat(pointerId)
        invalidate()
        if (activation != null) {
            activateTouchTarget(activation)
            performClick()
        }
        return true
    }

    private fun touchTargetAt(x: Float, y: Float): FrozenTouchTarget? {
        for (index in candidateControls.indices) {
            val slot = candidateControls[index]
            if (slot.enabled && slot.bounds.contains(x, y)) {
                return FrozenTouchTarget.CandidateControlValue(slot.control, RectF(slot.bounds))
            }
        }
        for (index in visibleCandidates.indices) {
            val candidate = visibleCandidates[index]
            if (candidate.bounds.contains(x, y)) {
                return FrozenTouchTarget.CandidateValue(
                    revision = candidateRevision,
                    sourceIndex = candidate.sourceIndex,
                    bounds = RectF(candidate.bounds),
                )
            }
        }
        for (index in keys.lastIndex downTo 0) {
            val key = keys[index]
            if (key.bounds.contains(x, y)) return FrozenTouchTarget.KeyValue(key)
        }
        return null
    }

    private fun isInsideGestureTarget(target: FrozenTouchTarget, x: Float, y: Float): Boolean {
        val bounds = target.bounds
        val key = (target as? FrozenTouchTarget.KeyValue)?.key ?: return bounds.contains(x, y)
        if (key.code == KeyCodes.DELETE) return bounds.contains(x, y)
        val acceptsSwipe = key.code > 0 || key.style == KeyStyle.EMOJI || key.style == KeyStyle.CARD
        if (!acceptsSwipe) return bounds.contains(x, y)
        return x >= bounds.left - dp(12f) &&
            x <= bounds.right + dp(12f) &&
            y >= bounds.top - dp(72f) &&
            y <= bounds.bottom + dp(72f)
    }

    private fun activateTouchTarget(activation: TouchInputReducer.Activation<FrozenTouchTarget>) {
        when (val target = activation.target) {
            is FrozenTouchTarget.CandidateValue -> if (activation.gesture == TouchInputReducer.Gesture.TAP) {
                dispatchQueuedKeysNow()
                candidateListener?.invoke(target.revision, target.sourceIndex)
            }
            is FrozenTouchTarget.CandidateControlValue -> if (activation.gesture == TouchInputReducer.Gesture.TAP) {
                activateCandidateControl(target.value)
            }
            is FrozenTouchTarget.KeyValue -> activateGesture(target.key, activation.gesture)
        }
    }

    private fun activateGesture(key: Key, gesture: TouchInputReducer.Gesture) {
        if (key.code == KeyCodes.DELETE) return // DELETE is emitted immediately on DOWN.
        when {
            key.style == KeyStyle.EMOJI && gesture != TouchInputReducer.Gesture.TAP -> {
                scrollEmoji(if (gesture == TouchInputReducer.Gesture.SWIPE_UP) 1 else -1)
            }
            key.style == KeyStyle.CARD && gesture != TouchInputReducer.Gesture.TAP && clipboardItems.isNotEmpty() -> {
                scrollClipboard(if (gesture == TouchInputReducer.Gesture.SWIPE_UP) 1 else -1)
            }
            gesture == TouchInputReducer.Gesture.SWIPE_UP && key.code > 0 -> {
                SwipeCharacterMap.forKey(key.code)?.let {
                    dispatchQueuedKeysNow()
                    textListener?.invoke(it)
                }
            }
            gesture == TouchInputReducer.Gesture.TAP -> activateKey(key)
        }
    }

    private fun activateCandidateControl(control: CandidateControl) {
        val expansionChanged = when (control) {
            CandidateControl.EXPAND -> {
                candidatesExpanded = true
                candidatePageIndex = 0
                true
            }

            CandidateControl.COLLAPSE -> {
                candidatesExpanded = false
                candidatePageIndex = 0
                true
            }

            CandidateControl.PREVIOUS_PAGE -> {
                candidatePageIndex = (candidatePageIndex - 1).coerceAtLeast(0)
                false
            }

            CandidateControl.NEXT_PAGE -> {
                candidatePageIndex = (candidatePageIndex + 1).coerceAtMost(candidatePages.lastIndex)
                false
            }
        }
        rebuildCandidateLayout(width, height)
        if (expansionChanged) rebuildKeys(width, height)
        invalidate()
    }

    private fun isCandidatePressed(candidate: VisibleCandidate): Boolean {
        repeat(pressedTargets.size()) { index ->
            val target = pressedTargets.valueAt(index)
            if (target is FrozenTouchTarget.CandidateValue && target.revision == candidateRevision && target.sourceIndex == candidate.sourceIndex) {
                return true
            }
        }
        return false
    }

    private fun isCandidateControlPressed(slot: CandidateControlSlot): Boolean {
        repeat(pressedTargets.size()) { index ->
            val target = pressedTargets.valueAt(index)
            if (target is FrozenTouchTarget.CandidateControlValue && target.value == slot.control) return true
        }
        return false
    }

    private fun isKeyPressed(key: Key): Boolean {
        repeat(pressedTargets.size()) { index ->
            val target = pressedTargets.valueAt(index)
            if (target is FrozenTouchTarget.KeyValue && target.key === key) return true
        }
        return false
    }

    private fun cancelAllTouches() {
        touchReducer.cancelAll()
        pressedTargets.clear()
        stopBackspaceRepeat()
    }

    private fun collapseCandidates() {
        candidatesExpanded = false
        candidatePageIndex = 0
        rebuildCandidateLayout(width, height)
    }

    private fun activateKey(key: Key) {
        key.clipboardAction?.let { action ->
            activateClipboardAction(action, key.clipboardIndex)
            return
        }
        if (key.style == KeyStyle.CATEGORY) {
            emojiGroupIndex = key.clipboardIndex.coerceIn(0, EMOJI_GROUPS.lastIndex)
            emojiPageIndex = 0
            rebuildKeys(width, height)
            invalidate()
            return
        }
        key.text?.let {
            dispatchQueuedKeysNow()
            textListener?.invoke(it)
            if (panel == Panel.CLIPBOARD) setPanel(Panel.LETTERS)
            return
        }
        if (key.code == 0) return
        when (key.code) {
            KeyCodes.LETTERS -> {
                dispatchQueuedKeysNow()
                setPanel(Panel.LETTERS)
            }
            KeyCodes.NUMBERS -> {
                dispatchQueuedKeysNow()
                setPanel(Panel.NUMBERS)
            }
            KeyCodes.SYMBOLS -> {
                dispatchQueuedKeysNow()
                setPanel(Panel.SYMBOLS)
            }
            KeyCodes.EMOJI -> {
                dispatchQueuedKeysNow()
                setPanel(Panel.EMOJI)
            }
            else -> enqueueKey(key.code)
        }
    }

    private fun activateClipboardAction(action: ClipboardAction, index: Int) {
        when (action) {
            ClipboardAction.CLEAR -> {
                clipboardItems = emptyList()
                clipboardPageIndex = 0
            }
            ClipboardAction.DELETE -> if (index in clipboardItems.indices) {
                clipboardItems = clipboardItems.filterIndexed { itemIndex, _ -> itemIndex != index }
                val pages = ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) / CLIPBOARD_ITEMS_PER_PAGE).coerceAtLeast(1)
                clipboardPageIndex = clipboardPageIndex.coerceAtMost(pages - 1)
            }
            ClipboardAction.REFRESH -> Unit
        }
        clipboardActionListener?.invoke(action, index)
        rebuildKeys(width, height)
        invalidate()
    }

    private fun scrollEmoji(delta: Int) {
        val values = EMOJI_GROUPS[emojiGroupIndex].values
        val pageCount = ((values.size + EMOJI_ITEMS_PER_PAGE - 1) / EMOJI_ITEMS_PER_PAGE).coerceAtLeast(1)
        emojiPageIndex = (emojiPageIndex + delta).coerceIn(0, pageCount - 1)
        rebuildKeys(width, height)
        invalidate()
    }

    private fun scrollClipboard(delta: Int) {
        val pageCount = ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) / CLIPBOARD_ITEMS_PER_PAGE).coerceAtLeast(1)
        clipboardPageIndex = (clipboardPageIndex + delta).coerceIn(0, pageCount - 1)
        rebuildKeys(width, height)
        invalidate()
    }

    private fun enqueueKey(code: Int) {
        keyEventQueue.offer(code)
        if (!keyDispatchPosted) {
            keyDispatchPosted = true
            post(keyDispatchRunnable)
        }
    }

    private fun dispatchQueuedKeysNow() {
        if (keyEventQueue.pendingCount == 0) return
        removeCallbacks(keyDispatchRunnable)
        keyDispatchPosted = false
        while (true) {
            val code = keyEventQueue.poll() ?: break
            keyListener?.onKey(code)
        }
    }

    private fun startBackspaceRepeat(pointerId: Int) {
        if (!backspaceRepeatSession.tryStart(pointerId, SystemClock.uptimeMillis())) return
        removeCallbacks(backspaceRepeatRunnable)
        postDelayed(backspaceRepeatRunnable, BackspaceRepeatPolicy.INITIAL_DELAY_MS)
    }

    private fun stopBackspaceRepeat(pointerId: Int? = null) {
        if (pointerId == null) {
            backspaceRepeatSession.clear()
        } else if (!backspaceRepeatSession.stop(pointerId)) {
            return
        }
        removeCallbacks(backspaceRepeatRunnable)
    }

    override fun onDetachedFromWindow() {
        cancelAllTouches()
        removeCallbacks(keyDispatchRunnable)
        keyEventQueue.clear()
        keyDispatchPosted = false
        super.onDetachedFromWindow()
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

    private companion object {
        const val CLIPBOARD_ITEMS_PER_PAGE = 6
        const val EMOJI_ITEMS_PER_PAGE = 21

        val EMOJI_GROUPS = listOf(
            EmojiGroup(
                "☺",
                listOf(
                    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋",
                    "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😭",
                ),
            ),
            EmojiGroup(
                "♥",
                listOf(
                    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "👍", "👎",
                    "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "🤝", "👏", "🙌", "👐", "🤲",
                ),
            ),
            EmojiGroup(
                "✿",
                listOf(
                    "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🌱", "🌿", "☘️", "🍀", "🎋", "🌵",
                    "🌴", "🌳", "🌲", "🌷", "🌹", "🥀", "🌺", "🌸", "🌼", "🌻", "🌞", "🌝", "🌚", "⭐", "🌟", "✨", "⚡", "🔥", "🌈", "☀️", "🌙",
                ),
            ),
            EmojiGroup(
                "♨",
                listOf(
                    "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🥑", "🍆", "🥔", "🥕",
                    "🍞", "🥐", "🥖", "🥨", "🧀", "🍳", "🥞", "🧇", "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🍜", "🍚", "🍣", "🍰", "🎂", "☕", "🍵",
                ),
            ),
            EmojiGroup(
                "⚑",
                listOf(
                    "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱", "🏓", "🏸", "🥅", "⛳", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹",
                    "🚗", "🚕", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐", "🛻", "🚚", "🚲", "🛵", "✈️", "🚀", "🚁", "⛵", "🚢", "⌚", "📱", "💻",
                ),
            ),
        )
    }
}
