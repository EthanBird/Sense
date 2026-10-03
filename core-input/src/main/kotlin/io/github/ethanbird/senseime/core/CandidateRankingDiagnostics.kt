package io.github.ethanbird.senseime.core

/** Scoped host inspection. With no observer the IME retains/logs no input or candidate pool. */
internal object CandidateRankingDiagnostics {
    data class Trace(val query: String, val limit: Int, val exact: Boolean,
        val composed: Boolean, val candidates: List<Candidate>)
    private val listener = ThreadLocal<((Trace) -> Unit)?>()

    fun <T> observe(observer: (Trace) -> Unit, block: () -> T): T {
        val previous = listener.get()
        listener.set(observer)
        return try { block() } finally {
            if (previous == null) listener.remove() else listener.set(previous)
        }
    }

    fun record(query: String, limit: Int, exact: Boolean, composed: Boolean, candidates: List<Candidate>) {
        listener.get()?.invoke(Trace(query, limit, exact, composed, candidates))
    }
}
