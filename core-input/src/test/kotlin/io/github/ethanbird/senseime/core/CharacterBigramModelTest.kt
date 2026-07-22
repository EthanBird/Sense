package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterBigramModelTest {
    @Test
    fun binaryModelFindsSortedCodePointPairs() {
        val data = model(
            Triple('中'.code, '国'.code, 2.5f),
            Triple('我'.code, '是'.code, 3f),
        )
        val model = BinaryCharacterBigramModel.fromBytes(data)

        assertEquals(2, model.size)
        assertEquals(2.5f, model.score('中'.code, '国'.code))
        assertEquals(3f, model.score('我'.code, '是'.code))
        assertEquals(0f, model.score('我'.code, '时'.code))

        val supplementary = BinaryCharacterBigramModel.fromBytes(model(Triple(0x20000, 0x20001, 1.25f)))
        assertEquals(1.25f, supplementary.score(0x20000, 0x20001))
    }

    @Test
    fun malformedOrUnsortedModelsAreRejected() {
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(byteArrayOf(1, 2, 3)) }.isFailure)
        val invalidMagic = model(Triple('我'.code, '是'.code, 1f)).also { it[0] = 'X'.code.toByte() }
        val invalidVersion = model(Triple('我'.code, '是'.code, 1f)).also { it[5] = 2 }
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(invalidMagic) }.isFailure)
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(invalidVersion) }.isFailure)
        val unsorted = model(
            Triple('我'.code, '是'.code, 2f),
            Triple('中'.code, '国'.code, 2f),
            preserveOrder = true,
        )
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(unsorted) }.isFailure)
        val duplicate = model(
            Triple('我'.code, '是'.code, 1f),
            Triple('我'.code, '是'.code, 2f),
            preserveOrder = true,
        )
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(duplicate) }.isFailure)
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(model(Triple('我'.code, '是'.code, 0f))) }.isFailure)
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(model(Triple('我'.code, '是'.code, 0.049f))) }.isFailure)
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(model(Triple('我'.code, '是'.code, 3.01f))) }.isFailure)
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(model(Triple('我'.code, '是'.code, Float.NaN))) }.isFailure)
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(model(Triple('我'.code, '是'.code, Float.POSITIVE_INFINITY))) }.isFailure)
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(model(Triple(0x11_0000, '是'.code, 1f))) }.isFailure)
        assertTrue(runCatching { BinaryCharacterBigramModel.fromBytes(model(Triple(0xD800, '是'.code, 1f))) }.isFailure)
        assertTrue(runCatching {
            BinaryCharacterBigramModel.fromBytes(model(Triple('我'.code, '是'.code, 1f)) + 0)
        }.isFailure)
    }

    private fun model(
        vararg entries: Triple<Int, Int, Float>,
        preserveOrder: Boolean = false,
    ): ByteArray {
        val ordered = if (preserveOrder) entries.toList() else entries.sortedWith(compareBy({ it.first }, { it.second }))
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeBytes("SBGM")
                output.writeShort(1)
                output.writeInt(ordered.size)
                ordered.forEach { (previous, next, score) ->
                    output.writeInt(previous)
                    output.writeInt(next)
                    output.writeFloat(score)
                }
            }
        }.toByteArray()
    }
}
