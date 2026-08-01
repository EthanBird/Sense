package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ImePreferencesV1
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPreferenceProjectionTest {
    @Test
    fun defaultPreferencesPreserveTheReleaseKeyboardGeometry() {
        val profile = ImePreferencesV1.DEFAULT.toKeyboardSizeProfile()

        assertEquals(358f, profile.portraitHeightDp)
        assertEquals(258f, profile.landscapeHeightDp)
    }

    @Test
    fun customOrientationHeightsProjectWithoutRoundingOrCoupling() {
        val profile = ImePreferencesV1(
            portraitKeyboardHeightDp = 487,
            landscapeKeyboardHeightDp = 311,
        ).toKeyboardSizeProfile()

        assertEquals(487f, profile.portraitHeightDp)
        assertEquals(311f, profile.landscapeHeightDp)
    }
}
