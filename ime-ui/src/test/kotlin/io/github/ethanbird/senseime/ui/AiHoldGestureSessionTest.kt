package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHoldGestureSessionTest {
    private fun session() = AiHoldGestureSession(
        longPressTimeoutMillis = 380L,
        activationConfirmationMillis = 16L,
        maximumStationaryDistance = 12f,
    )

    @Test
    fun shortSpacePressRemainsAnOrdinaryTap() {
        val session = session()
        val arm = session.begin(7, 100f, 200f, eventTimeMillis = 1_000L)

        assertEquals(1_396L, arm?.activationAtMillis)
        assertEquals(
            AiHoldGestureSession.Outcome.SHORT_TAP,
            session.pointerUp(7, eventTimeMillis = 1_200L),
        )
        assertNull(session.armedGeneration())
        assertNull(session.activeGeneration())
    }

    @Test
    fun stationaryOwnerActivatesAfterThresholdAndConfirmationFrame() {
        val session = session()
        val arm = requireNotNull(session.begin(4, 30f, 40f, 100L))

        assertEquals(
            AiHoldGestureSession.Outcome.NONE,
            session.tryActivate(4, arm.generation, 495L),
        )
        assertEquals(
            AiHoldGestureSession.Outcome.ACTIVATED,
            session.tryActivate(4, arm.generation, 497L),
        )
        assertEquals(arm.generation, session.activeGeneration())
    }

    @Test
    fun upInsideConfirmationFrameWinsRaceWithoutFalseActivation() {
        val session = session()
        val arm = requireNotNull(session.begin(3, 0f, 0f, 10_000L))

        assertEquals(
            AiHoldGestureSession.Outcome.SHORT_TAP,
            session.pointerUp(3, arm.activationAtMillis - 1L),
        )
        assertEquals(
            AiHoldGestureSession.Outcome.NONE,
            session.tryActivate(3, arm.generation, arm.activationAtMillis),
        )
    }

    @Test
    fun upAtCommitBoundaryConsumesLongHoldButCannotBeResurrected() {
        val session = session()
        val arm = requireNotNull(session.begin(3, 0f, 0f, 10_000L))

        assertEquals(
            AiHoldGestureSession.Outcome.HOLD_RELEASED,
            session.pointerUp(3, arm.activationAtMillis),
        )
        assertEquals(
            AiHoldGestureSession.Outcome.NONE,
            session.tryActivate(3, arm.generation, arm.activationAtMillis),
        )
    }

    @Test
    fun staleDelayedCallbackCannotActivateANewerSpacePress() {
        val session = session()
        val old = requireNotNull(session.begin(1, 0f, 0f, 0L))
        session.pointerUp(1, 100L)
        val current = requireNotNull(session.begin(1, 0f, 0f, 200L))

        assertEquals(
            AiHoldGestureSession.Outcome.NONE,
            session.tryActivate(1, old.generation, 1_000L),
        )
        assertEquals(current.generation, session.armedGeneration())
        assertEquals(
            AiHoldGestureSession.Outcome.ACTIVATED,
            session.tryActivate(1, current.generation, current.activationAtMillis + 1L),
        )
    }

    @Test
    fun secondPointerCannotStealOrCancelOwnership() {
        val session = session()
        val owner = requireNotNull(session.begin(5, 20f, 20f, 100L))

        assertNull(session.begin(8, 20f, 20f, 102L))
        assertFalse(session.owns(8))
        assertEquals(AiHoldGestureSession.Outcome.NONE, session.pointerUp(8, 700L))
        assertEquals(
            AiHoldGestureSession.Outcome.ACTIVATED,
            session.tryActivate(5, owner.generation, owner.activationAtMillis + 1L),
        )
    }

    @Test
    fun movementOutsideStationaryRadiusDisarmsOnlyAiEligibility() {
        val session = session()
        val arm = requireNotNull(session.begin(9, 50f, 50f, 500L))

        assertEquals(AiHoldGestureSession.Outcome.NONE, session.move(9, 57f, 57f))
        assertEquals(
            AiHoldGestureSession.Outcome.ELIGIBILITY_CANCELLED,
            session.move(9, 63f, 50f),
        )
        assertEquals(
            AiHoldGestureSession.Outcome.NONE,
            session.tryActivate(9, arm.generation, 2_000L),
        )
    }

    @Test
    fun ownerUpAndCancelImmediatelyTerminateActiveSession() {
        listOf(true, false).forEach { useUp ->
            val session = session()
            val arm = requireNotNull(session.begin(2, 0f, 0f, 0L))
            session.tryActivate(2, arm.generation, arm.activationAtMillis + 1L)

            val outcome = if (useUp) {
                session.pointerUp(2, arm.activationAtMillis + 1L)
            } else {
                session.pointerCancel(2)
            }
            assertEquals(AiHoldGestureSession.Outcome.ACTIVE_CANCELLED, outcome)
            assertNull(session.activeGeneration())
        }
    }

    @Test
    fun nonOwnerCancelCannotInterruptActiveSession() {
        val session = session()
        val arm = requireNotNull(session.begin(2, 0f, 0f, 0L))
        session.tryActivate(2, arm.generation, arm.activationAtMillis + 1L)

        assertEquals(AiHoldGestureSession.Outcome.NONE, session.pointerCancel(7))
        assertEquals(arm.generation, session.activeGeneration())
        assertEquals(AiHoldGestureSession.Outcome.ACTIVE_CANCELLED, session.pointerCancel(2))
    }

    @Test
    fun actionCancelBeforeThresholdSubmitsNothing() {
        val session = session()
        session.begin(2, 0f, 0f, 0L)

        assertEquals(
            AiHoldGestureSession.Outcome.ELIGIBILITY_CANCELLED,
            session.pointerCancel(2),
        )
        assertNull(session.armedGeneration())
        assertNull(session.activeGeneration())
    }

    @Test
    fun delayedUiThreadReleaseAfterLongHoldDoesNotInsertSpace() {
        val session = session()
        val arm = requireNotNull(session.begin(11, 0f, 0f, 0L))

        assertEquals(
            AiHoldGestureSession.Outcome.HOLD_RELEASED,
            session.pointerUp(11, arm.activationAtMillis + 300L),
        )
    }

    @Test
    fun surfaceBoundsDoNotChangeBetweenIdleAndStreamingStates() {
        val idle = AiSurfaceContract.bounds(358f, 45f, 52f)
        val streaming = AiSurfaceContract.bounds(358f, 45f, 52f)

        assertEquals(idle, streaming)
        assertEquals(45f, streaming.top)
        assertEquals(306f, streaming.bottom)
        assertEquals(261f, streaming.height)
    }

    @Test
    fun streamPreviewIsBoundedWithoutSplittingSurrogatePairs() {
        val prefix = "a".repeat(AiSurfaceContract.MAX_PREVIEW_CHARS - 1)
        val emoji = "\uD83D\uDC14"

        val bounded = AiSurfaceContract.appendBounded(prefix, emoji)

        assertEquals(AiSurfaceContract.MAX_PREVIEW_CHARS - 1, bounded.length)
        assertFalse(bounded.last().isHighSurrogate())
        assertTrue(AiSurfaceContract.boundedPreview("x".repeat(5_000)).length <= 4_096)
    }
}
