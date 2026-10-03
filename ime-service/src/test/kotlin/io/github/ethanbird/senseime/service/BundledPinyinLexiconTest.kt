package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.CharacterBigramModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import org.junit.Assert.*
import org.junit.Test

class BundledPinyinLexiconTest {
    @Test fun overlappingServiceStartsPublishOneImmutableBaseWithoutCachingFailedLoads() {
        val cache = ProcessPinyinLexiconCache()
        assertThrows(IllegalStateException::class.java) { cache.getOrLoad { error("synthetic first load failure") } }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
        val loads = java.util.concurrent.atomic.AtomicInteger()
        try {
            val results = (1..12).map { workers.submit<io.github.ethanbird.senseime.core.PinyinDecoder> {
                cache.getOrLoad {
                    loads.incrementAndGet()
                    BundledPinyinLexicon.load(CharacterBigramModel.EMPTY) { File("src/main/assets/$it").inputStream() }
                }
            } }.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, loads.get())
            results.forEach { assertSame(results.first(), it) }
            assertSame(results.first(), cache.getOrLoad { error("a new Service reloaded the immutable pack") })
        } finally { workers.shutdownNow() }
    }

    @Test fun actualPinnedAssetLoadsUsingOneDestinationArrayAndClosesItsStream() {
        val buffers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray, Boolean>())
        var closed = false
        val input = object : FilterInputStream(File("src/main/assets/pinyin_lexicon.bin").inputStream()) {
            override fun available(): Int = 0 // not a length contract
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffers.add(buffer)
                return super.read(buffer, offset, minOf(length, 32_768))
            }
            override fun close() { closed = true; super.close() }
        }
        val decoder = BundledPinyinLexicon.load(CharacterBigramModel.EMPTY) { input }
        assertTrue(closed)
        assertEquals(1, buffers.size)
        assertEquals("你好", decoder.decode("nihao", 5).first().text)
    }

    @Test fun truncatedAssetFailsAndClosesInsteadOfPublishingAPartialDictionary() {
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(0)) {
            override fun close() { closed = true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BundledPinyinLexicon.load(CharacterBigramModel.EMPTY) { input }
        }
        assertTrue(closed)
    }
}
