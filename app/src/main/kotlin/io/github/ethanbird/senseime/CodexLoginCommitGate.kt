package io.github.ethanbird.senseime

/**
 * Linearizes an OAuth attempt's cancellation (or screen close) against its credential write.
 *
 * Exchanging the authorization code does not grant permission to mutate the vault. The worker must
 * still claim this gate immediately before the write. Once that claim wins, a later cancel is not
 * reported as successful; once cancel/close wins, the attempt can no longer enter the write.
 */
internal class CodexLoginCommitGate {
    private val lock = Any()
    private var closed = false
    private var current: Attempt? = null

    fun begin(generation: Long) {
        synchronized(lock) {
            check(!closed) { "Codex login gate is closed" }
            check(current == null) { "A Codex login attempt is already active" }
            current = Attempt(generation)
        }
    }

    fun isActive(generation: Long): Boolean = synchronized(lock) {
        !closed && current?.let { it.generation == generation && it.phase == Phase.ACTIVE } == true
    }

    /** Returns true only when cancellation won before the credential commit claim. */
    fun cancel(generation: Long): Boolean = synchronized(lock) {
        val attempt = current?.takeIf { it.generation == generation } ?: return false
        if (attempt.phase != Phase.ACTIVE) return false
        attempt.phase = Phase.CANCELLED
        current = null
        true
    }

    /** Permanently closes the gate and cancels an attempt that has not claimed its commit. */
    fun close(): Boolean = synchronized(lock) {
        if (closed) return false
        closed = true
        val attempt = current ?: return false
        if (attempt.phase != Phase.ACTIVE) return false
        attempt.phase = Phase.CANCELLED
        current = null
        true
    }

    fun <T> commitIfActive(
        generation: Long,
        commit: () -> T,
    ): CodexLoginCommitResult<T> {
        val attempt = synchronized(lock) {
            val candidate = current?.takeIf { it.generation == generation }
                ?: return CodexLoginCommitResult.Rejected
            if (closed || candidate.phase != Phase.ACTIVE) {
                return CodexLoginCommitResult.Rejected
            }
            candidate.phase = Phase.COMMITTING
            candidate
        }

        val result = runCatching(commit)
        synchronized(lock) {
            if (current === attempt) {
                attempt.phase = if (result.isSuccess) Phase.COMMITTED else Phase.FAILED
            }
        }
        return CodexLoginCommitResult.Accepted(result)
    }

    /** Releases terminal bookkeeping after the matching UI completion has been delivered. */
    fun finish(generation: Long) {
        synchronized(lock) {
            val attempt = current?.takeIf { it.generation == generation } ?: return
            check(attempt.phase != Phase.COMMITTING) { "Credential commit is still running" }
            attempt.phase = Phase.FINISHED
            current = null
        }
    }

    private data class Attempt(
        val generation: Long,
        var phase: Phase = Phase.ACTIVE,
    )

    private enum class Phase {
        ACTIVE,
        COMMITTING,
        COMMITTED,
        FAILED,
        CANCELLED,
        FINISHED,
    }
}

internal sealed interface CodexLoginCommitResult<out T> {
    data object Rejected : CodexLoginCommitResult<Nothing>
    data class Accepted<T>(val result: Result<T>) : CodexLoginCommitResult<T>
}
