package io.github.ethanbird.senseime.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.max

/**
 * Background, candidate chrome and panel-header renderer.
 *
 * Background and system-bar paints are deliberately isolated from the mutable
 * chrome paint so translucent footer state cannot leak into a later dirty frame.
 */
internal class KeyboardChromeRenderer(
    private val density: Float,
    fontScale: Float,
    private val metrics: KeyboardMetrics,
    private val palette: KeyboardPalette,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint()
    private val systemBarPaint = Paint()
    private val text = KeyboardCanvasText()
    private var fontScale = fontScale

    fun updateSurface(
        width: Int,
        height: Int,
        fontScale: Float,
    ) {
        this.fontScale = fontScale
        backgroundPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.coerceAtLeast(1).toFloat(),
            color(0xFFF1F5FA.toInt(), 0xFF171819.toInt()),
            color(0xFFE6EDF6.toInt(), 0xFF111213.toInt()),
            Shader.TileMode.CLAMP,
        )
        systemBarPaint.color = color(0x18000000, 0x2A000000)
    }

    fun drawBackground(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        canvas.drawRect(
            0f,
            0f,
            state.viewWidth.toFloat(),
            state.viewHeight.toFloat(),
            backgroundPaint,
        )
        canvas.drawRect(
            0f,
            state.viewHeight - metrics.systemBarHeight,
            state.viewWidth.toFloat(),
            state.viewHeight.toFloat(),
            systemBarPaint,
        )
    }

    fun drawSkillFeedback(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        val message = state.skillFeedbackMessage ?: return
        val horizontalInset = dp(12f)
        val top = state.chromeBottom + dp(7f)
        val right = state.viewWidth - horizontalInset
        val bottom = top + dp(34f)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.alpha = 246
        paint.color = color(0xFFFDEBED.toInt(), 0xFF49262B.toInt())
        canvas.drawRoundRect(
            horizontalInset,
            top,
            right,
            bottom,
            dp(10f),
            dp(10f),
            paint,
        )
        paint.alpha = 255
        paint.color = color(0xFF9F2836.toInt(), 0xFFFFA3AC.toInt())
        paint.textSize = sp(11.5f)
        paint.textAlign = Paint.Align.CENTER
        text.drawEllipsized(
            canvas = canvas,
            text = message,
            paint = paint,
            x = (horizontalInset + right) / 2f,
            centerY = (top + bottom) / 2f,
            maximumWidth = right - horizontalInset - dp(20f),
        )
    }

    fun drawCandidates(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        val candidates = state.candidates
        if (candidates.expanded) {
            drawExpandedCandidates(canvas, state)
            return
        }
        if (candidates.composing.isBlank() && candidates.candidates.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.color = color(0x22FFFFFF, 0x0FFFFFFF)
        canvas.drawRect(
            0f,
            0f,
            state.viewWidth.toFloat(),
            state.collapsedCandidateBottom,
            paint,
        )

        if (candidates.composing.isNotBlank()) {
            paint.color = color(0xFF667085.toInt(), 0xFF8F949E.toInt())
            paint.textSize = sp(11f)
            paint.textAlign = Paint.Align.LEFT
            text.drawEllipsized(
                canvas = canvas,
                text = candidates.composing,
                paint = paint,
                x = dp(10f),
                centerY = dp(10f),
                maximumWidth = state.viewWidth - dp(20f),
                trimTrailingWhitespace = false,
            )
        }
        if (candidates.candidates.isEmpty()) return

        drawVisibleCandidates(canvas, state)
        var controlIndex = 0
        while (controlIndex < candidates.controls.size) {
            drawCandidateControl(canvas, state, candidates.controls[controlIndex])
            controlIndex += 1
        }
    }

    private fun drawExpandedCandidates(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        val candidates = state.candidates
        val systemBarTop = state.viewHeight - metrics.systemBarHeight
        paint.style = Paint.Style.FILL
        paint.color = color(0xF2EDF3FA.toInt(), 0xF2161718.toInt())
        canvas.drawRect(0f, 0f, state.viewWidth.toFloat(), systemBarTop, paint)

        paint.color = color(0x16000000, 0x24FFFFFF)
        canvas.drawRect(
            0f,
            metrics.candidateHeight - max(1f, density),
            state.viewWidth.toFloat(),
            metrics.candidateHeight,
            paint,
        )

        paint.color = color(0xFF596579.toInt(), 0xFFB8BBC2.toInt())
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.LEFT
        val headerRight = state.viewWidth - metrics.candidateControlWidth
        val saveCount = canvas.save()
        canvas.clipRect(dp(14f), 0f, headerRight, metrics.candidateHeight)
        text.drawCentered(
            canvas,
            if (candidates.composing.isBlank()) CANDIDATE_HEADER else candidates.composing,
            paint,
            dp(14f),
            metrics.candidateHeight / 2f,
        )
        canvas.restoreToCount(saveCount)

        drawVisibleCandidates(canvas, state)

        val pagerTop =
            systemBarTop - metrics.expandedCandidatePagerHeight
        paint.color = color(0x16000000, 0x24FFFFFF)
        canvas.drawRect(
            0f,
            pagerTop,
            state.viewWidth.toFloat(),
            pagerTop + max(1f, density),
            paint,
        )
        paint.color = color(0xFF667085.toInt(), 0xFF9B9EA5.toInt())
        paint.textSize = sp(12f)
        paint.textAlign = Paint.Align.CENTER
        text.drawCentered(
            canvas,
            candidates.pageLabel,
            paint,
            state.viewWidth / 2f,
            pagerTop + metrics.expandedCandidatePagerHeight / 2f,
        )
        var controlIndex = 0
        while (controlIndex < candidates.controls.size) {
            drawCandidateControl(canvas, state, candidates.controls[controlIndex])
            controlIndex += 1
        }
    }

    private fun drawVisibleCandidates(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        val candidates = state.candidates
        if (candidates.expanded) {
            var index = 0
            while (index < candidates.visibleCandidates.size) {
                drawCandidateValue(
                    canvas,
                    state,
                    candidates.visibleCandidates[index],
                    textSizeSp = 17f,
                )
                index += 1
            }
            return
        }

        val viewport = candidates.collapsedViewportBounds ?: return
        val offset = candidates.scrollOffset
        val visibleContentLeft = viewport.left + offset
        val visibleContentRight = viewport.right + offset
        val saveCount = canvas.save()
        canvas.clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom)
        canvas.translate(-offset, 0f)
        var candidateIndex = candidates.firstCandidateEndingAfter(visibleContentLeft)
        val textSize = if (state.candidatesTakeToolbar) 19f else 17f
        while (candidateIndex < candidates.visibleCandidates.size) {
            val candidate = candidates.visibleCandidates[candidateIndex]
            if (candidate.bounds.left >= visibleContentRight) break
            drawCandidateValue(canvas, state, candidate, textSizeSp = textSize)
            candidateIndex += 1
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawCandidateValue(
        canvas: Canvas,
        state: KeyboardRendererState,
        candidate: VisibleCandidate,
        textSizeSp: Float,
    ) {
        val value = state.candidates.candidates.getOrNull(candidate.sourceIndex) ?: return
        val bounds = candidate.bounds
        if (
            state.candidates.candidatesReady &&
            state.isCandidatePressed(candidate.sourceIndex)
        ) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x294F7CF5, 0x505E63D8)
            canvas.drawRoundRect(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                dp(7f),
                dp(7f),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
        // Keep the retained batch visually identical while the next async
        // decode is pending. Readiness is an interaction contract rather than
        // a theme state; changing text opacity on every key press looks like
        // a full candidate-strip flash.
        paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
        paint.textSize = sp(textSizeSp)
        paint.textAlign = Paint.Align.LEFT
        val saveCount = canvas.save()
        canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        text.drawCentered(
            canvas,
            value,
            paint,
            candidate.textAnchor,
            bounds.centerY + dp(2f),
        )
        canvas.restoreToCount(saveCount)
    }

    private fun drawCandidateControl(
        canvas: Canvas,
        state: KeyboardRendererState,
        slot: CandidateControlSlot,
    ) {
        val bounds = slot.bounds
        if (state.isCandidateControlPressed(slot.control)) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x254F7CF5, 0x405E63D8)
            canvas.drawRoundRect(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                dp(9f),
                dp(9f),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = if (slot.enabled) {
            color(0xFF586477.toInt(), 0xFFAAAEB6.toInt())
        } else {
            color(0x55586477, 0x55AAAEB6)
        }
        paint.textSize = sp(20f)
        paint.textAlign = Paint.Align.CENTER
        text.drawCentered(
            canvas,
            when (slot.control) {
                CandidateControl.EXPAND -> "⌄"
                CandidateControl.COLLAPSE -> "⌃"
                CandidateControl.PREVIOUS_PAGE -> "‹"
                CandidateControl.NEXT_PAGE -> "›"
            },
            paint,
            bounds.centerX,
            bounds.centerY,
        )
    }

    fun drawClipboardHeader(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        val top = state.chromeBottom
        paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
        paint.textSize = sp(13.5f)
        paint.textAlign = Paint.Align.LEFT
        text.drawCentered(canvas, CLIPBOARD_HEADER, paint, dp(14f), top + dp(19f))
        val pageLabel = state.scene.clipboardPageLabel
        if (pageLabel.isNotEmpty()) {
            paint.color = color(0xFF748094.toInt(), 0xFF92969E.toInt())
            paint.textSize = sp(10f)
            text.drawCentered(canvas, pageLabel, paint, dp(62f), top + dp(19f))
        }
    }

    fun drawEditorHeader(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) = drawPanelHeader(canvas, state, EDITOR_HEADER)

    fun drawInputSchemeHeader(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) = drawPanelHeader(canvas, state, INPUT_SCHEME_HEADER)

    private fun drawPanelHeader(
        canvas: Canvas,
        state: KeyboardRendererState,
        title: String,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color(0x16FFFFFF, 0x0AFFFFFF)
        canvas.drawRect(0f, 0f, state.viewWidth.toFloat(), metrics.candidateHeight, paint)
        paint.color = color(0xFF344054.toInt(), 0xFFE7E9EE.toInt())
        paint.textSize = sp(15f)
        paint.textAlign = Paint.Align.LEFT
        text.drawCentered(
            canvas,
            title,
            paint,
            dp(14f),
            metrics.candidateHeight / 2f,
        )
        paint.color = color(0x1F172033, 0x35FFFFFF)
        canvas.drawRect(
            0f,
            metrics.candidateHeight - max(1f, density),
            state.viewWidth.toFloat(),
            metrics.candidateHeight,
            paint,
        )
    }

    fun drawEditorPanelBackground(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        val scene = state.scene
        val bounds = scene.editorMainBounds ?: return
        paint.style = Paint.Style.FILL
        paint.color = color(0xCFFFFFFF.toInt(), 0xFF252628.toInt())
        canvas.drawRoundRect(bounds, dp(14f), dp(14f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density)
        paint.color = color(0x17172033, 0x2AFFFFFF)
        canvas.drawRoundRect(bounds, dp(14f), dp(14f), paint)
        paint.style = Paint.Style.FILL
        paint.color = color(0x12172033, 0x22FFFFFF)
        val divider = max(1f, density)
        canvas.drawRect(
            bounds.left,
            scene.editorBottomTop,
            bounds.right,
            scene.editorBottomTop + divider,
            paint,
        )
        var index = 0
        while (index < scene.editorBottomSeparatorCount) {
            val x = scene.editorBottomSeparators[index]
            canvas.drawRect(
                x,
                scene.editorBottomTop,
                x + divider,
                bounds.bottom,
                paint,
            )
            index += 1
        }
    }

    fun drawToolboxPanelBackground(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        val top = state.chromeBottom
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF344258.toInt(), 0xFFE7E9ED.toInt())
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.LEFT
        text.drawCentered(canvas, TOOLBOX_HEADER, paint, metrics.horizontalPadding, top + dp(20f))
        paint.color = color(0xFF8290A4.toInt(), 0xFF989DA7.toInt())
        paint.textSize = sp(10.5f)
        paint.textAlign = Paint.Align.RIGHT
        text.drawCentered(
            canvas,
            TOOLBOX_HINT,
            paint,
            state.viewWidth - metrics.horizontalPadding,
            top + dp(20f),
        )
    }

    fun drawSymbolPanelBackground(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color(0xB8FFFFFF.toInt(), 0xFF252627.toInt())
        val categoryBounds = state.scene.symbolCategoryBounds
        if (categoryBounds != null) {
            canvas.drawRoundRect(categoryBounds, dp(8f), dp(8f), paint)
        }
        val gridBounds = state.scene.symbolGridBounds
        if (gridBounds != null) {
            canvas.drawRoundRect(gridBounds, dp(8f), dp(8f), paint)
        }
    }

    private fun color(
        light: Int,
        dark: Int,
    ): Int = palette.color(light, dark)

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * density * fontScale

    private companion object {
        const val CANDIDATE_HEADER = "候选"
        const val CLIPBOARD_HEADER = "剪贴板"
        const val EDITOR_HEADER = "文字编辑"
        const val INPUT_SCHEME_HEADER = "键盘选择"
        const val TOOLBOX_HEADER = "工具箱"
        const val TOOLBOX_HINT = "长按空格唤醒 AI"
    }
}
