package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainIpcTextChunkerTest {
    @Test
    fun emptyTextHasNoBinderFrames() {
        assertTrue(BrainIpcTextChunker.chunk("").isEmpty())
    }

    @Test
    fun boundedTextStaysInOneFrame() {
        assertEquals(listOf("先思 AI"), BrainIpcTextChunker.chunk("先思 AI", maxChars = 8))
    }

    @Test
    fun longTextRoundTripsWithoutOversizedFrames() {
        val text = "中文".repeat(2_100)
        val chunks = BrainIpcTextChunker.chunk(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= BrainIpcTextChunker.DEFAULT_MAX_CHARS })
    }

    @Test
    fun surrogatePairIsNeverSplit() {
        val text = "1234😀5678"
        val chunks = BrainIpcTextChunker.chunk(text, maxChars = 5)
        assertEquals(text, chunks.joinToString(""))
        chunks.forEach { chunk ->
            assertTrue(chunk.firstOrNull()?.isLowSurrogate() != true)
            assertTrue(chunk.lastOrNull()?.isHighSurrogate() != true)
        }
    }
}
