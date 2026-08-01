package io.github.ethanbird.senseime.ui

/** Stable first-root legends for the official Wubi86 key zones. */
internal object Wubi86KeyLegend {
    fun forKey(character: Char): String? = when (character.lowercaseChar()) {
        'q' -> "金"
        'w' -> "人"
        'e' -> "月"
        'r' -> "白"
        't' -> "禾"
        'y' -> "言"
        'u' -> "立"
        'i' -> "水"
        'o' -> "火"
        'p' -> "之"
        'a' -> "工"
        's' -> "木"
        'd' -> "大"
        'f' -> "土"
        'g' -> "王"
        'h' -> "目"
        'j' -> "日"
        'k' -> "口"
        'l' -> "田"
        'x' -> "纟"
        'c' -> "又"
        'v' -> "女"
        'b' -> "子"
        'n' -> "已"
        'm' -> "山"
        'z' -> "反查"
        else -> null
    }
}
