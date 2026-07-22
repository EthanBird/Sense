package io.github.ethanbird.senseime.ui

/** Up-swipe characters shown as the small hint above every alphabet key. */
object SwipeCharacterMap {
    private val values = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "~", 's' to "!", 'd' to "@", 'f' to "#", 'g' to "%",
        'h' to "“", 'j' to "”", 'k' to "*", 'l' to "?",
        'z' to "（", 'x' to "）", 'c' to "-", 'v' to "_", 'b' to "：",
        'n' to "；", 'm' to "、",
    )

    fun forKey(code: Int): String? {
        if (code !in 'A'.code..'z'.code) return null
        return values[code.toChar().lowercaseChar()]
    }

    val size: Int
        get() = values.size
}
