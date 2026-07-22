package io.github.ethanbird.senseime.service

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestOnlyTaskRunnerTest {
    @Test
    fun replacesPendingWorkAndDeliversOnlyNewestResult() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val worked = Collections.synchronizedList(mutableListOf<Int>())
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val runner = LatestOnlyTaskRunner<Int, Int>(
            threadName = "latest-only-test",
            work = { value ->
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
            work = { value ->
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
}
