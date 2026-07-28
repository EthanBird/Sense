package io.github.ethanbird.senseime.ui

/**
 * Density-resolved geometry shared by keyboard layout and rendering.
 *
 * Keeping the values in one immutable object prevents individual panels from
 * quietly drifting to different spacing rules. The keyboard's overall height
 * remains controlled independently by [KeyboardSizeProfile].
 */
internal class KeyboardMetrics private constructor(
    val density: Float,
    val candidateHeight: Float,
    val toolbarHeight: Float,
    val systemBarHeight: Float,
    val keyGap: Float,
    val horizontalPadding: Float,
    val keyRadius: Float,
    val candidateTextInset: Float,
    val candidateGap: Float,
    val candidateMinimumWidth: Float,
    val candidateControlWidth: Float,
    val expandedCandidateRowHeight: Float,
    val expandedCandidatePagerHeight: Float,
) {
    fun dp(value: Float): Float = value * density

    companion object {
        fun fromDensity(density: Float): KeyboardMetrics {
            require(density.isFinite() && density > 0f)
            return KeyboardMetrics(
                density = density,
                candidateHeight = 45f * density,
                toolbarHeight = 42f * density,
                systemBarHeight = 52f * density,
                keyGap = 5f * density,
                horizontalPadding = 6f * density,
                keyRadius = 8f * density,
                candidateTextInset = 9f * density,
                candidateGap = 3f * density,
                candidateMinimumWidth = 44f * density,
                candidateControlWidth = 44f * density,
                expandedCandidateRowHeight = 42f * density,
                expandedCandidatePagerHeight = 38f * density,
            )
        }
    }
}
