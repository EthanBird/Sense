package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInputReducerTest {
    @Test
    fun pointersTwoMillisecondsApartAreCommittedIndependentlyInOrder() {
        val reducer = TouchInputReducer<String>(swipeThreshold = 24f, maximumHorizontalDrift = 30f)
        val queue = KeyEventQueue(initialCapacity = 2)

        reducer.onDown(pointerId = 7, target = "q", x = 10f, y = 50f)
        reducer.onDown(pointerId = 9, target = "w", x = 50f, y = 50f)
        queue.offer(reducer.onUp(7, 10f, 50f, insideFrozenTarget = true)!!.target.single().code)
        queue.offer(reducer.onUp(9, 50f, 50f, insideFrozenTarget = true)!!.target.single().code)

        val delivered = mutableListOf<Int>()
        queue.drain(delivered::add)
        assertEquals(listOf('q'.code, 'w'.code), delivered)
        assertEquals(0, reducer.activePointerCount)
    }

    @Test
    fun burstQueueGrowsWithoutDroppingOrReorderingKeys() {
        val queue = KeyEventQueue(initialCapacity = 2)
        val expected = List(64) { 'a'.code + it % 26 }
        expected.forEach(queue::offer)

        val actual = mutableListOf<Int>()
        queue.drain(actual::add)

        assertEquals(expected, actual)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun detachStyleClearDropsUndeliveredEvents() {
        val queue = KeyEventQueue()
        queue.offer('q'.code)
        queue.offer('w'.code)

        queue.clear()

        assertEquals(0, queue.pendingCount)
        assertNull(queue.poll())
    }

    @Test
    fun leavingFrozenKeyCancelsInsteadOfRetargeting() {
        val reducer = TouchInputReducer<String>(24f, 30f)
        reducer.onDown(1, "q", 10f, 50f)

        assertTrue(reducer.onMove(1, insideFrozenTarget = false))
        assertFalse(reducer.onMove(1, insideFrozenTarget = true))
        assertNull(reducer.onUp(1, 50f, 50f, insideFrozenTarget = true))
    }

    @Test
    fun upwardAndDownwardGesturesAreDistinguished() {
        val reducer = TouchInputReducer<String>(24f, 30f)
        reducer.onDown(1, "q", 50f, 60f)
        reducer.onDown(2, "emoji", 80f, 60f)

        assertEquals(TouchInputReducer.Gesture.SWIPE_UP, reducer.onUp(1, 52f, 30f, true)?.gesture)
        assertEquals(TouchInputReducer.Gesture.SWIPE_DOWN, reducer.onUp(2, 78f, 90f, true)?.gesture)
    }

    @Test
    fun cancelRemovesOnlyRequestedPointerAndCancelAllClearsRest() {
        val reducer = TouchInputReducer<String>(24f, 30f)
        reducer.onDown(1, "q", 0f, 0f)
        reducer.onDown(2, "w", 0f, 0f)

        assertEquals("q", reducer.cancel(1))
        assertEquals(1, reducer.activePointerCount)
        reducer.cancelAll()
        assertEquals(0, reducer.activePointerCount)
    }

    @Test
    fun backspaceRepeatAcceleratesMonotonically() {
        val intervals = listOf(330L, 1_000L, 2_000L, 4_000L).map(BackspaceRepeatPolicy::intervalMillis)

        assertEquals(listOf(92L, 58L, 40L, 28L), intervals)
        assertTrue(intervals.zipWithNext().all { (before, after) -> after < before })
        assertEquals(330L, BackspaceRepeatPolicy.INITIAL_DELAY_MS)
    }

    @Test
    fun backspaceRepeatStopsOnOwningPointerUpMoveOutOrCancel() {
        val session = BackspaceRepeatSession()
        assertTrue(session.tryStart(pointerId = 4, nowMillis = 100L))
        assertFalse(session.tryStart(pointerId = 8, nowMillis = 102L))
        assertEquals(4, session.activePointerId())
        assertEquals(500L, session.heldMillis(600L))

        assertTrue(session.stop(4)) // View calls this for UP or MOVE-out.
        assertNull(session.activePointerId())
        assertTrue(session.tryStart(pointerId = 8, nowMillis = 700L))
        session.clear() // ACTION_CANCEL.
        assertNull(session.activePointerId())
    }
}
