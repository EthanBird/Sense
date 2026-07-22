package io.github.ethanbird.senseime.core

/**
 * Candidates bound to one rendered composition revision.
 *
 * A UI selection must match both the currently active input revision and this
 * snapshot. Invalid or stale indices intentionally return null; only explicit
 * primary actions such as space/enter may fall back to raw composing text.
 */
data class CandidateSnapshot(
    val revision: Long,
    val candidates: List<Candidate>,
) {
    fun select(
        currentRevision: Long,
        requestedRevision: Long,
        sourceIndex: Int,
    ): Candidate? {
        if (revision != currentRevision || requestedRevision != revision) return null
        return candidates.getOrNull(sourceIndex)
    }

    companion object {
        val EMPTY = CandidateSnapshot(-1L, emptyList())
    }
}
