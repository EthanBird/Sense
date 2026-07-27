package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.brain.api.BrainRunHandle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainRetentionFailureGateTest {
    @Test
    fun `failure before synchronous start returns prevents later handle admission`() {
        val gate = BrainRetentionFailureGate()

        gate.markFailed()

        assertFalse(gate.install(handle()))
    }

    @Test
    fun `failure after handle admission returns exact handle for immediate cancellation`() {
        val gate = BrainRetentionFailureGate()
        val handle = handle()
        assertTrue(gate.install(handle))

        assertSame(handle, gate.markFailed())
        assertSame(handle, gate.currentHandle())
    }

    private fun handle(): BrainRunHandle = object : BrainRunHandle {
        override val requestId = "request"
        override val runGeneration = 1L
        override val isTerminal = false

        override fun tick() = Unit

        override fun cancel(reason: HarnessCancelReason) = Unit
    }
}
