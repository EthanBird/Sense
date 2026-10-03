package io.github.ethanbird.senseime.service

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BundledAssociationModelTest {
    @Test fun realAssetHashLoadsWordsAndKnownStops() {
        val loaded = BundledAssociationModel.load { asset(it).inputStream() }
        assertEquals(BundledAssociationModel.State.READY, loaded.state)
        assertEquals("早上", requireNotNull(loaded.model).suggest("今天", 8).first().text)
        assertTrue(requireNotNull(loaded.model).suggest("你好", 8).isEmpty())
    }

    @Test fun missingCorruptTruncatedAndOversizedAssetsRetainLegacyFallback() {
        assertEquals(BundledAssociationModel.State.MISSING,
            BundledAssociationModel.load { throw FileNotFoundException() }.state)
        val bytes = asset(BundledAssociationModel.ASSET).readBytes()
        for (value in listOf(byteArrayOf(1), bytes + byteArrayOf(0), bytes.copyOf().also { it[40] = 0 })) {
            val loaded = BundledAssociationModel.load { ByteArrayInputStream(value) }
            assertEquals(BundledAssociationModel.State.INVALID, loaded.state)
            assertNull(loaded.model)
        }
    }

    @Test fun ioErrorStillClosesAsset() {
        var closed = false
        val loaded = BundledAssociationModel.load {
            object : InputStream() {
                override fun read(): Int = throw IOException()
                override fun close() { closed = true }
            }
        }
        assertTrue(closed)
        assertEquals(BundledAssociationModel.State.IO_ERROR, loaded.state)
        assertNull(loaded.model)
    }

    private fun asset(name: String): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .map { File(it, "ime-service/src/main/assets/$name") }.first { it.isFile }
}
