package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSkillAuroraLoopPolicyTest {
    @Test
    fun inactiveInputsNeverRetainAFrameCallback() {
        val inactiveModes = listOf(
            mode(active = false),
            mode(attached = false),
            mode(visible = false),
            mode(hostRenderingEnabled = false),
            mode(hasDrawableBounds = false),
        )

        inactiveModes.forEach { mode ->
            assertEquals(ActiveSkillAuroraLoopPolicy.RenderMode.INACTIVE, mode)
            assertFalse(ActiveSkillAuroraLoopPolicy.requiresFrameCallback(mode))
        }
    }

    @Test
    fun reducedMotionKeepsStaticAuroraWithoutRecurringCallback() {
        val mode = mode(animatorsEnabled = false)

        assertEquals(ActiveSkillAuroraLoopPolicy.RenderMode.STATIC, mode)
        assertFalse(ActiveSkillAuroraLoopPolicy.requiresFrameCallback(mode))
        assertEquals(
            ActiveSkillAuroraLoopPolicy.STATIC_PHASE,
            ActiveSkillAuroraLoopPolicy.phase(
                uptimeMillis = 4_799L,
                periodMillis = 4_800L,
                mode = mode,
            ),
            0f,
        )
    }

    @Test
    fun visibleActiveAuroraUsesBoundedPeriodicPhase() {
        val mode = mode()

        assertEquals(ActiveSkillAuroraLoopPolicy.RenderMode.ANIMATED, mode)
        assertTrue(ActiveSkillAuroraLoopPolicy.requiresFrameCallback(mode))
        assertEquals(
            0f,
            ActiveSkillAuroraLoopPolicy.phase(0L, 4_800L, mode),
            0f,
        )
        assertEquals(
            0.5f,
            ActiveSkillAuroraLoopPolicy.phase(2_400L, 4_800L, mode),
            0f,
        )
        assertEquals(
            0f,
            ActiveSkillAuroraLoopPolicy.phase(4_800L, 4_800L, mode),
            0f,
        )
        assertEquals(33L, ActiveSkillAuroraLoopPolicy.FRAME_INTERVAL_MILLIS)
    }

    @Test
    fun imeWindowHideStopsAndShowRestartsAnimation() {
        val hidden = mode(hostRenderingEnabled = false)
        val shown = mode(hostRenderingEnabled = true)

        assertEquals(ActiveSkillAuroraLoopPolicy.RenderMode.INACTIVE, hidden)
        assertFalse(ActiveSkillAuroraLoopPolicy.requiresFrameCallback(hidden))
        assertEquals(ActiveSkillAuroraLoopPolicy.RenderMode.ANIMATED, shown)
        assertTrue(ActiveSkillAuroraLoopPolicy.requiresFrameCallback(shown))
    }

    private fun mode(
        active: Boolean = true,
        attached: Boolean = true,
        visible: Boolean = true,
        hostRenderingEnabled: Boolean = true,
        hasDrawableBounds: Boolean = true,
        animatorsEnabled: Boolean = true,
    ): ActiveSkillAuroraLoopPolicy.RenderMode =
        ActiveSkillAuroraLoopPolicy.renderMode(
            active = active,
            attached = attached,
            visible = visible,
            hostRenderingEnabled = hostRenderingEnabled,
            hasDrawableBounds = hasDrawableBounds,
            animatorsEnabled = animatorsEnabled,
        )
}
