package io.github.ethanbird.senseime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeWindowHeightPolicyTest {
    @Test
    fun readingPageIsCompactAndKeepsAVisibleHostArea() {
        val height = ImeWindowHeightPolicy.agentHeightPx(
            availableHeightPx = 1_000,
            keyboardHeightPx = 320,
            density = 1f,
            landscape = false,
            composing = false,
        )

        assertEquals(640, height)
        assertTrue(height <= 904)
        assertTrue(height >= 320)
    }

    @Test
    fun composingPageReservesKeyboardAndFourLineComposerSpace() {
        val height = ImeWindowHeightPolicy.agentHeightPx(
            availableHeightPx = 1_000,
            keyboardHeightPx = 320,
            density = 1f,
            landscape = false,
            composing = true,
        )

        assertEquals(720, height)
        assertTrue(height >= 510)
    }

    @Test
    fun clampsSmallWindowsToTheirStableUpperBound() {
        val height = ImeWindowHeightPolicy.agentHeightPx(
            availableHeightPx = 420,
            keyboardHeightPx = 300,
            density = 1f,
            landscape = false,
            composing = true,
        )

        assertEquals(364, height)
    }
}
