package io.github.ethanbird.senseime.ui

/**
 * Density-resolved geometry shared by keyboard layout and rendering.
 *
 * The shared instance changes only on configuration updates. Font-scaled chrome
 * and the host height use the same policy, leaving the letter rows unchanged.
 */
internal class KeyboardMetrics private constructor(
    val density: Float,
    val systemBarHeight: Float,
    val keyGap: Float,
    val horizontalPadding: Float,
    val keyRadius: Float,
    val candidateTextInset: Float,
    val candidateGap: Float,
    val candidateMinimumWidth: Float,
    val candidateControlWidth: Float,
    val expandedCandidateStatusHeight: Float,
) {
    var chromeScale: Float = 1f
        private set
    val candidateHeight: Float get() = dp(45f) * chromeScale
    val toolbarHeight: Float get() = dp(42f) * chromeScale
    // Expanded mode has a one-line header, unlike the two-line compact strip.
    // Keep its padding fixed rather than doubling all empty space at 200%.
    val expandedCandidateHeaderHeight: Float get() = dp(maxOf(45f, 26f * chromeScale))
    val expandedCandidateRowHeight: Float get() = dp(42f + 19f * (chromeScale - 1f))

    fun updateFontScale(fontScale: Float) {
        chromeScale = KeyboardFontGeometry.chromeScale(fontScale)
    }

    fun dp(value: Float): Float = value * density

    companion object {
        fun fromDensity(density: Float, fontScale: Float = 1f): KeyboardMetrics {
            require(density.isFinite() && density > 0f)
            return KeyboardMetrics(
                density = density,
                systemBarHeight = 52f * density,
                keyGap = 5f * density,
                horizontalPadding = 6f * density,
                keyRadius = 8f * density,
                candidateTextInset = 9f * density,
                candidateGap = 3f * density,
                candidateMinimumWidth = 44f * density,
                candidateControlWidth = 44f * density,
                expandedCandidateStatusHeight = 38f * density,
            ).also { it.updateFontScale(fontScale) }
        }
    }
}
