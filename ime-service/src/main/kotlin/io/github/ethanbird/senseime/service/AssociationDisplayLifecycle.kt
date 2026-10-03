package io.github.ethanbird.senseime.service

/**
 * Main-thread state machine for unobtrusive next-word presentation.
 *
 * A commit arms a delayed ticket. Any intervening input cancels that ticket, so
 * continuous typing never flashes an association strip. Once revealed, the same
 * ticket owns its auto-hide callback and stale callbacks are ignored.
 */
internal class AssociationDisplayLifecycle {
    private enum class Phase {
        IDLE,
        WAITING,
        VISIBLE,
    }

    private var generation = 0L
    private var phase = Phase.IDLE
    private var interactionHeld = false

    val waiting: Boolean
        get() = phase == Phase.WAITING

    val visible: Boolean
        get() = phase == Phase.VISIBLE

    val visibleTicket: Long?
        get() = generation.takeIf { visible }

    fun arm(): Long {
        generation = nextGeneration(generation)
        phase = Phase.WAITING
        interactionHeld = false
        return generation
    }

    fun reveal(ticket: Long): Boolean {
        if (phase != Phase.WAITING || ticket != generation) return false
        phase = Phase.VISIBLE
        return true
    }

    fun expire(ticket: Long): Boolean {
        if (phase != Phase.VISIBLE || interactionHeld || ticket != generation) return false
        phase = Phase.IDLE
        return true
    }

    /** Idle time excludes an active candidate touch/drag. Invalidate already queued expiry. */
    fun holdInteraction(): Boolean {
        if (!visible || interactionHeld) return false
        interactionHeld = true
        generation = nextGeneration(generation)
        return true
    }

    /** Returns a fresh expiry ticket only when the same visible strip resumes being idle. */
    fun releaseInteraction(): Long? {
        if (!visible || !interactionHeld) return null
        interactionHeld = false
        generation = nextGeneration(generation)
        return generation
    }

    fun cancel(): Boolean {
        val changed = phase != Phase.IDLE
        generation = nextGeneration(generation)
        phase = Phase.IDLE
        interactionHeld = false
        return changed
    }

    private fun nextGeneration(value: Long): Long =
        if (value == Long.MAX_VALUE) 1L else value + 1L
}
