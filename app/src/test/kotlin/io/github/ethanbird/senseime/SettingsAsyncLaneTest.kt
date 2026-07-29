package io.github.ethanbird.senseime

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAsyncLaneTest {
    @Test
    fun refreshIsLatestWinsWhileIndependentChannelStillDelivers() {
        val worker = Executors.newSingleThreadExecutor()
        val ui = QueueExecutor()
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val delivered = mutableListOf<String>()
        worker.execute {
            workerBlocked.countDown()
            check(releaseWorker.await(2, TimeUnit.SECONDS))
        }
        assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))
        val lane = SettingsAsyncLane("test-settings", ui, worker)

        try {
            lane.refresh("profile", { completed.countDown(); "old" }) {
                delivered += it.getOrThrow()
            }
            lane.refresh("profile", { completed.countDown(); "new" }) {
                delivered += it.getOrThrow()
            }
            lane.refresh("permission", { completed.countDown(); "permission" }) {
                delivered += it.getOrThrow()
            }

            releaseWorker.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            ui.runAll()

            assertEquals(listOf("new", "permission"), delivered)
        } finally {
            releaseWorker.countDown()
            lane.close()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun closeLetsAcceptedMutationFinishButRevokesItsCallback() {
        val worker = Executors.newSingleThreadExecutor()
        val ui = QueueExecutor()
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        var callbacks = 0
        worker.execute {
            workerBlocked.countDown()
            check(releaseWorker.await(2, TimeUnit.SECONDS))
        }
        assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))
        val lane = SettingsAsyncLane("test-settings-close", ui, worker)

        assertTrue(
            lane.execute(
                operation = {
                    writeCompleted.countDown()
                    Unit
                },
                deliver = { callbacks += 1 },
            ),
        )
        lane.close()
        assertFalse(lane.execute(operation = { Unit }))
        releaseWorker.countDown()

        assertTrue(writeCompleted.await(2, TimeUnit.SECONDS))
        assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        ui.runAll()
        assertEquals(0, callbacks)
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
