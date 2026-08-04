package io.github.ethanbird.senseime.service

import kotlin.math.roundToInt

internal object ImeWindowHeightPolicy {
    fun agentHeightPx(
        availableHeightPx: Int,
        keyboardHeightPx: Int,
        density: Float,
        landscape: Boolean,
    ): Int {
        if (availableHeightPx <= 0) return keyboardHeightPx
        val hostPeek = (56f * density).roundToInt()
        val conversationMinimum = (180f * density).roundToInt()
        val upperBound = (availableHeightPx - hostPeek).coerceAtLeast(keyboardHeightPx)
        val desired = (availableHeightPx * if (landscape) 0.74f else 0.80f).roundToInt()
        val lowerBound = (keyboardHeightPx + conversationMinimum).coerceAtMost(upperBound)
        return desired.coerceIn(lowerBound, upperBound)
    }
}
