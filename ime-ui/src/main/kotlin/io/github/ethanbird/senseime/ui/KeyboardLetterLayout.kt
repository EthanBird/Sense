package io.github.ethanbird.senseime.ui

/**
 * Immutable input for a primary letter-layout adapter.
 */
internal data class KeyboardLetterLayoutRequest(
    val viewWidth: Int,
    val viewHeight: Int,
    val chromeBottom: Float,
    val shifted: Boolean,
    val chineseMode: Boolean,
    val swipeMode: SwipeCharacterMode,
    val legendMode: PrimaryKeyboardLegendMode = PrimaryKeyboardLegendMode.SWIPE_HINTS,
)

/**
 * Strategy seam for primary letter geometry.
 *
 * QWERTY is the production adapter. A T9 adapter can be registered with
 * [KeyboardPrimaryLayout] without changing the scene, renderer or touch code.
 */
internal fun interface KeyboardLetterLayout {
    fun appendKeys(
        request: KeyboardLetterLayoutRequest,
        metrics: KeyboardMetrics,
        output: MutableList<Key>,
    )
}
