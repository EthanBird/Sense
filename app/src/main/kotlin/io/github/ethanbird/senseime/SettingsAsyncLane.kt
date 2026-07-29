package io.github.ethanbird.senseime

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A screen-owned bounded I/O lane.
 *
 * Refreshes are latest-wins per channel. Mutations remain FIFO and are never cancelled after being
 * accepted. Closing revokes callbacks and lets already accepted work finish before the worker exits.
 */
internal interface SettingsTaskRunner : AutoCloseable {
    fun <T> refresh(
        channel: String,
        operation: () -> T,
        deliver: (Result<T>) -> Unit,
    ): Boolean

    fun <T> execute(
        operation: () -> T,
        deliver: (Result<T>) -> Unit = {},
    ): Boolean
}

internal class SettingsAsyncLane(
    threadName: String,
    private val uiExecutor: Executor,
    private val workerExecutor: ExecutorService = newWorker(threadName),
) : SettingsTaskRunner {
    private val lock = Any()
    private var closed = false
    private var nextSequence = 0L
    private val latestRefreshByChannel = mutableMapOf<String, Long>()

    override fun <T> refresh(
        channel: String,
        operation: () -> T,
        deliver: (Result<T>) -> Unit,
    ): Boolean {
        require(channel.isNotBlank())
        val sequence = synchronized(lock) {
            if (closed) return false
            (++nextSequence).also { latestRefreshByChannel[channel] = it }
        }
        return submit(
            operation = operation,
            shouldDeliver = {
                synchronized(lock) {
                    !closed && latestRefreshByChannel[channel] == sequence
                }
            },
            deliver = deliver,
        )
    }

    override fun <T> execute(
        operation: () -> T,
        deliver: (Result<T>) -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (closed) return false
        }
        return submit(
            operation = operation,
            shouldDeliver = { synchronized(lock) { !closed } },
            deliver = deliver,
        )
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            latestRefreshByChannel.clear()
        }
        workerExecutor.shutdown()
    }

    private fun <T> submit(
        operation: () -> T,
        shouldDeliver: () -> Boolean,
        deliver: (Result<T>) -> Unit,
    ): Boolean = runCatching {
        workerExecutor.execute {
            val result = runCatching(operation)
            if (!shouldDeliver()) return@execute
            uiExecutor.execute {
                if (shouldDeliver()) deliver(result)
            }
        }
    }.isSuccess

    private companion object {
        private val laneIds = AtomicInteger()

        fun newWorker(threadName: String): ExecutorService =
            Executors.newSingleThreadExecutor { command ->
                Thread(
                    command,
                    "$threadName-${laneIds.incrementAndGet()}",
                ).apply {
                    isDaemon = true
                }
            }
    }
}
