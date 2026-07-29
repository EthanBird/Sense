package io.github.ethanbird.senseime

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Activity-scoped delivery over a process-scoped serial Skill I/O executor.
 *
 * Work accepted before [close] always runs. Closing only revokes delivery, so a draft fsync or
 * catalog mutation promised by a stopping Activity is not abandoned. Refreshes are latest-wins per
 * channel at delivery time; ordinary operations are never coalesced.
 */
internal class SkillSettingsIoSession(
    private val workerExecutor: Executor,
    private val uiExecutor: Executor,
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
        require(channel.isNotBlank()) { "Skill I/O refresh channel cannot be blank" }
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
}

/**
 * Bounded lifecycle join for a recovery snapshot that is too large for Android saved state.
 *
 * Timeout only releases the Activity main thread; the accepted serial operation is deliberately
 * not cancelled, so it can still complete its fsync while the process remains alive.
 */
internal class SkillSettingsDurabilityBarrier<K : Any, T>(
    private val workerExecutor: ExecutorService,
    private val timeoutMillis: Long,
) {
    private val stateLock = Any()
    private var pendingKey: K? = null
    private var pendingFuture: Future<T>? = null
    private var pendingDeadlineNanos = 0L

    init {
        require(timeoutMillis > 0L)
    }

    fun execute(
        key: K,
        operation: () -> T,
    ): Result<T> {
        val (future, deadlineNanos) = synchronized(stateLock) {
            val current = pendingFuture
            if (current != null && !current.isCancelled && pendingKey == key) {
                current to pendingDeadlineNanos
            } else {
                runCatching { workerExecutor.submit<T>(operation) }
                    .getOrElse { return Result.failure(it) }
                    .let {
                        pendingKey = key
                        pendingFuture = it
                        pendingDeadlineNanos =
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                        it to pendingDeadlineNanos
                    }
            }
        }
        val result = runCatching {
            if (future.isDone) {
                future.get()
            } else {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    throw TimeoutException("Skill durability wait budget exhausted")
                }
                future.get(remainingNanos, TimeUnit.NANOSECONDS)
            }
        }
        if (future.isDone) {
            synchronized(stateLock) {
                if (pendingFuture === future) {
                    pendingKey = null
                    pendingFuture = null
                    pendingDeadlineNanos = 0L
                }
            }
        }
        return result
    }
}
