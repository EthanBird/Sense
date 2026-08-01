package io.github.ethanbird.senseime.ui

import kotlin.math.abs
import kotlin.math.max

object KeyboardGestureThresholds {
    fun upwardFlickDistance(minimumDistance: Float, keyHeight: Float): Float {
        require(minimumDistance > 0f)
        require(keyHeight > 0f)
        return max(minimumDistance, keyHeight * 0.18f)
    }
}

/**
 * Allocation-light, pointer-aware reducer for keyboard gestures.
 *
 * A target is frozen on DOWN. MOVE can cancel it, but can never retarget the
 * pointer to a neighbouring key. This is important for fast chords where a
 * second finger may go down only a couple of milliseconds after the first.
 */
class TouchInputReducer<T>(
    private val swipeThreshold: Float,
    private val maximumHorizontalDrift: Float,
) {
    enum class Gesture {
        TAP,
        SWIPE_UP,
        SWIPE_DOWN,
        SWIPE_LEFT,
        SWIPE_RIGHT,
    }

    data class Activation<T>(
        val target: T,
        val gesture: Gesture,
    )

    /**
     * Target-specific gesture rules. A key flick and a scroll deliberately do
     * not share thresholds: flicks resolve from their final displacement,
     * while scrolling latches as soon as a directional drag clears touch slop.
     */
    class GesturePolicy private constructor(
        internal val kind: Kind,
        internal val threshold: Float,
        internal val verticalDominanceRatio: Float,
        internal val requirePointerExit: Boolean,
    ) {
        internal enum class Kind {
            TAP_ONLY,
            UPWARD_FLICK,
            VERTICAL_SCROLL,
            HORIZONTAL_SCROLL,
        }

        companion object {
            fun tapOnly(): GesturePolicy = GesturePolicy(
                kind = Kind.TAP_ONLY,
                threshold = 0f,
                verticalDominanceRatio = 1f,
                requirePointerExit = false,
            )

            fun upwardFlick(
                minimumDistance: Float,
                verticalDominanceRatio: Float,
                requirePointerExit: Boolean = false,
            ): GesturePolicy {
                require(minimumDistance > 0f)
                require(verticalDominanceRatio >= 1f)
                return GesturePolicy(
                    kind = Kind.UPWARD_FLICK,
                    threshold = minimumDistance,
                    verticalDominanceRatio = verticalDominanceRatio,
                    requirePointerExit = requirePointerExit,
                )
            }

            fun verticalScroll(
                touchSlop: Float,
                verticalDominanceRatio: Float,
            ): GesturePolicy {
                require(touchSlop > 0f)
                require(verticalDominanceRatio >= 1f)
                return GesturePolicy(
                    kind = Kind.VERTICAL_SCROLL,
                    threshold = touchSlop,
                    verticalDominanceRatio = verticalDominanceRatio,
                    requirePointerExit = false,
                )
            }

            fun horizontalScroll(
                touchSlop: Float,
                horizontalDominanceRatio: Float,
            ): GesturePolicy {
                require(touchSlop > 0f)
                require(horizontalDominanceRatio >= 1f)
                return GesturePolicy(
                    kind = Kind.HORIZONTAL_SCROLL,
                    threshold = touchSlop,
                    verticalDominanceRatio = horizontalDominanceRatio,
                    requirePointerExit = false,
                )
            }
        }
    }

    data class MoveResult(
        val canceled: Boolean,
        val tapSuppressed: Boolean,
        val verticalScrollLatched: Boolean,
    )

    /**
     * Primitive result bits for the hot MOVE path. Callers that process
     * MotionEvent streams should prefer [onMoveFlags] so every sampled pointer
     * reuses reducer storage instead of allocating a [MoveResult].
     */
    companion object {
        const val MOVE_CANCELED: Int = 1
        const val MOVE_TAP_SUPPRESSED: Int = 1 shl 1
        const val MOVE_VERTICAL_SCROLL_LATCHED: Int = 1 shl 2
        const val MOVE_SCROLL_LATCHED: Int = MOVE_VERTICAL_SCROLL_LATCHED

        private const val NONE = -1
        private const val NO_GESTURE: Byte = 0
        private const val SWIPE_UP_GESTURE: Byte = -1
        private const val SWIPE_DOWN_GESTURE: Byte = 1
    }

    private var pointerIds = IntArray(10) { NONE }
    private var targets = arrayOfNulls<Any>(10)
    private var downXs = FloatArray(10)
    private var downYs = FloatArray(10)
    private var canceled = BooleanArray(10)
    private var tapSuppressed = BooleanArray(10)
    private var exitedGestureBounds = BooleanArray(10)
    private var latchedGestures = ByteArray(10)
    private var pointerCount = 0

    /** Starts a new MotionEvent stream, discarding pointers orphaned by a missing terminal event. */
    fun onPrimaryDown(pointerId: Int, target: T, x: Float, y: Float) {
        cancelAll()
        onDown(pointerId, target, x, y)
    }

    fun onDown(pointerId: Int, target: T, x: Float, y: Float) {
        var slot = findSlot(pointerId)
        if (slot < 0) {
            slot = findFreeSlot()
            if (slot < 0) {
                grow()
                slot = findFreeSlot()
            }
            pointerCount++
        }
        pointerIds[slot] = pointerId
        targets[slot] = target
        downXs[slot] = x
        downYs[slot] = y
        canceled[slot] = false
        tapSuppressed[slot] = false
        exitedGestureBounds[slot] = false
        latchedGestures[slot] = NO_GESTURE
    }

    /** Returns true only when this MOVE newly canceled the pointer. */
    fun onMove(pointerId: Int, insideFrozenTarget: Boolean): Boolean {
        val slot = findSlot(pointerId)
        if (slot < 0) return false
        if (!insideFrozenTarget && !canceled[slot]) {
            canceled[slot] = true
            return true
        }
        return false
    }

    /**
     * Records a policy-aware MOVE without retargeting the pointer. Scroll
     * policies suppress taps permanently after touch slop, even if the finger
     * later returns to its origin. Flick policies stay alive outside the key so
     * a short upward release can still resolve against the frozen DOWN target.
     */
    fun onMove(
        pointerId: Int,
        x: Float,
        y: Float,
        insideTapTarget: Boolean,
        policy: GesturePolicy,
        insideGestureBounds: Boolean = insideTapTarget,
    ): MoveResult {
        val flags = onMoveFlags(
            pointerId = pointerId,
            x = x,
            y = y,
            insideTapTarget = insideTapTarget,
            policy = policy,
            insideGestureBounds = insideGestureBounds,
        )
        return MoveResult(
            canceled = flags and MOVE_CANCELED != 0,
            tapSuppressed = flags and MOVE_TAP_SUPPRESSED != 0,
            verticalScrollLatched = flags and MOVE_VERTICAL_SCROLL_LATCHED != 0,
        )
    }

    /**
     * Allocation-free variant of [onMove]. The returned bit set contains
     * [MOVE_CANCELED], [MOVE_TAP_SUPPRESSED], and
     * [MOVE_VERTICAL_SCROLL_LATCHED].
     */
    fun onMoveFlags(
        pointerId: Int,
        x: Float,
        y: Float,
        insideTapTarget: Boolean,
        policy: GesturePolicy,
        insideGestureBounds: Boolean = insideTapTarget,
    ): Int {
        val slot = findSlot(pointerId)
        if (slot < 0) return 0
        updateGestureTrace(slot, x, y, insideGestureBounds, policy)
        if (policy.kind == GesturePolicy.Kind.TAP_ONLY && !insideTapTarget && !canceled[slot]) {
            canceled[slot] = true
        }
        var flags = 0
        if (canceled[slot]) flags = flags or MOVE_CANCELED
        if (tapSuppressed[slot]) flags = flags or MOVE_TAP_SUPPRESSED
        if (latchedGestures[slot] != NO_GESTURE) {
            flags = flags or MOVE_VERTICAL_SCROLL_LATCHED
        }
        return flags
    }

    fun onUp(pointerId: Int, x: Float, y: Float, insideFrozenTarget: Boolean): Activation<T>? {
        val slot = findSlot(pointerId)
        if (slot < 0) return null
        @Suppress("UNCHECKED_CAST")
        val target = targets[slot] as T
        val wasCanceled = canceled[slot]
        val deltaX = x - downXs[slot]
        val deltaY = y - downYs[slot]
        release(slot)
        if (wasCanceled || !insideFrozenTarget) return null
        val gesture = when {
            abs(deltaX) <= maximumHorizontalDrift && deltaY <= -swipeThreshold -> Gesture.SWIPE_UP
            abs(deltaX) <= maximumHorizontalDrift && deltaY >= swipeThreshold -> Gesture.SWIPE_DOWN
            else -> Gesture.TAP
        }
        return Activation(target, gesture)
    }

    fun onUp(
        pointerId: Int,
        x: Float,
        y: Float,
        insideTapTarget: Boolean,
        policy: GesturePolicy,
        insideGestureBounds: Boolean = insideTapTarget,
    ): Activation<T>? {
        val slot = findSlot(pointerId)
        if (slot < 0) return null
        @Suppress("UNCHECKED_CAST")
        val target = targets[slot] as T
        val gesture = onUpGesture(
            pointerId = pointerId,
            x = x,
            y = y,
            insideTapTarget = insideTapTarget,
            policy = policy,
            insideGestureBounds = insideGestureBounds,
        ) ?: return null
        return Activation(target, gesture)
    }

    /**
     * Allocation-free terminal path. The caller reads [target] before this
     * method and receives only the enum outcome; reducer ownership is released
     * exactly as in [onUp].
     */
    fun onUpGesture(
        pointerId: Int,
        x: Float,
        y: Float,
        insideTapTarget: Boolean,
        policy: GesturePolicy,
        insideGestureBounds: Boolean = insideTapTarget,
    ): Gesture? {
        val slot = findSlot(pointerId)
        if (slot < 0) return null
        updateGestureTrace(slot, x, y, insideGestureBounds, policy)
        val wasCanceled = canceled[slot]
        val wasTapSuppressed = tapSuppressed[slot]
        val latchedGesture = latchedGestures[slot]
        val pointerExitedGestureBounds = exitedGestureBounds[slot]
        val deltaX = x - downXs[slot]
        val deltaY = y - downYs[slot]
        release(slot)
        if (wasCanceled) return null

        val gesture = when (policy.kind) {
            GesturePolicy.Kind.TAP_ONLY -> {
                if (!insideTapTarget) return null
                Gesture.TAP
            }

            GesturePolicy.Kind.UPWARD_FLICK -> {
                if (
                    deltaY <= -policy.threshold &&
                    -deltaY > abs(deltaX) * policy.verticalDominanceRatio &&
                    (!policy.requirePointerExit || pointerExitedGestureBounds)
                ) {
                    Gesture.SWIPE_UP
                } else {
                    if (!insideTapTarget) return null
                    Gesture.TAP
                }
            }

            GesturePolicy.Kind.VERTICAL_SCROLL -> when (latchedGesture) {
                SWIPE_UP_GESTURE -> Gesture.SWIPE_UP
                SWIPE_DOWN_GESTURE -> Gesture.SWIPE_DOWN
                else -> {
                    if (wasTapSuppressed || !insideTapTarget) return null
                    Gesture.TAP
                }
            }

            GesturePolicy.Kind.HORIZONTAL_SCROLL -> when (latchedGesture) {
                SWIPE_UP_GESTURE -> Gesture.SWIPE_LEFT
                SWIPE_DOWN_GESTURE -> Gesture.SWIPE_RIGHT
                else -> {
                    if (wasTapSuppressed || !insideTapTarget) return null
                    Gesture.TAP
                }
            }
        }
        return gesture
    }

    fun cancel(pointerId: Int): T? {
        val slot = findSlot(pointerId)
        if (slot < 0) return null
        @Suppress("UNCHECKED_CAST")
        val target = targets[slot] as T
        release(slot)
        return target
    }

    fun cancelAll() {
        for (index in pointerIds.indices) {
            pointerIds[index] = NONE
            targets[index] = null
            canceled[index] = false
            tapSuppressed[index] = false
            exitedGestureBounds[index] = false
            latchedGestures[index] = NO_GESTURE
        }
        pointerCount = 0
    }

    fun target(pointerId: Int): T? {
        val slot = findSlot(pointerId)
        if (slot < 0) return null
        @Suppress("UNCHECKED_CAST")
        return targets[slot] as T
    }

    fun isPressed(pointerId: Int): Boolean {
        val slot = findSlot(pointerId)
        return slot >= 0 && !canceled[slot] && !tapSuppressed[slot]
    }

    val activePointerCount: Int
        get() = pointerCount

    private fun findSlot(pointerId: Int): Int {
        for (index in pointerIds.indices) if (pointerIds[index] == pointerId) return index
        return -1
    }

    private fun findFreeSlot(): Int {
        for (index in pointerIds.indices) if (pointerIds[index] == NONE) return index
        return -1
    }

    private fun release(slot: Int) {
        pointerIds[slot] = NONE
        targets[slot] = null
        canceled[slot] = false
        tapSuppressed[slot] = false
        exitedGestureBounds[slot] = false
        latchedGestures[slot] = NO_GESTURE
        pointerCount--
    }

    private fun grow() {
        val oldSize = pointerIds.size
        pointerIds = pointerIds.copyOf(oldSize * 2).also { values ->
            for (index in oldSize until values.size) values[index] = NONE
        }
        targets = targets.copyOf(oldSize * 2)
        downXs = downXs.copyOf(oldSize * 2)
        downYs = downYs.copyOf(oldSize * 2)
        canceled = canceled.copyOf(oldSize * 2)
        tapSuppressed = tapSuppressed.copyOf(oldSize * 2)
        exitedGestureBounds = exitedGestureBounds.copyOf(oldSize * 2)
        latchedGestures = latchedGestures.copyOf(oldSize * 2)
    }

    private fun updateGestureTrace(
        slot: Int,
        x: Float,
        y: Float,
        insideGestureBounds: Boolean,
        policy: GesturePolicy,
    ) {
        if (policy.requirePointerExit && !insideGestureBounds) {
            exitedGestureBounds[slot] = true
        }
        val deltaX = x - downXs[slot]
        val deltaY = y - downYs[slot]
        val threshold = policy.threshold
        if (
            policy.kind != GesturePolicy.Kind.VERTICAL_SCROLL &&
            policy.kind != GesturePolicy.Kind.HORIZONTAL_SCROLL
        ) {
            return
        }
        if (deltaX * deltaX + deltaY * deltaY >= threshold * threshold) {
            tapSuppressed[slot] = true
        }
        if (latchedGestures[slot] != NO_GESTURE) return
        when (policy.kind) {
            GesturePolicy.Kind.VERTICAL_SCROLL -> if (
                abs(deltaY) >= threshold &&
                abs(deltaY) > abs(deltaX) * policy.verticalDominanceRatio
            ) {
                latchedGestures[slot] =
                    if (deltaY < 0f) SWIPE_UP_GESTURE else SWIPE_DOWN_GESTURE
            }

            GesturePolicy.Kind.HORIZONTAL_SCROLL -> if (
                abs(deltaX) >= threshold &&
                abs(deltaX) > abs(deltaY) * policy.verticalDominanceRatio
            ) {
                latchedGestures[slot] =
                    if (deltaX < 0f) SWIPE_UP_GESTURE else SWIPE_DOWN_GESTURE
            }

            else -> Unit
        }
    }

}

/**
 * Small allocation-free state holder for one-dimensional keyboard-panel scrolling.
 *
 * The state deliberately knows nothing about Android touch events. The View can
 * feed it either-axis deltas while JVM tests verify clamping and partial-item movement.
 */
class ContinuousVerticalScrollState {
    var offset: Float = 0f
        private set

    var maximumOffset: Float = 0f
        private set

    fun configure(contentExtent: Float, viewportExtent: Float) {
        require(contentExtent >= 0f)
        require(viewportExtent >= 0f)
        this.viewportExtent = viewportExtent
        maximumOffset = (contentExtent - viewportExtent).coerceAtLeast(0f)
        offset = offset.coerceIn(0f, maximumOffset)
    }

    /** Positive deltas move toward later content; negative deltas move back. */
    fun scrollBy(delta: Float): Boolean {
        if (delta == 0f) return false
        return scrollTo(offset + delta)
    }

    /** Moves to an absolute content offset, clamped to the current viewport. */
    fun scrollTo(value: Float): Boolean {
        require(value.isFinite())
        val next = value.coerceIn(0f, maximumOffset)
        if (next == offset) return false
        offset = next
        return true
    }

    fun reset(): Boolean {
        if (offset == 0f) return false
        offset = 0f
        return true
    }

    /** Reveals one item with the smallest possible offset change. */
    fun ensureVisible(itemStart: Float, itemEnd: Float): Boolean {
        require(itemStart.isFinite() && itemEnd.isFinite())
        require(itemEnd >= itemStart)
        return when {
            itemStart < offset -> scrollTo(itemStart)
            itemEnd > offset + viewportExtent -> scrollTo(itemEnd - viewportExtent)
            else -> false
        }
    }

    private var viewportExtent: Float = 0f
}

/** Fixed-size-fast FIFO that grows only during an exceptional input burst. */
class KeyEventQueue(initialCapacity: Int = 32) {
    private var values = IntArray(initialCapacity.coerceAtLeast(2))
    private var head = 0
    private var size = 0

    fun offer(code: Int) {
        if (size == values.size) grow()
        values[(head + size) % values.size] = code
        size++
    }

    fun poll(): Int? {
        if (size == 0) return null
        val value = values[head]
        head = (head + 1) % values.size
        size--
        return value
    }

    fun drain(consumer: (Int) -> Unit) {
        while (true) consumer(poll() ?: return)
    }

    val pendingCount: Int
        get() = size

    fun clear() {
        head = 0
        size = 0
    }

    private fun grow() {
        val next = IntArray(values.size * 2)
        repeat(size) { index -> next[index] = values[(head + index) % values.size] }
        values = next
        head = 0
    }
}

object BackspaceRepeatPolicy {
    const val INITIAL_DELAY_MS = 330L

    fun intervalMillis(heldMillis: Long): Long = when {
        heldMillis < 900L -> 92L
        heldMillis < 1_800L -> 58L
        heldMillis < 3_000L -> 40L
        else -> 28L
    }
}

/** Pointer ownership for a single accelerating backspace repeat stream. */
class BackspaceRepeatSession {
    private var pointerId = NONE
    private var startedAtMillis = 0L

    fun tryStart(pointerId: Int, nowMillis: Long): Boolean {
        if (this.pointerId != NONE) return false
        this.pointerId = pointerId
        startedAtMillis = nowMillis
        return true
    }

    fun owns(pointerId: Int): Boolean = this.pointerId == pointerId

    fun stop(pointerId: Int): Boolean {
        if (!owns(pointerId)) return false
        clear()
        return true
    }

    fun clear() {
        pointerId = NONE
        startedAtMillis = 0L
    }

    fun activePointerId(): Int? = pointerId.takeUnless { it == NONE }

    fun heldMillis(nowMillis: Long): Long = if (pointerId == NONE) 0L else (nowMillis - startedAtMillis).coerceAtLeast(0L)

    private companion object {
        const val NONE = -1
    }
}
