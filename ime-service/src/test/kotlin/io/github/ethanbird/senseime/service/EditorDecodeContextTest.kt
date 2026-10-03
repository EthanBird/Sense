package io.github.ethanbird.senseime.service

import org.junit.Assert.*
import org.junit.Test

class EditorDecodeContextTest {
    @Test fun unicodeScalarSuffixMatchesTheTrigramWindow() {
        val alphabet = listOf("好", "𠀀", "。", " ", "🙂")
        for (a in alphabet) for (b in alphabet) for (c in alphabet) {
            assertEquals(b + c, EditorDecodeContext.retain(a + b + c))
        }
    }

    @Test fun shortNullAndMalformedHostValuesStayBounded() {
        assertEquals("", EditorDecodeContext.retain(null))
        assertEquals("", EditorDecodeContext.retain(""))
        assertEquals("𠀀", EditorDecodeContext.retain("𠀀"))
        assertEquals("好", EditorDecodeContext.retain("好"))
        assertEquals("\uDC00好", EditorDecodeContext.retain("\uDC00好"))
        assertEquals("好\uD840", EditorDecodeContext.retain("好\uD840"))
    }

    @Test fun slicesAnOverReturningHostBeforeConvertingToString() {
        val host = object : CharSequence by ("old text ".repeat(1_000) + "𠀀好") {
            override fun toString(): String = error("Do not copy the entire host response")
        }
        assertEquals("𠀀好", EditorDecodeContext.retain(host))
    }
}
