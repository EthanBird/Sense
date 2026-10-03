package io.github.ethanbird.senseime.core

import java.util.Collections

/** Bounded, in-memory reuse of local ordinary spelling edges for ONE immutable inventory. */
internal class SpellingEdgeCache<T>(
    private val maximumEntries: Int = 128,
    private val maximumEdges: Int = 4_096,
) {
    // An extra leading h/g can be fuzzy after the preceding z/c/s/n. It is part of the key,
    // even at a forced joint, matching the original scanner rather than changing its costs.
    data class Key(val lookahead: String, val forcedJoints: Long, val previousLetter: Int = -1)
    private val values = LinkedHashMap<Key, List<T>>(16, .75f, true)
    private var edges = 0

    init { require(maximumEntries > 0 && maximumEdges > 0) }

    fun getOrCompute(key: Key, compute: () -> List<T>): List<T> {
        DecodeWorkScope.checkpoint()
        // The graph's longest accepted unit is 24 letters, plus one possible extra key.
        if (key.lookahead.length !in 1..25) return compute()
        synchronized(this) { values[key]?.let { return it } }
        val result = compute()
        DecodeWorkScope.checkpoint()
        if (result.size > maximumEdges) return result
        val snapshot = Collections.unmodifiableList(ArrayList(result))
        synchronized(this) {
            values[key]?.let { return it }
            values[key] = snapshot
            edges += snapshot.size
            val oldest = values.entries.iterator()
            while (values.size > maximumEntries || edges > maximumEdges) {
                edges -= oldest.next().value.size
                oldest.remove()
            }
        }
        return snapshot
    }
}
