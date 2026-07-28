package io.github.ethanbird.senseime

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSettingsIoSessionTest {
    @Test
    fun refreshDeliversOnlyLatestRequestInSameChannel() {
        val worker = QueueExecutor()
        val ui = QueueExecutor()
        val delivered = mutableListOf<String>()
        val session = SkillSettingsIoSession(worker, ui)

        assertTrue(session.refresh("catalog", { "old" }) { delivered += it.getOrThrow() })
        assertTrue(session.refresh("catalog", { "new" }) { delivered += it.getOrThrow() })

        worker.runAll()
        ui.runAll()

        assertEquals(listOf("new"), delivered)
    }

    @Test
    fun independentRefreshChannelsDoNotSuppressEachOther() {
        val worker = QueueExecutor()
        val ui = QueueExecutor()
        val delivered = mutableListOf<String>()
        val session = SkillSettingsIoSession(worker, ui)

        session.refresh("catalog", { "catalog" }) { delivered += it.getOrThrow() }
        session.refresh("history", { "history" }) { delivered += it.getOrThrow() }

        worker.runAll()
        ui.runAll()

        assertEquals(listOf("catalog", "history"), delivered)
    }

    @Test
    fun ordinaryOperationsAreNeverCoalescedAndRemainSerial() {
        val worker = QueueExecutor()
        val ui = QueueExecutor()
        val executed = mutableListOf<Int>()
        val delivered = mutableListOf<Int>()
        val session = SkillSettingsIoSession(worker, ui)

        session.execute(
            operation = { executed += 1; 1 },
            deliver = { delivered += it.getOrThrow() },
        )
        session.execute(
            operation = { executed += 2; 2 },
            deliver = { delivered += it.getOrThrow() },
        )

        worker.runAll()
        ui.runAll()

        assertEquals(listOf(1, 2), executed)
        assertEquals(listOf(1, 2), delivered)
    }

    @Test
    fun closeLetsPromisedWriteFinishButDropsLateCallbackAndRejectsNewWork() {
        val worker = QueueExecutor()
        val ui = QueueExecutor()
        var writes = 0
        var callbacks = 0
        val session = SkillSettingsIoSession(worker, ui)

        assertTrue(
            session.execute(
                operation = { writes += 1 },
                deliver = { callbacks += 1 },
            ),
        )
        session.close()

        worker.runAll()
        ui.runAll()

        assertEquals(1, writes)
        assertEquals(0, callbacks)
        assertFalse(session.execute(operation = { writes += 1 }))
        worker.runAll()
        assertEquals(1, writes)
    }

    @Test
    fun lifecycleBarrierRunsAfterPreviouslyAcceptedWrites() {
        val worker = Executors.newSingleThreadExecutor()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val order = mutableListOf<String>()
        worker.execute {
            synchronized(order) { order += "previous" }
            firstEntered.countDown()
            check(releaseFirst.await(2, TimeUnit.SECONDS))
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        val barrier =
            SkillSettingsDurabilityBarrier<String, String>(worker, timeoutMillis = 2_000L)
        val releaseThread = Thread {
            releaseFirst.countDown()
        }

        try {
            releaseThread.start()
            val result = barrier.execute("current") {
                synchronized(order) { order += "lifecycle" }
                "durable"
            }

            assertEquals("durable", result.getOrThrow())
            assertEquals(
                listOf("previous", "lifecycle"),
                synchronized(order) { order.toList() },
            )
        } finally {
            releaseFirst.countDown()
            releaseThread.join()
            worker.shutdownNow()
        }
    }

    @Test
    fun terminalSnapshotWinsWhenAnOlderQueuedWriteWouldOtherwiseOverwriteARevert() {
        val worker = Executors.newSingleThreadExecutor()
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val olderWriteCompleted = CountDownLatch(1)
        var durable = "A"
        worker.execute {
            blockerEntered.countDown()
            check(releaseBlocker.await(2, TimeUnit.SECONDS))
        }
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
        worker.execute {
            durable = "B"
            olderWriteCompleted.countDown()
        }
        val barrier =
            SkillSettingsDurabilityBarrier<String, Unit>(worker, timeoutMillis = 2_000L)
        val releaseThread = Thread {
            releaseBlocker.countDown()
        }

        try {
            releaseThread.start()
            barrier.execute("A") {
                durable = "A"
            }.getOrThrow()

            assertTrue(olderWriteCompleted.await(2, TimeUnit.SECONDS))
            assertEquals("A", durable)
        } finally {
            releaseBlocker.countDown()
            releaseThread.join()
            worker.shutdownNow()
        }
    }

    @Test
    fun lifecycleBarrierTimeoutDoesNotCancelPromisedDurabilityWork() {
        val worker = Executors.newSingleThreadExecutor()
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val durabilityCompleted = CountDownLatch(1)
        var durabilityRuns = 0
        worker.execute {
            blockerEntered.countDown()
            check(releaseBlocker.await(2, TimeUnit.SECONDS))
        }
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
        val barrier =
            SkillSettingsDurabilityBarrier<String, Unit>(worker, timeoutMillis = 25L)

        try {
            assertTrue(
                barrier.execute("same-snapshot") {
                    durabilityRuns += 1
                    durabilityCompleted.countDown()
                }.isFailure,
            )
            assertFalse(durabilityCompleted.await(25L, TimeUnit.MILLISECONDS))
            releaseBlocker.countDown()
            assertTrue(durabilityCompleted.await(2, TimeUnit.SECONDS))
            assertTrue(
                barrier.execute("same-snapshot") {
                    durabilityRuns += 1
                    durabilityCompleted.countDown()
                }.isSuccess,
            )
            assertEquals(1, durabilityRuns)
        } finally {
            releaseBlocker.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun repeatedJoinOfSameFutureSharesOneTotalMainThreadWaitBudget() {
        val worker = Executors.newSingleThreadExecutor()
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val durabilityCompleted = CountDownLatch(1)
        worker.execute {
            blockerEntered.countDown()
            check(releaseBlocker.await(2, TimeUnit.SECONDS))
        }
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
        val timeoutMillis = 200L
        val barrier =
            SkillSettingsDurabilityBarrier<String, Unit>(worker, timeoutMillis)
        var durabilityRuns = 0
        val startedAt = System.nanoTime()

        try {
            assertTrue(
                barrier.execute("same-snapshot") {
                    durabilityRuns += 1
                    durabilityCompleted.countDown()
                }.isFailure,
            )
            val secondStartedAt = System.nanoTime()
            assertTrue(
                barrier.execute("same-snapshot") {
                    durabilityRuns += 1
                    durabilityCompleted.countDown()
                }.isFailure,
            )
            val secondElapsedMs =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - secondStartedAt)
            val totalElapsedMs =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue("second join repeated the full timeout: ${secondElapsedMs}ms", secondElapsedMs < 75L)
            assertTrue("shared wait exceeded one budget: ${totalElapsedMs}ms", totalElapsedMs < 300L)
            releaseBlocker.countDown()
            assertTrue(durabilityCompleted.await(2, TimeUnit.SECONDS))
            assertEquals(1, durabilityRuns)
        } finally {
            releaseBlocker.countDown()
            worker.shutdownNow()
        }
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }
}
