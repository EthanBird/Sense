package io.github.ethanbird.senseime.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Serializes durable writes and always closes storage after the final accepted write. */
class SerialPersistenceQueue<T>(
    threadName: String,
    private val persist: (T) -> Unit,
    private val closeStorage: () -> Unit,
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val closedLatch = CountDownLatch(1)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).apply { isDaemon = false }
    }
    private var closed = false

    fun submit(value: T): Boolean = synchronized(lifecycleLock) {
        if (closed) return@synchronized false
        executor.execute {
            runCatching { persist(value) }.onFailure(onError)
        }
        true
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            executor.execute {
                try {
                    closeStorage()
                } catch (error: Throwable) {
                    onError(error)
                } finally {
                    closedLatch.countDown()
                }
            }
            executor.shutdown()
        }
    }

    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean = closedLatch.await(timeout, unit)
}
