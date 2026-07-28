package io.github.ethanbird.senseime.service

import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillProjectionCoordinatorTest {
    @Test
    fun watcherIsRegisteredBeforeCatchUpReadClosesInitialGap() {
        val background = Executors.newSingleThreadExecutor()
        val delivery = ManualExecutor()
        val currentGeneration = AtomicLong(1L)
        val firstRead = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val readCount = AtomicInteger()
        val generationTwoPublished = CountDownLatch(1)
        val published = mutableListOf<Long>()
        var watcher: (() -> Unit)? = null
        val coordinator = AgentSkillProjectionCoordinator(
            backgroundExecutor = background,
            deliveryExecutor = delivery,
            load = {
                val snapshot = currentGeneration.get()
                if (readCount.incrementAndGet() == 1) {
                    firstRead.countDown()
                    check(releaseFirstRead.await(2, TimeUnit.SECONDS))
                }
                Result.success(snapshot)
            },
            registerWatcher = { callback, _ ->
                watcher = callback
                true
            },
            unregisterWatcher = {},
            publish = { generation ->
                published += generation
                if (generation == 2L) generationTwoPublished.countDown()
            },
        )

        try {
            coordinator.start()
            assertTrue(firstRead.await(2, TimeUnit.SECONDS))

            // The external commit lands after the initial read but before watcher registration.
            currentGeneration.set(2L)
            releaseFirstRead.countDown()
            delivery.runNext()
            assertTrue(watcher != null)

            // No watch callback can exist for the gap commit; the mandatory catch-up must find it.
            delivery.runNext()
            assertTrue(generationTwoPublished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1L, 2L), published)
            assertEquals(2, readCount.get())
        } finally {
            releaseFirstRead.countDown()
            coordinator.close()
            background.shutdown()
            assertTrue(background.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun toolCommitAfterCancellationIsEventuallyProjected() {
        val background = Executors.newSingleThreadExecutor()
        val delivery = ManualExecutor()
        val currentGeneration = AtomicLong(7L)
        val readCount = AtomicInteger()
        val cancelledRefreshStarted = CountDownLatch(1)
        val releaseCancelledRefresh = CountDownLatch(1)
        val generationEightPublished = CountDownLatch(1)
        val published = mutableListOf<Long>()
        var watcher: (() -> Unit)? = null
        val coordinator = AgentSkillProjectionCoordinator(
            backgroundExecutor = background,
            deliveryExecutor = delivery,
            load = {
                val snapshot = currentGeneration.get()
                if (readCount.incrementAndGet() == 3) {
                    cancelledRefreshStarted.countDown()
                    check(releaseCancelledRefresh.await(2, TimeUnit.SECONDS))
                }
                Result.success(snapshot)
            },
            registerWatcher = { callback, _ ->
                watcher = callback
                true
            },
            unregisterWatcher = {},
            publish = { generation ->
                published += generation
                if (generation == 8L) generationEightPublished.countDown()
            },
        )

        try {
            coordinator.start()
            delivery.runNext() // Initial generation 7; registers watcher and schedules catch-up.
            delivery.runNext() // Catch-up generation 7.

            // Cancellation requests a refresh after the tool has crossed its start linearization.
            coordinator.refresh()
            assertTrue(cancelledRefreshStarted.await(2, TimeUnit.SECONDS))

            // The atomic tool mutation is allowed to finish after cancellation and emits CURRENT.
            currentGeneration.set(8L)
            requireNotNull(watcher).invoke()
            releaseCancelledRefresh.countDown()

            delivery.runNext() // Cancellation refresh captured generation 7.
            delivery.runNext() // Watch-triggered refresh observes generation 8.
            assertTrue(generationEightPublished.await(2, TimeUnit.SECONDS))
            assertEquals(8L, published.last())
        } finally {
            releaseCancelledRefresh.countDown()
            coordinator.close()
            background.shutdown()
            assertTrue(background.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun watcherCanRecoverAnInitiallyUnreadableProjection() {
        val background = Executors.newSingleThreadExecutor()
        val delivery = ManualExecutor()
        val readable = java.util.concurrent.atomic.AtomicBoolean(false)
        val recovered = CountDownLatch(1)
        var watcher: (() -> Unit)? = null
        val coordinator = AgentSkillProjectionCoordinator(
            backgroundExecutor = background,
            deliveryExecutor = delivery,
            load = {
                if (readable.get()) Result.success(12L) else Result.failure(
                    IllegalStateException("catalog is temporarily unreadable"),
                )
            },
            registerWatcher = { callback, _ ->
                watcher = callback
                true
            },
            unregisterWatcher = {},
            publish = { generation ->
                if (generation == 12L) recovered.countDown()
            },
        )

        try {
            coordinator.start()
            delivery.runNext() // Failed initial read still installs the watcher and catch-up.
            delivery.runNext() // Catch-up also fails while the store remains unreadable.
            assertTrue(watcher != null)

            readable.set(true)
            requireNotNull(watcher).invoke()
            delivery.runNext()

            assertTrue(recovered.await(2, TimeUnit.SECONDS))
        } finally {
            coordinator.close()
            background.shutdown()
            assertTrue(background.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun lifecycleRefreshesAreCoalescedAndWatcherRebuildStaysOnBackgroundLane() {
        val background = ManualExecutor()
        val delivery = ManualExecutor()
        val loadCount = AtomicInteger()
        val registerCount = AtomicInteger()
        val unregisterCount = AtomicInteger()
        val coordinator = AgentSkillProjectionCoordinator(
            backgroundExecutor = background,
            deliveryExecutor = delivery,
            load = {
                loadCount.incrementAndGet()
                Result.success(1L)
            },
            registerWatcher = { _, _ ->
                registerCount.incrementAndGet()
                true
            },
            unregisterWatcher = { unregisterCount.incrementAndGet() },
            publish = {},
        )

        try {
            coordinator.start()
            coordinator.refreshAndRebuildWatcher()
            coordinator.refreshAndRebuildWatcher()

            assertEquals(1, background.pendingCount())
            background.runNext()
            delivery.runNext()

            // The coalesced lifecycle request plus mandatory post-registration catch-up is one
            // follow-up background task, not one disk read per callback.
            assertEquals(1, background.pendingCount())
            background.runNext()
            delivery.runNext()
            assertEquals(2, loadCount.get())
            assertEquals(1, unregisterCount.get())
            assertEquals(1, registerCount.get())
            assertEquals(0, background.pendingCount())

            // The paired onWindowShown lifecycle callback requests a read, not another rebuild.
            coordinator.refresh()
            background.runNext()
            delivery.runNext()
            assertEquals(0, background.pendingCount())
            assertEquals(3, loadCount.get())
            assertEquals(1, unregisterCount.get())
            assertEquals(1, registerCount.get())
        } finally {
            coordinator.close()
            background.runNext()
        }
    }

    @Test
    fun invalidatedWatcherIsRebuiltAndCatchUpRecoversLostEvent() {
        val background = ManualExecutor()
        val delivery = ManualExecutor()
        val generation = AtomicLong(3L)
        val published = mutableListOf<Long>()
        val unregisterCount = AtomicInteger()
        var invalidated: (() -> Unit)? = null
        val coordinator = AgentSkillProjectionCoordinator(
            backgroundExecutor = background,
            deliveryExecutor = delivery,
            load = { Result.success(generation.get()) },
            registerWatcher = { _, onInvalidated ->
                invalidated = onInvalidated
                true
            },
            unregisterWatcher = { unregisterCount.incrementAndGet() },
            publish = { published += it },
        )

        try {
            coordinator.start()
            background.runNext()
            delivery.runNext()
            background.runNext() // Initial read→watch catch-up.
            delivery.runNext()

            generation.set(4L)
            requireNotNull(invalidated).invoke()
            background.runNext()
            delivery.runNext()
            background.runNext() // Rebuild catch-up covers a commit with no watch event.
            delivery.runNext()

            assertEquals(1, unregisterCount.get())
            assertEquals(4L, published.last())
        } finally {
            coordinator.close()
            background.runNext()
        }
    }

    @Test
    fun failedMutationIsDeliveredAsVisibleFeedbackInsteadOfBeingSilent() {
        val background = ManualExecutor()
        val delivery = ManualExecutor()
        val failures = mutableListOf<AgentSkillProjectionFailure>()
        val coordinator = AgentSkillProjectionCoordinator(
            backgroundExecutor = background,
            deliveryExecutor = delivery,
            load = { Result.success(1L) },
            registerWatcher = { _, _ -> true },
            unregisterWatcher = {},
            publish = { error("A failed mutation must not publish") },
            reportFailure = { failures += it },
        )

        try {
            coordinator.submit(requestToken = 73L) {
                Result.failure(IllegalStateException("deterministic mutation failure"))
            }
            background.runNext()
            delivery.runNext()

            assertEquals(1, failures.size)
            assertEquals(AgentSkillProjectionOperation.MUTATION, failures.single().operation)
            assertEquals(73L, failures.single().requestToken)
            assertEquals(
                "deterministic mutation failure",
                failures.single().cause.message,
            )
            assertEquals(
                "Skill 切换失败，配置未更改，请重新选择",
                AgentSkillProjectionFailureText.message(failures.single().operation),
            )
        } finally {
            coordinator.close()
            background.runNext()
        }
    }

    @Test
    fun closeAppendsWatcherCleanupAfterAcceptedMutationWithoutRunningItInline() {
        val background = ManualExecutor()
        val delivery = ManualExecutor()
        val order = mutableListOf<String>()
        val coordinator = AgentSkillProjectionCoordinator(
            backgroundExecutor = background,
            deliveryExecutor = delivery,
            load = { Result.success(1L) },
            registerWatcher = { _, _ -> true },
            unregisterWatcher = { order += "unregister" },
            publish = {},
        )

        coordinator.submit {
            order += "mutation"
            Result.success(2L)
        }
        coordinator.close()

        assertEquals(emptyList<String>(), order)
        assertEquals(2, background.pendingCount())
        background.runNext()
        assertEquals(listOf("mutation"), order)
        background.runNext()
        assertEquals(listOf("mutation", "unregister"), order)
        assertEquals(0, background.pendingCount())

        // Closing twice must not enqueue or execute watcher teardown twice.
        coordinator.close()
        assertEquals(0, background.pendingCount())
        assertEquals(listOf("mutation", "unregister"), order)
    }

    @Test
    fun rejectedPrimaryCloseUsesOffCallerCleanupExecutorExactlyOnce() {
        val fallback = ManualExecutor()
        val unregisterCount = AtomicInteger()
        val coordinator = AgentSkillProjectionCoordinator(
            backgroundExecutor = RejectingExecutor,
            deliveryExecutor = ManualExecutor(),
            load = { Result.success(1L) },
            registerWatcher = { _, _ -> true },
            unregisterWatcher = { unregisterCount.incrementAndGet() },
            publish = {},
            rejectedCleanupExecutor = fallback,
        )

        coordinator.close()
        coordinator.close()

        assertEquals(0, unregisterCount.get())
        assertEquals(1, fallback.pendingCount())
        fallback.runNext()
        assertEquals(1, unregisterCount.get())
        assertEquals(0, fallback.pendingCount())
    }

    @Test
    fun failedRefreshHasDistinctAccessibleFeedbackText() {
        assertEquals(
            "Skill 配置读取失败，请重试",
            AgentSkillProjectionFailureText.message(AgentSkillProjectionOperation.REFRESH),
        )
    }

    private class ManualExecutor : Executor {
        private val tasks: BlockingQueue<Runnable> = LinkedBlockingQueue()

        override fun execute(command: Runnable) {
            tasks.put(command)
        }

        fun runNext() {
            val task = tasks.poll(2, TimeUnit.SECONDS)
            checkNotNull(task) { "Timed out waiting for a delivery task" }
            task.run()
        }

        fun pendingCount(): Int = tasks.size
    }

    private object RejectingExecutor : Executor {
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("deterministic rejection")
        }
    }
}
