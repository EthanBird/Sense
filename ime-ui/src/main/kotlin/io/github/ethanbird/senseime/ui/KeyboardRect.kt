package io.github.ethanbird.senseime.ui

/**
 * Android-free rectangle used by immutable keyboard scene geometry.
 *
 * The View converts a rectangle to Android drawing/touch primitives only at
 * the platform seam. Keeping scene geometry independent from [android.graphics.RectF]
 * makes layout and hit testing available to ordinary JVM tests.
 */
internal data class KeyboardRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f

    val isEmpty: Boolean
        get() = left >= right || top >= bottom

    /** Matches Android RectF's inclusive-left/top, exclusive-right/bottom rule. */
    fun contains(x: Float, y: Float): Boolean =
        left < right &&
            top < bottom &&
            x >= left &&
            x < right &&
            y >= top &&
            y < bottom
}
