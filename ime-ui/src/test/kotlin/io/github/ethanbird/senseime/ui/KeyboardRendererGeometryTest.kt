package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardRendererGeometryTest {
    @Test
    fun `ai geometry reuses rectangles and publishes stop hit target before draw`() {
        val geometry = MutableAiSurfaceRenderGeometry()
        geometry.update(
            viewWidth = 400,
            viewHeight = 300,
            keyRegionTop = 50f,
            systemBarHeight = 52f,
            horizontalPadding = 6f,
            density = 1f,
            active = true,
            locked = false,
        )
        val card = geometry.card
        val lockPill = geometry.lockPill
        val stop = geometry.stopBounds

        assertRect(card, 6f, 57f, 394f, 241f)
        assertRect(lockPill, 125f, 255f, 275f, 293f)
        assertTrue(stop.isEmpty)

        geometry.update(
            viewWidth = 400,
            viewHeight = 300,
            keyRegionTop = 50f,
            systemBarHeight = 52f,
            horizontalPadding = 6f,
            density = 1f,
            active = true,
            locked = true,
        )

        assertSame(card, geometry.card)
        assertSame(lockPill, geometry.lockPill)
        assertSame(stop, geometry.stopBounds)
        assertRect(geometry.stopBounds, 342f, 253f, 390f, 295f)
        assertTrue(geometry.stopBounds.contains(366f, 274f))
        assertFalse(geometry.stopBounds.contains(390f, 274f))
        assertFalse(geometry.stopBounds.contains(341f, 274f))
    }

    @Test
    fun `inactive ai geometry clears every retained hit region`() {
        val geometry = MutableAiSurfaceRenderGeometry()
        geometry.update(
            viewWidth = 400,
            viewHeight = 300,
            keyRegionTop = 50f,
            systemBarHeight = 52f,
            horizontalPadding = 6f,
            density = 1f,
            active = true,
            locked = true,
        )

        geometry.update(
            viewWidth = 400,
            viewHeight = 300,
            keyRegionTop = 50f,
            systemBarHeight = 52f,
            horizontalPadding = 6f,
            density = 1f,
            active = false,
            locked = false,
        )

        assertEquals(0f, geometry.surfaceTop)
        assertEquals(0f, geometry.surfaceBottom)
        assertTrue(geometry.card.isEmpty)
        assertTrue(geometry.lockPill.isEmpty)
        assertTrue(geometry.stopBounds.isEmpty)
    }

    @Test
    fun `frame policy scans only timeline rows that the renderer displays`() {
        assertTrue(
            KeyboardRenderFramePolicy.aiNeedsDelayedFrame(
                aiState(AiSurfacePhase.STARTING),
            ),
        )
        assertFalse(
            KeyboardRenderFramePolicy.aiNeedsDelayedFrame(
                aiState(AiSurfacePhase.COMPLETE),
            ),
        )

        val hiddenRunning = listOf(
            activity("hidden", AiSurfaceActivityState.RUNNING),
            activity("one", AiSurfaceActivityState.COMPLETED),
            activity("two", AiSurfaceActivityState.COMPLETED),
            activity("three", AiSurfaceActivityState.COMPLETED),
            activity("four", AiSurfaceActivityState.COMPLETED),
        )
        assertFalse(
            KeyboardRenderFramePolicy.aiNeedsDelayedFrame(
                aiState(
                    phase = AiSurfacePhase.STREAMING,
                    activities = hiddenRunning,
                ),
            ),
        )
        assertTrue(
            KeyboardRenderFramePolicy.aiNeedsDelayedFrame(
                aiState(
                    phase = AiSurfacePhase.STREAMING,
                    activities = hiddenRunning.dropLast(1) +
                        activity("visible", AiSurfaceActivityState.RUNNING),
                ),
            ),
        )
    }

    @Test
    fun `voice frame policy animates active phases only`() {
        assertTrue(
            KeyboardRenderFramePolicy.voiceNeedsAnimationFrame(
                voiceState(VoiceSurfacePhase.STARTING),
            ),
        )
        assertTrue(
            KeyboardRenderFramePolicy.voiceNeedsAnimationFrame(
                voiceState(VoiceSurfacePhase.LISTENING),
            ),
        )
        assertTrue(
            KeyboardRenderFramePolicy.voiceNeedsAnimationFrame(
                voiceState(VoiceSurfacePhase.PROCESSING),
            ),
        )
        assertFalse(
            KeyboardRenderFramePolicy.voiceNeedsAnimationFrame(
                voiceState(VoiceSurfacePhase.ERROR),
            ),
        )
    }

    @Test
    fun `scene key partitions expose primitive offsets instead of ranges`() {
        val methods = KeyboardScene::class.java.methods

        assertFalse(methods.any { it.returnType == IntRange::class.java })
        assertEquals(
            Int::class.javaPrimitiveType,
            methods.single { it.name == "getToolbarKeyStart" }.returnType,
        )
        assertEquals(
            Int::class.javaPrimitiveType,
            methods.single { it.name == "getPanelKeyEndExclusive" }.returnType,
        )
        assertEquals(
            Int::class.javaPrimitiveType,
            methods.single { it.name == "getSystemBarKeyEndExclusive" }.returnType,
        )
    }

    private fun assertRect(
        rect: RenderRect,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        assertEquals(left, rect.left)
        assertEquals(top, rect.top)
        assertEquals(right, rect.right)
        assertEquals(bottom, rect.bottom)
    }

    private fun aiState(
        phase: AiSurfacePhase,
        activities: List<AiSurfaceActivity> = emptyList(),
    ) = AiSurfaceState(
        generation = 1L,
        phase = phase,
        preview = "",
        statusText = "",
        activities = activities,
    )

    private fun activity(
        id: String,
        state: AiSurfaceActivityState,
    ) = AiSurfaceActivity(
        id = id,
        title = id,
        state = state,
    )

    private fun voiceState(phase: VoiceSurfacePhase) = VoiceSurfaceState(
        sessionId = 1L,
        revision = 1L,
        phase = phase,
        providerName = "test",
        statusText = "",
    )
}
