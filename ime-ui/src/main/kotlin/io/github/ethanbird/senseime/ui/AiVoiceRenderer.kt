package io.github.ethanbird.senseime.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * AI activity/preview and voice-session renderer.
 *
 * Timeline fallback, preview line ranges, waveform samples and all graphics
 * scratch are retained for the module lifetime.
 */
internal class AiVoiceRenderer(
    private val density: Float,
    fontScale: Float,
    private val metrics: KeyboardMetrics,
    private val palette: KeyboardPalette,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = KeyboardCanvasText()
    private val sharedPath = Path()
    private val previewLines = AiPreviewLineLayoutCache()
    private val waveformSamples = FloatArray(VoiceWaveformBuffer.DEFAULT_CAPACITY)
    private var fontScale = fontScale
    private var waveformShader: Shader? = null
    private val previewTextBreaker = AiPreviewTextBreaker { value, start, end, maximumWidth ->
        paint.breakText(value, start, end, true, maximumWidth, null)
    }

    fun updateSurface(
        width: Int,
        height: Int,
        fontScale: Float,
    ) {
        this.fontScale = fontScale
        waveformShader = LinearGradient(
            dp(22f),
            0f,
            maxOf(dp(23f), width - dp(22f)),
            0f,
            intArrayOf(
                color(0xFF20C7EE.toInt(), 0xFF34D9FF.toInt()),
                color(0xFF557EF7.toInt(), 0xFF7A89FF.toInt()),
                color(0xFFA24DF4.toInt(), 0xFFC05CFF.toInt()),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    fun drawAiSurface(
        canvas: Canvas,
        rendererState: KeyboardRendererState,
        state: AiSurfaceState,
    ) {
        val geometry = rendererState.aiGeometry
        val card = geometry.card
        if (card.isEmpty) return
        val keyRegionTop = geometry.surfaceTop
        val accent = when (state.phase) {
            AiSurfacePhase.STARTING -> color(0xFF5B72E8.toInt(), 0xFF9C8CFF.toInt())
            AiSurfacePhase.STREAMING -> color(0xFF3F7CEA.toInt(), 0xFF9C8CFF.toInt())
            AiSurfacePhase.COMPLETE -> color(0xFF26845A.toInt(), 0xFF71D9A8.toInt())
            AiSurfacePhase.ERROR -> color(0xFFD14D58.toInt(), 0xFFFF8A93.toInt())
        }

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color(0x26FFFFFF, 0x1AFFFFFF)
        canvas.drawRect(0f, 0f, rendererState.viewWidth.toFloat(), keyRegionTop, paint)
        paint.color = accent
        canvas.drawCircle(dp(17f), keyRegionTop / 2f, dp(4f), paint)
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = sp(13.5f)
        val status = state.statusText.ifBlank { defaultAiStatus(state.phase) }
        text.drawEllipsized(
            canvas,
            status,
            paint,
            dp(29f),
            keyRegionTop / 2f,
            rendererState.viewWidth - dp(41f),
        )

        paint.color = color(0xD8FFFFFF.toInt(), 0xFF252627.toInt())
        canvas.drawRoundRect(
            card.left,
            card.top,
            card.right,
            card.bottom,
            dp(13f),
            dp(13f),
            paint,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density)
        paint.color = color(0x185B72E8, 0x309C8CFF)
        canvas.drawRoundRect(
            card.left,
            card.top,
            card.right,
            card.bottom,
            dp(13f),
            dp(13f),
            paint,
        )
        paint.style = Paint.Style.FILL

        val timelineBottom = drawAiActivityTimeline(canvas, state, card, accent)
        val preview = state.preview
        if (preview.isNotEmpty() && timelineBottom < card.bottom - dp(25f)) {
            paint.style = Paint.Style.FILL
            paint.color = color(0xFF748096.toInt(), 0xFFAEB3BE.toInt())
            paint.textSize = sp(10.5f)
            paint.textAlign = Paint.Align.LEFT
            text.drawCentered(
                canvas,
                OUTPUT_PREVIEW,
                paint,
                card.left + dp(14f),
                timelineBottom + dp(11f),
            )
            drawAiPreviewText(
                canvas = canvas,
                value = preview,
                left = card.left + dp(14f),
                top = timelineBottom + dp(22f),
                right = card.right - dp(14f),
                bottom = card.bottom - dp(14f),
            )
        } else if (preview.isEmpty() && timelineBottom < card.bottom - dp(28f)) {
            paint.style = Paint.Style.FILL
            paint.color = color(0xFF78859A.toInt(), 0xFF9FA4AF.toInt())
            paint.textSize = sp(11.5f)
            paint.textAlign = Paint.Align.LEFT
            text.drawEllipsized(
                canvas,
                waitingText(state.phase),
                paint,
                card.left + dp(14f),
                timelineBottom + dp(18f),
                card.width - dp(28f),
            )
        }

        drawAiLockAffordance(canvas, rendererState, state, accent)
    }

    private fun drawAiActivityTimeline(
        canvas: Canvas,
        state: AiSurfaceState,
        card: RenderRect,
        accent: Int,
    ): Float {
        val maximumRows = if (state.preview.isEmpty()) 4 else 3
        val left = card.left + dp(14f)
        val right = card.right - dp(14f)
        val rowHeight = dp(if (state.preview.isEmpty()) 34f else 31f)
        var top = card.top + dp(8f)
        val pulse = (SystemClock.uptimeMillis() % 1_200L) / 1_200f
        val activities = state.activities
        if (activities.isEmpty()) {
            drawAiActivityRow(
                canvas = canvas,
                title = state.statusText.ifBlank { defaultAiStatus(state.phase) },
                detail = EMPTY_TEXT,
                activityState = when (state.phase) {
                    AiSurfacePhase.COMPLETE -> AiSurfaceActivityState.COMPLETED
                    AiSurfacePhase.ERROR -> AiSurfaceActivityState.FAILED
                    else -> AiSurfaceActivityState.RUNNING
                },
                left = left,
                right = right,
                top = top,
                rowHeight = rowHeight,
                accent = accent,
                pulse = pulse,
                drawDivider = false,
            )
            return top + rowHeight + dp(2f)
        }

        var index = (activities.size - maximumRows).coerceAtLeast(0)
        while (index < activities.size) {
            val activity = activities[index]
            drawAiActivityRow(
                canvas = canvas,
                title = activity.title,
                detail = activity.detail,
                activityState = activity.state,
                left = left,
                right = right,
                top = top,
                rowHeight = rowHeight,
                accent = accent,
                pulse = pulse,
                drawDivider = index < activities.lastIndex,
            )
            top += rowHeight
            index += 1
        }
        return top + dp(2f)
    }

    private fun drawAiActivityRow(
        canvas: Canvas,
        title: String,
        detail: String,
        activityState: AiSurfaceActivityState,
        left: Float,
        right: Float,
        top: Float,
        rowHeight: Float,
        accent: Int,
        pulse: Float,
        drawDivider: Boolean,
    ) {
        val centerY = top + rowHeight / 2f
        val markerX = left + dp(5f)
        when (activityState) {
            AiSurfaceActivityState.RUNNING -> {
                paint.style = Paint.Style.FILL
                paint.color = color(0x205B72E8, 0x309C8CFF)
                canvas.drawCircle(
                    markerX,
                    centerY,
                    dp(4.5f + 2f * sin(pulse * Math.PI).toFloat()),
                    paint,
                )
                paint.color = accent
                canvas.drawCircle(markerX, centerY, dp(2.8f), paint)
            }
            AiSurfaceActivityState.COMPLETED -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.5f, density * 1.3f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = color(0xFF3D9A70.toInt(), 0xFF74D9AA.toInt())
                canvas.drawCircle(markerX, centerY, dp(5f), paint)
                sharedPath.reset()
                sharedPath.moveTo(markerX - dp(2.5f), centerY)
                sharedPath.lineTo(markerX - dp(0.5f), centerY + dp(2f))
                sharedPath.lineTo(markerX + dp(3f), centerY - dp(2.5f))
                canvas.drawPath(sharedPath, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
            AiSurfaceActivityState.FAILED -> {
                paint.style = Paint.Style.FILL
                paint.color = color(0xFFD14D58.toInt(), 0xFFFF8A93.toInt())
                canvas.drawCircle(markerX, centerY, dp(5f), paint)
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = sp(8.5f)
                text.drawCentered(canvas, FAILURE_MARK, paint, markerX, centerY)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF26344A.toInt(), 0xFFE9EBEF.toInt())
        paint.textSize = sp(12.5f)
        paint.textAlign = Paint.Align.LEFT
        val detailWidth = if (detail.isBlank()) 0f else dp(54f)
        text.drawEllipsized(
            canvas,
            title,
            paint,
            left + dp(16f),
            centerY,
            right - left - dp(18f) - detailWidth,
        )
        if (detail.isNotBlank()) {
            paint.color = color(0xFF7B8798.toInt(), 0xFFA7ABB4.toInt())
            paint.textSize = sp(10.5f)
            paint.textAlign = Paint.Align.RIGHT
            text.drawCentered(canvas, detail, paint, right, centerY)
        }
        if (drawDivider) {
            paint.color = color(0x0F172033, 0x12FFFFFF)
            canvas.drawRect(
                left + dp(16f),
                top + rowHeight - 1f,
                right,
                top + rowHeight,
                paint,
            )
        }
    }

    fun drawVoiceSurface(
        canvas: Canvas,
        rendererState: KeyboardRendererState,
        state: VoiceSurfaceState,
    ) {
        val contentBottom = rendererState.viewHeight - metrics.systemBarHeight
        if (contentBottom <= metrics.candidateHeight) return

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color(0x14FFFFFF, 0x0FFFFFFF)
        canvas.drawRect(
            0f,
            0f,
            rendererState.viewWidth.toFloat(),
            metrics.candidateHeight,
            paint,
        )
        paint.color = color(0xFF263247.toInt(), 0xFFF1F2F5.toInt())
        paint.textSize = sp(15.5f)
        paint.textAlign = Paint.Align.LEFT
        text.drawCentered(
            canvas,
            VOICE_HEADER,
            paint,
            dp(15f),
            metrics.candidateHeight / 2f,
        )

        val providerLeft = dp(106f)
        val providerRight = minOf(
            rendererState.viewWidth - dp(58f),
            providerLeft + dp(126f),
        )
        paint.color = color(0x15557EF7, 0x28557EF7)
        canvas.drawRoundRect(
            providerLeft,
            dp(9f),
            providerRight,
            metrics.candidateHeight - dp(9f),
            dp(12f),
            dp(12f),
            paint,
        )
        paint.color = color(0xFF52627B.toInt(), 0xFFBFC4CE.toInt())
        paint.textSize = sp(10.5f)
        paint.textAlign = Paint.Align.CENTER
        val providerSave = canvas.save()
        canvas.clipRect(
            providerLeft + dp(6f),
            0f,
            providerRight - dp(6f),
            metrics.candidateHeight,
        )
        text.drawCentered(
            canvas,
            state.providerName,
            paint,
            (providerLeft + providerRight) / 2f,
            metrics.candidateHeight / 2f,
        )
        canvas.restoreToCount(providerSave)

        paint.color = color(0x1820C7EE, 0x1620C7EE)
        canvas.drawCircle(
            rendererState.viewWidth * 0.18f,
            metrics.candidateHeight + dp(92f),
            dp(76f),
            paint,
        )
        paint.color = color(0x16A24DF4, 0x18A24DF4)
        canvas.drawCircle(
            rendererState.viewWidth * 0.82f,
            metrics.candidateHeight + dp(126f),
            dp(88f),
            paint,
        )

        val statusY = rendererState.scene.voiceStatusCenterY.takeIf {
            it > metrics.candidateHeight
        } ?: (metrics.candidateHeight + dp(30f))
        paint.color = when (state.phase) {
            VoiceSurfacePhase.ERROR -> color(0xFFC23E4A.toInt(), 0xFFFF8F98.toInt())
            else -> color(0xFF516078.toInt(), 0xFFC2C6CF.toInt())
        }
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.CENTER
        val statusSave = canvas.save()
        canvas.clipRect(
            dp(16f),
            metrics.candidateHeight,
            rendererState.viewWidth - dp(16f),
            statusY + dp(17f),
        )
        text.drawCentered(canvas, state.statusText, paint, rendererState.viewWidth / 2f, statusY)
        canvas.restoreToCount(statusSave)

        paint.color = color(0xFF192337.toInt(), 0xFFF2F3F6.toInt())
        paint.textSize = sp(16f)
        paint.textAlign = Paint.Align.CENTER
        val transcriptY = rendererState.scene.voiceTranscriptCenterY.takeIf {
            it > statusY
        } ?: (statusY + dp(34f))
        val transcriptSave = canvas.save()
        canvas.clipRect(
            dp(22f),
            transcriptY - dp(16f),
            rendererState.viewWidth - dp(22f),
            transcriptY + dp(16f),
        )
        text.drawCentered(
            canvas,
            voiceVisibleText(state),
            paint,
            rendererState.viewWidth / 2f,
            transcriptY,
        )
        canvas.restoreToCount(transcriptSave)

        drawVoiceWaveform(canvas, rendererState, state)
    }

    private fun drawVoiceWaveform(
        canvas: Canvas,
        rendererState: KeyboardRendererState,
        state: VoiceSurfaceState,
    ) {
        val bounds = rendererState.scene.voiceWaveformBounds
        if (bounds.isEmpty) return
        val sampleCount = rendererState.voiceWaveformBuffer.copyInto(waveformSamples)
        var hasRealSignal = false
        var sampleIndex = 0
        while (sampleIndex < sampleCount) {
            if (waveformSamples[sampleIndex] > 0.015f) {
                hasRealSignal = true
                break
            }
            sampleIndex += 1
        }
        val barCount = VoiceWaveformBuffer.DEFAULT_CAPACITY
        val step = bounds.width() / (barCount - 1).coerceAtLeast(1)
        val nowPhase = (SystemClock.uptimeMillis() % 1_800L) / 1_800f
        val centerY = bounds.centerY()
        val maximumHalfHeight = bounds.height() * 0.46f

        paint.shader = waveformShader
        paint.strokeCap = Paint.Cap.ROUND
        drawVoiceWaveformPass(
            canvas = canvas,
            boundsLeft = bounds.left,
            state = state,
            sampleCount = sampleCount,
            hasRealSignal = hasRealSignal,
            barCount = barCount,
            step = step,
            nowPhase = nowPhase,
            centerY = centerY,
            maximumHalfHeight = maximumHalfHeight,
            strokeWidth = dp(5.5f),
            alpha = 46,
        )
        drawVoiceWaveformPass(
            canvas = canvas,
            boundsLeft = bounds.left,
            state = state,
            sampleCount = sampleCount,
            hasRealSignal = hasRealSignal,
            barCount = barCount,
            step = step,
            nowPhase = nowPhase,
            centerY = centerY,
            maximumHalfHeight = maximumHalfHeight,
            strokeWidth = max(1.5f, density * 1.4f),
            alpha = 235,
        )
        paint.alpha = 255
        paint.shader = null
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawVoiceWaveformPass(
        canvas: Canvas,
        boundsLeft: Float,
        state: VoiceSurfaceState,
        sampleCount: Int,
        hasRealSignal: Boolean,
        barCount: Int,
        step: Float,
        nowPhase: Float,
        centerY: Float,
        maximumHalfHeight: Float,
        strokeWidth: Float,
        alpha: Int,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.alpha = alpha
        var index = 0
        while (index < barCount) {
            val normalizedX = index / (barCount - 1f)
            val envelope = 0.45f + (1f - abs(normalizedX * 2f - 1f)) * 0.55f
            val rawLevel = if (hasRealSignal && sampleCount > 0) {
                val sampleIndex = ((sampleCount - 1) * normalizedX).toInt()
                waveformSamples[sampleIndex]
            } else {
                val wave = (sin(index * 0.41f + nowPhase * TWO_PI) + 1f) * 0.5f
                val phaseScale = when (state.phase) {
                    VoiceSurfacePhase.STARTING -> 0.18f
                    VoiceSurfacePhase.LISTENING -> 0.27f
                    VoiceSurfacePhase.PROCESSING -> 0.13f
                    VoiceSurfacePhase.ERROR -> 0.04f
                }
                0.05f + wave * phaseScale
            }
            val halfHeight = maxOf(dp(1.5f), rawLevel * envelope * maximumHalfHeight)
            val x = boundsLeft + index * step
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, paint)
            index += 1
        }
    }

    private fun drawAiLockAffordance(
        canvas: Canvas,
        rendererState: KeyboardRendererState,
        state: AiSurfaceState,
        accent: Int,
    ) {
        val geometry = rendererState.aiGeometry
        val pill = geometry.lockPill
        if (pill.isEmpty) return
        val centerY = pill.centerY
        if (rendererState.aiLocked) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x165B72E8, 0x269C8CFF)
            canvas.drawRoundRect(
                pill.left,
                pill.top,
                pill.right,
                pill.bottom,
                pill.height / 2f,
                pill.height / 2f,
                paint,
            )
            paint.color = accent
            canvas.drawCircle(pill.left + dp(18f), centerY, dp(4f), paint)
            paint.color = color(0xFF42526A.toInt(), 0xFFE1E3E8.toInt())
            paint.textSize = sp(12.5f)
            paint.textAlign = Paint.Align.LEFT
            text.drawCentered(
                canvas,
                AI_LOCKED_HINT,
                paint,
                pill.left + dp(31f),
                centerY,
            )

            val stop = geometry.stopBounds
            val pressed = rendererState.aiStopPressed
            paint.color = if (pressed) {
                color(0xFFE34B58.toInt(), 0xFFFF6D78.toInt())
            } else {
                color(0x22D14D58, 0x36FF7C86)
            }
            canvas.drawRoundRect(
                stop.left,
                stop.top,
                stop.right,
                stop.bottom,
                stop.height / 2f,
                stop.height / 2f,
                paint,
            )
            paint.color = color(0xFFD14D58.toInt(), 0xFFFFA1A8.toInt())
            val square = dp(if (pressed) 11f else 10f)
            canvas.drawRoundRect(
                stop.centerX - square / 2f,
                stop.centerY - square / 2f,
                stop.centerX + square / 2f,
                stop.centerY + square / 2f,
                dp(2f),
                dp(2f),
                paint,
            )
            return
        }

        val progress = AiSurfaceContract.lockVisualProgress(rendererState.aiLockProgress)
        paint.style = Paint.Style.FILL
        paint.color = color(0x145B72E8, 0x229C8CFF)
        canvas.drawRoundRect(
            pill.left,
            pill.top,
            pill.right,
            pill.bottom,
            pill.height / 2f,
            pill.height / 2f,
            paint,
        )
        val arrowX = pill.left + dp(22f)
        val arrowLift = dp(7f) * progress
        paint.color = accent
        paint.strokeWidth = max(1.8f * density, 2f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            arrowX,
            centerY + dp(5f) - arrowLift,
            arrowX,
            centerY - dp(6f) - arrowLift,
            paint,
        )
        canvas.drawLine(
            arrowX,
            centerY - dp(6f) - arrowLift,
            arrowX - dp(4f),
            centerY - dp(2f) - arrowLift,
            paint,
        )
        canvas.drawLine(
            arrowX,
            centerY - dp(6f) - arrowLift,
            arrowX + dp(4f),
            centerY - dp(2f) - arrowLift,
            paint,
        )
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = color(0xFF667085.toInt(), 0xFFCACCD2.toInt())
        paint.textSize = sp(12.5f)
        paint.textAlign = Paint.Align.LEFT
        text.drawCentered(
            canvas,
            if (progress >= 0.72f) AI_LOCK_NEAR_HINT else AI_LOCK_HINT,
            paint,
            pill.left + dp(39f),
            centerY,
        )
    }

    private fun drawAiPreviewText(
        canvas: Canvas,
        value: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        if (right <= left || bottom <= top) return
        val saveCount = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        paint.color = color(0xFF182235.toInt(), 0xFFF1F2F5.toInt())
        paint.textSize = sp(15f)
        paint.textAlign = Paint.Align.LEFT
        val fontMetrics = text.fontMetrics(paint)
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent) + dp(5f)
        previewLines.ensure(
            text = value,
            width = right - left,
            textSize = paint.textSize,
            breakText = previewTextBreaker,
        )
        val maxLines = maxOf(1, ((bottom - top) / lineHeight).toInt())
        val firstLine = maxOf(0, previewLines.lineCount - maxLines)
        var baseline = top - fontMetrics.ascent
        var lineIndex = firstLine
        while (lineIndex < previewLines.lineCount) {
            if (baseline + fontMetrics.descent > bottom) break
            val start = previewLines.startAt(lineIndex)
            val end = previewLines.endAt(lineIndex)
            if (end > start) canvas.drawText(value, start, end, left, baseline, paint)
            baseline += lineHeight
            lineIndex += 1
        }
        canvas.restoreToCount(saveCount)
    }

    private fun waitingText(phase: AiSurfacePhase): String = when (phase) {
        AiSurfacePhase.STARTING -> WAITING_START
        AiSurfacePhase.STREAMING -> WAITING_STREAM
        AiSurfacePhase.COMPLETE -> WAITING_COMPLETE
        AiSurfacePhase.ERROR -> WAITING_ERROR
    }

    private fun voiceVisibleText(state: VoiceSurfaceState): String =
        state.visibleText.ifBlank {
            when (state.phase) {
                VoiceSurfacePhase.STARTING -> VOICE_STARTING
                VoiceSurfacePhase.LISTENING -> VOICE_LISTENING
                VoiceSurfacePhase.PROCESSING -> VOICE_PROCESSING
                VoiceSurfacePhase.ERROR -> VOICE_ERROR
            }
        }

    private fun color(
        light: Int,
        dark: Int,
    ): Int = palette.color(light, dark)

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * density * fontScale

    private companion object {
        const val EMPTY_TEXT = ""
        const val FAILURE_MARK = "!"
        const val OUTPUT_PREVIEW = "输出预览"
        const val WAITING_START = "正在建立会话，稍后将在这里显示结果"
        const val WAITING_STREAM = "Agent 仍在工作；可继续按住，或上滑锁定后松手"
        const val WAITING_COMPLETE = "本次任务没有需要展示的替换文字"
        const val WAITING_ERROR = "已保留最后状态，输入框未被未经校验的结果覆盖"
        const val AI_LOCKED_HINT = "AI 已锁定 · 可松手"
        const val AI_LOCK_NEAR_HINT = "继续上滑即可锁定"
        const val AI_LOCK_HINT = "上滑锁定 · 松开取消"
        const val VOICE_HEADER = "语音转文字"
        const val VOICE_STARTING = "正在准备麦克风…"
        const val VOICE_LISTENING = "请开始说话"
        const val VOICE_PROCESSING = "正在整理识别结果"
        const val VOICE_ERROR = "可重试或返回键盘"
        const val TWO_PI = 6.28318f
    }
}

internal fun defaultAiStatus(phase: AiSurfacePhase): String = when (phase) {
    AiSurfacePhase.STARTING -> "先思 AI · 正在思考"
    AiSurfacePhase.STREAMING -> "先思 AI · 正在生成"
    AiSurfacePhase.COMPLETE -> "先思 AI · 已完成"
    AiSurfacePhase.ERROR -> "先思 AI · 出现错误"
}
