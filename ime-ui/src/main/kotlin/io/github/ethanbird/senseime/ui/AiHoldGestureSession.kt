package io.github.ethanbird.senseime.ui

import kotlin.math.min

/**
 * Android-free state machine for the press-and-hold Space AI gesture.
 *
 * Activation uses a small confirmation window after the advertised long-press
 * threshold. This gives an UP/CANCEL delivered in the same display frame a
 * deterministic chance to win instead of briefly starting an AI request.
 */
class AiHoldGestureSession(
    private val longPressTimeoutMillis: Long = DEFAULT_LONG_PRESS_TIMEOUT_MILLIS,
    private val activationConfirmationMillis: Long = DEFAULT_ACTIVATION_CONFIRMATION_MILLIS,
    private val maximumStationaryDistance: Float,
) {
    init {
        require(longPressTimeoutMillis > 0L)
        require(activationConfirmationMillis >= 0L)
        require(maximumStationaryDistance > 0f && maximumStationaryDistance.isFinite())
    }

    enum class Outcome {
        NONE,
        SHORT_TAP,
        HOLD_RELEASED,
        ELIGIBILITY_CANCELLED,
        ACTIVATED,
        ACTIVE_CANCELLED,
    }

    data class Arm(
        val pointerId: Int,
        val generation: Long,
        val activationAtMillis: Long,
    )

    private enum class State {
        IDLE,
        ARMED,
        ACTIVE,
    }

    private var state = State.IDLE
    private var ownerPointerId = NO_POINTER
    private var generation = 0L
    private var downX = 0f
    private var downY = 0f
    private var activationAtMillis = 0L

    /**
     * Arms only an idle session. A second pointer can never steal ownership.
     */
    fun begin(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
    ): Arm? {
        require(eventTimeMillis >= 0L)
        require(x.isFinite() && y.isFinite())
        if (state != State.IDLE) return null
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        ownerPointerId = pointerId
        downX = x
        downY = y
        activationAtMillis = saturatingAdd(
            saturatingAdd(eventTimeMillis, longPressTimeoutMillis),
            activationConfirmationMillis,
        )
        state = State.ARMED
        return Arm(pointerId, generation, activationAtMillis)
    }

    /**
     * Movement outside the stationary radius only disarms AI eligibility. It
     * does not consume or reinterpret the View's ordinary Space gesture.
     */
    fun move(pointerId: Int, x: Float, y: Float): Outcome {
        require(x.isFinite() && y.isFinite())
        if (state != State.ARMED || pointerId != ownerPointerId) return Outcome.NONE
        val deltaX = x - downX
        val deltaY = y - downY
        val maximumDistanceSquared = maximumStationaryDistance * maximumStationaryDistance
        if (deltaX * deltaX + deltaY * deltaY <= maximumDistanceSquared) return Outcome.NONE
        clear()
        return Outcome.ELIGIBILITY_CANCELLED
    }

    /**
     * Called by a delayed callback. Both pointer and generation are checked so
     * a callback left over from a prior tap can never activate a newer press.
     */
    fun tryActivate(
        pointerId: Int,
        expectedGeneration: Long,
        nowMillis: Long,
    ): Outcome {
        require(nowMillis >= 0L)
        if (
            state != State.ARMED ||
            pointerId != ownerPointerId ||
            expectedGeneration != generation ||
            nowMillis <= activationAtMillis
        ) {
            return Outcome.NONE
        }
        state = State.ACTIVE
        return Outcome.ACTIVATED
    }

    /**
     * UP is terminal for the owning pointer. A short press remains an ordinary
     * Space tap; a long press that could not be rendered before release is
     * consumed instead of unexpectedly inserting a late space.
     */
    fun pointerUp(pointerId: Int, eventTimeMillis: Long): Outcome {
        require(eventTimeMillis >= 0L)
        if (pointerId != ownerPointerId) return Outcome.NONE
        return when (state) {
            State.IDLE -> Outcome.NONE
            State.ARMED -> {
                val result = if (eventTimeMillis < activationAtMillis) {
                    Outcome.SHORT_TAP
                } else {
                    Outcome.HOLD_RELEASED
                }
                clear()
                result
            }
            State.ACTIVE -> {
                clear()
                Outcome.ACTIVE_CANCELLED
            }
        }
    }

    fun pointerCancel(pointerId: Int): Outcome {
        if (pointerId != ownerPointerId) return Outcome.NONE
        return cancelAll()
    }

    fun cancelAll(): Outcome {
        val result = when (state) {
            State.IDLE -> Outcome.NONE
            State.ARMED -> Outcome.ELIGIBILITY_CANCELLED
            State.ACTIVE -> Outcome.ACTIVE_CANCELLED
        }
        clear()
        return result
    }

    fun owns(pointerId: Int): Boolean =
        state != State.IDLE && ownerPointerId == pointerId

    fun activeGeneration(): Long? =
        generation.takeIf { state == State.ACTIVE }

    fun armedGeneration(): Long? =
        generation.takeIf { state == State.ARMED }

    fun millisUntilActivation(nowMillis: Long): Long {
        require(nowMillis >= 0L)
        return if (state == State.ARMED) {
            if (nowMillis > activationAtMillis) {
                0L
            } else {
                saturatingAdd(activationAtMillis - nowMillis, 1L)
            }
        } else {
            0L
        }
    }

    private fun clear() {
        state = State.IDLE
        ownerPointerId = NO_POINTER
        downX = 0f
        downY = 0f
        activationAtMillis = 0L
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        const val DEFAULT_LONG_PRESS_TIMEOUT_MILLIS = 380L
        const val DEFAULT_ACTIVATION_CONFIRMATION_MILLIS = 16L
        private const val NO_POINTER = -1
    }
}

enum class AiSurfacePhase {
    STARTING,
    STREAMING,
    COMPLETE,
    ERROR,
}

data class AiSurfaceState(
    val generation: Long,
    val phase: AiSurfacePhase,
    val preview: String,
    val statusText: String,
)

/**
 * Geometry and memory limits shared by the Canvas view and host-side tests.
 */
object AiSurfaceContract {
    const val MAX_PREVIEW_CHARS = 4_096

    data class Bounds(
        val top: Float,
        val bottom: Float,
    ) {
        val height: Float
            get() = bottom - top
    }

    fun bounds(
        keyboardHeight: Float,
        keyRegionTop: Float,
        systemBarHeight: Float,
    ): Bounds {
        require(keyboardHeight > 0f)
        require(keyRegionTop >= 0f)
        require(systemBarHeight >= 0f)
        require(keyRegionTop < keyboardHeight - systemBarHeight)
        return Bounds(
            top = keyRegionTop,
            bottom = keyboardHeight - systemBarHeight,
        )
    }

    fun boundedPreview(text: String): String {
        if (text.length <= MAX_PREVIEW_CHARS) return text
        val end = safeUtf16Boundary(text, MAX_PREVIEW_CHARS)
        return text.substring(0, end)
    }

    fun appendBounded(current: String, delta: String): String {
        if (delta.isEmpty()) return boundedPreview(current)
        if (current.length >= MAX_PREVIEW_CHARS) return boundedPreview(current)
        val remaining = MAX_PREVIEW_CHARS - current.length
        val end = safeUtf16Boundary(delta, min(delta.length, remaining))
        return current + delta.substring(0, end)
    }

    private fun safeUtf16Boundary(text: String, proposedEnd: Int): Int {
        if (
            proposedEnd in 1 until text.length &&
            text[proposedEnd - 1].isHighSurrogate() &&
            text[proposedEnd].isLowSurrogate()
        ) {
            return proposedEnd - 1
        }
        return proposedEnd
    }
}
