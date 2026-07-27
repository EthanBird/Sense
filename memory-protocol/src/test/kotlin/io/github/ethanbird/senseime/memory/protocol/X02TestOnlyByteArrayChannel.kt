package io.github.ethanbird.senseime.memory.protocol

import java.util.concurrent.atomic.AtomicReference

/**
 * Test-only bounded transport for aliasing and arbitrary-byte fault injection.
 *
 * It intentionally defines no snapshot framing, version, generation, digest, slot, or parser.
 */
internal class X02TestOnlyByteArrayChannel(
    private val maxBytes: Int,
) {
    private val cell = AtomicReference<ByteArray?>(null)

    init {
        require(maxBytes in 1..ABSOLUTE_MAX_TEST_BYTES)
    }

    fun send(bytes: ByteArray) {
        require(bytes.size <= maxBytes)
        cell.set(bytes.copyOf())
    }

    fun receiveCopy(): ByteArray? = cell.get()?.copyOf()

    fun clear() {
        cell.set(null)
    }

    private companion object {
        const val ABSOLUTE_MAX_TEST_BYTES = 1_048_576
    }
}
