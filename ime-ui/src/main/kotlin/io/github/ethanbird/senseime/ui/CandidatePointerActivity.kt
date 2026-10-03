package io.github.ethanbird.senseime.ui

/** Physical ownership survives drag latching, which intentionally clears the pressed highlight. */
internal class CandidatePointerActivity(private val changed: (Boolean) -> Unit) {
    private val pointers = HashSet<Int>()

    fun down(pointerId: Int) {
        if (pointers.add(pointerId) && pointers.size == 1) changed(true)
    }

    fun up(pointerId: Int) {
        if (pointers.remove(pointerId) && pointers.isEmpty()) changed(false)
    }

    fun cancel() {
        if (pointers.isEmpty()) return
        pointers.clear()
        changed(false)
    }
}
