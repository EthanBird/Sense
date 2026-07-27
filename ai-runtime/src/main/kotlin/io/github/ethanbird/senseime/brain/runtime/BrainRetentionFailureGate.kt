package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.BrainRunHandle

/**
 * Closes the small admission race between synchronous events emitted by `engine.start()` and the
 * returned handle being installed by the Service.
 *
 * Once durable retention fails, a later handle can never become runnable. A handle that was
 * already installed is returned to the caller for immediate cancellation.
 */
internal class BrainRetentionFailureGate {
    private var failed = false
    private var handle: BrainRunHandle? = null

    @Synchronized
    fun install(candidate: BrainRunHandle): Boolean {
        if (failed) return false
        check(handle == null) { "Brain run handle is already installed" }
        handle = candidate
        return true
    }

    @Synchronized
    fun markFailed(): BrainRunHandle? {
        failed = true
        return handle
    }

    @Synchronized
    fun currentHandle(): BrainRunHandle? = handle
}
