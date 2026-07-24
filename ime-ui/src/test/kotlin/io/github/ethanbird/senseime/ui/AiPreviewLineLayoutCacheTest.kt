package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPreviewLineLayoutCacheTest {
    @Test
    fun sameTextIdentityAndGeometryReusePrimitiveLineRanges() {
        val cache = AiPreviewLineLayoutCache()
        val text = "Sense AI streaming preview"
        var breakCalls = 0
        val breaker = { _: String, start: Int, end: Int, _: Float ->
            breakCalls++
            minOf(6, end - start)
        }

        assertTrue(cache.ensure(text, 100f, 15f, breaker))
        val callsAfterLayout = breakCalls
        assertFalse(cache.ensure(text, 100f, 15f, breaker))
        assertEquals(callsAfterLayout, breakCalls)
        assertTrue(cache.ensure(text, 80f, 15f, breaker))
        assertTrue(breakCalls > callsAfterLayout)
    }

    @Test
    fun rangesUsePrimitiveOffsetsAndPreserveExplicitEmptyLines() {
        val cache = AiPreviewLineLayoutCache(initialLineCapacity = 1)
        val text = "ab\n\ncd"
        cache.ensure(text, 100f, 15f) { _, start, end, _ -> end - start }

        assertEquals(3, cache.lineCount)
        assertEquals("ab", text.substring(cache.startAt(0), cache.endAt(0)))
        assertEquals(cache.startAt(1), cache.endAt(1))
        assertEquals("cd", text.substring(cache.startAt(2), cache.endAt(2)))
    }
}
