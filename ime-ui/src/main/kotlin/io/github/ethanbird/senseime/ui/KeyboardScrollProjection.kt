package io.github.ethanbird.senseime.ui

/**
 * Maps stable content-space geometry to a scrolling panel viewport.
 */
internal object KeyboardScrollProjection {
    fun screenCoordinate(
        contentCoordinate: Float,
        offset: Float,
    ): Float = contentCoordinate - offset

    fun contentCoordinate(
        screenCoordinate: Float,
        offset: Float,
    ): Float = screenCoordinate + offset

    fun intersectsViewport(
        contentTop: Float,
        contentBottom: Float,
        offset: Float,
        viewportTop: Float,
        viewportBottom: Float,
    ): Boolean {
        val screenTop = screenCoordinate(contentTop, offset)
        val screenBottom = screenCoordinate(contentBottom, offset)
        return screenBottom > viewportTop && screenTop < viewportBottom
    }
}
