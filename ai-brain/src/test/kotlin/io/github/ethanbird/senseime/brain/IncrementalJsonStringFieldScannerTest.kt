package io.github.ethanbird.senseime.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IncrementalJsonStringFieldScannerTest {
    @Test
    fun `one-character fragments are decoded once and remain linear`() {
        val scanner = IncrementalJsonStringFieldScanner(setOf("description", "text"))
        val source =
            """{"description":"流式处理","patch":{"operation":{"text":"先\n\"思\"\\\ud83e\udde0"}}}"""
        val observed = linkedMapOf<String, StringBuilder>()

        source.forEach { char ->
            scanner.append(char.toString()).forEach { (field, delta) ->
                observed.getOrPut(field, ::StringBuilder).append(delta)
            }
        }

        assertEquals("流式处理", observed.getValue("description").toString())
        assertEquals("先\n\"思\"\\🧠", observed.getValue("text").toString())
        assertEquals(source.length.toLong(), scanner.scannedCharCount)
    }

    @Test
    fun `reordered fields and JSON-looking text inside another value are not confused`() {
        val scanner = IncrementalJsonStringFieldScanner(setOf("description", "text"))
        val source =
            """{"patch":{"text":"先思"},"ignored":"{\"description\":\"伪造\",\"text\":\"伪造\"}","description":"后置描述"}"""
        val observed = linkedMapOf<String, StringBuilder>()

        source.chunked(3).forEach { fragment ->
            scanner.append(fragment).forEach { (field, delta) ->
                observed.getOrPut(field, ::StringBuilder).append(delta)
            }
        }

        assertEquals("先思", observed.getValue("text").toString())
        assertEquals("后置描述", observed.getValue("description").toString())
        assertEquals(source.length.toLong(), scanner.scannedCharCount)
    }

    @Test
    fun `escaped key and surrogate pair split at every boundary emit only complete Unicode`() {
        val source = """{"te\u0078t":"A\ud83e\udde0B"}"""

        for (split in 1 until source.length) {
            val scanner = IncrementalJsonStringFieldScanner(setOf("text"))
            val first = scanner.append(source.substring(0, split))["text"].orEmpty()
            val second = scanner.append(source.substring(split))["text"].orEmpty()
            val value = first + second

            assertEquals("A🧠B", value)
            assertFalse(value.last().isHighSurrogate())
            assertEquals(source.length.toLong(), scanner.scannedCharCount)
        }
    }

    @Test(expected = ProviderPayloadException::class)
    fun `malformed escaped surrogate is rejected without emitting half a scalar`() {
        IncrementalJsonStringFieldScanner(setOf("text"))
            .append("""{"text":"\ud83eX"}""")
    }
}
