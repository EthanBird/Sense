package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidatePointerActivityTest {
    @Test fun onlyFirstDownAndLastUpChangeActivity() {
        val events = mutableListOf<Boolean>()
        val activity = CandidatePointerActivity(events::add)
        activity.down(7)
        activity.down(7)
        activity.down(19)
        activity.up(7)
        activity.up(8)
        assertEquals(listOf(true), events)
        activity.up(19)
        assertEquals(listOf(true, false), events)
    }

    @Test fun cancellationEndsOwnershipOnceAndStaleReleasesDoNotAffectNewPointers() {
        val events = mutableListOf<Boolean>()
        val activity = CandidatePointerActivity(events::add)
        activity.down(7)
        activity.cancel()
        activity.cancel()
        activity.down(9)
        activity.up(7)
        assertEquals(listOf(true, false, true), events)
        activity.up(9)
        assertEquals(listOf(true, false, true, false), events)
    }
}
