package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponseDecoderTest {
    @Test
    fun `Responses SSE normalizes fragmented delta and usage`() {
        val source = (
            "event: response.output_text.delta\n" +
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"先思🧠\"}\n\n" +
                "event: response.completed\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}}\n\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val decoder = OpenAiResponseDecoder(ProviderApiStyle.OPENAI_RESPONSES, streaming = true)
        val events = mutableListOf<ProviderContentEvent>()

        source.forEach { byte -> events += decoder.feed(byteArrayOf(byte)) }

        assertEquals(ProviderContentEvent.TextDelta("先思🧠"), events[0])
        assertEquals(ProviderContentEvent.Usage(7, 3), events[1])
        assertTrue(events[2] is ProviderContentEvent.Completed)
    }

    @Test
    fun `Chat SSE joins content and DONE`() {
        val decoder = OpenAiResponseDecoder(
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            streaming = true,
        )
        val source = (
            "data: {\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"world\"}}]}\n\n" +
                "data: [DONE]\n\n"
            ).toByteArray()

        val events = decoder.feed(source)

        assertEquals(
            listOf(
                ProviderContentEvent.TextDelta("hello "),
                ProviderContentEvent.TextDelta("world"),
                ProviderContentEvent.Completed(),
            ),
            events,
        )
    }

    @Test
    fun `non streaming Responses extracts output text`() {
        val decoder = OpenAiResponseDecoder(ProviderApiStyle.OPENAI_RESPONSES, streaming = false)
        decoder.feed(
            """
            {"output":[{"content":[{"type":"output_text","text":"result"}]}],
             "usage":{"input_tokens":2,"output_tokens":1}}
            """.trimIndent().toByteArray(),
        )

        assertEquals(
            listOf(
                ProviderContentEvent.Usage(2, 1),
                ProviderContentEvent.TextDelta("result"),
                ProviderContentEvent.Completed(),
            ),
            decoder.finish(),
        )
    }

    @Test
    fun `provider error is normalized without leaking structure`() {
        val decoder = OpenAiResponseDecoder(ProviderApiStyle.OPENAI_RESPONSES, streaming = true)
        val events = decoder.feed(
            "event: error\ndata: {\"error\":{\"message\":\"quota\",\"retryable\":false}}\n\n"
                .toByteArray(),
        )

        assertEquals(ProviderContentEvent.Error("quota", false), events.single())
    }

    @Test
    fun `streaming preview exposes only operation text`() {
        val preview = StreamingPatchPreview()

        assertEquals("", preview.append("""{"operation":{"type":"replace","te"""))
        assertEquals("先", preview.append("""xt":"先"""))
        assertEquals("思\n输入法", preview.append("""思\n输入法"}"""))
        assertEquals("", preview.append("}"))
    }

    @Test
    fun `streaming preview buffers an escaped surrogate pair split between deltas`() {
        val preview = StreamingPatchPreview()

        assertEquals("", preview.append("""{"operation":{"text":"\ud83e"""))
        assertEquals("🧠", preview.append("""\udde0"}}"""))
    }
}
