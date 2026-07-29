package io.github.ethanbird.senseime

/**
 * Coalesces high-frequency editor callbacks into one immutable draft snapshot.
 *
 * A 64 KiB Skill document may emit a TextWatcher callback for every edit. The watcher only marks
 * this coordinator dirty and schedules the existing debounce. Converting the complete View state
 * to Strings happens once when the debounce, an explicit action, or a lifecycle boundary claims
 * the pending capture.
 */
internal class SkillDraftCaptureCoordinator {
    private var dirty = false

    fun markDirty() {
        dirty = true
    }

    fun claimCapture(force: Boolean = false): Boolean {
        if (!dirty && !force) return false
        dirty = false
        return true
    }

    fun reset() {
        dirty = false
    }
}
