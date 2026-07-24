package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatWaveformRingBufferTest {
    @Test
    fun `snapshot preserves insertion order before capacity`() {
        val ring = FloatWaveformRingBuffer(4)
        ring.append(0.1f)
        ring.append(0.4f)

        assertArrayEquals(floatArrayOf(0.1f, 0.4f), ring.snapshotOldestFirst(), 0f)
        assertEquals(2, ring.size)
        assertEquals(2L, ring.revision)
    }

    @Test
    fun `new values overwrite oldest values at fixed capacity`() {
        val ring = FloatWaveformRingBuffer(3)
        ring.appendAll(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f))

        assertArrayEquals(
            floatArrayOf(0.3f, 0.4f, 0.5f),
            ring.snapshotOldestFirst(),
            0f,
        )
        assertEquals(3, ring.size)
        assertEquals(5L, ring.revision)
    }

    @Test
    fun `copy may request only the newest tail without allocating`() {
        val ring = FloatWaveformRingBuffer(5)
        ring.appendAll(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        val destination = FloatArray(2)

        val count = ring.copyOldestFirst(destination)

        assertEquals(2, count)
        assertArrayEquals(floatArrayOf(0.3f, 0.4f), destination, 0f)
    }

    @Test
    fun `append sanitizes out of range and non-finite amplitudes`() {
        val ring = FloatWaveformRingBuffer(4)
        ring.append(-2f)
        ring.append(4f)
        ring.append(Float.NaN)
        ring.append(Float.POSITIVE_INFINITY)

        assertArrayEquals(
            floatArrayOf(0f, 1f, 0f, 0f),
            ring.snapshotOldestFirst(),
            0f,
        )
    }

    @Test
    fun `clear advances revision only when data existed`() {
        val ring = FloatWaveformRingBuffer(2)
        ring.clear()
        assertEquals(0L, ring.revision)

        ring.append(0.5f)
        ring.clear()
        assertEquals(2L, ring.revision)
        assertEquals(0, ring.size)
    }
}
