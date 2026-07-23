package io.github.ethanbird.senseime.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Converts the finger velocity reported by Android into bounded content
 * velocity for a continuously scrolling keyboard panel.
 *
 * Dragging is handled directly by the View. This policy is consulted only
 * after release, so a slow drag remains exactly under the user's finger while
 * a deliberate fling keeps moving with native OverScroller deceleration.
 */
object KineticScrollPolicy {
    fun contentVelocity(
        fingerVelocity: Float,
        minimumFlingVelocity: Float,
        maximumFlingVelocity: Float,
    ): Int {
        require(minimumFlingVelocity >= 0f)
        require(maximumFlingVelocity >= minimumFlingVelocity)
        if (!fingerVelocity.isFinite() || abs(fingerVelocity) < minimumFlingVelocity) return 0
        return (-fingerVelocity)
            .coerceIn(-maximumFlingVelocity, maximumFlingVelocity)
            .roundToInt()
    }
}
