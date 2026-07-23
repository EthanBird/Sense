package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KineticScrollPolicyTest {
    @Test
    fun slowReleaseDoesNotStartInertia() {
        assertEquals(
            0,
            KineticScrollPolicy.contentVelocity(
                fingerVelocity = 499f,
                minimumFlingVelocity = 500f,
                maximumFlingVelocity = 8_000f,
            ),
        )
    }

    @Test
    fun fingerAndContentMoveInOppositeDirections() {
        assertEquals(
            1_250,
            KineticScrollPolicy.contentVelocity(
                fingerVelocity = -1_250f,
                minimumFlingVelocity = 500f,
                maximumFlingVelocity = 8_000f,
            ),
        )
        assertEquals(
            -1_250,
            KineticScrollPolicy.contentVelocity(
                fingerVelocity = 1_250f,
                minimumFlingVelocity = 500f,
                maximumFlingVelocity = 8_000f,
            ),
        )
    }

    @Test
    fun velocityIsClampedAndInvalidSamplesAreIgnored() {
        assertEquals(
            8_000,
            KineticScrollPolicy.contentVelocity(
                fingerVelocity = -42_000f,
                minimumFlingVelocity = 500f,
                maximumFlingVelocity = 8_000f,
            ),
        )
        assertEquals(
            0,
            KineticScrollPolicy.contentVelocity(
                fingerVelocity = Float.NaN,
                minimumFlingVelocity = 500f,
                maximumFlingVelocity = 8_000f,
            ),
        )
    }
}
