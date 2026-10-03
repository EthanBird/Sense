package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class PinyinDecoderResourceTest {
    private fun header(count: Int) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.writeBytes("SPLX"); it.writeShort(3); it.writeInt(count) }
    }.toByteArray()

    @Test fun inconsistentCountIsRejectedBeforeAllocatingTheOffsetTable() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PinyinDecoder.fromBytes(header(1_000_001))
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("does not fit"))
    }

    @Test fun offsetTableHasAnExplicitFiniteUpperBudget() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PinyinDecoder.fromBytes(header(1_250_001))
        }
        assertTrue(error.message.orEmpty().contains("record count is invalid"))
    }

    @Test fun assetByteBudgetIsCheckedBeforeParsingRecords() {
        val data = ByteArray(64 * 1024 * 1024 + 1)
        header(1).copyInto(data)
        val error = assertThrows(IllegalArgumentException::class.java) { PinyinDecoder.fromBytes(data) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("asset exceeds"))
    }

    private fun validRecord() = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(header(1)); out.writeByte(2); out.writeBytes("ni"); out.writeByte(1)
                val text = "你".toByteArray(); out.writeByte(text.size); out.write(text)
                out.writeInt(10); out.writeByte(1); out.writeBytes("n"); out.writeByte(0)
            }
        }.toByteArray()
    @Test fun smallWellFormedRecordStillDecodes() {
        assertEquals("你", PinyinDecoder.fromBytes(validRecord()).decode("ni").first().text)
    }

    @Test fun inputStreamStopsReadingAsSoonAsThePayloadBudgetIsExceeded() {
        val budget = 64 * 1024 * 1024
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int = if (consumed < budget + 16_384) { consumed++; 0 } else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = minOf(length, budget + 16_384 - consumed)
                if (count == 0) return -1
                bytes.fill(0, offset, offset + count); consumed += count; return count
            }
            override fun available() = Int.MAX_VALUE
        }
        val error = assertThrows(IllegalArgumentException::class.java) { PinyinDecoder.load(input) }
        assertTrue(error.message.orEmpty().contains("asset exceeds"))
        assertEquals("one bounded sentinel byte is enough to reject", budget + 1, consumed)
    }

    @Test fun shortReadsAndZeroByteReadsProgressWithoutTrustingAvailable() {
        val delegate = validRecord().inputStream()
        var calls = 0
        val input = object : InputStream() {
            override fun available() = Int.MAX_VALUE
            override fun read() = delegate.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                if (++calls % 2 == 1) 0 else delegate.read(bytes, offset, minOf(1, length))
        }
        assertEquals("你", PinyinDecoder.load(input).decode("ni").first().text)
        assertTrue(calls in 1..100)
    }
}
