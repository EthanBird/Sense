package io.github.ethanbird.senseime.ui

/** Keeps candidate glyph descent away from the clipped lower edge. */
internal object CandidateTextVerticalPolicy {
    fun centerY(top: Float, bottom: Float, density: Float): Float {
        require(bottom >= top)
        require(density > 0f)
        val safetyInset = density * 1.5f
        return ((top + bottom) / 2f - safetyInset).coerceAtLeast(top)
    }
}
