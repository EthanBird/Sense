package io.github.ethanbird.senseime.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Read-only projection consumed by the concrete renderer modules.
 *
 * The View owns touch/session state and publishes geometry before invalidation.
 * Renderers may mutate only their private Paint/Path/shader scratch objects.
 */
internal interface KeyboardRendererState {
    val viewWidth: Int
    val viewHeight: Int
    val panel: KeyboardPanel
    val scene: KeyboardScene
    val candidates: CandidateScene
    val candidatesTakeToolbar: Boolean
    val chromeBottom: Float
    val collapsedCandidateBottom: Float

    val aiSurface: AiSurfaceState?
    val aiLockProgress: Float
    val aiLocked: Boolean
    val aiGeometry: AiSurfaceRenderGeometry
    val aiStopPressed: Boolean

    val voiceSurface: VoiceSurfaceState?
    val voiceWaveformBuffer: VoiceWaveformBuffer

    val skillFeedbackMessage: String?
    val activeSkillSourceKey: Key?
    val activeKeyboardSkill: ActiveKeyboardSkill?
    val hasAuroraSibling: Boolean
    val skillPickerVisible: Boolean
    val skillPickerSourceBounds: RectF
    val skillPickerOptions: KeyboardSkillOptions?
    val skillPickerOptionBounds: Array<RectF>
    val highlightedSkillDirection: KeyboardSkillDirection?

    val emojiGroupIndex: Int
    val symbolCategoryIndex: Int
    val editorSelectionMode: Boolean

    fun isCandidatePressed(sourceIndex: Int): Boolean

    fun isCandidateControlPressed(control: CandidateControl): Boolean

    fun isKeyPressed(key: Key): Boolean

    fun isKeyEnabled(key: Key): Boolean
}

/**
 * Coordinates the small concrete renderer set and preserves the historical
 * single-Canvas draw order.
 */
internal class KeyboardRenderer(
    density: Float,
    fontScale: Float,
    private val metrics: KeyboardMetrics,
    private val palette: KeyboardPalette,
) {
    private val chrome = KeyboardChromeRenderer(density, fontScale, metrics, palette)
    private val aiVoice = AiVoiceRenderer(density, fontScale, metrics, palette)
    private val keys = KeyboardKeyRenderer(density, fontScale, metrics, palette)

    fun updateSurface(
        width: Int,
        height: Int,
        fontScale: Float,
    ) {
        chrome.updateSurface(width, height, fontScale)
        aiVoice.updateSurface(width, height, fontScale)
        keys.updateSurface(width, height, fontScale)
    }

    /**
     * Returns primitive frame-request bits; scheduling remains View-owned.
     */
    fun draw(
        canvas: Canvas,
        state: KeyboardRendererState,
    ): Int {
        var frameRequests = KeyboardFrameRequest.NONE
        chrome.drawBackground(canvas, state)

        val aiState = state.aiSurface
        if (aiState != null) {
            aiVoice.drawAiSurface(canvas, state, aiState)
            if (KeyboardRenderFramePolicy.aiNeedsDelayedFrame(aiState)) {
                frameRequests = frameRequests or KeyboardFrameRequest.DELAYED_AI
            }
            chrome.drawSkillFeedback(canvas, state)
            return frameRequests
        }

        if (state.panel == KeyboardPanel.VOICE) {
            val voiceState = state.voiceSurface
            if (voiceState != null) {
                aiVoice.drawVoiceSurface(canvas, state, voiceState)
                if (KeyboardRenderFramePolicy.voiceNeedsAnimationFrame(voiceState)) {
                    frameRequests = frameRequests or KeyboardFrameRequest.NEXT_ANIMATION
                }
            }
            keys.drawKeys(canvas, state)
            chrome.drawSkillFeedback(canvas, state)
            return frameRequests
        }

        if (state.panel == KeyboardPanel.EDITOR) {
            chrome.drawEditorHeader(canvas, state)
            chrome.drawEditorPanelBackground(canvas, state)
        } else {
            chrome.drawCandidates(canvas, state)
            if (!state.candidates.expanded) {
                when (state.panel) {
                    KeyboardPanel.CLIPBOARD -> chrome.drawClipboardHeader(canvas, state)
                    KeyboardPanel.SYMBOLS -> chrome.drawSymbolPanelBackground(canvas, state)
                    KeyboardPanel.TOOLBOX -> chrome.drawToolboxPanelBackground(canvas, state)
                    else -> Unit
                }
            }
        }
        keys.drawKeys(canvas, state)
        chrome.drawSkillFeedback(canvas, state)
        return frameRequests
    }
}

internal object KeyboardFrameRequest {
    const val NONE = 0
    const val DELAYED_AI = 1
    const val NEXT_ANIMATION = 1 shl 1
}

/**
 * Pure frame policy shared by renderer code and ordinary JVM tests.
 */
internal object KeyboardRenderFramePolicy {
    fun aiNeedsDelayedFrame(state: AiSurfaceState): Boolean {
        val activities = state.activities
        if (activities.isEmpty()) {
            return state.phase == AiSurfacePhase.STARTING ||
                state.phase == AiSurfacePhase.STREAMING
        }
        val maximumRows = if (state.preview.isEmpty()) 4 else 3
        var index = (activities.size - maximumRows).coerceAtLeast(0)
        while (index < activities.size) {
            if (activities[index].state == AiSurfaceActivityState.RUNNING) return true
            index += 1
        }
        return false
    }

    fun voiceNeedsAnimationFrame(state: VoiceSurfaceState): Boolean =
        state.phase == VoiceSurfacePhase.STARTING ||
            state.phase == VoiceSurfacePhase.LISTENING ||
            state.phase == VoiceSurfacePhase.PROCESSING
}

internal interface RenderRect {
    val left: Float
    val top: Float
    val right: Float
    val bottom: Float

    val width: Float
        get() = right - left
    val height: Float
        get() = bottom - top
    val centerX: Float
        get() = (left + right) / 2f
    val centerY: Float
        get() = (top + bottom) / 2f
    val isEmpty: Boolean
        get() = left >= right || top >= bottom

    fun contains(x: Float, y: Float): Boolean =
        !isEmpty && x >= left && x < right && y >= top && y < bottom
}

internal class MutableRenderRect : RenderRect {
    override var left = 0f
        private set
    override var top = 0f
        private set
    override var right = 0f
        private set
    override var bottom = 0f
        private set

    fun set(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun clear() {
        set(0f, 0f, 0f, 0f)
    }
}

internal interface AiSurfaceRenderGeometry {
    val surfaceTop: Float
    val surfaceBottom: Float
    val card: RenderRect
    val lockPill: RenderRect
    val stopBounds: RenderRect
}

/**
 * Reused AI geometry. Hit bounds are updated when size/visual lock state
 * changes, never as a side effect of drawing.
 */
internal class MutableAiSurfaceRenderGeometry : AiSurfaceRenderGeometry {
    override var surfaceTop = 0f
        private set
    override var surfaceBottom = 0f
        private set
    private val mutableCard = MutableRenderRect()
    private val mutableLockPill = MutableRenderRect()
    private val mutableStopBounds = MutableRenderRect()
    override val card: RenderRect
        get() = mutableCard
    override val lockPill: RenderRect
        get() = mutableLockPill
    override val stopBounds: RenderRect
        get() = mutableStopBounds

    internal fun update(
        viewWidth: Int,
        viewHeight: Int,
        keyRegionTop: Float,
        systemBarHeight: Float,
        horizontalPadding: Float,
        density: Float,
        active: Boolean,
        locked: Boolean,
    ) {
        if (
            !active ||
            viewWidth <= 0 ||
            viewHeight.toFloat() <= keyRegionTop + systemBarHeight
        ) {
            clear()
            return
        }
        surfaceTop = keyRegionTop
        surfaceBottom = viewHeight - systemBarHeight
        mutableCard.set(
            horizontalPadding,
            surfaceTop + 7f * density,
            viewWidth - horizontalPadding,
            surfaceBottom - 7f * density,
        )
        val barTop = viewHeight - systemBarHeight
        if (locked) {
            mutableLockPill.set(
                14f * density,
                barTop + 7f * density,
                viewWidth - 68f * density,
                viewHeight - 7f * density,
            )
            mutableStopBounds.set(
                viewWidth - 58f * density,
                barTop + 5f * density,
                viewWidth - 10f * density,
                viewHeight - 5f * density,
            )
        } else {
            val pillWidth = 150f * density
            mutableLockPill.set(
                viewWidth / 2f - pillWidth / 2f,
                barTop + 7f * density,
                viewWidth / 2f + pillWidth / 2f,
                viewHeight - 7f * density,
            )
            mutableStopBounds.clear()
        }
    }

    internal fun clear() {
        surfaceTop = 0f
        surfaceBottom = 0f
        mutableCard.clear()
        mutableLockPill.clear()
        mutableStopBounds.clear()
    }
}

/**
 * Allocation-free text baseline and ellipsis helper. The FontMetrics instance
 * and ellipsis token live for the renderer lifetime.
 */
internal class KeyboardCanvasText {
    private val fontMetrics = Paint.FontMetrics()

    fun drawCentered(
        canvas: Canvas,
        text: CharSequence,
        paint: Paint,
        x: Float,
        centerY: Float,
    ) {
        val baseline = baseline(paint, centerY)
        canvas.drawText(text, 0, text.length, x, baseline, paint)
    }

    fun drawEllipsized(
        canvas: Canvas,
        text: CharSequence,
        paint: Paint,
        x: Float,
        centerY: Float,
        maximumWidth: Float,
        trimTrailingWhitespace: Boolean = true,
    ) {
        if (maximumWidth <= 0f || text.isEmpty()) return
        val fullWidth = paint.measureText(text, 0, text.length)
        if (fullWidth <= maximumWidth) {
            drawCentered(canvas, text, paint, x, centerY)
            return
        }

        val ellipsisWidth = paint.measureText(ELLIPSIS)
        val available = (maximumWidth - ellipsisWidth).coerceAtLeast(0f)
        var count = paint.breakText(text, 0, text.length, true, available, null)
        if (
            count in 1 until text.length &&
            text[count - 1].isHighSurrogate() &&
            text[count].isLowSurrogate()
        ) {
            count -= 1
        }
        if (trimTrailingWhitespace) {
            while (count > 0 && text[count - 1].isWhitespace()) count -= 1
        }
        val contentWidth = if (count > 0) paint.measureText(text, 0, count) else 0f
        val totalWidth = contentWidth + ellipsisWidth
        val originalAlign = paint.textAlign
        val startX = when (originalAlign) {
            Paint.Align.CENTER -> x - totalWidth / 2f
            Paint.Align.RIGHT -> x - totalWidth
            Paint.Align.LEFT -> x
        }
        val baseline = baseline(paint, centerY)
        paint.textAlign = Paint.Align.LEFT
        if (count > 0) canvas.drawText(text, 0, count, startX, baseline, paint)
        canvas.drawText(ELLIPSIS, startX + contentWidth, baseline, paint)
        paint.textAlign = originalAlign
    }

    fun fontMetrics(
        paint: Paint,
    ): Paint.FontMetrics {
        paint.getFontMetrics(fontMetrics)
        return fontMetrics
    }

    private fun baseline(
        paint: Paint,
        centerY: Float,
    ): Float {
        paint.getFontMetrics(fontMetrics)
        return centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
    }

    private companion object {
        const val ELLIPSIS = "…"
    }
}
