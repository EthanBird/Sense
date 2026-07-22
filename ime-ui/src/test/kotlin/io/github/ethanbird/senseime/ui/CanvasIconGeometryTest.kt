package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasIconGeometryTest {
    @Test
    fun wideAndTallKeysProduceTheSameUndistortedIconFrame() {
        val wide = CanvasIconGeometry.resolve(0f, 0f, 120f, 40f, density = 1f)
        val tall = CanvasIconGeometry.resolve(0f, 0f, 40f, 120f, density = 1f)

        assertEquals(wide.unit, tall.unit)
        assertEquals(wide.frameWidth, tall.frameWidth)
        assertEquals(wide.frameHeight, tall.frameHeight)
        assertTrue(wide.frameWidth / 40f in 0.48f..0.52f)
        assertTrue(tall.frameHeight / 40f in 0.48f..0.52f)
        assertTrue(wide.frameLeft >= 0f && wide.frameRight <= 120f)
        assertTrue(wide.frameTop >= 0f && wide.frameBottom <= 40f)
        assertTrue(tall.frameLeft >= 0f && tall.frameRight <= 40f)
        assertTrue(tall.frameTop >= 0f && tall.frameBottom <= 120f)
        assertTrue(wide.frameTop - wide.strokeWidth / 2f >= 0f)
        assertTrue(tall.frameLeft - tall.strokeWidth / 2f >= 0f)
    }

    @Test
    fun geometryAndStrokeScaleWithDensityInsteadOfFixedPixels() {
        val mdpi = CanvasIconGeometry.resolve(0f, 0f, 48f, 48f, density = 1f)
        val xxxhdpi = CanvasIconGeometry.resolve(0f, 0f, 144f, 144f, density = 3f)

        assertEquals(mdpi.unit * 3f, xxxhdpi.unit, 0.0001f)
        assertEquals(mdpi.strokeWidth * 3f, xxxhdpi.strokeWidth, 0.0001f)
        assertTrue(mdpi.strokeWidth / 48f in 0.045f..0.06f)
        assertTrue(xxxhdpi.strokeWidth / 144f in 0.045f..0.06f)
    }
}
