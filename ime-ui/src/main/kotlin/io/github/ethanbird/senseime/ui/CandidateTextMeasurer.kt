package io.github.ethanbird.senseime.ui

/**
 * Text measurement seam for candidate geometry.
 *
 * CandidatePanel stays Android-free; the production adapter uses Paint while
 * JVM tests can supply deterministic widths.
 */
internal fun interface CandidateTextMeasurer {
    fun measure(text: String, textSizePx: Float): Float
}
