package io.github.ethanbird.senseime.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.OverScroller

internal interface PanelScrollHost {
    fun scrollStateFor(panel: ScrollPanel): ContinuousVerticalScrollState
    fun invalidateScrollPanel(panel: ScrollPanel)
}

/**
 * Owns pointer arbitration and kinetic motion for one-dimensional keyboard panels.
 * Gesture classification and axis selection remain explicit; this class is
 * concerned only with scroll ownership, velocity and pixels.
 */
internal class PanelScrollController(
    context: Context,
    private val minimumFlingVelocity: Float,
    private val maximumFlingVelocity: Float,
    private val host: PanelScrollHost,
) {
    private val pointerCoordinates = PointerFloatMap(initialCapacity = 4)
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var flingingPanel: ScrollPanel? = null

    var activePointerId: Int = NO_POINTER
        private set

    var activePanel: ScrollPanel? = null
        private set

    var isLatched: Boolean = false
        private set

    fun addMovement(event: MotionEvent) {
        if (activePointerId != NO_POINTER) velocityTracker?.addMovement(event)
    }

    fun rememberPointerCoordinate(pointerId: Int, coordinate: Float) {
        pointerCoordinates.put(pointerId, coordinate)
    }

    fun previousPointerCoordinate(pointerId: Int, fallback: Float): Float =
        pointerCoordinates.get(pointerId, fallback)

    fun forgetPointer(pointerId: Int) {
        pointerCoordinates.remove(pointerId)
    }

    fun start(
        pointerId: Int,
        panel: ScrollPanel,
        coordinate: Float,
        event: MotionEvent,
    ) {
        pointerCoordinates.put(pointerId, coordinate)
        if (activePointerId != NO_POINTER) return
        stopFling()
        activePointerId = pointerId
        activePanel = panel
        isLatched = false
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    fun acquireForLatchedPointer(
        pointerId: Int,
        panel: ScrollPanel,
        coordinate: Float,
        event: MotionEvent,
    ) {
        if (pointerId == activePointerId && panel == activePanel) return
        if (activePointerId != NO_POINTER && isLatched) return

        velocityTracker?.recycle()
        activePointerId = pointerId
        activePanel = panel
        isLatched = false
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        if (!pointerCoordinates.contains(pointerId)) {
            pointerCoordinates.put(pointerId, coordinate)
        }
    }

    fun latch(pointerId: Int, panel: ScrollPanel): Boolean {
        if (pointerId != activePointerId || panel != activePanel) return false
        val changed = !isLatched
        isLatched = true
        return changed
    }

    fun finish(
        pointerId: Int,
        panel: ScrollPanel?,
        shouldFling: Boolean,
    ) {
        if (pointerId != activePointerId) return
        val currentPanel = activePanel
        val tracker = velocityTracker
        if (shouldFling && panel != null && panel == currentPanel && tracker != null) {
            tracker.computeCurrentVelocity(1_000, maximumFlingVelocity)
            val fingerVelocity = if (panel.axis == ScrollAxis.HORIZONTAL) {
                tracker.getXVelocity(pointerId)
            } else {
                tracker.getYVelocity(pointerId)
            }
            val velocity = KineticScrollPolicy.contentVelocity(
                fingerVelocity = fingerVelocity,
                minimumFlingVelocity = minimumFlingVelocity,
                maximumFlingVelocity = maximumFlingVelocity,
            )
            if (velocity != 0) startFling(panel, velocity)
        }
        clearPointer()
    }

    fun computeScroll(): Boolean {
        val panel = flingingPanel ?: return false
        return if (scroller.computeScrollOffset()) {
            val coordinate = if (panel.axis == ScrollAxis.HORIZONTAL) {
                scroller.currX
            } else {
                scroller.currY
            }
            host.scrollStateFor(panel).scrollTo(coordinate.toFloat())
            host.invalidateScrollPanel(panel)
            true
        } else {
            flingingPanel = null
            false
        }
    }

    fun stopFling() {
        if (!scroller.isFinished) scroller.forceFinished(true)
        flingingPanel = null
    }

    /** Cancels only one panel's pointer/fling ownership during a scene revision. */
    fun cancel(panel: ScrollPanel) {
        if (activePanel == panel) {
            pointerCoordinates.remove(activePointerId)
            clearPointer()
        }
        if (flingingPanel == panel) {
            if (!scroller.isFinished) scroller.forceFinished(true)
            flingingPanel = null
        }
    }

    fun clear() {
        pointerCoordinates.clear()
        clearPointer()
        stopFling()
    }

    private fun startFling(panel: ScrollPanel, velocity: Int) {
        val state = host.scrollStateFor(panel)
        if (state.maximumOffset <= 0f) return
        scroller.forceFinished(true)
        flingingPanel = panel
        if (panel.axis == ScrollAxis.HORIZONTAL) {
            scroller.fling(
                state.offset.toInt(),
                0,
                velocity,
                0,
                0,
                state.maximumOffset.toInt(),
                0,
                0,
            )
        } else {
            scroller.fling(
                0,
                state.offset.toInt(),
                0,
                velocity,
                0,
                0,
                0,
                state.maximumOffset.toInt(),
            )
        }
        host.invalidateScrollPanel(panel)
    }

    private fun clearPointer() {
        velocityTracker?.recycle()
        velocityTracker = null
        activePointerId = NO_POINTER
        activePanel = null
        isLatched = false
    }

    private companion object {
        const val NO_POINTER = -1
    }
}

/** Tiny primitive map sized for Android's simultaneous pointer limit. */
internal class PointerFloatMap(initialCapacity: Int) {
    private var ids = IntArray(initialCapacity.coerceAtLeast(2)) { EMPTY }
    private var values = FloatArray(ids.size)

    fun put(pointerId: Int, value: Float) {
        var free = -1
        for (index in ids.indices) {
            when (ids[index]) {
                pointerId -> {
                    values[index] = value
                    return
                }

                EMPTY -> if (free < 0) free = index
            }
        }
        if (free < 0) {
            val oldSize = ids.size
            ids = ids.copyOf(oldSize * 2).also { grown ->
                for (index in oldSize until grown.size) grown[index] = EMPTY
            }
            values = values.copyOf(ids.size)
            free = oldSize
        }
        ids[free] = pointerId
        values[free] = value
    }

    fun get(pointerId: Int, fallback: Float): Float {
        for (index in ids.indices) if (ids[index] == pointerId) return values[index]
        return fallback
    }

    fun contains(pointerId: Int): Boolean {
        for (id in ids) if (id == pointerId) return true
        return false
    }

    fun remove(pointerId: Int) {
        for (index in ids.indices) {
            if (ids[index] == pointerId) {
                ids[index] = EMPTY
                values[index] = 0f
                return
            }
        }
    }

    fun clear() {
        ids.fill(EMPTY)
        values.fill(0f)
    }

    private companion object {
        const val EMPTY = -1
    }
}
