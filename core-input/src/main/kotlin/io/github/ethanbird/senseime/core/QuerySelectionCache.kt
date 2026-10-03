package io.github.ethanbird.senseime.core

import java.util.Collections

/**
 * Query-local reuse of selection over an immutable lexical source. Source identity,
 * LM state, output width and external context all affect selection. Never shared
 * across queries, model bindings, personal overlays or forced-joint validation.
 * Input sizes are conservatively charged per key, even when identities repeat.
 */
internal class QuerySelectionCache<T>(
    private val maximumEntries: Int = 128,
    private val maximumInputValues: Int = 4_096,
    private val maximumOutputValues: Int = 4_096,
) {
    private class Key(val source: List<*>, val state: Long, val width: Int, val context: Int) {
        override fun hashCode(): Int = (((System.identityHashCode(source) * 31 + state.hashCode()) * 31) + width) * 31 + context
        override fun equals(other: Any?): Boolean = other is Key && source === other.source &&
            state == other.state && width == other.width && context == other.context
    }
    private val values = LinkedHashMap<Key, List<T>>(16, .75f, true)
    private var inputs = 0
    private var outputs = 0

    init { require(maximumEntries > 0 && maximumInputValues > 0 && maximumOutputValues > 0) }

    fun getOrCompute(source: List<*>, state: Long, width: Int, context: Int, compute: () -> List<T>): List<T> {
        DecodeWorkScope.checkpoint()
        require(width > 0)
        if (source.size > maximumInputValues) return compute()
        val key = Key(source, state, width, context)
        values[key]?.let { return it }
        val result = compute()
        DecodeWorkScope.checkpoint()
        if (result.size > maximumOutputValues) return result
        val snapshot = Collections.unmodifiableList(ArrayList(result))
        values[key] = snapshot
        inputs += source.size
        outputs += snapshot.size
        val oldest = values.entries.iterator()
        while (values.size > maximumEntries || inputs > maximumInputValues || outputs > maximumOutputValues) {
            val entry = oldest.next()
            inputs -= entry.key.source.size
            outputs -= entry.value.size
            oldest.remove()
        }
        return snapshot
    }
}
