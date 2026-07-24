package io.github.ethanbird.senseime.brain.runtime

/** Keeps each streaming Binder message small without splitting a UTF-16 surrogate pair. */
object BrainIpcTextChunker {
    const val DEFAULT_MAX_CHARS = 2_048

    fun chunk(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<String> {
        require(maxChars > 0)
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxChars) return listOf(text)
        val result = ArrayList<String>((text.length + maxChars - 1) / maxChars)
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + maxChars)
            if (
                end < text.length &&
                end > start &&
                text[end - 1].isHighSurrogate() &&
                text[end].isLowSurrogate()
            ) {
                end -= 1
            }
            if (end == start) end = minOf(text.length, start + 2)
            result += text.substring(start, end)
            start = end
        }
        return result
    }
}
