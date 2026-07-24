package io.github.ethanbird.senseime.brain

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SseDecoderTest {
    @Test
    fun `arbitrary byte fragments preserve Chinese and emoji`() {
        val source = (
            "\uFEFFevent: response.output_text.delta\r\n" +
                "data: {\"delta\":\"先思🧠\"}\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val decoder = SseDecoder()
        val events = mutableListOf<SseEvent>()

        source.forEach { byte -> events += decoder.feed(byteArrayOf(byte)) }
        events += decoder.finish()

        assertEquals(
            listOf(
                SseEvent(
                    event = "response.output_text.delta",
                    data = "{\"delta\":\"先思🧠\"}",
                    id = null,
                ),
            ),
            events,
        )
    }

    @Test
    fun `multiple data lines are joined and comments ignored`() {
        val decoder = SseDecoder()
        val source = ": ping\ndata: first\ndata: second\nid: 42\n\n"

        val events = decoder.feed(source.toByteArray()) + decoder.finish()

        assertEquals(SseEvent(null, "first\nsecond", "42"), events.single())
    }

    @Test
    fun `unterminated final event is emitted at EOF`() {
        val decoder = SseDecoder()
        decoder.feed("event: done\ndata: ok".toByteArray())

        assertEquals(SseEvent("done", "ok", null), decoder.finish().single())
    }

    @Test
    fun `invalid UTF8 is rejected`() {
        val decoder = SseDecoder()

        assertThrows(ProviderPayloadException::class.java) {
            decoder.feed(byteArrayOf(0xC3.toByte(), '\n'.code.toByte()))
        }
    }

    @Test
    fun `line size is bounded across fragments`() {
        val decoder = SseDecoder(maxLineBytes = 4, maxEventBytes = 8)
        decoder.feed("data".toByteArray())

        assertThrows(ProviderPayloadException::class.java) {
            decoder.feed(":".toByteArray())
        }
    }
}
