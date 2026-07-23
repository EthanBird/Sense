package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateStripScrollStateTest {
    @Test
    fun allCandidatesRemainReachableInLarge255And510ItemSnapshots() {
        listOf(255, 510).forEach { candidateCount ->
            val layout = CandidateStripGeometry.layout(
                viewWidth = 360f,
                measuredTextWidths = List(candidateCount) { index -> 22f + index % 9 * 4f },
                padding = 6f,
                textInset = 9f,
                gap = 3f,
                minimumWidth = 44f,
                overflowControlWidth = 44f,
            )

            assertEquals(candidateCount, layout.slots.size)
            assertTrue(layout.hasOverflow)
            assertEquals(layout.viewportRight, layout.slots.last().right - layout.maximumOffset, 0.001f)
            layout.slots.forEach { slot ->
                val revealingOffset = (slot.left - layout.viewportLeft)
                    .coerceIn(0f, layout.maximumOffset)
                val visibleLeft = slot.left - revealingOffset
                val visibleRight = slot.right - revealingOffset
                assertTrue(visibleRight > layout.viewportLeft)
                assertTrue(visibleLeft < layout.viewportRight)
            }
        }
    }

    @Test
    fun geometryCacheDoesNotRemeasureDuringPixelDragRebuilds() {
        val cache = CandidateStripLayoutCache()
        val key = CandidateStripLayoutCache.Key(
            generation = 8L,
            viewWidth = 360,
            takesToolbar = true,
        )
        var builds = 0
        repeat(240) {
            cache.getOrBuild(key) {
                builds++
                CandidateStripGeometry.layout(
                    viewWidth = 360f,
                    measuredTextWidths = List(510) { 30f },
                    padding = 6f,
                    textInset = 9f,
                    gap = 3f,
                    minimumWidth = 44f,
                    overflowControlWidth = 44f,
                )
            }
        }

        assertEquals(1, builds)
        cache.getOrBuild(key.copy(generation = 9L)) {
            builds++
            CandidateStripGeometry.layout(
                viewWidth = 360f,
                measuredTextWidths = listOf(30f),
                padding = 6f,
                textInset = 9f,
                gap = 3f,
                minimumWidth = 44f,
                overflowControlWidth = 44f,
            )
        }
        assertEquals(2, builds)
    }

    @Test
    fun slowDragFollowsFingerAndDoesNotSnap() {
        val state = configuredState()
        assertTrue(state.begin(pointerId = 4, x = 240f, y = 20f, eventTimeMillis = 0L))

        val update = state.move(pointerId = 4, x = 183f, y = 22f, eventTimeMillis = 180L)
        val settle = state.finish(
            pointerId = 4,
            x = 180f,
            y = 22f,
            eventTimeMillis = 260L,
            fastFlingVelocity = 720f,
        )

        assertTrue(update.dragLatched)
        assertEquals(60f, state.offset, 0.001f)
        assertEquals(60f, settle!!.targetOffset, 0.001f)
        assertFalse(settle.animate)
        assertFalse(settle.fastFling)
        assertTrue(settle.dragged)
    }

    @Test
    fun fastDragMovesAtMostOnePageAndSnapsToCandidateStart() {
        val state = configuredState()
        state.moveTo(100f)
        state.begin(pointerId = 7, x = 260f, y = 20f, eventTimeMillis = 0L)
        state.move(pointerId = 7, x = 180f, y = 20f, eventTimeMillis = 25L)
        val settle = state.finish(
            pointerId = 7,
            x = 150f,
            y = 20f,
            eventTimeMillis = 35L,
            fastFlingVelocity = 720f,
        )!!

        assertTrue(settle.fastFling)
        assertTrue(settle.animate)
        assertTrue(settle.targetOffset > 100f)
        assertTrue(settle.targetOffset <= 300f)
        assertTrue(settle.targetOffset in listOf(0f, 50f, 100f, 150f, 200f, 250f, 300f, 350f, 400f))
    }

    @Test
    fun edgeOverdragIsDampedAndSpringsBackWithoutAFalseFling() {
        val state = configuredState()
        state.begin(pointerId = 2, x = 100f, y = 20f, eventTimeMillis = 0L)
        state.move(pointerId = 2, x = 180f, y = 20f, eventTimeMillis = 200L)

        assertTrue(state.offset < 0f)
        assertTrue(state.offset > -80f)

        val settle = state.finish(
            pointerId = 2,
            x = 180f,
            y = 20f,
            eventTimeMillis = 320L,
            fastFlingVelocity = 720f,
        )!!
        assertEquals(0f, settle.targetOffset, 0.001f)
        assertTrue(settle.animate)
        assertFalse(settle.fastFling)
    }

    @Test
    fun perpendicularOrShortMovementRemainsATapAndOtherPointerCannotStealDrag() {
        val state = configuredState()
        assertTrue(state.begin(pointerId = 1, x = 100f, y = 20f, eventTimeMillis = 0L))
        assertFalse(state.begin(pointerId = 2, x = 200f, y = 20f, eventTimeMillis = 2L))
        val move = state.move(pointerId = 1, x = 106f, y = 45f, eventTimeMillis = 20L)
        val settle = state.finish(
            pointerId = 1,
            x = 106f,
            y = 45f,
            eventTimeMillis = 30L,
            fastFlingVelocity = 720f,
        )!!

        assertFalse(move.dragLatched)
        assertFalse(settle.dragged)
        assertEquals(0f, state.offset, 0.001f)
    }

    @Test
    fun cubicSettleEndsExactlyAtTarget() {
        assertEquals(25f, CandidateStripScrollPhysics.easeOutCubic(25f, 100f, 0f), 0.001f)
        assertEquals(100f, CandidateStripScrollPhysics.easeOutCubic(25f, 100f, 1f), 0.001f)
        assertTrue(CandidateStripScrollPhysics.easeOutCubic(25f, 100f, 0.5f) > 62.5f)
    }

    private fun configuredState(): CandidateStripScrollState =
        CandidateStripScrollState(touchSlop = 8f).also { state ->
            state.configure(
                maximumOffset = 400f,
                viewportExtent = 200f,
                snapOffsets = (0..8).map { it * 50f },
            )
        }
}
