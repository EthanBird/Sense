package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainRunTickerSlotTest {
    @Test
    fun oldRunCleanupCannotClearNewRunCallback() {
        val oldRun = BrainRunTickerSlot()
        val newRun = BrainRunTickerSlot()
        val oldCallback = Runnable {}
        val newCallback = Runnable {}

        assertTrue(oldRun.install(oldCallback))
        assertTrue(newRun.install(newCallback))

        assertSame(oldCallback, oldRun.clear())
        assertTrue(newRun.owns(newCallback))
        assertSame(newCallback, newRun.clearIfOwned(newCallback))
    }

    @Test
    fun staleCallbackCannotClearReplacementOrInstallTwice() {
        val slot = BrainRunTickerSlot()
        val current = Runnable {}
        val stale = Runnable {}

        assertTrue(slot.install(current))
        assertFalse(slot.install(stale))
        assertNull(slot.clearIfOwned(stale))
        assertTrue(slot.owns(current))
        assertSame(current, slot.clearIfOwned(current))
    }
}
