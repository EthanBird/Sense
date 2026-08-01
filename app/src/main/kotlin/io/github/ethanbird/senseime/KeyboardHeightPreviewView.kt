package io.github.ethanbird.senseime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.ethanbird.senseime.config.KeyboardHeightPolicy
import kotlin.math.roundToInt

/** Lightweight Canvas preview; dragging height never constructs the production IME scene. */
internal class KeyboardHeightPreviewView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val key = RectF()
    private val previewBounds = RectF()
    private val primaryColor = context.getColor(R.color.sense_primary)
    private val secondaryColor = context.getColor(R.color.sense_secondary)
    private val surfaceColor = context.getColor(R.color.sense_surface)
    private var keyboardHeightDp = KeyboardHeightPolicy.DEFAULT_PORTRAIT_HEIGHT_DP
    private var landscape = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumHeight = dp(MIN_PREVIEW_HEIGHT_DP)
        updateAccessibilityDescription()
    }

    fun setKeyboardHeightDp(heightDp: Int, landscape: Boolean) {
        KeyboardHeightPolicy.requireValid(heightDp)
        if (keyboardHeightDp == heightDp && this.landscape == landscape) return
        keyboardHeightDp = heightDp
        this.landscape = landscape
        updateAccessibilityDescription()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val progress =
            (keyboardHeightDp - KeyboardHeightPolicy.MIN_HEIGHT_DP).toFloat() /
                (KeyboardHeightPolicy.MAX_HEIGHT_DP - KeyboardHeightPolicy.MIN_HEIGHT_DP)
        val orientationScale = if (landscape) LANDSCAPE_PREVIEW_SCALE else 1f
        val desiredHeight = dp(
            (MIN_PREVIEW_HEIGHT_DP + PREVIEW_HEIGHT_RANGE_DP * progress * orientationScale)
                .roundToInt(),
        )
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = dp(4).toFloat()
        previewBounds.set(inset, inset, width - inset, height - inset)
        paint.color = primaryColor
        canvas.drawRoundRect(previewBounds, dp(16).toFloat(), dp(16).toFloat(), paint)

        val padding = dp(9).toFloat()
        val toolbarHeight = (previewBounds.height() * 0.16f).coerceAtLeast(dp(20).toFloat())
        paint.color = secondaryColor
        repeat(6) { index ->
            val x = previewBounds.left + padding +
                index * ((previewBounds.width() - padding * 2f) / 5f)
            canvas.drawCircle(
                x,
                previewBounds.top + toolbarHeight * 0.52f,
                dp(2.2f),
                paint,
            )
        }

        val gridTop = previewBounds.top + toolbarHeight
        val gridBottom = previewBounds.bottom - padding
        val rowGap = dp(4).toFloat()
        val rowHeight = (gridBottom - gridTop - rowGap * 3f) / 4f
        drawRow(canvas, gridTop, rowHeight, 10, 0.04f, 0.04f)
        drawRow(canvas, gridTop + rowHeight + rowGap, rowHeight, 9, 0.08f, 0.08f)
        drawRow(canvas, gridTop + (rowHeight + rowGap) * 2f, rowHeight, 7, 0.15f, 0.15f)
        drawBottomRow(canvas, gridTop + (rowHeight + rowGap) * 3f, rowHeight)
    }

    private fun drawRow(
        canvas: Canvas,
        top: Float,
        rowHeight: Float,
        count: Int,
        startFraction: Float,
        endFraction: Float,
    ) {
        val horizontalGap = dp(3).toFloat()
        val left = width * startFraction
        val right = width * (1f - endFraction)
        val keyWidth = (right - left - horizontalGap * (count - 1)) / count
        paint.color = surfaceColor
        repeat(count) { index ->
            val keyLeft = left + index * (keyWidth + horizontalGap)
            key.set(keyLeft, top, keyLeft + keyWidth, top + rowHeight)
            canvas.drawRoundRect(key, dp(4).toFloat(), dp(4).toFloat(), paint)
        }
    }

    private fun drawBottomRow(canvas: Canvas, top: Float, rowHeight: Float) {
        val gap = dp(4).toFloat()
        val left = width * 0.06f
        val right = width * 0.94f
        val unit = (right - left - gap * 3f) / 6f
        paint.color = surfaceColor
        var x = left
        repeat(4) { index ->
            val value = if (index == 2) unit * 3f else unit
            key.set(x, top, x + value, top + rowHeight)
            canvas.drawRoundRect(key, dp(5).toFloat(), dp(5).toFloat(), paint)
            x += value + gap
        }
    }

    private fun updateAccessibilityDescription() {
        contentDescription = context.getString(
            if (landscape) {
                R.string.keyboard_height_preview_landscape
            } else {
                R.string.keyboard_height_preview_portrait
            },
            keyboardHeightDp,
        )
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun dp(value: Float): Float = value * density

    private companion object {
        const val MIN_PREVIEW_HEIGHT_DP = 128
        const val PREVIEW_HEIGHT_RANGE_DP = 96
        const val LANDSCAPE_PREVIEW_SCALE = 0.72f
    }
}
