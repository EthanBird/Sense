package io.github.ethanbird.senseime.memory.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class X02TestOnlyByteArrayChannelTest {
    @Test
    fun sendAndReceiveAreBothDefensiveCopies() {
        val channel = X02TestOnlyByteArrayChannel(maxBytes = 8)
        val source = byteArrayOf(1, 2, 3, 4)

        channel.send(source)
        source[0] = 99
        val firstRead = channel.receiveCopy()!!
        firstRead[1] = 88

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), channel.receiveCopy())
    }

    @Test
    fun emptyAndEveryBoundedLengthRoundTripExactly() {
        val channel = X02TestOnlyByteArrayChannel(maxBytes = 256)
        for (size in 0..256) {
            val bytes = ByteArray(size) { index -> (index xor size).toByte() }

            channel.send(bytes)

            assertArrayEquals(bytes, channel.receiveCopy())
        }
    }

    @Test
    fun oversizeAndInvalidCapsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            X02TestOnlyByteArrayChannel(maxBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            X02TestOnlyByteArrayChannel(maxBytes = 1_048_577)
        }
        val channel = X02TestOnlyByteArrayChannel(maxBytes = 4)
        assertThrows(IllegalArgumentException::class.java) {
            channel.send(ByteArray(5))
        }
    }

    @Test
    fun clearDropsTheOnlyInMemoryReference() {
        val channel = X02TestOnlyByteArrayChannel(maxBytes = 4)
        channel.send(byteArrayOf(1, 2, 3))

        channel.clear()

        assertNull(channel.receiveCopy())
    }

    @Test
    fun arbitraryFaultBytesAreTransportedWithoutInventingAProtocol() {
        val channel = X02TestOnlyByteArrayChannel(maxBytes = 64)
        val seed = ByteArray(64) { index -> (index * 31).toByte() }

        seed.indices.forEach { index ->
            val mutated = seed.copyOf()
            mutated[index] = (mutated[index].toInt() xor 0x80).toByte()
            channel.send(mutated)
            assertArrayEquals(mutated, channel.receiveCopy())
        }
    }
}
