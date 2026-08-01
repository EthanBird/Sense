package io.github.ethanbird.senseime.core

/** Revisioned Wubi86 code transaction. `z` is reserved for the reverse-lookup state. */
data class WubiComposition(
    val code: String = "",
    /** Null means direct Wubi; non-null is the Pinyin suffix after the reserved `z` prefix. */
    val reversePinyin: String? = null,
    val revision: Long = 0,
) {
    init {
        require(code.all(::isCodeCharacter) && code.length <= MAX_CODE_LENGTH)
        require(reversePinyin == null || code.isEmpty())
        require(reversePinyin?.all { it in 'a'..'z' } != false)
    }

    val isReverseLookup: Boolean
        get() = reversePinyin != null

    val visibleCode: String
        get() = reversePinyin?.let { "z$it" } ?: code

    val isAtMaximumLength: Boolean
        get() = code.length == MAX_CODE_LENGTH

    fun type(character: Char): WubiComposition {
        val normalized = character.lowercaseChar()
        if (isReverseLookup) {
            if (normalized !in 'a'..'z') return this
            val query = checkNotNull(reversePinyin)
            if (query.length >= MAX_REVERSE_PINYIN_LENGTH) return this
            return copy(reversePinyin = query + normalized, revision = nextRevision())
        }
        if (code.isEmpty() && normalized == REVERSE_LOOKUP_PREFIX) {
            return copy(reversePinyin = "", revision = nextRevision())
        }
        if (!isCodeCharacter(normalized) || isAtMaximumLength) return this
        return copy(code = code + normalized, revision = nextRevision())
    }

    fun backspace(): WubiComposition = when {
        isReverseLookup && checkNotNull(reversePinyin).isNotEmpty() ->
            copy(reversePinyin = checkNotNull(reversePinyin).dropLast(1), revision = nextRevision())
        isReverseLookup -> copy(reversePinyin = null, revision = nextRevision())
        code.isNotEmpty() -> copy(code = code.dropLast(1), revision = nextRevision())
        else -> this
    }

    fun reset(): WubiComposition =
        if (visibleCode.isEmpty()) this else WubiComposition(revision = nextRevision())

    private fun nextRevision(): Long = if (revision == Long.MAX_VALUE) 1L else revision + 1L

    companion object {
        const val MAX_CODE_LENGTH = 4
        const val MAX_REVERSE_PINYIN_LENGTH = PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH
        const val REVERSE_LOOKUP_PREFIX = 'z'

        fun isCodeCharacter(value: Char): Boolean = value in 'a'..'y'
    }
}
