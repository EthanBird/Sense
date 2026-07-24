package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16AudioTest {
    @Test
    fun `wav encoder writes canonical PCM16 mono 16k header and preserves samples`() {
        val pcm = byteArrayOf(0x34, 0x12, 0x00, 0x80.toByte())
        val wav = Pcm16WavEncoder.encode(pcm)

        assertEquals("RIFF", wav.bytes.ascii(0, 4))
        assertEquals(wav.bytes.size - 8, wav.bytes.leInt(4))
        assertEquals("WAVE", wav.bytes.ascii(8, 4))
        assertEquals("fmt ", wav.bytes.ascii(12, 4))
        assertEquals(16, wav.bytes.leInt(16))
        assertEquals(1, wav.bytes.leShort(20))
        assertEquals(1, wav.bytes.leShort(22))
        assertEquals(16_000, wav.bytes.leInt(24))
        assertEquals(32_000, wav.bytes.leInt(28))
        assertEquals(2, wav.bytes.leShort(32))
        assertEquals(16, wav.bytes.leShort(34))
        assertEquals("data", wav.bytes.ascii(36, 4))
        assertEquals(pcm.size, wav.bytes.leInt(40))
        assertArrayEquals(pcm, wav.bytes.copyOfRange(Pcm16WavEncoder.HEADER_BYTES, wav.bytes.size))
    }

    @Test
    fun `wav encoder reports duration without retaining unused capture bytes`() {
        val capture = ByteArray(Pcm16AudioFormat.SAMPLE_RATE_HZ * 2 + 100) { 7 }
        val wav = Pcm16WavEncoder.encode(
            capture,
            Pcm16AudioFormat.SAMPLE_RATE_HZ * Pcm16AudioFormat.BYTES_PER_SAMPLE,
        )

        assertEquals(1_000L, wav.durationMillis)
        assertEquals(
            Pcm16WavEncoder.HEADER_BYTES + 32_000,
            wav.bytes.size,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wav encoder rejects a partial PCM16 sample`() {
        Pcm16WavEncoder.encode(ByteArray(3))
    }

    @Test
    fun `RMS reports silence and half-scale signal`() {
        val silence = ByteArray(64)
        assertEquals(-96f, Pcm16Rms.dbFs(silence, 0, silence.size), 0.001f)

        val halfScale = ByteArray(64)
        for (index in halfScale.indices step 2) {
            halfScale[index] = 0
            halfScale[index + 1] = 0x40
        }
        assertEquals(-6.02f, Pcm16Rms.dbFs(halfScale, 0, halfScale.size), 0.03f)
        assertTrue(Pcm16Rms.speechUiDb(halfScale, 0, halfScale.size) in -2f..10f)
    }

    private fun ByteArray.ascii(
        offset: Int,
        length: Int,
    ): String = copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun ByteArray.leShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.leInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
}
