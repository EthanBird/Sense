package io.github.ethanbird.senseime.mic

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseMicGenerationStateTest {
    @Test
    fun restartRejectsLatePublicationFromPreviousRuntime() {
        val state = SenseMicGenerationState("off")
        val first = state.begin("first-starting")
        assertTrue(state.publish(first, "first-waiting"))

        val second = state.begin("second-starting")

        assertFalse(state.publish(first, "stale-waiting"))
        assertTrue(state.publish(second, "second-waiting"))
        assertEquals("second-waiting", state.snapshot().value)
    }

    @Test
    fun finishMakesQueuedWorkerPublicationInert() {
        val state = SenseMicGenerationState("off")
        val generation = state.begin("starting")
        assertTrue(state.finish(generation, "error"))

        assertFalse(state.publish(generation, "resurrected-waiting"))
        assertFalse(state.snapshot().active)
        assertEquals("error", state.snapshot().value)
    }

    @Test
    fun concurrentRestartAlwaysWinsOverReleasedOldWorker() {
        val state = SenseMicGenerationState("off")
        val first = state.begin("first")
        val workerReady = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            workerReady.countDown()
            assertTrue(releaseWorker.await(2, TimeUnit.SECONDS))
            state.publish(first, "late-first")
        }

        assertTrue(workerReady.await(2, TimeUnit.SECONDS))
        val second = state.begin("second")
        releaseWorker.countDown()
        future.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue(state.isCurrent(second))
        assertEquals("second", state.snapshot().value)
    }

    @Test
    fun networkCallbackBurstKeepsOnlyNewestTicketForCurrentGeneration() {
        val gate = SenseMicRebindGate()
        val available = gate.request(7)
        val capabilities = gate.request(7)
        val linkProperties = gate.request(7)

        assertFalse(gate.isLatest(available, 7))
        assertFalse(gate.isLatest(capabilities, 7))
        assertTrue(gate.isLatest(linkProperties, 7))
        assertFalse(gate.isLatest(linkProperties, 8))

        gate.cancel()
        assertFalse(gate.isLatest(linkProperties, 7))
    }

    @Test
    fun recoveryTicketIsCurrentOnlyWhileRuntimeRemainsInactive() {
        val gate = SenseMicRebindGate()
        val recovery = gate.request(null)

        assertTrue(gate.isLatest(recovery, null))
        assertFalse(gate.isLatest(recovery, 8))

        val active = gate.request(8)
        assertFalse(gate.isLatest(recovery, null))
        assertTrue(gate.isLatest(active, 8))

        val nextRecovery = gate.request(null)
        gate.cancel()
        assertFalse(gate.isLatest(nextRecovery, null))
    }

    @Test
    fun completeRuntimeTransitionsAreSerialized() {
        val gate = SenseMicRuntimeTransitionGate()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)

        val first = executor.submit {
            gate.run {
                firstEntered.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        val second = executor.submit {
            secondAttempting.countDown()
            gate.run { secondEntered.set(true) }
        }

        assertTrue(secondAttempting.await(2, TimeUnit.SECONDS))
        assertFalse(secondEntered.get())
        releaseFirst.countDown()
        first.get(2, TimeUnit.SECONDS)
        second.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue(secondEntered.get())
    }
}
