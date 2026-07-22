package io.github.ethanbird.senseime.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialPersistenceQueueTest {
    @Test
    fun closeRunsAfterTheLastAcceptedWriteWithoutDroppingIt() {
        val persistStarted = CountDownLatch(1)
        val releasePersist = CountDownLatch(1)
        val events = mutableListOf<String>()
        val queue = SerialPersistenceQueue<String>(
            threadName = "test-user-lexicon",
            persist = { value ->
                persistStarted.countDown()
                assertTrue(releasePersist.await(5, TimeUnit.SECONDS))
                synchronized(events) { events += "persist:$value" }
            },
            closeStorage = { synchronized(events) { events += "close" } },
        )

        assertTrue(queue.submit("phrase"))
        assertTrue(persistStarted.await(5, TimeUnit.SECONDS))
        queue.close()
        assertFalse(queue.submit("too-late"))
        releasePersist.countDown()

        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
        assertEquals(listOf("persist:phrase", "close"), synchronized(events) { events.toList() })
        queue.close()
    }
}
