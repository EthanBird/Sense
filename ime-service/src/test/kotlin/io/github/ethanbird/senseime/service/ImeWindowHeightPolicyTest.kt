package io.github.ethanbird.senseime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeWindowHeightPolicyTest {
    @Test
    fun keepsAHostPeekAndEnoughRoomAboveTheKeyboard() {
        val height = ImeWindowHeightPolicy.agentHeightPx(
            availableHeightPx = 1_000,
            keyboardHeightPx = 320,
            density = 1f,
            landscape = false,
        )

        assertEquals(800, height)
        assertTrue(height <= 944)
        assertTrue(height >= 500)
    }

    @Test
    fun clampsSmallWindowsToTheirStableUpperBound() {
        val height = ImeWindowHeightPolicy.agentHeightPx(
            availableHeightPx = 420,
            keyboardHeightPx = 300,
            density = 1f,
            landscape = false,
        )

        assertEquals(364, height)
    }
}
