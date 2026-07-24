package io.github.ethanbird.senseime.speech

/**
 * Allocation-free append path for waveform amplitudes.
 *
 * [copyOldestFirst] is intended to run at the UI frame rate (not the microphone callback rate).
 */
class FloatWaveformRingBuffer(
    val capacity: Int,
) {
    private val values: FloatArray
    private var writeIndex = 0

    var size: Int = 0
        private set

    var revision: Long = 0L
        private set

    init {
        require(capacity > 0) { "capacity must be positive" }
        values = FloatArray(capacity)
    }

    fun append(value: Float) {
        values[writeIndex] = value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        writeIndex = (writeIndex + 1) % capacity
        if (size < capacity) size += 1
        revision += 1L
    }

    fun appendAll(source: FloatArray) {
        source.forEach(::append)
    }

    fun clear() {
        if (size == 0) return
        values.fill(0f)
        size = 0
        writeIndex = 0
        revision += 1L
    }

    fun copyOldestFirst(destination: FloatArray): Int {
        val count = minOf(size, destination.size)
        if (count == 0) return 0
        val oldest = if (size < capacity) 0 else writeIndex
        val skip = size - count
        var sourceIndex = (oldest + skip) % capacity
        repeat(count) { destinationIndex ->
            destination[destinationIndex] = values[sourceIndex]
            sourceIndex = (sourceIndex + 1) % capacity
        }
        return count
    }

    fun snapshotOldestFirst(): FloatArray =
        FloatArray(size).also(::copyOldestFirst)
}
