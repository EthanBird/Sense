package io.github.ethanbird.senseime.core

data class MixedPinyinSegment(
    val code: String,
    val abbreviated: Boolean,
)

/**
 * A display-only view of how continuous input was interpreted.
 *
 * [rawCode] remains separator-free and is the value kept in the composing
 * transaction. [formatted] is suitable for candidate-bar explanation only.
 */
data class MixedPinyinPath(
    val segments: List<MixedPinyinSegment>,
) {
    val rawCode: String
        get() = segments.joinToString(separator = "") { it.code }

    val formatted: String
        get() = segments.joinToString(separator = "'") { it.code }

    val abbreviatedSyllables: Int
        get() = segments.count { it.abbreviated }

    val fullSyllables: Int
        get() = segments.size - abbreviatedSyllables

    val fullCharacters: Int
        get() = segments.sumOf { if (it.abbreviated) 0 else it.code.length }

    val initials: String
        get() = buildString(segments.size) {
            segments.forEach { append(it.code.first()) }
        }

    /**
     * Strong enough to demote edit-path corrections rather than merely join
     * the recall set. One full syllable followed mostly by initials is still
     * useful recall, but is ambiguous with a short adjacent-key typo.
     */
    internal val hasStrongTypedEvidence: Boolean
        get() = fullSyllables >= MIN_STRONG_MIXED_FULL_SYLLABLES &&
            abbreviatedSyllables > 0

    private companion object {
        const val MIN_STRONG_MIXED_FULL_SYLLABLES = 2
    }
}

/** Splits continuous full pinyin using bounded, allocation-light dynamic programming. */
class PinyinSyllableSegmenter(syllables: Collection<String>) {
    private val syllablesByInitial = syllables
        .asSequence()
        .map(::normalize)
        .filter { it.isNotEmpty() }
        .distinct()
        .groupBy { it.first() }
        .mapValues { (_, values) -> values.sortedByDescending { it.length } }

    internal fun syllablesStartingWith(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        return syllablesByInitial[prefix.first()].orEmpty()
            .filter { it.startsWith(prefix) }
    }

    /**
     * Verifies that candidate metadata follows this exact mixed segmentation,
     * rather than merely sharing the same initials string.
     */
    internal fun matchesCanonical(
        path: MixedPinyinPath,
        canonicalPinyin: String?,
        canonicalInitials: String?,
    ): Boolean {
        val canonical = canonicalPinyin ?: return false
        if (canonicalInitials != path.initials) return false
        val memo = Array(path.segments.size + 1) {
            ByteArray(canonical.length + 1) { MATCH_UNKNOWN }
        }
        fun matches(segmentIndex: Int, offset: Int): Boolean {
            if (segmentIndex == path.segments.size) return offset == canonical.length
            if (offset >= canonical.length) return false
            when (memo[segmentIndex][offset]) {
                MATCH_FALSE -> return false
                MATCH_TRUE -> return true
            }
            val segment = path.segments[segmentIndex]
            val result = if (segment.abbreviated) {
                syllablesStartingWith(segment.code).any { syllable ->
                    canonical.regionMatches(offset, syllable, 0, syllable.length) &&
                        matches(segmentIndex + 1, offset + syllable.length)
                }
            } else {
                canonical.regionMatches(offset, segment.code, 0, segment.code.length) &&
                    matches(segmentIndex + 1, offset + segment.code.length)
            }
            memo[segmentIndex][offset] = if (result) MATCH_TRUE else MATCH_FALSE
            return result
        }
        return matches(segmentIndex = 0, offset = 0)
    }

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
     * Finds the strongest full-pinyin/initials interpretation of continuous input.
     *
     * Full syllables carry stronger evidence than one-letter initials; ties
     * prefer fewer segments and then deterministic lexical order.
     */
    fun segmentMixed(value: String): MixedPinyinPath? =
        segmentMixedPaths(value, maxPaths = 1).firstOrNull()

    /**
     * Selects the bounded mixed path that agrees with a concrete candidate.
     * Candidate-bar formatting can therefore explain the decoder's chosen
     * syllable boundaries without changing the raw composing transaction.
     */
    fun segmentMixed(
        value: String,
        canonicalPinyin: String?,
        canonicalInitials: String?,
    ): MixedPinyinPath? =
        segmentMixedPaths(value, MAX_CANDIDATE_MATCH_PATHS)
            .firstOrNull { path ->
                matchesCanonical(path, canonicalPinyin, canonicalInitials)
            }

    /**
     * Bounded variant used by the decoder when a spelling has more than one
     * plausible full-syllable/initial interpretation.
     */
    internal fun segmentMixedPaths(value: String, maxPaths: Int): List<MixedPinyinPath> {
        if (maxPaths <= 0) return emptyList()
        val parsed = parseMixedInput(value)
        val normalized = parsed.code
        if (normalized.isEmpty() || normalized.length > MAX_SEGMENT_INPUT_LENGTH) {
            return emptyList()
        }
        val paths = arrayOfNulls<MutableList<MixedPathState>>(normalized.length + 1)
        paths[0] = mutableListOf(
            MixedPathState(emptyList(), abbreviatedSyllables = 0, fullCharacters = 0),
        )
        normalized.indices.forEach { offset ->
            val states = paths[offset] ?: return@forEach
            val syllables = syllablesByInitial[normalized[offset]].orEmpty()
            states.forEach { state ->
                syllables.forEach { syllable ->
                    val end = offset + syllable.length
                    if (
                        end <= normalized.length &&
                        !crossesForcedJoint(parsed.forcedJoints, offset, end) &&
                        normalized.regionMatches(offset, syllable, 0, syllable.length)
                    ) {
                        retainMixedPath(
                            paths,
                            end,
                            state.append(syllable, abbreviated = false),
                            maxPaths,
                        )
                    }
                }
                readableInitialUnit(normalized, offset)?.let { initialUnit ->
                    if (crossesForcedJoint(parsed.forcedJoints, offset, offset + initialUnit.length)) {
                        return@let
                    }
                    retainMixedPath(
                        paths,
                        offset + initialUnit.length,
                        state.append(initialUnit, abbreviated = true),
                        maxPaths,
                    )
                }
                if (syllables.any { it.length > 1 }) {
                    retainMixedPath(
                        paths,
                        offset + 1,
                        state.append(normalized[offset].toString(), abbreviated = true),
                        maxPaths,
                    )
                }
                incompleteTail(normalized, offset, syllables)?.let { tail ->
                    if (crossesForcedJoint(parsed.forcedJoints, offset, normalized.length)) {
                        return@let
                    }
                    retainMixedPath(
                        paths,
                        normalized.length,
                        state.append(tail, abbreviated = true),
                        maxPaths,
                    )
                }
            }
        }
        return paths.last()
            .orEmpty()
            .sortedWith(MIXED_PATH_ORDER)
            .take(maxPaths)
            .map { MixedPinyinPath(it.segments) }
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

    private data class MixedPathState(
        val segments: List<MixedPinyinSegment>,
        val abbreviatedSyllables: Int,
        val fullCharacters: Int,
    ) {
        fun append(code: String, abbreviated: Boolean): MixedPathState =
            MixedPathState(
                segments = segments + MixedPinyinSegment(code, abbreviated),
                abbreviatedSyllables = abbreviatedSyllables + if (abbreviated) 1 else 0,
                fullCharacters = fullCharacters + if (abbreviated) 0 else code.length,
            )
    }

    private fun retainMixedPath(
        paths: Array<MutableList<MixedPathState>?>,
        end: Int,
        candidate: MixedPathState,
        maxPaths: Int,
    ) {
        val retained = paths[end] ?: ArrayList<MixedPathState>(maxPaths + 1).also {
            paths[end] = it
        }
        if (retained.any { it.segments == candidate.segments }) return
        retained += candidate
        if (retained.size > maxPaths) {
            retained.sortWith(MIXED_PATH_ORDER)
            retained.subList(maxPaths, retained.size).clear()
        }
    }

    private fun readableInitialUnit(code: String, offset: Int): String? {
        if (offset + 2 > code.length) return null
        val value = code.substring(offset, offset + 2)
        if (value !in READABLE_INITIAL_UNITS) return null
        return value.takeIf { syllablesStartingWith(it).isNotEmpty() }
    }

    private fun incompleteTail(
        code: String,
        offset: Int,
        syllables: List<String>,
    ): String? {
        val remaining = code.length - offset
        if (remaining < 2) return null
        val tail = code.substring(offset)
        return tail.takeIf { prefix ->
            syllables.any { it.length > prefix.length && it.startsWith(prefix) }
        }
    }

    private data class ParsedMixedInput(
        val code: String,
        val forcedJoints: BooleanArray,
    )

    private fun parseMixedInput(value: String): ParsedMixedInput {
        val code = StringBuilder(value.length)
        val jointOffsets = ArrayList<Int>()
        var pendingJoint = false
        value.forEach { character ->
            when {
                character == '\'' -> pendingJoint = code.isNotEmpty()
                character.lowercaseChar() in 'a'..'z' -> {
                    if (pendingJoint && code.isNotEmpty()) jointOffsets += code.length
                    code.append(character.lowercaseChar())
                    pendingJoint = false
                }
            }
        }
        val joints = BooleanArray(code.length + 1)
        jointOffsets.forEach { joints[it] = true }
        return ParsedMixedInput(code.toString(), joints)
    }

    private fun crossesForcedJoint(
        forcedJoints: BooleanArray,
        start: Int,
        end: Int,
    ): Boolean {
        for (joint in (start + 1) until end) {
            if (forcedJoints[joint]) return true
        }
        return false
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
        private val MIXED_PATH_ORDER =
            compareBy<MixedPathState> { it.abbreviatedSyllables }
                .thenByDescending { it.fullCharacters }
                .thenBy { it.segments.size }
                .thenBy { state ->
                    state.segments.joinToString(separator = "'") { it.code }
                }

        fun normalize(value: String): String {
            if (value.all { it in 'a'..'z' }) return value
            return buildString(value.length) {
                value.forEach { character ->
                    val lower = character.lowercaseChar()
                    if (lower in 'a'..'z') append(lower)
                }
            }
        }

        private const val MAX_SEGMENT_INPUT_LENGTH = 96
        private const val MAX_PREFIX_BOUNDARIES = 8
        private const val MAX_CANDIDATE_MATCH_PATHS = 4
        private const val MATCH_UNKNOWN: Byte = -1
        private const val MATCH_FALSE: Byte = 0
        private const val MATCH_TRUE: Byte = 1
        private val READABLE_INITIAL_UNITS = setOf("zh", "ch", "sh")
    }
}
