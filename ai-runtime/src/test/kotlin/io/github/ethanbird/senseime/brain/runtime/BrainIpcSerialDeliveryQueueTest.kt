package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class BrainIpcSerialDeliveryQueueTest {
    @Test
    fun frameFlushAlwaysPrecedesLaterTerminalDelivery() {
        val queue = BrainIpcSerialDeliveryQueue<List<String>>()

        assertTrue(queue.enqueue(listOf("preview")))
        assertFalse(queue.enqueue(listOf("final")))

        assertEquals(listOf("preview"), queue.poll())
        assertEquals(listOf("final"), queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun producerRacingLastDeliveryReusesTheActiveDrain() {
        val queue = BrainIpcSerialDeliveryQueue<String>()

        assertTrue(queue.enqueue("first"))
        assertEquals("first", queue.poll())
        // The consumer has not observed the queue empty yet, so this producer
        // must not schedule a second main-thread drain.
        assertFalse(queue.enqueue("raced"))
        assertEquals("raced", queue.poll())
        assertNull(queue.poll())

        assertTrue(queue.enqueue("next-cycle"))
    }

    @Test
    fun failedRecipientCanBeRemovedWithoutDroppingANewerRun() {
        data class Delivery(val generation: Long, val value: String)

        val queue = BrainIpcSerialDeliveryQueue<Delivery>()
        queue.enqueue(Delivery(1L, "old-1"))
        queue.enqueue(Delivery(2L, "new"))
        queue.enqueue(Delivery(1L, "old-2"))

        queue.removeAll { it.generation == 1L }

        assertEquals(Delivery(2L, "new"), queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun timerThreadDrainCannotBeOvertakenByTerminalThread() {
        val queue = BrainIpcSerialDeliveryQueue<String>()
        val previewQueued = CountDownLatch(1)
        val schedulingRequests = AtomicInteger(0)

        val timerThread = Thread {
            if (queue.enqueue("preview")) schedulingRequests.incrementAndGet()
            previewQueued.countDown()
        }
        val terminalThread = Thread {
            previewQueued.await()
            if (queue.enqueue("terminal")) schedulingRequests.incrementAndGet()
        }

        timerThread.start()
        terminalThread.start()
        timerThread.join()
        terminalThread.join()

        assertEquals(1, schedulingRequests.get())
        assertEquals("preview", queue.poll())
        assertEquals("terminal", queue.poll())
        assertNull(queue.poll())
    }
}
