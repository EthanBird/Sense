package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasIconGeometryTest {
    @Test
    fun navigationAndEditingIconsUseTheFineStrokeProfile() {
        assertTrue(KeyboardIconStrokePolicy.factor(Icon.DELETE) < 1f)
        assertEquals(
            KeyboardIconStrokePolicy.factor(Icon.DELETE),
            KeyboardIconStrokePolicy.factor(Icon.ENTER),
            0f,
        )
        assertEquals(
            KeyboardIconStrokePolicy.factor(Icon.DELETE),
            KeyboardIconStrokePolicy.factor(Icon.UP),
            0f,
        )
        assertEquals(
            KeyboardIconStrokePolicy.factor(Icon.LEFT),
            KeyboardIconStrokePolicy.factor(Icon.RIGHT),
            0f,
        )
        assertEquals(1f, KeyboardIconStrokePolicy.factor(Icon.EMOJI), 0f)
    }

    @Test
    fun fineIconVectorsAreBoundedAndOppositeDirectionsMirror() {
        val scratch = FloatArray(KeyboardFineIconGeometry.MAX_SEGMENTS * 4)
        val deleteSegments = KeyboardFineIconGeometry.writeSegments(Icon.DELETE, scratch)
        val deleteBounds = boundsOf(scratch, deleteSegments)
        val enterSegments = KeyboardFineIconGeometry.writeSegments(Icon.ENTER, scratch)
        val enterBounds = boundsOf(scratch, enterSegments)
        val leftSegments = KeyboardFineIconGeometry.writeSegments(Icon.LEFT, scratch)
        val leftBounds = boundsOf(scratch, leftSegments)
        val rightSegments = KeyboardFineIconGeometry.writeSegments(Icon.RIGHT, scratch)
        val rightBounds = boundsOf(scratch, rightSegments)

        assertEquals(9, deleteSegments)
        assertTrue(deleteBounds.all { it in -8.2f..8.2f })
        assertEquals(5, enterSegments)
        assertTrue(enterBounds.all { it in -8.2f..8.2f })
        assertEquals(3, leftSegments)
        assertEquals(3, rightSegments)
        assertEquals(-leftBounds[2], rightBounds[0], 0.001f)
        assertEquals(-leftBounds[0], rightBounds[2], 0.001f)
        assertEquals(leftBounds[1], rightBounds[1], 0.001f)
        assertEquals(leftBounds[3], rightBounds[3], 0.001f)
        assertEquals(0, KeyboardFineIconGeometry.writeSegments(Icon.EMOJI, scratch))
    }

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

    @Test
    fun mutableResolverMatchesAllocatingReferenceGeometry() {
        val expected = CanvasIconGeometry.resolve(
            left = 13f,
            top = 21f,
            right = 117f,
            bottom = 73f,
            density = 2.75f,
        )
        val actual = CanvasIconGeometry.MutableMetrics()

        CanvasIconGeometry.resolveInto(
            left = 13f,
            top = 21f,
            right = 117f,
            bottom = 73f,
            density = 2.75f,
            out = actual,
        )

        assertEquals(expected.centerX, actual.centerX, 0f)
        assertEquals(expected.centerY, actual.centerY, 0f)
        assertEquals(expected.unit, actual.unit, 0f)
        assertEquals(expected.strokeWidth, actual.strokeWidth, 0f)
        assertEquals(expected.frameLeft, actual.frameLeft, 0f)
        assertEquals(expected.frameTop, actual.frameTop, 0f)
        assertEquals(expected.frameRight, actual.frameRight, 0f)
        assertEquals(expected.frameBottom, actual.frameBottom, 0f)
    }

    private fun boundsOf(values: FloatArray, segmentCount: Int): FloatArray {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var index = 0
        while (index < segmentCount * 4) {
            minX = minOf(minX, values[index], values[index + 2])
            minY = minOf(minY, values[index + 1], values[index + 3])
            maxX = maxOf(maxX, values[index], values[index + 2])
            maxY = maxOf(maxY, values[index + 1], values[index + 3])
            index += 4
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
