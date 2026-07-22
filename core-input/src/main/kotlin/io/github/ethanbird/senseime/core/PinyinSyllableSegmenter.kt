package io.github.ethanbird.senseime.core

/** Splits continuous full pinyin using bounded, allocation-light dynamic programming. */
class PinyinSyllableSegmenter(syllables: Collection<String>) {
    private val syllablesByInitial = syllables
        .asSequence()
        .map(::normalize)
        .filter { it.isNotEmpty() }
        .distinct()
        .groupBy { it.first() }
        .mapValues { (_, values) -> values.sortedByDescending { it.length } }

    /** Returns one deterministic complete segmentation, preferring longer leading syllables. */
    fun segment(fullPinyin: String): List<String>? {
        val normalized = normalize(fullPinyin)
        if (normalized.isEmpty() || normalized.length > MAX_SEGMENT_INPUT_LENGTH) return null
        val next = IntArray(normalized.length + 1) { -1 }
        next[normalized.length] = normalized.length
        for (offset in normalized.lastIndex downTo 0) {
            for (syllable in syllablesByInitial[normalized[offset]].orEmpty()) {
                val end = offset + syllable.length
                if (end <= normalized.length && next[end] >= 0 && normalized.regionMatches(offset, syllable, 0, syllable.length)) {
                    next[offset] = end
                    break
                }
            }
        }
        if (next[0] < 0) return null
        val result = ArrayList<String>()
        var offset = 0
        while (offset < normalized.length) {
            val end = next[offset]
            if (end <= offset) return null
            result += normalized.substring(offset, end)
            offset = end
        }
        return result
    }

    fun isComplete(fullPinyin: String): Boolean {
        val normalized = normalize(fullPinyin)
        return normalized.isNotEmpty() &&
            normalized.length <= MAX_SEGMENT_INPUT_LENGTH &&
            suffixReachability(normalized)[0]
    }

    /**
     * Legal first-syllable boundaries whose suffix is also completely segmentable.
     * The full-input boundary is excluded because it is a whole candidate, not a
     * partial selection. The result is bounded by the pinyin syllable inventory.
     */
    fun selectablePrefixLengths(fullPinyin: String): IntArray {
        val normalized = normalize(fullPinyin)
        if (normalized.length !in 2..MAX_SEGMENT_INPUT_LENGTH) return IntArray(0)
        val reachable = suffixReachability(normalized)
        val values = IntArray(MAX_PREFIX_BOUNDARIES)
        var size = 0
        for (syllable in syllablesByInitial[normalized[0]].orEmpty()) {
            val end = syllable.length
            if (
                end < normalized.length &&
                reachable[end] &&
                normalized.regionMatches(0, syllable, 0, syllable.length)
            ) {
                values[size++] = end
                if (size == values.size) break
            }
        }
        values.sort(0, size)
        return values.copyOf(size)
    }

    private fun suffixReachability(code: String): BooleanArray {
        val reachable = BooleanArray(code.length + 1)
        reachable[code.length] = true
        for (offset in code.lastIndex downTo 0) {
            for (syllable in syllablesByInitial[code[offset]].orEmpty()) {
                val end = offset + syllable.length
                if (end <= code.length && reachable[end] && code.regionMatches(offset, syllable, 0, syllable.length)) {
                    reachable[offset] = true
                    break
                }
            }
        }
        return reachable
    }

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

        private const val MAX_SEGMENT_INPUT_LENGTH = 96
        private const val MAX_PREFIX_BOUNDARIES = 8
    }
}
