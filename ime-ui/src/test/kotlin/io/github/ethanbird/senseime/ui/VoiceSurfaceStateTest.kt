package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSurfaceStateTest {
    @Test
    fun waveformHistoryIsBoundedAndChronological() {
        val buffer = VoiceWaveformBuffer(capacity = 4)
        repeat(6) { buffer.append(it / 5f) }
        val destination = FloatArray(4)

        assertEquals(4, buffer.copyInto(destination))
        assertArrayEquals(floatArrayOf(0.4f, 0.6f, 0.8f, 1f), destination, 0.0001f)
    }

    @Test
    fun invalidAndOutOfRangeLevelsAreNormalizedWithoutAllocation() {
        val buffer = VoiceWaveformBuffer(capacity = 3)
        buffer.append(Float.NaN)
        buffer.append(-2f)
        buffer.append(5f)
        val destination = FloatArray(3)

        buffer.copyInto(destination)

        assertArrayEquals(floatArrayOf(0f, 0f, 1f), destination, 0.0001f)
        buffer.clear()
        assertEquals(0, buffer.size)
    }

    @Test
    fun staleOrForeignSurfaceUpdatesCannotReplaceTheActiveSession() {
        val current = state(sessionId = 7L, revision = 4L)

        assertFalse(VoiceSurfaceUpdatePolicy.accepts(current, current.copy(revision = 4L)))
        assertFalse(
            VoiceSurfaceUpdatePolicy.accepts(
                current,
                current.copy(sessionId = 6L, revision = 9L),
            ),
        )
        assertTrue(VoiceSurfaceUpdatePolicy.accepts(current, current.copy(revision = 5L)))
    }

    @Test
    fun primaryControlFollowsRecognitionPhase() {
        assertEquals(
            KeyCodes.VOICE_DONE,
            VoiceSurfaceControlPolicy.primaryKeyCode(VoiceSurfacePhase.LISTENING),
        )
        assertEquals(
            0,
            VoiceSurfaceControlPolicy.primaryKeyCode(VoiceSurfacePhase.PROCESSING),
        )
        assertEquals(
            KeyCodes.VOICE_RETRY,
            VoiceSurfaceControlPolicy.primaryKeyCode(VoiceSurfacePhase.ERROR),
        )
    }

    private fun state(sessionId: Long, revision: Long) = VoiceSurfaceState(
        sessionId = sessionId,
        revision = revision,
        phase = VoiceSurfacePhase.LISTENING,
        providerName = "系统语音识别",
        statusText = "正在聆听",
    )
}
