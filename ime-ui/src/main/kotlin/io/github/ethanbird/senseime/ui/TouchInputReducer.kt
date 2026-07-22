package io.github.ethanbird.senseime.ui

import kotlin.math.abs

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
    }

    data class Activation<T>(
        val target: T,
        val gesture: Gesture,
    )

    private var pointerIds = IntArray(10) { NONE }
    private var targets = arrayOfNulls<Any>(10)
    private var downXs = FloatArray(10)
    private var downYs = FloatArray(10)
    private var canceled = BooleanArray(10)
    private var pointerCount = 0

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
        return slot >= 0 && !canceled[slot]
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
    }

    private companion object {
        const val NONE = -1
    }
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
