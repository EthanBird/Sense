package io.github.ethanbird.senseime.core

data class CommittedTextUnit(
    val text: String,
    val canonicalPinyin: String?,
    val editorSessionId: Long,
    val committedAtMillis: Long,
    val start: Int,
    val endExclusive: Int,
)

data class AssociationObservation(
    val context: String,
    val nextText: String,
)

data class CommitSequenceOutcome(
    val association: AssociationObservation?,
)

/**
 * Tracks one reliable adjacent lexical boundary. Cross-commit text is learned as
 * an association only; arbitrary sentence fragments are never promoted into the
 * exact user lexicon.
 */
class CommitSequenceTracker(
    private val maximumGapMillis: Long = DEFAULT_MAXIMUM_GAP_MILLIS,
) {
    private var previous: CommittedTextUnit? = null

    init {
        require(maximumGapMillis >= 0L)
    }

    fun record(unit: CommittedTextUnit): CommitSequenceOutcome {
        if (!isHanUnit(unit.text)) {
            previous = null
            return CommitSequenceOutcome(null)
        }
        val normalized = unit.copy(canonicalPinyin = normalizePinyin(unit.canonicalPinyin))
        val prior = previous?.takeIf { value ->
            value.editorSessionId == normalized.editorSessionId &&
                normalized.committedAtMillis >= value.committedAtMillis &&
                normalized.committedAtMillis - value.committedAtMillis <= maximumGapMillis &&
                value.start >= 0 &&
                value.endExclusive > value.start &&
                normalized.start >= 0 &&
                normalized.endExclusive > normalized.start &&
                value.endExclusive == normalized.start
        }
        previous = normalized
        return CommitSequenceOutcome(
            association = prior?.let { AssociationObservation(it.text, normalized.text) },
        )
    }

    fun context(editorSessionId: Long, cursor: Int? = null): String? = previous
        ?.takeIf {
            it.editorSessionId == editorSessionId &&
                (cursor == null || cursor >= 0 && it.endExclusive == cursor)
        }
        ?.text

    fun breakSequence() {
        previous = null
    }

    private fun normalizePinyin(value: String?): String? = value
        ?.let(PinyinSyllableSegmenter::normalize)
        ?.takeIf(String::isNotEmpty)

    private fun isHanUnit(value: String): Boolean {
        if (value.isEmpty()) return false
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private companion object {
        const val DEFAULT_MAXIMUM_GAP_MILLIS = 120_000L
    }
}
