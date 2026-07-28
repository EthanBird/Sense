package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyboardSizePolicyTest {
    @Test
    fun defaultProfilePreservesReleaseGeometry() {
        val profile = KeyboardSizeProfile.DEFAULT

        assertEquals(358f, profile.preferredHeightDp(isLandscape = false))
        assertEquals(258f, profile.preferredHeightDp(isLandscape = true))
        assertEquals(716, profile.preferredHeightPx(isLandscape = false, density = 2f))
        assertEquals(516, profile.preferredHeightPx(isLandscape = true, density = 2f))
    }

    @Test
    fun customProfileUsesOneOrientationAwareResolver() {
        val profile = KeyboardSizeProfile(
            portraitHeightDp = 420f,
            landscapeHeightDp = 240f,
        )

        assertEquals(1260, profile.preferredHeightPx(isLandscape = false, density = 3f))
        assertEquals(720, profile.preferredHeightPx(isLandscape = true, density = 3f))
    }

    @Test
    fun rejectsNonFiniteOrOutOfRangeProfiles() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardSizeProfile(portraitHeightDp = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardSizeProfile(landscapeHeightDp = 120f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardSizeProfile(landscapeHeightDp = 239.99f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardSizeProfile.DEFAULT.preferredHeightPx(
                isLandscape = false,
                density = 0f,
            )
        }
    }
}
