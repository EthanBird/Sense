package io.github.ethanbird.senseime.ui

/**
 * Configuration snapshot used by the renderer.
 *
 * The View updates it only when Android publishes a configuration change,
 * avoiding repeated Resources/Configuration reads for every key color.
 */
internal class KeyboardPalette(
    nightMode: Boolean,
) {
    var nightMode: Boolean = nightMode
        private set

    fun update(nightMode: Boolean): Boolean {
        if (this.nightMode == nightMode) return false
        this.nightMode = nightMode
        return true
    }

    fun color(
        light: Int,
        dark: Int,
    ): Int = if (nightMode) dark else light
}
