package io.github.ethanbird.senseime.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/**
 * Key-sized transparent overlay for the exact physical key owning an active Skill.
 *
 * Add this View after [SenseKeyboardView] in a common [FrameLayout]. The
 * rectangle passed to [updateBounds] uses the common parent's coordinate
 * system. This View immediately copies it, moves/resizes its actual child
 * layer to the integer envelope of that key, and draws in local coordinates.
 * It is intentionally non-interactive and accessibility-hidden so the keyboard
 * remains the sole touch and TalkBack target.
 *
 * The SweepGradient, Matrix and Paint are created once. Animation callbacks
 * mutate only primitive fields and cached graphics objects, and invalidate
 * only the active key. When the system disables animators, a stable Aurora
 * phase is drawn once and no recurring callback is retained.
 *
 * All public methods must be called on the main thread.
 */
class ActiveSkillAuroraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val parentBounds = RectF()
    private val activeBounds = RectF()
    private val strokeBounds = RectF()
    private val shaderMatrix = Matrix()
    private val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val auroraShader: SweepGradient = SweepGradient(
        0f,
        0f,
        intArrayOf(
            0xFF16E6D4.toInt(),
            0xFF4F7CFF.toInt(),
            0xFFD355FF.toInt(),
            0xFFFF5CB6.toInt(),
            0xFF77F4A8.toInt(),
            0xFF16E6D4.toInt(),
        ),
        null,
    )

    private var active = false
    private var hostRenderingEnabled = true
    private var cornerRadiusPixels = 0f
    private var frameCallbackPosted = false

    private var postedFrameCallbacks = 0L
    private var executedFrameCallbacks = 0L
    private var cancelledFrameCallbacks = 0L
    private var drawCalls = 0L
    private var animatedDrawCalls = 0L
    private var staticDrawCalls = 0L

    private val frameRunnable = object : Runnable {
        override fun run() {
            frameCallbackPosted = false
            executedFrameCallbacks += 1L
            when (currentRenderMode()) {
                ActiveSkillAuroraLoopPolicy.RenderMode.ANIMATED -> {
                    invalidateActiveBounds()
                    postNextFrame()
                }

                ActiveSkillAuroraLoopPolicy.RenderMode.STATIC -> {
                    // Commit the deterministic reduced-motion phase, then stop.
                    invalidateActiveBounds()
                }

                ActiveSkillAuroraLoopPolicy.RenderMode.INACTIVE -> Unit
            }
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isLongClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Atomically updates the active physical-key rectangle in parent coordinates.
     *
     * Passing `null` deactivates the effect. Non-null bounds must be finite and
     * non-empty. The requested radius is clamped only to the geometric maximum
     * of the exact rectangle; the rectangle itself is never expanded.
     */
    fun updateBounds(
        boundsInParent: RectF?,
        cornerRadiusPx: Float = 0f,
    ) {
        requireMainThread()
        if (boundsInParent == null) {
            clear()
            return
        }
        require(
            boundsInParent.left.isFinite() &&
                boundsInParent.top.isFinite() &&
                boundsInParent.right.isFinite() &&
                boundsInParent.bottom.isFinite() &&
                !boundsInParent.isEmpty,
        ) { "Active Skill Aurora bounds must be finite and non-empty" }
        require(cornerRadiusPx.isFinite() && cornerRadiusPx >= 0f) {
            "Active Skill Aurora corner radius must be finite and non-negative"
        }

        val boundsChanged = !parentBounds.equals(boundsInParent)
        val layerLeft = floor(boundsInParent.left.toDouble()).toInt()
        val layerTop = floor(boundsInParent.top.toDouble()).toInt()
        val layerRight = ceil(boundsInParent.right.toDouble()).toInt()
        val layerBottom = ceil(boundsInParent.bottom.toDouble()).toInt()
        val layerWidth = (layerRight - layerLeft).coerceAtLeast(1)
        val layerHeight = (layerBottom - layerTop).coerceAtLeast(1)
        val resolvedRadius = minOf(
            cornerRadiusPx,
            boundsInParent.width() * 0.5f,
            boundsInParent.height() * 0.5f,
        )
        val radiusChanged = cornerRadiusPixels != resolvedRadius
        val wasActive = active
        if (wasActive && (boundsChanged || radiusChanged)) {
            invalidateActiveBounds()
        }

        parentBounds.set(boundsInParent)
        activeBounds.set(
            boundsInParent.left - layerLeft,
            boundsInParent.top - layerTop,
            boundsInParent.right - layerLeft,
            boundsInParent.bottom - layerTop,
        )
        val halfStroke = STROKE_WIDTH_DP * density * 0.5f
        strokeBounds.set(activeBounds)
        strokeBounds.inset(halfStroke, halfStroke)
        cornerRadiusPixels = resolvedRadius
        active = true
        updateLayerFrame(layerLeft, layerTop, layerWidth, layerHeight)

        if (!wasActive || boundsChanged || radiusChanged) {
            invalidateActiveBounds()
        }
        synchronizeAnimation(invalidateStaticFrame = false)
    }

    /** Deactivates the effect, removes its callback, and redraws the old key area. */
    fun clear() {
        requireMainThread()
        if (!active) {
            cancelFrameCallback()
            return
        }
        active = false
        parentBounds.setEmpty()
        activeBounds.setEmpty()
        strokeBounds.setEmpty()
        cornerRadiusPixels = 0f
        cancelFrameCallback()
        // The entire View is only one physical key, so a full invalidation is
        // both exact and independent of platform dirty-rectangle behavior.
        invalidate()
    }

    /**
     * Enables rendering only while the owning IME window is shown.
     *
     * An [android.inputmethodservice.InputMethodService] window is normally
     * non-focusable, so window focus must never be used as the lifecycle signal.
     * Production hosts drive this method from `onWindowShown`/`onWindowHidden`.
     */
    fun setHostRenderingEnabled(enabled: Boolean) {
        requireMainThread()
        if (hostRenderingEnabled == enabled) return
        hostRenderingEnabled = enabled
        invalidateActiveBounds()
        synchronizeAnimation(invalidateStaticFrame = false)
    }

    /**
     * Re-evaluates the system animator scale.
     *
     * Hosts may call this after returning from accessibility/developer settings.
     * Attach, visibility, host rendering state, activation and every pending frame already
     * perform the same check.
     */
    fun refreshMotionPreference() {
        requireMainThread()
        synchronizeAnimation(invalidateStaticFrame = true)
    }

    /**
     * Returns cheap always-on counters for device gates.
     *
     * Counter collection contains no build-type branch and allocates only this
     * explicitly requested immutable snapshot, never an animation frame.
     */
    fun instrumentationSnapshot(): ActiveSkillAuroraInstrumentationSnapshot {
        requireMainThread()
        return ActiveSkillAuroraInstrumentationSnapshot(
            postedFrameCallbacks = postedFrameCallbacks,
            executedFrameCallbacks = executedFrameCallbacks,
            cancelledFrameCallbacks = cancelledFrameCallbacks,
            drawCalls = drawCalls,
            animatedDrawCalls = animatedDrawCalls,
            staticDrawCalls = staticDrawCalls,
            active = active,
            frameCallbackPosted = frameCallbackPosted,
        )
    }

    /**
     * Starts a fresh measurement interval without changing the visible effect.
     */
    fun resetInstrumentation() {
        requireMainThread()
        cancelFrameCallback()
        postedFrameCallbacks = 0L
        executedFrameCallbacks = 0L
        cancelledFrameCallbacks = 0L
        drawCalls = 0L
        animatedDrawCalls = 0L
        staticDrawCalls = 0L
        synchronizeAnimation(invalidateStaticFrame = false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mode = currentRenderMode()
        if (mode == ActiveSkillAuroraLoopPolicy.RenderMode.INACTIVE) {
            cancelFrameCallback()
            return
        }

        val phase = ActiveSkillAuroraLoopPolicy.phase(
            uptimeMillis = SystemClock.uptimeMillis(),
            periodMillis = resolvedPeriodMillis(),
            mode = mode,
        )
        drawAurora(canvas, phase, mode)
        drawCalls += 1L
        if (mode == ActiveSkillAuroraLoopPolicy.RenderMode.ANIMATED) {
            animatedDrawCalls += 1L
            postNextFrame()
        } else {
            staticDrawCalls += 1L
            cancelFrameCallback()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronizeAnimation(invalidateStaticFrame = true)
    }

    override fun onDetachedFromWindow() {
        cancelFrameCallback()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        synchronizeAnimation(invalidateStaticFrame = true)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        synchronizeAnimation(invalidateStaticFrame = true)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        synchronizeAnimation(invalidateStaticFrame = true)
    }

    private fun synchronizeAnimation(invalidateStaticFrame: Boolean) {
        when (currentRenderMode()) {
            ActiveSkillAuroraLoopPolicy.RenderMode.ANIMATED -> postNextFrame()
            ActiveSkillAuroraLoopPolicy.RenderMode.STATIC -> {
                cancelFrameCallback()
                if (invalidateStaticFrame) invalidateActiveBounds()
            }

            ActiveSkillAuroraLoopPolicy.RenderMode.INACTIVE -> cancelFrameCallback()
        }
    }

    private fun postNextFrame() {
        if (frameCallbackPosted) return
        if (
            !ActiveSkillAuroraLoopPolicy.requiresFrameCallback(currentRenderMode())
        ) {
            return
        }
        frameCallbackPosted = true
        postedFrameCallbacks += 1L
        postOnAnimationDelayed(
            frameRunnable,
            ActiveSkillAuroraLoopPolicy.FRAME_INTERVAL_MILLIS,
        )
    }

    private fun cancelFrameCallback() {
        if (!frameCallbackPosted) return
        removeCallbacks(frameRunnable)
        frameCallbackPosted = false
        cancelledFrameCallbacks += 1L
    }

    private fun currentRenderMode(): ActiveSkillAuroraLoopPolicy.RenderMode =
        ActiveSkillAuroraLoopPolicy.renderMode(
            active = active,
            attached = isAttachedToWindow,
            visible = isShown && windowVisibility == VISIBLE,
            hostRenderingEnabled = hostRenderingEnabled,
            hasDrawableBounds = hasDrawableBounds(),
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
        )

    private fun hasDrawableBounds(): Boolean =
        width > 0 &&
            height > 0 &&
            !activeBounds.isEmpty &&
            activeBounds.right > 0f &&
            activeBounds.bottom > 0f &&
            activeBounds.left < width.toFloat() &&
            activeBounds.top < height.toFloat()

    private fun drawAurora(
        canvas: Canvas,
        phase: Float,
        mode: ActiveSkillAuroraLoopPolicy.RenderMode,
    ) {
        val scale = maxOf(activeBounds.width(), activeBounds.height()).coerceAtLeast(1f)
        shaderMatrix.reset()
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postRotate(phase * FULL_ROTATION_DEGREES)
        shaderMatrix.postTranslate(activeBounds.centerX(), activeBounds.centerY())
        auroraShader.setLocalMatrix(shaderMatrix)

        val pulse = if (mode == ActiveSkillAuroraLoopPolicy.RenderMode.ANIMATED) {
            ((sin(phase * TWO_PI_RADIANS) + 1f) * 0.5f)
        } else {
            STATIC_PULSE
        }
        auroraPaint.shader = auroraShader
        auroraPaint.style = Paint.Style.FILL
        auroraPaint.alpha = (FILL_ALPHA_MIN + pulse * FILL_ALPHA_RANGE).toInt()
        canvas.drawRoundRect(
            activeBounds,
            cornerRadiusPixels,
            cornerRadiusPixels,
            auroraPaint,
        )

        auroraPaint.style = Paint.Style.STROKE
        auroraPaint.strokeWidth = STROKE_WIDTH_DP * density
        auroraPaint.alpha = (STROKE_ALPHA_MIN + pulse * STROKE_ALPHA_RANGE).toInt()
        val strokeRadius = (
            cornerRadiusPixels - auroraPaint.strokeWidth * 0.5f
        ).coerceAtLeast(0f)
        canvas.drawRoundRect(
            strokeBounds,
            strokeRadius,
            strokeRadius,
            auroraPaint,
        )

        auroraPaint.shader = null
        auroraPaint.alpha = 255
        auroraPaint.style = Paint.Style.FILL
    }

    private fun invalidateActiveBounds() {
        if (!activeBounds.isEmpty) invalidate()
    }

    private fun updateLayerFrame(left: Int, top: Int, width: Int, height: Int) {
        val current = layoutParams
        val frame = when (current) {
            is FrameLayout.LayoutParams -> current
            else -> FrameLayout.LayoutParams(width, height)
        }
        if (
            frame.width == width &&
            frame.height == height &&
            frame.leftMargin == left &&
            frame.topMargin == top
        ) {
            return
        }
        frame.width = width
        frame.height = height
        frame.leftMargin = left
        frame.topMargin = top
        layoutParams = frame
    }

    private fun resolvedPeriodMillis(): Long {
        val durationScale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ValueAnimator.getDurationScale()
                .takeIf { it.isFinite() && it > 0f }
                ?: 1f
        } else {
            1f
        }
        return (ActiveSkillAuroraLoopPolicy.BASE_PERIOD_MILLIS * durationScale)
            .toLong()
            .coerceAtLeast(1L)
    }

    private fun requireMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "ActiveSkillAuroraOverlayView must be updated on the main thread"
        }
    }

    private companion object {
        const val FULL_ROTATION_DEGREES = 360f
        const val TWO_PI_RADIANS = 6.2831855f
        const val STATIC_PULSE = 0.72f
        const val FILL_ALPHA_MIN = 72f
        const val FILL_ALPHA_RANGE = 54f
        const val STROKE_ALPHA_MIN = 182f
        const val STROKE_ALPHA_RANGE = 65f
        const val STROKE_WIDTH_DP = 1.75f
    }
}

/** Explicitly allocated device-test snapshot for [ActiveSkillAuroraOverlayView]. */
data class ActiveSkillAuroraInstrumentationSnapshot(
    val postedFrameCallbacks: Long,
    val executedFrameCallbacks: Long,
    val cancelledFrameCallbacks: Long,
    val drawCalls: Long,
    val animatedDrawCalls: Long,
    val staticDrawCalls: Long,
    val active: Boolean,
    val frameCallbackPosted: Boolean,
)
