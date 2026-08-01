package io.github.ethanbird.senseime.ui

enum class SwipeCharacterMode {
    CHINESE,
    ENGLISH,
}

/** Up-swipe characters shown as the small hint above every alphabet key. */
object SwipeCharacterMap {
    private val chineseValues = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "~", 's' to "！", 'd' to "@", 'f' to "#", 'g' to "%",
        'h' to "“", 'j' to "”", 'k' to "*", 'l' to "？",
        'z' to "（", 'x' to "）", 'c' to "-", 'v' to "_", 'b' to "：",
        'n' to "；", 'm' to "、",
    )

    /** ASCII-only counterpart used by the English keyboard. */
    private val englishValues = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "~", 's' to "!", 'd' to "@", 'f' to "#", 'g' to "%",
        'h' to "\"", 'j' to "'", 'k' to "*", 'l' to "?",
        'z' to "(", 'x' to ")", 'c' to "-", 'v' to "_", 'b' to ":",
        'n' to ";", 'm' to "/",
    )

    /**
     * Returns the character produced by an upward flick.
     *
     * Chinese is the default because a composing pinyin keyboard must not
     * silently insert ASCII sentence punctuation. Callers rendering the
     * English keyboard should pass [SwipeCharacterMode.ENGLISH].
     */
    fun forKey(
        code: Int,
        mode: SwipeCharacterMode = SwipeCharacterMode.CHINESE,
    ): String? {
        if (code !in 'A'.code..'z'.code) return null
        val letter = code.toChar().lowercaseChar()
        return when (mode) {
            SwipeCharacterMode.CHINESE -> chineseValues[letter]
            SwipeCharacterMode.ENGLISH -> englishValues[letter]
        }
    }

    val size: Int
        get() = chineseValues.size
}
