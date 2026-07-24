package io.github.ethanbird.senseime.speech

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Fixed-format microphone audio used by the cloud speech adapters.
 *
 * Keeping the format constant avoids resampling, temporary files, and provider-specific codecs in
 * the IME process. The default 30 second ceiling is 960 KiB of PCM and is always enforced before
 * recording starts.
 */
object Pcm16AudioFormat {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    const val DEFAULT_MAX_DURATION_MILLIS = 30_000
    const val ABSOLUTE_MAX_DURATION_MILLIS = 60_000

    fun maxPcmBytes(durationMillis: Int): Int {
        require(durationMillis in 1..ABSOLUTE_MAX_DURATION_MILLIS) {
            "durationMillis must be between 1 and $ABSOLUTE_MAX_DURATION_MILLIS"
        }
        return (
            SAMPLE_RATE_HZ.toLong() *
                CHANNEL_COUNT *
                BYTES_PER_SAMPLE *
                durationMillis /
                1_000L
            ).toInt()
    }
}

/** A bounded in-memory WAV payload. No recording is written to disk. */
class Pcm16WavAudio internal constructor(
    val bytes: ByteArray,
    val pcmByteCount: Int,
    val durationMillis: Long,
) {
    val sampleRateHz: Int
        get() = Pcm16AudioFormat.SAMPLE_RATE_HZ

    val channelCount: Int
        get() = Pcm16AudioFormat.CHANNEL_COUNT

    fun erase() {
        bytes.fill(0)
    }

    override fun toString(): String =
        "Pcm16WavAudio(bytes=<redacted:${bytes.size}>, pcmByteCount=$pcmByteCount, " +
            "durationMillis=$durationMillis)"
}

object Pcm16WavEncoder {
    const val HEADER_BYTES = 44
    private const val RIFF_FORMAT_PCM = 1

    fun encode(
        pcm: ByteArray,
        pcmByteCount: Int = pcm.size,
    ): Pcm16WavAudio {
        require(pcmByteCount in 0..pcm.size) { "pcmByteCount is outside the source buffer" }
        require(pcmByteCount % Pcm16AudioFormat.BYTES_PER_SAMPLE == 0) {
            "PCM16 byte count must be sample-aligned"
        }
        require(pcmByteCount <= Pcm16AudioFormat.maxPcmBytes(
            Pcm16AudioFormat.ABSOLUTE_MAX_DURATION_MILLIS,
        )) {
            "PCM exceeds the fixed recording ceiling"
        }

        val output = ByteArray(HEADER_BYTES + pcmByteCount)
        output.writeAscii(0, "RIFF")
        output.writeLeInt(4, output.size - 8)
        output.writeAscii(8, "WAVE")
        output.writeAscii(12, "fmt ")
        output.writeLeInt(16, 16)
        output.writeLeShort(20, RIFF_FORMAT_PCM)
        output.writeLeShort(22, Pcm16AudioFormat.CHANNEL_COUNT)
        output.writeLeInt(24, Pcm16AudioFormat.SAMPLE_RATE_HZ)
        val byteRate =
            Pcm16AudioFormat.SAMPLE_RATE_HZ *
                Pcm16AudioFormat.CHANNEL_COUNT *
                Pcm16AudioFormat.BYTES_PER_SAMPLE
        output.writeLeInt(28, byteRate)
        output.writeLeShort(
            32,
            Pcm16AudioFormat.CHANNEL_COUNT * Pcm16AudioFormat.BYTES_PER_SAMPLE,
        )
        output.writeLeShort(34, Pcm16AudioFormat.BITS_PER_SAMPLE)
        output.writeAscii(36, "data")
        output.writeLeInt(40, pcmByteCount)
        pcm.copyInto(output, destinationOffset = HEADER_BYTES, endIndex = pcmByteCount)

        val sampleCount = pcmByteCount / Pcm16AudioFormat.BYTES_PER_SAMPLE
        val durationMillis =
            sampleCount.toLong() * 1_000L / Pcm16AudioFormat.SAMPLE_RATE_HZ
        return Pcm16WavAudio(
            bytes = output,
            pcmByteCount = pcmByteCount,
            durationMillis = durationMillis,
        )
    }

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.forEachIndexed { index, character ->
            this[offset + index] = character.code.toByte()
        }
    }

    private fun ByteArray.writeLeShort(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.writeLeInt(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }
}

/** Allocation-free PCM16 RMS calculation for microphone chunks. */
object Pcm16Rms {
    private const val SILENCE_DBFS = -96f

    fun dbFs(
        pcm: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Float {
        require(offset >= 0 && byteCount >= 0 && offset + byteCount <= pcm.size) {
            "PCM range is outside the source buffer"
        }
        require(byteCount % Pcm16AudioFormat.BYTES_PER_SAMPLE == 0) {
            "PCM16 byte count must be sample-aligned"
        }
        if (byteCount == 0) return SILENCE_DBFS

        var sumSquares = 0.0
        var index = offset
        val end = offset + byteCount
        while (index < end) {
            val low = pcm[index].toInt() and 0xff
            val high = pcm[index + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += sample.toDouble() * sample.toDouble()
            index += Pcm16AudioFormat.BYTES_PER_SAMPLE
        }
        val sampleCount = byteCount / Pcm16AudioFormat.BYTES_PER_SAMPLE
        val normalizedRms = sqrt(sumSquares / sampleCount) / 32_768.0
        if (normalizedRms <= 0.0) return SILENCE_DBFS
        return (20.0 * log10(normalizedRms)).toFloat().coerceIn(SILENCE_DBFS, 0f)
    }

    /**
     * Maps dBFS to the implementation-defined range consumed by [SpeechRmsNormalizer].
     *
     * Android recognizers commonly report roughly -2..10. Mapping -60..0 dBFS onto that range
     * preserves useful low-volume motion while keeping the existing UI normalization unchanged.
     */
    fun speechUiDb(
        pcm: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Float {
        val dbFs = dbFs(pcm, offset, byteCount)
        return (-2f + ((dbFs + 60f) / 60f) * 12f).coerceIn(-2f, 10f)
    }
}
