package io.github.ethanbird.senseime.core

/**
 * One clickable canonical spelling for the next unresolved span of a T9 composition.
 *
 * [previewPinyin] explains the best complete path that supports the choice, while selection locks
 * only this edge. The next call can therefore advance one syllable at a time and backspace can
 * undo one explicit choice at a time without reconstructing [sourceDigits].
 */
data class T9PinyinChoice(
    val stableId: String,
    val sourceDigits: String,
    val sourceRevision: Long,
    val canonicalPinyin: String,
    val digitStart: Int,
    val digitEnd: Int,
    val kind: T9PinyinSegmentKind,
    val previewPinyin: String,
    /** Number of paths in the bounded search beam that support this exact edge. */
    val supportingPathCount: Int,
) {
    init {
        require(sourceDigits.all { it in '2'..'9' })
        require(digitStart >= 0 && digitEnd in (digitStart + 1)..sourceDigits.length)
        require(canonicalPinyin.isNotEmpty() && canonicalPinyin.all { it in 'a'..'z' })
        require(previewPinyin.isNotEmpty())
        require(supportingPathCount > 0)
    }

    internal val lockedEdge: T9LockedEdge
        get() = T9LockedEdge(digitStart, digitEnd, canonicalPinyin)
}

/** Stable, Android-free source for the dynamic pinyin choices displayed beside a T9 keyboard. */
fun interface T9PinyinChoiceSource {
    fun choices(composition: T9Composition, maxChoices: Int): List<T9PinyinChoice>
}

/** Reuses an already generated path beam so decode and choice publication never traverse twice. */
internal fun buildT9PinyinChoices(
    composition: T9Composition,
    paths: List<T9PinyinPath>,
    maxChoices: Int,
): List<T9PinyinChoice> {
    if (maxChoices <= 0 || composition.rawDigits.isEmpty() || paths.isEmpty()) return emptyList()
    val unresolvedStart = nextUnresolvedDigit(composition)
    if (unresolvedStart >= composition.rawDigits.length) return emptyList()
    val choices = LinkedHashMap<T9ChoiceKey, T9ChoiceAccumulator>()
    paths.forEachIndexed { pathIndex, path ->
        val segment = path.segments.firstOrNull { it.digitStart == unresolvedStart }
            ?: return@forEachIndexed
        if (!segment.matches(composition.rawDigits)) return@forEachIndexed
        val key = T9ChoiceKey(
            digitStart = segment.digitStart,
            digitEnd = segment.digitEnd,
            spelling = segment.spelling,
            kind = segment.kind,
        )
        val previous = choices[key]
        if (previous == null) {
            choices[key] = T9ChoiceAccumulator(segment, path, pathIndex, support = 1)
        } else {
            previous.support += 1
        }
    }
    return choices.values
        .sortedWith(T9_CHOICE_ORDER)
        .take(minOf(maxChoices, MAX_T9_CHOICE_LIMIT))
        .map { value ->
            val segment = value.segment
            T9PinyinChoice(
                stableId = buildT9ChoiceId(composition.rawDigits, segment),
                sourceDigits = composition.rawDigits,
                sourceRevision = composition.revision,
                canonicalPinyin = segment.spelling,
                digitStart = segment.digitStart,
                digitEnd = segment.digitEnd,
                kind = segment.kind,
                previewPinyin = value.preview.formatted,
                supportingPathCount = value.support,
            )
        }
}

/**
 * Injectable path sources are also used by deterministic decoder tests and by downstream
 * integrations. Keep malformed or stale edges out of the public choice surface instead of
 * letting one foreign path invalidate the complete decoding result.
 */
private fun T9PinyinSegment.matches(digits: String): Boolean {
    if (digitStart !in digits.indices || digitEnd !in (digitStart + 1)..digits.length) return false
    if (spelling.isEmpty() || spelling.any { it !in 'a'..'z' }) return false
    return T9SyllableIndex.digitsFor(spelling) == digits.substring(digitStart, digitEnd)
}

private fun nextUnresolvedDigit(composition: T9Composition): Int {
    if (composition.lockedEdges.isEmpty()) return 0
    val byStart = composition.lockedEdges.associateBy(T9LockedEdge::digitStart)
    var offset = 0
    while (offset < composition.rawDigits.length) {
        offset = byStart[offset]?.digitEnd ?: break
    }
    return offset
}

private fun buildT9ChoiceId(digits: String, segment: T9PinyinSegment): String =
    "$digits:${segment.digitStart}-${segment.digitEnd}:" +
        "${segment.kind.name.lowercase()}:${segment.spelling}"

private data class T9ChoiceKey(
    val digitStart: Int,
    val digitEnd: Int,
    val spelling: String,
    val kind: T9PinyinSegmentKind,
)

private data class T9ChoiceAccumulator(
    val segment: T9PinyinSegment,
    val preview: T9PinyinPath,
    val firstPathIndex: Int,
    var support: Int,
)

private val T9_CHOICE_ORDER =
    compareBy<T9ChoiceAccumulator> { value ->
        when (value.segment.kind) {
            T9PinyinSegmentKind.SYLLABLE -> 0
            T9PinyinSegmentKind.INITIAL -> 1
            T9PinyinSegmentKind.INCOMPLETE -> 2
        }
    }
        .thenByDescending { it.segment.digitEnd - it.segment.digitStart }
        .thenByDescending { it.support }
        .thenBy { it.firstPathIndex }
        .thenBy { it.segment.spelling }

private const val MAX_T9_CHOICE_LIMIT = 32
