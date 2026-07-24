package io.github.ethanbird.senseime.brain.runtime

/**
 * Run-local ownership for the Brain engine timer callback.
 *
 * The service guards every call with its active-run lock. Keeping the callback
 * in a slot owned by one run means cleanup from an older run can never clear a
 * newer run's timer.
 */
internal class BrainRunTickerSlot {
    private var callback: Runnable? = null

    fun install(value: Runnable): Boolean {
        if (callback != null) return false
        callback = value
        return true
    }

    fun owns(value: Runnable): Boolean = callback === value

    fun clearIfOwned(value: Runnable): Runnable? =
        if (callback === value) {
            callback.also { callback = null }
        } else {
            null
        }

    fun clear(): Runnable? = callback.also { callback = null }
}
