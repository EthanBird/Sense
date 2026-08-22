package io.github.ethanbird.senseime.mic

/**
 * Small JVM-testable generation gate for state owned by an asynchronous runtime.
 *
 * Every start gets a new generation. A worker may publish only while its generation is current,
 * so a late callback from a stopped/rebound runtime cannot resurrect stale UI state.
 */
internal class SenseMicGenerationState<T>(initialValue: T) {
    data class Snapshot<T>(
        val generation: Long,
        val active: Boolean,
        val value: T,
    )

    private val lock = Any()
    private var generation = 0L
    private var active = false
    private var value = initialValue

    fun begin(initialValue: T): Long = synchronized(lock) {
        generation = nextGeneration(generation)
        active = true
        value = initialValue
        generation
    }

    fun finish(generation: Long, finalValue: T): Boolean = synchronized(lock) {
        if (!active || this.generation != generation) return@synchronized false
        this.generation = nextGeneration(this.generation)
        active = false
        value = finalValue
        true
    }

    fun reset(finalValue: T): Long = synchronized(lock) {
        generation = nextGeneration(generation)
        active = false
        value = finalValue
        generation
    }

    fun publish(generation: Long, newValue: T): Boolean = synchronized(lock) {
        if (!active || this.generation != generation) return@synchronized false
        value = newValue
        true
    }

    fun update(generation: Long, transform: (T) -> T): Boolean = synchronized(lock) {
        if (!active || this.generation != generation) return@synchronized false
        value = transform(value)
        true
    }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        active && this.generation == generation
    }

    fun snapshot(): Snapshot<T> = synchronized(lock) {
        Snapshot(generation = generation, active = active, value = value)
    }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L
}

/** Coalesces callback bursts and rejects a queued network rebind after its runtime was replaced. */
internal class SenseMicRebindGate {
    data class Ticket(
        val runtimeGeneration: Long?,
        val sequence: Long,
    )

    private val lock = Any()
    private var sequence = 0L

    fun request(runtimeGeneration: Long?): Ticket = synchronized(lock) {
        sequence = if (sequence == Long.MAX_VALUE) 1L else sequence + 1L
        Ticket(runtimeGeneration, sequence)
    }

    fun isLatest(ticket: Ticket, currentRuntimeGeneration: Long?): Boolean = synchronized(lock) {
        ticket.sequence == sequence && ticket.runtimeGeneration == currentRuntimeGeneration
    }

    fun cancel() = synchronized(lock) {
        sequence = if (sequence == Long.MAX_VALUE) 1L else sequence + 1L
    }
}

/**
 * Serializes the complete resource transition, not only the generation-field update.
 *
 * Socket close/bind, lock hand-off, key rotation, and foreground ownership must stay in the same
 * critical section. Otherwise a STOP/START or queued network rebind can observe cleared fields
 * while the previous transition still owns those resources in local variables.
 */
internal class SenseMicRuntimeTransitionGate {
    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock) {
        block()
    }
}
