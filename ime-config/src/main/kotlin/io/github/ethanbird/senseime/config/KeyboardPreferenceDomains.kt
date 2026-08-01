package io.github.ethanbird.senseime.config

/** Stable persisted bounds shared by Settings and the IME runtime projection. */
object KeyboardHeightPolicy {
    const val DEFAULT_PORTRAIT_HEIGHT_DP = 358
    const val DEFAULT_LANDSCAPE_HEIGHT_DP = 258
    const val MIN_HEIGHT_DP = 240
    const val MAX_HEIGHT_DP = 640

    fun requireValid(heightDp: Int, fieldName: String = "keyboardHeightDp"): Int {
        require(heightDp in MIN_HEIGHT_DP..MAX_HEIGHT_DP) {
            "$fieldName must be in $MIN_HEIGHT_DP..$MAX_HEIGHT_DP"
        }
        return heightDp
    }
}

/** Dependency-neutral route contract shared by the isolated IME and Settings Activity. */
object ImeSettingsRoute {
    const val EXTRA_INITIAL_SECTION =
        "io.github.ethanbird.senseime.extra.INITIAL_SETTINGS_SECTION"
    const val KEYBOARD_SECTION = "KEYBOARD"
}

/** Canonical persistence policy for the user-editable idle T9 symbol rail. */
object T9SideSymbolPolicy {
    const val MAX_SYMBOL_COUNT = 32
    const val MAX_CODE_POINTS_PER_SYMBOL = 4
    const val MAX_CODE_UNITS_PER_SYMBOL = 16

    val DEFAULT_SYMBOLS: List<String> = listOf(
        "，", "。", "？", "！", "、", "；", "：", "…", "—", "·",
        "～", "@", "#", "%", "&", "*", "（", "）", "《", "》",
    )

    /** Trims, removes empty/oversized entries, de-duplicates stably and applies the hard cap. */
    fun normalize(values: Iterable<String>): List<String> {
        val result = LinkedHashSet<String>(MAX_SYMBOL_COUNT)
        for (raw in values) {
            val value = raw.trim()
            if (value.isEmpty() || value.length > MAX_CODE_UNITS_PER_SYMBOL) continue
            val codePoints = value.codePointCount(0, value.length)
            if (codePoints !in 1..MAX_CODE_POINTS_PER_SYMBOL) continue
            if (!value.hasOnlyUnicodeScalarValues()) continue
            result += value
            if (result.size == MAX_SYMBOL_COUNT) break
        }
        return result.toList()
    }

    fun requireValid(values: List<String>): List<String> {
        require(values.isNotEmpty()) { "T9 side symbol list must not be empty" }
        require(values.size <= MAX_SYMBOL_COUNT) {
            "T9 side symbol list exceeds $MAX_SYMBOL_COUNT entries"
        }
        require(normalize(values) == values) { "T9 side symbol list is not canonical" }
        return values
    }

    /** Human-editable projection: whitespace separates short tokens; compact text is code-point based. */
    fun fromEditorText(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DEFAULT_SYMBOLS
        val whitespaceSeparated = trimmed.split(Regex("\\s+"))
        val raw = if (whitespaceSeparated.size > 1) {
            whitespaceSeparated
        } else {
            buildList {
                var offset = 0
                while (offset < trimmed.length) {
                    val codePoint = trimmed.codePointAt(offset)
                    add(String(Character.toChars(codePoint)))
                    offset += Character.charCount(codePoint)
                }
            }
        }
        return normalize(raw).ifEmpty { DEFAULT_SYMBOLS }
    }

    fun toEditorText(values: List<String>): String = requireValid(values).joinToString(" ")

    private fun String.hasOnlyUnicodeScalarValues(): Boolean {
        var offset = 0
        while (offset < length) {
            val codePoint = codePointAt(offset)
            if (codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) {
                return false
            }
            offset += Character.charCount(codePoint)
        }
        return true
    }
}
