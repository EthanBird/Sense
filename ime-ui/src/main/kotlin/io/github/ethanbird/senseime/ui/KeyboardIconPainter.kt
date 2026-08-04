package io.github.ethanbird.senseime.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Paints keyboard icons without mutating the shared text/key Paint.
 *
 * Geometry and Path instances are retained, so a frame does not allocate one
 * metrics object per icon.
 */
internal class KeyboardIconPainter(
    private val density: Float,
    fontScale: Float,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val metrics = CanvasIconGeometry.MutableMetrics()
    private val fineIconSegments = FloatArray(KeyboardFineIconGeometry.MAX_SEGMENTS * 4)
    private var scaledDensity = density * fontScale

    fun updateFontScale(fontScale: Float) {
        scaledDensity = density * fontScale
    }

    fun draw(
        canvas: Canvas,
        icon: Icon,
        bounds: RectF,
        tint: Int,
    ) {
        CanvasIconGeometry.resolveInto(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            density = density,
            out = metrics,
        )
        val cx = metrics.centerX
        val cy = metrics.centerY
        val unit = metrics.unit
        paint.shader = null
        paint.color = tint
        paint.strokeWidth = metrics.strokeWidth * KeyboardIconStrokePolicy.factor(icon)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        path.reset()
        val fineSegmentCount = KeyboardFineIconGeometry.writeSegments(icon, fineIconSegments)
        if (fineSegmentCount > 0) {
            var index = 0
            val coordinateCount = fineSegmentCount * 4
            while (index < coordinateCount) {
                fineIconSegments[index] = cx + fineIconSegments[index] * unit
                fineIconSegments[index + 1] = cy + fineIconSegments[index + 1] * unit
                fineIconSegments[index + 2] = cx + fineIconSegments[index + 2] * unit
                fineIconSegments[index + 3] = cy + fineIconSegments[index + 3] * unit
                index += 4
            }
            canvas.drawLines(fineIconSegments, 0, coordinateCount, paint)
            return
        }
        when (icon) {
            Icon.TOOLS -> {
                repeat(2) { row ->
                    repeat(2) { column ->
                        val x = cx + (column * 2 - 1) * unit * 4f
                        val y = cy + (row * 2 - 1) * unit * 4f
                        canvas.drawRoundRect(
                            x - unit * 2f,
                            y - unit * 2f,
                            x + unit * 2f,
                            y + unit * 2f,
                            unit,
                            unit,
                            paint,
                        )
                    }
                }
            }
            Icon.SYMBOLS -> {
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = sp(17f)
                paint.style = Paint.Style.FILL
                canvas.drawText("Ω", cx - unit * 3.2f, cy + unit * 3.4f, paint)
                paint.textSize = sp(12f)
                canvas.drawText("#", cx + unit * 4.2f, cy - unit * 2.2f, paint)
            }
            Icon.KEYBOARD -> {
                canvas.drawRoundRect(
                    cx - unit * 8f,
                    cy - unit * 6f,
                    cx + unit * 8f,
                    cy + unit * 6f,
                    unit * 2f,
                    unit * 2f,
                    paint,
                )
                repeat(3) { row ->
                    repeat(5) { column ->
                        canvas.drawCircle(
                            cx - unit * 5.5f + column * unit * 2.75f,
                            cy - unit * 3.5f + row * unit * 3f,
                            unit * 0.45f,
                            paint,
                        )
                    }
                }
                canvas.drawLine(cx - unit * 4f, cy + unit * 3.8f, cx + unit * 4f, cy + unit * 3.8f, paint)
            }
            Icon.EMOJI -> {
                canvas.drawCircle(cx, cy, unit * 8f, paint)
                canvas.drawCircle(cx - unit * 2.8f, cy - unit * 2f, unit * 0.7f, paint)
                canvas.drawCircle(cx + unit * 2.8f, cy - unit * 2f, unit * 0.7f, paint)
                path.moveTo(cx - unit * 3.5f, cy + unit * 2f)
                path.quadTo(cx, cy + unit * 5.5f, cx + unit * 3.5f, cy + unit * 2f)
                canvas.drawPath(path, paint)
            }
            Icon.EDITOR -> {
                canvas.drawLine(cx, cy - unit * 7f, cx, cy + unit * 7f, paint)
                canvas.drawLine(cx - unit * 3f, cy - unit * 7f, cx + unit * 3f, cy - unit * 7f, paint)
                canvas.drawLine(cx - unit * 3f, cy + unit * 7f, cx + unit * 3f, cy + unit * 7f, paint)
                path.moveTo(cx - unit * 5f, cy - unit * 2.5f)
                path.lineTo(cx - unit * 7.5f, cy)
                path.lineTo(cx - unit * 5f, cy + unit * 2.5f)
                path.moveTo(cx + unit * 5f, cy - unit * 2.5f)
                path.lineTo(cx + unit * 7.5f, cy)
                path.lineTo(cx + unit * 5f, cy + unit * 2.5f)
                canvas.drawPath(path, paint)
            }
            Icon.VOICE -> {
                canvas.drawRoundRect(
                    cx - unit * 3.2f,
                    cy - unit * 7.5f,
                    cx + unit * 3.2f,
                    cy + unit * 2f,
                    unit * 3.2f,
                    unit * 3.2f,
                    paint,
                )
                path.moveTo(cx - unit * 6f, cy)
                path.quadTo(cx - unit * 5.5f, cy + unit * 6f, cx, cy + unit * 6f)
                path.quadTo(cx + unit * 5.5f, cy + unit * 6f, cx + unit * 6f, cy)
                canvas.drawPath(path, paint)
                canvas.drawLine(cx, cy + unit * 6f, cx, cy + unit * 9f, paint)
            }
            Icon.SETTINGS -> {
                canvas.drawCircle(cx, cy, unit * 3.2f, paint)
                repeat(8) { index ->
                    val angle = Math.PI * index / 4.0
                    val innerX = cx + cos(angle).toFloat() * unit * 5.5f
                    val innerY = cy + sin(angle).toFloat() * unit * 5.5f
                    val outerX = cx + cos(angle).toFloat() * unit * 8f
                    val outerY = cy + sin(angle).toFloat() * unit * 8f
                    canvas.drawLine(innerX, innerY, outerX, outerY, paint)
                }
            }
            Icon.AGENT -> {
                path.moveTo(cx, cy - unit * 8f)
                path.lineTo(cx + unit * 1.8f, cy - unit * 2f)
                path.lineTo(cx + unit * 7.5f, cy)
                path.lineTo(cx + unit * 1.8f, cy + unit * 2f)
                path.lineTo(cx, cy + unit * 8f)
                path.lineTo(cx - unit * 1.8f, cy + unit * 2f)
                path.lineTo(cx - unit * 7.5f, cy)
                path.lineTo(cx - unit * 1.8f, cy - unit * 2f)
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawCircle(cx + unit * 6.2f, cy - unit * 6f, unit * 1.2f, paint)
            }
            Icon.HIDE -> {
                path.moveTo(cx - unit * 7f, cy - unit * 2f)
                path.lineTo(cx, cy + unit * 5f)
                path.lineTo(cx + unit * 7f, cy - unit * 2f)
                canvas.drawPath(path, paint)
            }
            Icon.DELETE,
            Icon.ENTER,
            -> Unit
            Icon.SHIFT -> {
                path.moveTo(cx - unit * 7f, cy)
                path.lineTo(cx, cy - unit * 7f)
                path.lineTo(cx + unit * 7f, cy)
                path.lineTo(cx + unit * 3.5f, cy)
                path.lineTo(cx + unit * 3.5f, cy + unit * 7f)
                path.lineTo(cx - unit * 3.5f, cy + unit * 7f)
                path.lineTo(cx - unit * 3.5f, cy)
                path.close()
                canvas.drawPath(path, paint)
            }
            Icon.SPACE -> {
                path.moveTo(cx - unit * 8f, cy + unit)
                path.lineTo(cx - unit * 8f, cy + unit * 5f)
                path.lineTo(cx + unit * 8f, cy + unit * 5f)
                path.lineTo(cx + unit * 8f, cy + unit)
                canvas.drawPath(path, paint)
            }
            Icon.BACK -> {
                path.moveTo(cx - unit * 7f, cy)
                path.lineTo(cx - unit * 2f, cy - unit * 5f)
                path.moveTo(cx - unit * 7f, cy)
                path.lineTo(cx - unit * 2f, cy + unit * 5f)
                path.moveTo(cx - unit * 7f, cy)
                path.lineTo(cx + unit * 7f, cy)
                canvas.drawPath(path, paint)
            }
            Icon.UP,
            Icon.DOWN,
            Icon.LEFT,
            Icon.RIGHT,
            Icon.HOME,
            Icon.END,
            -> Unit
            Icon.CLEAR -> {
                canvas.drawRoundRect(
                    cx - unit * 5f,
                    cy - unit * 4f,
                    cx + unit * 5f,
                    cy + unit * 7f,
                    unit,
                    unit,
                    paint,
                )
                canvas.drawLine(cx - unit * 7f, cy - unit * 6f, cx + unit * 7f, cy - unit * 6f, paint)
                canvas.drawLine(cx - unit * 2.5f, cy - unit * 8f, cx + unit * 2.5f, cy - unit * 8f, paint)
            }
            Icon.REFRESH -> {
                path.moveTo(cx + unit * 6f, cy - unit * 5f)
                path.lineTo(cx + unit * 6f, cy - unit * 0.5f)
                path.lineTo(cx + unit * 2f, cy - unit * 2f)
                path.moveTo(cx + unit * 5f, cy - unit * 3f)
                path.cubicTo(
                    cx + unit,
                    cy - unit * 8f,
                    cx - unit * 7f,
                    cy - unit * 5f,
                    cx - unit * 7f,
                    cy + unit,
                )
                path.cubicTo(
                    cx - unit * 7f,
                    cy + unit * 7f,
                    cx + unit * 3f,
                    cy + unit * 9f,
                    cx + unit * 7f,
                    cy + unit * 3f,
                )
                canvas.drawPath(path, paint)
            }
            Icon.CLIPBOARD -> {
                canvas.drawRoundRect(
                    cx - unit * 7f,
                    cy - unit * 6f,
                    cx + unit * 4f,
                    cy + unit * 5f,
                    unit * 1.5f,
                    unit * 1.5f,
                    paint,
                )
                canvas.drawRoundRect(
                    cx - unit * 3f,
                    cy - unit * 3f,
                    cx + unit * 8f,
                    cy + unit * 8f,
                    unit * 1.5f,
                    unit * 1.5f,
                    paint,
                )
                canvas.drawLine(cx, cy + unit, cx + unit * 5f, cy + unit, paint)
                canvas.drawLine(cx, cy + unit * 4f, cx + unit * 5f, cy + unit * 4f, paint)
            }
        }
    }

    private fun sp(value: Float): Float = value * scaledDensity
}

/** Fine-line navigation glyphs stay visually lighter than decorative toolbar icons. */
internal object KeyboardIconStrokePolicy {
    fun factor(icon: Icon): Float = when (icon) {
        Icon.DELETE,
        Icon.ENTER,
        Icon.BACK,
        Icon.UP,
        Icon.DOWN,
        Icon.LEFT,
        Icon.RIGHT,
        Icon.HOME,
        Icon.END,
        -> 0.72f

        else -> 1f
    }
}
