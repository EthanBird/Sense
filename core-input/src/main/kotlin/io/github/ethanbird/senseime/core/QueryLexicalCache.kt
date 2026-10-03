package io.github.ethanbird.senseime.core

import java.util.Collections

/**
 * A single decode's prepared BASE word edges, shared by correction scouts and full beams.
 * Only syllable validation and dictionary/boundary ordering are cached, keyed by all their
 * inputs. Personal evidence, forced-joint validation and LM state selection stay outside.
 * The caller owns this instance on its stack; it never outlives the query or crosses threads.
 */
internal class QueryLexicalCache(
    private val load: (Int, Int?, Int) -> List<Candidate>,
    private val maximumEntries: Int = 128,
    private val maximumCandidates: Int = 4_096,
) {
    private data class Key(val record: Int, val syllables: Int?, val previousCodePoint: Int)
    private val records = LinkedHashMap<Key, List<Candidate>>(16, .75f, true)
    private var candidates = 0

    init { require(maximumEntries > 0 && maximumCandidates > 0) }

    fun get(record: Int, syllables: Int? = null, previousCodePoint: Int = -1): List<Candidate> {
        DecodeWorkScope.checkpoint()
        require(record >= 0)
        val key = Key(record, syllables, previousCodePoint)
        records[key]?.let { return it }
        val result = load(record, syllables, previousCodePoint)
        if (result.size > maximumCandidates) return result
        val snapshot = Collections.unmodifiableList(ArrayList(result))
        records[key] = snapshot
        candidates += snapshot.size
        val oldest = records.entries.iterator()
        while (records.size > maximumEntries || candidates > maximumCandidates) {
            candidates -= oldest.next().value.size
            oldest.remove()
        }
        return snapshot
    }
}
