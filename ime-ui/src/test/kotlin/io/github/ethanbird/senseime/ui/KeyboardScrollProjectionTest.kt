package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardScrollProjectionTest {
    @Test
    fun screenAndContentCoordinatesRoundTrip() {
        val screen = KeyboardScrollProjection.screenCoordinate(
            contentCoordinate = 180f,
            offset = 64f,
        )

        assertEquals(116f, screen)
        assertEquals(180f, KeyboardScrollProjection.contentCoordinate(screen, offset = 64f))
    }

    @Test
    fun viewportIntersectionUsesProjectedCoordinates() {
        assertTrue(
            KeyboardScrollProjection.intersectsViewport(
                contentTop = 100f,
                contentBottom = 150f,
                offset = 80f,
                viewportTop = 10f,
                viewportBottom = 60f,
            ),
        )
        assertFalse(
            KeyboardScrollProjection.intersectsViewport(
                contentTop = 200f,
                contentBottom = 250f,
                offset = 80f,
                viewportTop = 10f,
                viewportBottom = 60f,
            ),
        )
    }
}
