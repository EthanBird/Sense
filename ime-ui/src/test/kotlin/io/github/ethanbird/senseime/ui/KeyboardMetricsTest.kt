package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyboardMetricsTest {
    @Test
    fun largeTextAddsChromeWithoutShrinkingTheLetterArea() {
        val density = 2.625f
        val metrics = KeyboardMetrics.fromDensity(density)
        for (landscape in listOf(false, true)) {
            val originalHeight = KeyboardSizeProfile.DEFAULT.preferredHeightPx(landscape, density)
            val originalLetterArea = originalHeight - metrics.candidateHeight - metrics.systemBarHeight
            for (scale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
                metrics.updateFontScale(scale)
                val height = KeyboardSizeProfile.DEFAULT.preferredHeightPx(landscape, density, scale)
                assertEquals(originalLetterArea, height - metrics.candidateHeight - metrics.systemBarHeight, 1f)
                assertEquals((42f + 19f * (scale.coerceAtLeast(1f) - 1f)) * density,
                    metrics.expandedCandidateRowHeight, 0.001f)
            }
            metrics.updateFontScale(1f)
        }
    }

    @Test
    fun rejectsInvalidFontScalesAndRestoresDefaultGeometry() {
        val metrics = KeyboardMetrics.fromDensity(1f, 2f)
        assertEquals(90f, metrics.candidateHeight, 0f)
        metrics.updateFontScale(1f)
        assertEquals(45f, metrics.candidateHeight, 0f)
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { metrics.updateFontScale(invalid) }
            assertThrows(IllegalArgumentException::class.java) {
                KeyboardSizeProfile.DEFAULT.preferredHeightPx(false, 1f, invalid)
            }
        }
    }

    @Test
    fun `resolves all shared dimensions from one density`() {
        val metrics = KeyboardMetrics.fromDensity(2.5f)

        assertEquals(112.5f, metrics.candidateHeight, 0f)
        assertEquals(105f, metrics.toolbarHeight, 0f)
        assertEquals(130f, metrics.systemBarHeight, 0f)
        assertEquals(12.5f, metrics.keyGap, 0f)
        assertEquals(15f, metrics.horizontalPadding, 0f)
        assertEquals(20f, metrics.keyRadius, 0f)
        assertEquals(95f, metrics.expandedCandidateStatusHeight, 0f)
    }

    @Test
    fun `rejects invalid density`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardMetrics.fromDensity(0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardMetrics.fromDensity(Float.NaN)
        }
    }
}
