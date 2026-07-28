package io.github.ethanbird.senseime.brain.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainAdmissionSerialLaneTest {
    @Test
    fun binderCallerReturnsBeforeAdmissionAndCandidatesStayOrdered() {
        val executor = Executors.newSingleThreadExecutor()
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val admitted = mutableListOf<Int>()
        val lane = BrainAdmissionSerialLane<Int>(executor) { value ->
            if (value == 1) check(releaseFirst.await(2, TimeUnit.SECONDS))
            synchronized(admitted) { admitted += value }
            completed.countDown()
        }

        try {
            assertTrue(lane.submit(1))
            assertTrue(lane.submit(2))
            assertTrue(synchronized(admitted) { admitted.isEmpty() })
            releaseFirst.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), synchronized(admitted) { admitted.toList() })
        } finally {
            releaseFirst.countDown()
            lane.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeRejectsNewBinderAdmissionLocally() {
        val lane = BrainAdmissionSerialLane<Int>(ExecutorDirect) {
            error("closed lane must not admit")
        }
        lane.close()

        assertFalse(lane.submit(1))
    }

    @Test
    fun destroyDuringBlockingAdmissionCancelsBeforeEngineAndCleanupRunsLast() {
        val executor = Executors.newSingleThreadExecutor()
        val enteredConfigRead = CountDownLatch(1)
        val releaseConfigRead = CountDownLatch(1)
        val cleanupComplete = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val engineStarted = AtomicBoolean(false)
        val order = mutableListOf<String>()
        val lane = BrainAdmissionSerialLane<Int>(executor) {
            synchronized(order) { order += "begin" }
            enteredConfigRead.countDown()
            check(releaseConfigRead.await(2, TimeUnit.SECONDS))
            if (cancelled.get()) {
                synchronized(order) { order += "cancelled" }
            } else {
                engineStarted.set(true)
                synchronized(order) { order += "engine" }
            }
        }

        try {
            assertTrue(lane.submit(1))
            assertTrue(enteredConfigRead.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            assertTrue(
                lane.closeAfterDraining {
                    synchronized(order) { order += "cleanup" }
                    cleanupComplete.countDown()
                },
            )
            assertFalse(lane.submit(2))
            releaseConfigRead.countDown()

            assertTrue(cleanupComplete.await(2, TimeUnit.SECONDS))
            assertFalse(engineStarted.get())
            assertEquals(
                listOf("begin", "cancelled", "cleanup"),
                synchronized(order) { order.toList() },
            )
        } finally {
            releaseConfigRead.countDown()
            lane.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun replacementServiceInitializationWaitsForPreviousInstanceCleanupOnSharedLane() {
        val processExecutor = Executors.newSingleThreadExecutor()
        val oldAdmissionEntered = CountDownLatch(1)
        val releaseOldAdmission = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)
        val order = mutableListOf<String>()
        val oldLane = BrainAdmissionSerialLane<Int>(processExecutor) {
            synchronized(order) { order += "old-admission" }
            oldAdmissionEntered.countDown()
            check(releaseOldAdmission.await(2, TimeUnit.SECONDS))
        }

        try {
            assertTrue(oldLane.submit(1))
            assertTrue(oldAdmissionEntered.await(2, TimeUnit.SECONDS))
            assertTrue(
                oldLane.closeAfterDraining {
                    synchronized(order) { order += "old-cleanup" }
                },
            )

            /*
             * A replacement Service enqueues resource initialization before exposing its lane.
             * Both instances use the process executor, so acquiring the next journal writer can
             * never overtake the previous instance's queued close.
             */
            processExecutor.execute {
                synchronized(order) { order += "new-initialize" }
            }
            val replacementLane = BrainAdmissionSerialLane<Int>(processExecutor) {
                synchronized(order) { order += "new-admission" }
                replacementFinished.countDown()
            }
            assertTrue(replacementLane.submit(2))

            releaseOldAdmission.countDown()
            assertTrue(replacementFinished.await(2, TimeUnit.SECONDS))
            assertEquals(
                listOf(
                    "old-admission",
                    "old-cleanup",
                    "new-initialize",
                    "new-admission",
                ),
                synchronized(order) { order.toList() },
            )
            replacementLane.close()
        } finally {
            releaseOldAdmission.countDown()
            oldLane.close()
            processExecutor.shutdownNow()
        }
    }

    private object ExecutorDirect : java.util.concurrent.Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
