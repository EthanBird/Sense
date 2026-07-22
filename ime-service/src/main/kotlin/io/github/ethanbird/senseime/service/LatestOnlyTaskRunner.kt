package io.github.ethanbird.senseime.service

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Runs at most one task and retains at most one newer pending task.
 *
 * Submitting while work is running replaces the pending request instead of
 * growing an unbounded queue. A completed value is delivered only when it is
 * still the newest submission. The consumer must additionally verify its own
 * revision when delivery crosses another thread boundary.
 */
internal class LatestOnlyTaskRunner<Input : Any, Output : Any>(
    threadName: String,
    private val work: (Input) -> Output,
    private val deliver: (sequence: Long, input: Input, output: Output) -> Unit,
    private val fail: (sequence: Long, input: Input, error: Throwable) -> Unit = { _, _, _ -> },
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    },
) : AutoCloseable {
    private data class Request<Input>(val sequence: Long, val input: Input)

    private val lock = Any()
    private var newestSequence = 0L
    private var pending: Request<Input>? = null
    private var workerActive = false
    private var closed = false

    /** Returns the monotonic submission sequence, or -1 after close. */
    fun submit(input: Input): Long = synchronized(lock) {
        if (closed) return -1L
        val request = Request(++newestSequence, input)
        pending = request
        if (!workerActive) {
            workerActive = true
            try {
                executor.execute(::drain)
            } catch (_: RejectedExecutionException) {
                workerActive = false
                pending = null
                return -1L
            }
        }
        request.sequence
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                if (closed) {
                    pending = null
                    workerActive = false
                    return
                }
                pending?.also { pending = null } ?: run {
                    workerActive = false
                    return
                }
            }

            val result = runCatching { work(request.input) }
            val isNewest = synchronized(lock) {
                !closed && request.sequence == newestSequence
            }
            if (!isNewest) continue
            result.fold(
                onSuccess = { deliver(request.sequence, request.input, it) },
                onFailure = { fail(request.sequence, request.input, it) },
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending = null
        }
        executor.shutdownNow()
    }
}
