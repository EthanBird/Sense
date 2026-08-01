package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Wubi86LexiconTest {
    private val lexicon by lazy {
        val asset = File("../ime-service/src/main/assets/wubi86_lexicon.bin")
        require(asset.isFile) { "Missing production Wubi asset: ${asset.absolutePath}" }
        asset.inputStream().use(Wubi86Lexicon::load)
    }

    @Test
    fun officialLevelOneShortCodesRemainPrimaryExactCandidates() {
        assertEquals("工", lexicon.lookup("a").exact.first().text)
        assertEquals("了", lexicon.lookup("b").exact.first().text)
        assertEquals("我", lexicon.lookup("q").exact.first().text)
        assertEquals("的", lexicon.lookup("r").exact.first().text)
        assertEquals(99_054, lexicon.metrics.exactGroups)
        assertEquals(15_259, lexicon.metrics.prefixGroups)
        assertEquals(70_386, lexicon.metrics.reverseEntries)
        assertTrue(lexicon.metrics.estimatedRetainedBytes < 9L * 1024L * 1024L)
    }

    @Test
    fun exactCandidatesAlwaysPrecedeCompletions() {
        val lookup = lexicon.lookup("a", limit = 32)

        assertTrue(lookup.exact.isNotEmpty())
        assertTrue(lookup.completions.isNotEmpty())
        assertTrue(lookup.candidates.take(lookup.exact.size).all { it.matchKind == WubiMatchKind.EXACT })
        assertTrue(lookup.candidates.drop(lookup.exact.size).all { it.matchKind == WubiMatchKind.COMPLETION })
    }

    @Test
    fun fourCodeLookupDoesNotMixPrefixCompletions() {
        val lookup = lexicon.lookup("aaaa", limit = 32)

        assertTrue(lookup.exact.any { it.text == "工" })
        assertTrue(lookup.completions.isEmpty())
    }

    @Test
    fun reverseIndexReturnsShortCodeBeforeLongCode() {
        val codes = lexicon.codesFor("工".codePointAt(0))

        assertEquals("a", codes.first())
        assertTrue("aaaa" in codes)
    }

    @Test
    fun compositionKeepsMaximumFourCodeAndRevisionedDeletion() {
        val full = "aaaaq".fold(WubiComposition()) { value, key -> value.type(key) }
        assertEquals("aaaa", full.code)

        val deleted = full.backspace()
        assertEquals("aaa", deleted.code)
        assertTrue(deleted.revision > full.revision)
    }

    @Test
    fun zEntersReversiblePinyinLookupWithoutPollutingDirectCode() {
        val reverse = "zhao".fold(WubiComposition()) { value, key -> value.type(key) }

        assertTrue(reverse.isReverseLookup)
        assertEquals("zhao", reverse.visibleCode)
        assertEquals("", reverse.code)
        assertEquals("zha", reverse.backspace().visibleCode)
    }

    @Test
    fun impossibleHeaderCountsAreRejectedBeforeIndexAllocation() {
        val malformed = byteArrayOf(
            'S'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'X'.code.toByte(),
            0, 1,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0, 0, 0, 1,
            0, 0, 0, 1,
        )

        val failure = runCatching { Wubi86Lexicon.fromBytes(malformed) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("asset boundary"))
    }
}
