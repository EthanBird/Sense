package io.github.ethanbird.senseime.core

/**
 * Cooperative cancellation for a synchronous CPU-only decode call tree.
 *
 * The scope belongs to the calling worker thread, not a decoder/model/cache instance. It never
 * crosses an asynchronous boundary. Existing synchronous callers need no token or behavior
 * change; the IME binds its latest-request probe once around a complete decode. Nested scopes
 * respect their parent and always restore it, including after exceptions.
 */
object DecodeWorkScope {
    private val current = ThreadLocal<(() -> Boolean)?>()

    fun <T> whileCurrent(shouldContinue: () -> Boolean, work: () -> T): T {
        val parent = current.get()
        current.set(if (parent == null) shouldContinue else { { parent() && shouldContinue() } })
        return try {
            checkpoint()
            work().also { checkpoint() }
        } finally {
            if (parent == null) current.remove() else current.set(parent)
        }
    }

    /** Called at bounded search boundaries, not inside score comparators or model transitions. */
    internal fun checkpoint() {
        if (current.get()?.invoke() == false) throw DecodeSupersededException()
    }
}

/** No editor text and no expensive stack capture for normal supersession. */
class DecodeSupersededException internal constructor() : RuntimeException("Decode superseded", null, false, false)
