package io.github.ethanbird.senseime.core

/** Splits continuous full pinyin into a requested number of legal syllables. */
class PinyinSyllableSegmenter(syllables: Collection<String>) {
    private val syllablesByInitial = syllables
        .asSequence()
        .map(::normalize)
        .filter { it.isNotEmpty() }
        .distinct()
        .groupBy { it.first() }
        .mapValues { (_, values) -> values.sortedByDescending { it.length } }

    fun initials(fullPinyin: String, expectedSyllables: Int): String? {
        if (expectedSyllables <= 0) return null
        val normalized = normalize(fullPinyin)
        if (normalized.isEmpty()) return null
        val values = segmentInitials(normalized, expectedSyllables, 0, HashMap())
        return values.singleOrNull()
    }

    private fun segmentInitials(
        code: String,
        remaining: Int,
        offset: Int,
        memo: MutableMap<Long, Set<String>>,
    ): Set<String> {
        if (offset == code.length) return if (remaining == 0) setOf("") else emptySet()
        if (remaining <= 0 || code.length - offset < remaining) return emptySet()
        val key = (offset.toLong() shl 32) or remaining.toLong()
        memo[key]?.let { return it }

        val values = LinkedHashSet<String>()
        val options = syllablesByInitial[code[offset]].orEmpty()
        for (syllable in options) {
            if (!code.regionMatches(offset, syllable, 0, syllable.length)) continue
            segmentInitials(code, remaining - 1, offset + syllable.length, memo).forEach { suffix ->
                values += syllable.first() + suffix
            }
            if (values.size > 1) break
        }
        return values.also { memo[key] = it }
    }

    companion object {
        fun normalize(value: String): String = buildString(value.length) {
            value.forEach { character ->
                val lower = character.lowercaseChar()
                if (lower in 'a'..'z') append(lower)
            }
        }
    }
}
