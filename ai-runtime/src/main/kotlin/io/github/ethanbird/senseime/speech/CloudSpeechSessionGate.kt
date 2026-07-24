package io.github.ethanbird.senseime.speech

import java.util.ArrayDeque
import java.util.concurrent.Executor

class CloudSpeechSessionToken internal constructor(
    val sessionId: Long,
    internal val generation: Long,
)

/**
 * Guards asynchronous microphone and HTTP callbacks against cancellation and session reuse.
 *
 * Session ids are strictly monotonic. A late callback holding an old generation can never become
 * authoritative again, even if its numeric session id is accidentally reused.
 */
class CloudSpeechSessionGate {
    private var generation = 0L
    private var highestSessionId = 0L
    private var active: CloudSpeechSessionToken? = null

    @Synchronized
    fun tryBegin(sessionId: Long): CloudSpeechSessionToken? {
        if (sessionId <= highestSessionId || active != null) return null
        generation += 1L
        highestSessionId = sessionId
        return CloudSpeechSessionToken(sessionId, generation).also { active = it }
    }

    @Synchronized
    fun isCurrent(token: CloudSpeechSessionToken): Boolean = active == token

    @Synchronized
    fun complete(token: CloudSpeechSessionToken): Boolean {
        if (active != token) return false
        active = null
        generation += 1L
        return true
    }

    @Synchronized
    fun cancel(sessionId: Long): CloudSpeechSessionToken? {
        val token = active ?: return null
        if (token.sessionId != sessionId) return null
        active = null
        generation += 1L
        return token
    }

    @Synchronized
    fun invalidateAll(): CloudSpeechSessionToken? {
        val token = active
        active = null
        generation += 1L
        return token
    }

    @Synchronized
    fun activeToken(): CloudSpeechSessionToken? = active
}

/** Preserves callback ordering even when the caller supplies a pooled executor. */
internal class SerialExecutor(
    private val delegate: Executor,
) : Executor {
    private val queue = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    override fun execute(command: Runnable) {
        synchronized(queue) {
            queue.addLast(
                Runnable {
                    try {
                        command.run()
                    } finally {
                        scheduleNext()
                    }
                },
            )
            if (active == null) scheduleNextLocked()
        }
    }

    private fun scheduleNext() {
        synchronized(queue) {
            scheduleNextLocked()
        }
    }

    private fun scheduleNextLocked() {
        active = if (queue.isEmpty()) null else queue.removeFirst()
        active?.let(delegate::execute)
    }
}
