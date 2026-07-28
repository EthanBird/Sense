package io.github.ethanbird.senseime.brain.runtime

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * One non-Binder lane for potentially blocking Brain run admission.
 *
 * The Messenger handler only decodes a bounded request, revokes the previous identity and enqueues
 * here. A service-provided [admit] function remains responsible for checking the request identity
 * between durable/config/catalog stages. Keeping the lane serial preserves START arrival order and
 * makes config, history and immutable-catalog reads deterministic without blocking the main looper.
 */
internal class BrainAdmissionSerialLane<T>(
    private val executor: Executor,
    private val admit: (T) -> Unit,
) : AutoCloseable {
    private val stateLock = Any()
    private var closed = false

    fun submit(candidate: T): Boolean = synchronized(stateLock) {
        if (closed) return false
        try {
            /*
             * Acceptance is the linearization point. close() rejects future Binder requests but
             * does not discard already-accepted history work during Service teardown.
             */
            executor.execute { admit(candidate) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /**
     * Rejects new admissions and queues owner cleanup after every previously accepted admission.
     *
     * Scheduling and submit acceptance share [stateLock], so cleanup cannot overtake an accepted
     * candidate even when Service teardown races a Binder callback.
     */
    fun closeAfterDraining(cleanup: () -> Unit): Boolean = synchronized(stateLock) {
        if (closed) return false
        closed = true
        try {
            executor.execute(cleanup)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun close() {
        synchronized(stateLock) {
            closed = true
        }
    }
}
