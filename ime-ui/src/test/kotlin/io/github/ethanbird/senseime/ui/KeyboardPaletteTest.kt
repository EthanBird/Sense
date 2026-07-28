package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPaletteTest {
    @Test
    fun resolvesColorsFromOneConfigurationSnapshot() {
        val palette = KeyboardPalette(nightMode = false)

        assertEquals(11, palette.color(light = 11, dark = 22))
        assertTrue(palette.update(nightMode = true))
        assertEquals(22, palette.color(light = 11, dark = 22))
        assertFalse(palette.update(nightMode = true))
    }
}
