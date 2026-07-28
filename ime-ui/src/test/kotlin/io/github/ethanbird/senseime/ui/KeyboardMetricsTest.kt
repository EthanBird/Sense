package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyboardMetricsTest {
    @Test
    fun `resolves all shared dimensions from one density`() {
        val metrics = KeyboardMetrics.fromDensity(2.5f)

        assertEquals(112.5f, metrics.candidateHeight, 0f)
        assertEquals(105f, metrics.toolbarHeight, 0f)
        assertEquals(130f, metrics.systemBarHeight, 0f)
        assertEquals(12.5f, metrics.keyGap, 0f)
        assertEquals(15f, metrics.horizontalPadding, 0f)
        assertEquals(20f, metrics.keyRadius, 0f)
        assertEquals(95f, metrics.expandedCandidatePagerHeight, 0f)
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
