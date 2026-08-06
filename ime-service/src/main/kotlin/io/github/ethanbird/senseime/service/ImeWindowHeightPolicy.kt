package io.github.ethanbird.senseime.service

import kotlin.math.roundToInt

internal object ImeWindowHeightPolicy {
    fun agentHeightPx(
        availableHeightPx: Int,
        keyboardHeightPx: Int,
        density: Float,
        landscape: Boolean,
        composing: Boolean,
    ): Int {
        if (availableHeightPx <= 0) return keyboardHeightPx
        val hostPeek = ((if (composing) 56f else 96f) * density).roundToInt()
        val upperBound = (availableHeightPx - hostPeek).coerceAtLeast(keyboardHeightPx)
        val desiredFraction = when {
            composing && landscape -> 0.78f
            composing -> 0.72f
            landscape -> 0.70f
            else -> 0.64f
        }
        val desired = (availableHeightPx * desiredFraction).roundToInt()
        val lowerBound = if (composing) {
            (keyboardHeightPx + (190f * density).roundToInt()).coerceAtMost(upperBound)
        } else {
            (320f * density).roundToInt().coerceAtMost(upperBound)
        }
        return desired.coerceIn(lowerBound, upperBound)
    }
}
