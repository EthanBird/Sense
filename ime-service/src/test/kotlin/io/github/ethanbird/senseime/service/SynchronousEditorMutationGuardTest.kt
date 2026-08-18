package io.github.ethanbird.senseime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynchronousEditorMutationGuardTest {
    @Test
    fun synchronousHostCallbackSeesFenceAndFenceAlwaysUnwinds() {
        val guard = SynchronousEditorMutationGuard()
        var callbackSawFence = false

        guard.duringMutation {
            callbackSawFence = guard.isActive
            guard.duringMutation { assertTrue(guard.isActive) }
        }

        assertTrue(callbackSawFence)
        assertFalse(guard.isActive)
    }

    @Test
    fun exceptionAlsoUnwindsFence() {
        val guard = SynchronousEditorMutationGuard()
        runCatching { guard.duringMutation { error("host failure") } }
        assertFalse(guard.isActive)
    }
}
