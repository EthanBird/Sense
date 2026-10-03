package io.github.ethanbird.senseime.core

import java.util.Collections

/**
 * Bounded cache for immutable BASE prefix probes, never personalized results or whole sentences.
 * Learning is merged by AdaptivePinyinDecoder after every lookup, including a cache hit.
 */
internal class PrefixCandidateCache(
    private val maximumEntries: Int = 32,
    private val maximumCandidates: Int = 2_048,
) {
    data class Key(val input: String, val limit: Int, val previousCodePoint: Int, val languageContext: Long = 0L)

    private val values = LinkedHashMap<Key, List<Candidate>>(16, 0.75f, true)
    private var candidateCount = 0

    init {
        require(maximumEntries > 0 && maximumCandidates > 0)
    }

    fun getOrCompute(key: Key, compute: () -> List<Candidate>): List<Candidate> {
        // Prefix state is bounded independently of the size of an editor's composition.
        if (key.input.length !in 1..8 || key.limit <= 0) return compute()
        synchronized(this) { values[key]?.let { return it } }
        val result = compute()
        if (result.size > maximumCandidates) return result
        val snapshot = Collections.unmodifiableList(ArrayList(result))
        synchronized(this) {
            // A concurrent miss may have completed first. Publish exactly one immutable value.
            values[key]?.let { return it }
            values[key] = snapshot
            candidateCount += snapshot.size
            val oldest = values.entries.iterator()
            while (values.size > maximumEntries || candidateCount > maximumCandidates) {
                candidateCount -= oldest.next().value.size
                oldest.remove()
            }
        }
        return snapshot
    }
}
