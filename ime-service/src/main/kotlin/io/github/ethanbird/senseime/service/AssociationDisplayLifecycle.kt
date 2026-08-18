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

    val waiting: Boolean
        get() = phase == Phase.WAITING

    val visible: Boolean
        get() = phase == Phase.VISIBLE

    fun arm(): Long {
        generation = nextGeneration(generation)
        phase = Phase.WAITING
        return generation
    }

    fun reveal(ticket: Long): Boolean {
        if (phase != Phase.WAITING || ticket != generation) return false
        phase = Phase.VISIBLE
        return true
    }

    fun expire(ticket: Long): Boolean {
        if (phase != Phase.VISIBLE || ticket != generation) return false
        phase = Phase.IDLE
        return true
    }

    fun cancel(): Boolean {
        val changed = phase != Phase.IDLE
        generation = nextGeneration(generation)
        phase = Phase.IDLE
        return changed
    }

    private fun nextGeneration(value: Long): Long =
        if (value == Long.MAX_VALUE) 1L else value + 1L
}
