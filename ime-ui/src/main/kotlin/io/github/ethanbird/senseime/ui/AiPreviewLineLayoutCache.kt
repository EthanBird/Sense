package io.github.ethanbird.senseime.ui

/**
 * Reusable primitive line ranges for the AI preview.
 *
 * Pointer movement changes only lock progress, so the preview String instance is retained and the
 * 4K text does not get wrapped again on every frame. Arrays grow only when a genuinely larger
 * layout arrives; no Pair/List object is created per line.
 */
internal class AiPreviewLineLayoutCache(
    initialLineCapacity: Int = 32,
) {
    private var cachedText: String? = null
    private var cachedWidthBits = 0
    private var cachedTextSizeBits = 0
    private var starts = IntArray(initialLineCapacity.coerceAtLeast(1))
    private var ends = IntArray(starts.size)

    var lineCount: Int = 0
        private set

    fun ensure(
        text: String,
        width: Float,
        textSize: Float,
        breakText: (text: String, start: Int, end: Int, maximumWidth: Float) -> Int,
    ): Boolean {
        val widthBits = width.toRawBits()
        val textSizeBits = textSize.toRawBits()
        if (
            cachedText === text &&
            cachedWidthBits == widthBits &&
            cachedTextSizeBits == textSizeBits
        ) {
            return false
        }
        cachedText = text
        cachedWidthBits = widthBits
        cachedTextSizeBits = textSizeBits
        lineCount = 0

        var index = 0
        while (index < text.length) {
            if (text[index] == '\n') {
                append(index, index)
                index++
                continue
            }
            val newline = text.indexOf('\n', index).let {
                if (it < 0) text.length else it
            }
            // Reuse the same explicit-line boundary for every wrapped visual line.
            // Re-running indexOf from each visual line makes a 4K line quadratic.
            while (index < newline) {
                var count = breakText(text, index, newline, width)
                if (count <= 0) count = 1
                if (
                    index + count < text.length &&
                    text[index + count - 1].isHighSurrogate() &&
                    text[index + count].isLowSurrogate()
                ) {
                    count--
                }
                if (count <= 0) count = minOf(2, text.length - index)
                append(index, index + count)
                index += count
            }
            if (index == newline) index++
        }
        return true
    }

    fun startAt(lineIndex: Int): Int {
        require(lineIndex in 0 until lineCount)
        return starts[lineIndex]
    }

    fun endAt(lineIndex: Int): Int {
        require(lineIndex in 0 until lineCount)
        return ends[lineIndex]
    }

    private fun append(start: Int, end: Int) {
        ensureCapacity(lineCount + 1)
        starts[lineCount] = start
        ends[lineCount] = end
        lineCount++
    }

    private fun ensureCapacity(required: Int) {
        if (required <= starts.size) return
        val next = maxOf(required, starts.size * 2)
        starts = starts.copyOf(next)
        ends = ends.copyOf(next)
    }
}
