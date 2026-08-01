package io.github.ethanbird.senseime.ui

/** Defensive UI boundary for the configurable T9 side-symbol rail. */
internal object T9SideSymbolPolicy {
    const val MAX_SYMBOLS = 32
    private const val MAX_CODE_POINTS = 4
    private const val MAX_UTF16_UNITS = 16

    val DEFAULT_SYMBOLS: List<String> = listOf(
        "，", "。", "？", "！", "、", "；", "：", "“", "”", "‘",
        "’", "（", "）", "【", "】", "《", "》", "…", "—", "·",
    )

    fun normalize(symbols: List<String>): List<String> {
        if (symbols.isEmpty()) return DEFAULT_SYMBOLS
        val output = ArrayList<String>(minOf(symbols.size, MAX_SYMBOLS))
        val seen = HashSet<String>(minOf(symbols.size, MAX_SYMBOLS))
        for (raw in symbols) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val codePointCount = trimmed.codePointCount(0, trimmed.length)
            val codePointEnd = trimmed.offsetByCodePoints(0, minOf(codePointCount, MAX_CODE_POINTS))
            val normalized = trimmed.substring(0, minOf(codePointEnd, MAX_UTF16_UNITS))
            if (normalized.isNotEmpty() && seen.add(normalized)) output += normalized
            if (output.size == MAX_SYMBOLS) break
        }
        return if (output.isEmpty()) DEFAULT_SYMBOLS else output
    }
}
