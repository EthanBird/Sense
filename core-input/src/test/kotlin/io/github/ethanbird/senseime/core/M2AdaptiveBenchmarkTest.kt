package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Test

class M2AdaptiveBenchmarkTest {
    @Test
    fun percentileUsesNearestRankInsteadOfRoundingDown() {
        assertEquals(4, M2AdaptiveBenchmark.nearestRankIndex(5, 0.95))
        assertEquals(6, M2AdaptiveBenchmark.nearestRankIndex(7, 0.95))
        assertEquals(3, M2AdaptiveBenchmark.nearestRankIndex(7, 0.50))
    }
}
