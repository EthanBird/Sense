package io.github.ethanbird.senseime.core

/** A candidate that consumes only the first syllable(s) of [remainingPinyin]. */
data class PinyinPrefixCandidate(
    val candidate: Candidate,
    val consumedPinyin: String,
    val remainingPinyin: String,
)

/**
 * One immutable decoder result bound to a [PinyinComposition.revision].
 *
 * Whole candidates convert all pending pinyin. Prefix candidates let the UI
 * select a leading syllable without committing text to the editor.
 */
data class ProgressivePinyinDecoding(
    val revision: Long,
    val remainingPinyin: String,
    val wholeCandidates: List<Candidate>,
    val prefixCandidates: List<PinyinPrefixCandidate>,
)

data class AcceptedPinyinSegment(
    val text: String,
    val consumedPinyin: String,
)

/**
 * Revisioned, reversible composition state for partial pinyin selections.
 *
 * Accepted Chinese stays inside Android's composing span. For example,
 * accepting `pi -> 匹` from `pipei` produces visible text `匹pei`; it does not
 * call commitText. Enter can therefore emit [confirmRaw] (`匹pei`), while Space
 * can emit [confirmPrimary] (`匹配` when the primary candidate is `配`).
 */
data class PinyinComposition(
    val acceptedSegments: List<AcceptedPinyinSegment> = emptyList(),
    val remainingPinyin: String = "",
    val revision: Long = 0,
) {
    val acceptedText: String
        get() = acceptedSegments.joinToString(separator = "") { it.text }

    val visibleText: String
        get() = acceptedText + remainingPinyin

    val isComplete: Boolean
        get() = remainingPinyin.isEmpty()

    fun type(character: Char): PinyinComposition {
        val normalized = character.lowercaseChar()
        if (normalized !in 'a'..'z') return this
        return copy(remainingPinyin = remainingPinyin + normalized, revision = revision + 1)
    }

    /** Deletes pending letters first; an empty tail rolls the latest prefix back to editable pinyin. */
    fun backspace(): PinyinComposition = when {
        remainingPinyin.isNotEmpty() -> copy(
            remainingPinyin = remainingPinyin.dropLast(1),
            revision = revision + 1,
        )

        acceptedSegments.isNotEmpty() -> {
            val restored = acceptedSegments.last().consumedPinyin
            copy(
                acceptedSegments = acceptedSegments.dropLast(1),
                remainingPinyin = restored,
                revision = revision + 1,
            )
        }

        else -> this
    }

    /** Applies only a result produced for the current revision and pending pinyin. */
    fun acceptPrefix(
        decodingRevision: Long,
        selection: PinyinPrefixCandidate,
    ): PinyinComposition {
        if (decodingRevision != revision) return this
        if (!remainingPinyin.startsWith(selection.consumedPinyin)) return this
        if (remainingPinyin.drop(selection.consumedPinyin.length) != selection.remainingPinyin) return this
        return copy(
            acceptedSegments = acceptedSegments + AcceptedPinyinSegment(
                text = selection.candidate.text,
                consumedPinyin = selection.consumedPinyin,
            ),
            remainingPinyin = selection.remainingPinyin,
            revision = revision + 1,
        )
    }

    /** Enter semantics: preserve all still-unconverted letters. */
    fun confirmRaw(): String = visibleText

    /** Space/whole-candidate semantics: convert the pending tail and prepend accepted text. */
    fun confirmPrimary(candidate: Candidate?): String = acceptedText + (candidate?.text ?: remainingPinyin)

    fun reset(): PinyinComposition = PinyinComposition(revision = revision + 1)
}

/** Optional progressive capability; ordinary [InputDecoder.decode] remains source-compatible. */
interface ProgressivePinyinDecoder : InputDecoder {
    fun decodeProgressively(
        composition: PinyinComposition,
        limit: Int = 5,
    ): ProgressivePinyinDecoding
}
