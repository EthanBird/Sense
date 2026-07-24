package io.github.ethanbird.senseime.ui

enum class VoiceSurfacePhase {
    STARTING,
    LISTENING,
    PROCESSING,
    ERROR,
}

data class VoiceSurfaceState(
    val sessionId: Long,
    val revision: Long,
    val phase: VoiceSurfacePhase,
    val providerName: String,
    val visibleText: String = "",
    val statusText: String,
    val waveformLevel: Float = 0f,
    val usingOnDeviceRecognizer: Boolean = false,
)

internal object VoiceSurfaceUpdatePolicy {
    fun accepts(current: VoiceSurfaceState, next: VoiceSurfaceState): Boolean =
        next.sessionId == current.sessionId && next.revision > current.revision
}

internal object VoiceSurfaceControlPolicy {
    fun primaryKeyCode(phase: VoiceSurfacePhase): Int = when (phase) {
        VoiceSurfacePhase.STARTING,
        VoiceSurfacePhase.LISTENING,
        -> KeyCodes.VOICE_DONE

        VoiceSurfacePhase.PROCESSING -> 0
        VoiceSurfacePhase.ERROR -> KeyCodes.VOICE_RETRY
    }

    fun primaryLabel(phase: VoiceSurfacePhase): String = when (phase) {
        VoiceSurfacePhase.STARTING,
        VoiceSurfacePhase.LISTENING,
        -> "说完了"

        VoiceSurfacePhase.PROCESSING -> "正在识别…"
        VoiceSurfacePhase.ERROR -> "重新开始"
    }
}

/**
 * Allocation-free fixed history used by the keyboard waveform renderer.
 *
 * Recognition callbacks only append a normalized level. Drawing copies the samples into a
 * caller-owned array, so neither PCM nor boxed collections ever reach the UI thread.
 */
class VoiceWaveformBuffer(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    private val samples = FloatArray(capacity)
    private var next = 0
    private var count = 0

    init {
        require(capacity > 1)
    }

    val size: Int
        get() = count

    fun append(level: Float) {
        samples[next] = level.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Copies oldest-to-newest samples and returns the number written.
     */
    fun copyInto(destination: FloatArray): Int {
        require(destination.size >= capacity)
        val first = (next - count + capacity) % capacity
        repeat(count) { index ->
            destination[index] = samples[(first + index) % capacity]
        }
        return count
    }

    fun clear() {
        samples.fill(0f)
        next = 0
        count = 0
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
