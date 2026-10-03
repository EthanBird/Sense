package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.CharacterLanguageModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BundledPinyinLanguageModelTest {
    @Test fun packagedModelHasTheFrozenHashAndLoadsKnownChineseTransitions() {
        val result = BundledPinyinLanguageModel.load { name -> asset(name).inputStream() }
        assertEquals(BundledPinyinLanguageModel.State.READY, result.state)
        assertTrue(result.model.containsCodePoint('我'.code))
        assertTrue(result.model.logProbability(CharacterLanguageModel.BOS, '我'.code, '们'.code).isFinite())
        assertEquals(.5f, BundledPinyinLanguageModel.WEIGHT, 0f)
    }

    @Test fun disabledMissingTruncatedWrongHashAndOversizeAllKeepTheLegacyModel() {
        val off = BundledPinyinLanguageModel.load(enabled = false) { error("disabled loader performed I/O") }
        assertEquals(BundledPinyinLanguageModel.State.DISABLED, off.state)
        assertSame(CharacterLanguageModel.EMPTY, off.model)
        val missing = BundledPinyinLanguageModel.load { throw FileNotFoundException("fixture") }
        assertEquals(BundledPinyinLanguageModel.State.MISSING, missing.state)
        val bytes = asset(BundledPinyinLanguageModel.ASSET).readBytes()
        for (input in listOf(byteArrayOf(1), bytes.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }, bytes + byteArrayOf(0))) {
            val result = BundledPinyinLanguageModel.load { ByteArrayInputStream(input) }
            assertEquals(BundledPinyinLanguageModel.State.INVALID, result.state)
            assertSame(CharacterLanguageModel.EMPTY, result.model)
        }
    }

    @Test fun ioFailureClosesTheStreamAndDoesNotReplaceTheDecoderWithAnEmptyCandidateProvider() {
        var closed = false
        val result = BundledPinyinLanguageModel.load {
            object : InputStream() {
                override fun read(): Int = throw IOException("synthetic read failure")
                override fun close() { closed = true }
            }
        }
        assertTrue(closed)
        assertEquals(BundledPinyinLanguageModel.State.IO_ERROR, result.state)
        assertSame(CharacterLanguageModel.EMPTY, result.model)
    }

    private fun asset(name: String): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .map { File(it, "ime-service/src/main/assets/$name") }.first { it.isFile }
}
