package io.github.ethanbird.senseime.core

/** Scoped host diagnostics. No observer is installed in the IME, no text is logged or retained. */
internal object CorrectionSearchDiagnostics {
    data class Trace(val query: String, val paths: List<PinyinSpellingPath>,
        val probes: List<PinyinSpellingPath>, val selected: List<PinyinSpellingPath>)
    private val listener = ThreadLocal<((Trace) -> Unit)?>()

    fun <T> observe(observer: (Trace) -> Unit, block: () -> T): T {
        val previous = listener.get()
        listener.set(observer)
        return try { block() } finally {
            if (previous == null) listener.remove() else listener.set(previous)
        }
    }

    fun record(query: String, paths: List<PinyinSpellingPath>,
        probes: () -> List<PinyinSpellingPath>, selected: () -> List<PinyinSpellingPath>) {
        listener.get()?.invoke(Trace(query, paths, probes(), selected()))
    }
}
