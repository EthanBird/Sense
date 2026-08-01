package io.github.ethanbird.senseime.core

data class Candidate(
    val text: String,
    val score: Float = 0f,
    val canonicalPinyin: String? = null,
    val matchKind: CandidateMatchKind = CandidateMatchKind.BASE_EXACT,
    val canonicalInitials: String? = null,
    /** Scheme-native spelling, for example a Wubi code. */
    val canonicalCode: String? = null,
)

enum class CandidateMatchKind {
    BASE_EXACT,
    BASE_COMPOSED,
    BASE_HYBRID,
    BASE_PREFIX,
    BASE_INITIALS,
    CORRECTED,
    ENGLISH_EXACT,
    ENGLISH_PREFIX,
    USER_FULL,
    USER_INITIALS,
    WUBI_EXACT,
    WUBI_COMPLETION,
}

data class InputState(
    val composing: String = "",
    val revision: Long = 0,
    val committed: List<String> = emptyList(),
)

sealed interface InputAction {
    data class Type(val character: Char) : InputAction
    data object Backspace : InputAction
    data class Commit(val text: String) : InputAction
    data object Reset : InputAction
}

data class EditorTransaction(
    val id: Long,
    val sessionId: Long,
    val revision: Long,
    val selectionStart: Int,
    val selectionEnd: Int,
    val selectedText: String?,
    val beforeCursor: String,
    val afterCursor: String,
    val composingText: String?,
)

interface InputDecoder {
    fun decode(composing: String, limit: Int = 5): List<Candidate>
}

/**
 * Marker contract for decoders whose results are already filtered, deduplicated,
 * score-ranked and bounded by the requested limit.
 *
 * Adapters may use this contract to avoid running the same candidate set through
 * [CandidateRanker] a second time when no additional evidence is present. When
 * combined with [ContextualInputDecoder] or [ProgressivePrefixProbeDecoder], the
 * same guarantee applies to every decode seam exposed by those interfaces.
 */
interface RankedCandidateDecoder : InputDecoder

/** Optional boundary-aware ranking for a previously selected composing segment. */
interface ContextualInputDecoder : InputDecoder {
    fun decodeAfter(previousCodePoint: Int, composing: String, limit: Int = 5): List<Candidate>
}

/** Chinese-only recall seam for schemes whose key stream must never summon the English lexicon. */
interface ChineseOnlyInputDecoder : InputDecoder {
    fun decodeChineseOnly(composing: String, limit: Int = 5): List<Candidate>

    fun decodeChineseOnlyAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int = 5,
    ): List<Candidate> = decodeChineseOnly(composing, limit)
}

/**
 * Chinese-only decoding for a spelling that has already been validated by an input scheme.
 *
 * T9 paths come from the canonical syllable trie, so running the typo-correction graph again
 * only repeats work and can introduce candidates that contradict the numeric path. Implementors
 * retain exact, composed, hybrid, initials and personalization recall while omitting edit paths.
 */
interface CanonicalChineseOnlyInputDecoder : ChineseOnlyInputDecoder {
    fun decodeCanonicalChineseOnly(composing: String, limit: Int = 5): List<Candidate>

    fun decodeCanonicalChineseOnlyAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int = 5,
    ): List<Candidate> = decodeCanonicalChineseOnly(composing, limit)
}

/**
 * Optional low-cost lexical seam for an already validated canonical spelling.
 *
 * It returns dictionary, hybrid, initials and personalization evidence without constructing a
 * fallback sentence composition. Ambiguous input schemes can probe a wider bounded path beam,
 * then run the complete canonical decoder only for retained winners or a structural fallback.
 */
interface CanonicalChineseLexicalProbeDecoder : InputDecoder {
    fun probeCanonicalChineseOnly(composing: String, limit: Int = 1): List<Candidate>

    fun probeCanonicalChineseOnlyAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int = 1,
    ): List<Candidate> = probeCanonicalChineseOnly(composing, limit)
}

/**
 * Optional low-allocation seam for progressive prefix selection.
 *
 * A selectable prefix is already a concrete portion of the user's spelling,
 * so callers need canonical, segmented and completion recall but not a second
 * spelling-correction graph for every prefix boundary.
 */
interface ProgressivePrefixProbeDecoder : InputDecoder {
    fun decodePrefixProbe(composing: String, limit: Int = 5): List<Candidate>

    fun decodePrefixProbeAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int = 5,
    ): List<Candidate> = decodePrefixProbe(composing, limit)
}
