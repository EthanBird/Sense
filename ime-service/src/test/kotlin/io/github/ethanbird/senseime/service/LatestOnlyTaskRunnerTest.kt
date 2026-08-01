package io.github.ethanbird.senseime.service

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestOnlyTaskRunnerTest {
    @Test
    fun submissionCannotCrossFreshnessCheckAndDeliveryBoundary() {
        val firstDeliveryStarted = CountDownLatch(1)
        val releaseFirstDelivery = CountDownLatch(1)
        val secondSubmitStarted = CountDownLatch(1)
        val secondSubmitReturned = CountDownLatch(1)
        val secondDelivered = CountDownLatch(1)
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val runner = LatestOnlyTaskRunner<Int, Int>(
            threadName = "latest-only-publication-test",
            work = { value, _ -> value },
            deliver = { _, _, value ->
                values += value
                if (value == 1) {
                    firstDeliveryStarted.countDown()
                    assertTrue(releaseFirstDelivery.await(2, TimeUnit.SECONDS))
                } else {
                    secondDelivered.countDown()
                }
            },
        )
        val submitter = Thread({
            secondSubmitStarted.countDown()
            runner.submit(2)
            secondSubmitReturned.countDown()
        }, "latest-only-concurrent-submit")

        try {
            runner.submit(1)
            assertTrue(firstDeliveryStarted.await(2, TimeUnit.SECONDS))
            submitter.start()
            assertTrue(secondSubmitStarted.await(2, TimeUnit.SECONDS))

            assertFalse(secondSubmitReturned.await(500, TimeUnit.MILLISECONDS))
            releaseFirstDelivery.countDown()

            assertTrue(secondSubmitReturned.await(2, TimeUnit.SECONDS))
            assertTrue(secondDelivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), values.toList())
        } finally {
            releaseFirstDelivery.countDown()
            submitter.join(2_000)
            runner.close()
        }
    }

    @Test
    fun replacesPendingWorkAndDeliversOnlyNewestResult() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val worked = Collections.synchronizedList(mutableListOf<Int>())
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val runner = LatestOnlyTaskRunner<Int, Int>(
            threadName = "latest-only-test",
            work = { value, _ ->
                worked += value
                if (value == 1) {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                value * 10
            },
            deliver = { _, _, value ->
                values += value
                delivered.countDown()
            },
        )

        try {
            runner.submit(1)
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            runner.submit(2)
            runner.submit(3)
            releaseFirst.countDown()

            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), worked.toList())
            assertEquals(listOf(30), values.toList())
        } finally {
            runner.close()
        }
    }

    @Test
    fun closeDropsInFlightResultAndRejectsNewSubmissions() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val runner = LatestOnlyTaskRunner<Int, Int>(
            threadName = "latest-only-close-test",
            work = { value, _ ->
                started.countDown()
                try {
                    release.await(2, TimeUnit.SECONDS)
                    value
                } finally {
                    finished.countDown()
                }
            },
            deliver = { _, _, value -> values += value },
        )

        runner.submit(1)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        runner.close()
        release.countDown()

        assertEquals(-1L, runner.submit(2))
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(values.isEmpty())
    }

    @Test
    fun supersededWorkObservesCancellationBeforeItsNextExpensiveStep() {
        val firstStarted = CountDownLatch(1)
        val releaseFirstProbe = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val oldExpensiveSteps = java.util.concurrent.atomic.AtomicInteger()
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val runner = LatestOnlyTaskRunner<Int, Int>(
            threadName = "latest-only-cooperative-test",
            work = { value, shouldContinue ->
                if (value == 1) {
                    firstStarted.countDown()
                    assertTrue(releaseFirstProbe.await(2, TimeUnit.SECONDS))
                    for (step in 0 until 31) {
                        if (!shouldContinue()) break
                        oldExpensiveSteps.incrementAndGet()
                    }
                }
                value
            },
            deliver = { _, _, value ->
                values += value
                delivered.countDown()
            },
        )

        try {
            runner.submit(1)
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            runner.submit(2)
            releaseFirstProbe.countDown()

            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(0, oldExpensiveSteps.get())
            assertEquals(listOf(2), values.toList())
        } finally {
            runner.close()
        }
    }
}
