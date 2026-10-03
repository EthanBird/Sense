package io.github.ethanbird.senseime.core

import java.util.Collections

/** Bounded explicit-choice memory, separate from automatic/global frequency learning. */
object UserContextSelection {
    const val MAX_CONTEXTS = 8
    const val LIFETIME_MILLIS = 30L * 24 * 60 * 60 * 1_000

    /** Stop at punctuation, whitespace or a non-Han character; retain at most two code points. */
    fun suffix(text: CharSequence): String {
        var start = text.length
        repeat(2) {
            if (start == 0) return@repeat
            val cp = Character.codePointBefore(text, start)
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return text.subSequence(start, text.length).toString()
            start -= Character.charCount(cp)
        }
        return text.subSequence(start, text.length).toString()
    }

    fun sanitize(values: Map<String, Long>): Map<String, Long> {
        val entries = values.entries.asSequence()
            .filter { it.key.isNotEmpty() && it.key == suffix(it.key) && it.value >= 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(MAX_CONTEXTS).associateTo(LinkedHashMap()) { it.key to it.value }
        return if (entries.isEmpty()) emptyMap() else Collections.unmodifiableMap(entries)
    }

    fun updated(values: Map<String, Long>, evidence: UserLearningEvidence, now: Long): Map<String, Long> {
        val context = suffix(evidence.leftContext)
        if (context.isEmpty() || evidence.kind == UserSelectionKind.DEFAULT_ACCEPT) return values
        return sanitize(values + (context to now))
    }

    fun isFresh(timestamp: Long, now: Long): Boolean = timestamp >= 0 &&
        (now <= timestamp || now - timestamp <= LIFETIME_MILLIS)

    /** Han contexts contain no tab/newline, so the small storage format needs no escaping. */
    fun encode(values: Map<String, Long>): String = sanitize(values).entries.joinToString("\n") { "${it.key}\t${it.value}" }

    fun decode(value: String): Map<String, Long> {
        if (value.length > 2_048) return emptyMap()
        val decoded = LinkedHashMap<String, Long>()
        value.lineSequence().forEach { line ->
            val split = line.indexOf('\t')
            if (split <= 0) return@forEach
            val context = line.substring(0, split)
            val timestamp = line.substring(split + 1).toLongOrNull() ?: return@forEach
            if (context != suffix(context) || timestamp < 0) return@forEach
            decoded[context] = maxOf(decoded[context] ?: Long.MIN_VALUE, timestamp)
        }
        return sanitize(decoded)
    }
}
