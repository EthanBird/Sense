package io.github.ethanbird.senseime.service

import android.os.FileObserver
import io.github.ethanbird.senseime.brain.runtime.AgentSkillStore
import java.io.File

/**
 * Owns the real filesystem watch used by the IME's immutable Skill projection.
 *
 * Registration and teardown are intentionally synchronous: callers must invoke both methods from
 * [AgentSkillProjectionCoordinator]'s serial background lane. FileObserver callbacks never touch
 * the store directly; they only enqueue reconciliation back onto that lane.
 */
internal class AgentSkillDirectoryWatcher(
    private val directory: File,
    private val ownerIsAlive: () -> Boolean = { true },
) {
    private val lock = Any()
    private var observer: FileObserver? = null

    @Suppress("DEPRECATION")
    fun register(
        onChanged: () -> Unit,
        onInvalidated: () -> Unit,
    ): Boolean {
        if (!ownerIsAlive()) return false
        synchronized(lock) {
            if (observer != null) return true
        }
        if (!directory.isDirectory) return false

        val candidate = object : FileObserver(directory.absolutePath, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                val ownsWatch = synchronized(lock) {
                    observer === this
                }
                if (!ownsWatch || !ownerIsAlive()) return
                if (event and INVALIDATION_MASK != 0) {
                    onInvalidated()
                    return
                }
                if (
                    path == AgentSkillStore.CURRENT_FILE_NAME &&
                    event and CURRENT_CHANGE_MASK != 0
                ) {
                    onChanged()
                }
            }
        }
        candidate.startWatching()
        val installed = synchronized(lock) {
            if (!ownerIsAlive() || observer != null) {
                false
            } else {
                observer = candidate
                true
            }
        }
        if (!installed) candidate.stopWatching()
        return installed || synchronized(lock) { observer != null }
    }

    fun unregister() {
        val current = synchronized(lock) {
            observer.also { observer = null }
        }
        current?.stopWatching()
    }

    internal fun isRegisteredForTest(): Boolean = synchronized(lock) {
        observer != null
    }

    private companion object {
        val CURRENT_CHANGE_MASK =
            FileObserver.CLOSE_WRITE or
                FileObserver.MOVED_TO or
                FileObserver.CREATE or
                FileObserver.DELETE
        val INVALIDATION_MASK = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val WATCH_MASK = CURRENT_CHANGE_MASK or INVALIDATION_MASK
    }
}
