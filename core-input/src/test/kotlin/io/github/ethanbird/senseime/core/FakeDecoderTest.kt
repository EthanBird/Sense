package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDecoderTest {
    private val decoder = FakeDecoder()

    @Test
    fun knownPinyinReturnsChineseCandidates() {
        assertEquals(listOf("你好", "你号"), decoder.decode("NiHao", limit = 2).map { it.text })
    }

    @Test
    fun unknownPinyinRemainsVisibleForM0Fallback() {
        assertEquals("codex", decoder.decode("codex").single().text)
    }

    @Test
    fun blankInputHasNoCandidates() {
        assertTrue(decoder.decode(" ").isEmpty())
    }
}

