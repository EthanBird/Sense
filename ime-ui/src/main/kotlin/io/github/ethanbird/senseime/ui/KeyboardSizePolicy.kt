package io.github.ethanbird.senseime.ui

/**
 * The single height policy consumed by the IME host, debug host and keyboard View.
 *
 * A profile is deliberately independent from Android resources so it can be
 * persisted and tested as plain data. Key geometry always consumes the actual
 * measured viewport; changing this profile therefore does not fork layout math.
 */
data class KeyboardSizeProfile(
    val portraitHeightDp: Float = DEFAULT_PORTRAIT_HEIGHT_DP,
    val landscapeHeightDp: Float = DEFAULT_LANDSCAPE_HEIGHT_DP,
) {
    init {
        require(portraitHeightDp.isFinite() && portraitHeightDp in MIN_HEIGHT_DP..MAX_HEIGHT_DP)
        require(landscapeHeightDp.isFinite() && landscapeHeightDp in MIN_HEIGHT_DP..MAX_HEIGHT_DP)
    }

    fun preferredHeightDp(isLandscape: Boolean): Float =
        if (isLandscape) landscapeHeightDp else portraitHeightDp

    fun preferredHeightPx(
        isLandscape: Boolean,
        density: Float,
    ): Int {
        require(density.isFinite() && density > 0f)
        return (preferredHeightDp(isLandscape) * density).toInt()
    }

    companion object {
        const val DEFAULT_PORTRAIT_HEIGHT_DP = 358f
        const val DEFAULT_LANDSCAPE_HEIGHT_DP = 258f
        /**
         * Smallest height supported by every built-in panel. At 240dp the
         * compact QWERTY rows and voice waveform still retain positive,
         * ordered geometry; hosts may impose a larger platform constraint.
         */
        const val MIN_HEIGHT_DP = 240f
        const val MAX_HEIGHT_DP = 640f

        val DEFAULT = KeyboardSizeProfile()
    }
}
