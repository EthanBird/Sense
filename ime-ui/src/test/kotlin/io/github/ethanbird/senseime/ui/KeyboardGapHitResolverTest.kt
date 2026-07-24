package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGapHitResolverTest {
    private data class Target(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val eligible: Boolean = true,
    )

    @Test
    fun everyPointAcrossAVisualGapResolvesToAKey() {
        val targets = listOf(
            Target(0f, 0f, 40f, 40f),
            Target(45f, 0f, 85f, 40f),
        )

        for (step in 0..10) {
            val x = 40f + step * 0.5f
            val index = resolve(x = x, y = 20f, maximumDistance = 5f, targets = targets)
            check(index == 0 || index == 1) {
                "The $x point in the visual gap did not resolve to either key"
            }
        }
    }

    @Test
    fun crossingOfHorizontalAndVerticalGapsStillResolves() {
        val targets = listOf(
            Target(0f, 0f, 40f, 40f),
            Target(45f, 0f, 85f, 40f),
            Target(0f, 45f, 40f, 85f),
            Target(45f, 45f, 85f, 85f),
        )

        assertEquals(0, resolve(42.5f, 42.5f, 5f, targets))
    }

    @Test
    fun equallyDistantKeysUseCenterThenStableLayoutOrder() {
        val nearerCenter = listOf(
            Target(0f, 0f, 40f, 40f),
            Target(50f, 0f, 110f, 40f),
        )
        assertEquals(0, resolve(45f, 20f, 5f, nearerCenter))

        val equalCenters = listOf(
            Target(0f, 0f, 40f, 40f),
            Target(50f, 0f, 90f, 40f),
        )
        assertEquals(0, resolve(45f, 20f, 5f, equalCenters))
    }

    @Test
    fun trueMarginsBeyondCaptureRadiusRemainUnassigned() {
        val targets = listOf(Target(18f, 0f, 58f, 40f))

        assertEquals(
            KeyboardGapHitResolver.NONE,
            resolve(x = 0f, y = 20f, maximumDistance = 5f, targets = targets),
        )
    }

    @Test
    fun ineligibleScrollableAndCardTargetsAreIgnored() {
        val targets = listOf(
            Target(0f, 0f, 40f, 40f, eligible = false),
            Target(45f, 0f, 85f, 40f, eligible = false),
        )

        assertEquals(
            KeyboardGapHitResolver.NONE,
            resolve(x = 42.5f, y = 20f, maximumDistance = 5f, targets = targets),
        )
    }

    @Test
    fun gapOriginRemainsInsideFrozenKeyUntilItLeavesExitSlop() {
        assertTrue(
            KeyboardGapHitResolver.containsWithSlop(
                x = 42.5f,
                y = 20f,
                left = 0f,
                top = 0f,
                right = 40f,
                bottom = 40f,
                slop = 5f,
            ),
        )
        assertFalse(
            KeyboardGapHitResolver.containsWithSlop(
                x = 46f,
                y = 20f,
                left = 0f,
                top = 0f,
                right = 40f,
                bottom = 40f,
                slop = 5f,
            ),
        )
    }

    private fun resolve(
        x: Float,
        y: Float,
        maximumDistance: Float,
        targets: List<Target>,
    ): Int = KeyboardGapHitResolver.nearestIndex(
        x = x,
        y = y,
        maximumDistance = maximumDistance,
        targetCount = targets.size,
        isEligible = { index -> targets[index].eligible },
        left = { index -> targets[index].left },
        top = { index -> targets[index].top },
        right = { index -> targets[index].right },
        bottom = { index -> targets[index].bottom },
    )
}
