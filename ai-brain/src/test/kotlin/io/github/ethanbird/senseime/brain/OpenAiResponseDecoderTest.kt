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
    fun `stream EOF without DONE or finish reason is a retryable truncation`() {
        val decoder = OpenAiResponseDecoder(
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            streaming = true,
        )
        decoder.feed(
            "data: {\"choices\":[{\"delta\":{\"content\":\"half\"}}]}\n\n".toByteArray(),
        )

        val terminal = decoder.finish().single() as ProviderContentEvent.Error

        assertTrue(terminal.retryable)
        assertEquals(OpenAiResponseDecoder.UNEXPECTED_STREAM_EOF, terminal.providerCode)
    }

    @Test
    fun `chat finish reason completes immediately and drops late DONE and EOF`() {
        val decoder = OpenAiResponseDecoder(
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            streaming = true,
        )
        val streamed = decoder.feed(
            (
                "data: {\"choices\":[{\"delta\":{\"content\":\"complete\"}," +
                    "\"finish_reason\":\"stop\"}]}\n\n"
                ).toByteArray(),
        )

        assertEquals(
            listOf(
                ProviderContentEvent.TextDelta("complete"),
                ProviderContentEvent.Completed(),
            ),
            streamed,
        )
        assertEquals(emptyList<ProviderContentEvent>(), decoder.feed("data: [DONE]\n\n".toByteArray()))
        assertEquals(emptyList<ProviderContentEvent>(), decoder.finish())
    }

    @Test
    fun `DeepSeek reasoning content becomes only a safe activity marker`() {
        val decoder = OpenAiResponseDecoder(
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            streaming = true,
        )
        val events = decoder.feed(
            (
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":" +
                    "\"private chain of thought\"}}]}\n\n"
                ).toByteArray(),
        )

        assertEquals(listOf(ProviderContentEvent.ReasoningActivity), events)
        assertTrue(events.none { it is ProviderContentEvent.TextDelta })
        assertTrue(events.toString().contains("private chain of thought").not())
    }

    @Test
    fun `DeepSeek streams native tool argument fragments without interpreting them`() {
        val decoder = OpenAiResponseDecoder(
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            streaming = true,
        )
        val events = decoder.feed(
            (
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                    "\"id\":\"call-1\",\"function\":{\"name\":\"sense_submit_patch\"," +
                    "\"arguments\":\"{\\\"description\\\":\\\"已改\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                    "\"function\":{\"arguments\":\"写\\\",\\\"patch\\\":{}}\"}}]}," +
                    "\"finish_reason\":\"tool_calls\"}]}\n\n"
                ).toByteArray(),
        )

        assertEquals(
            listOf(
                ProviderContentEvent.ToolCallDelta(
                    index = 0,
                    id = "call-1",
                    name = "sense_submit_patch",
                    arguments = "{\"description\":\"已改",
                ),
                ProviderContentEvent.ToolCallDelta(
                    index = 0,
                    arguments = "写\",\"patch\":{}}",
                ),
                ProviderContentEvent.Completed(),
            ),
            events,
        )
    }

    @Test
    fun `terminal finish reasons distinguish token exhaustion and transient capacity`() {
        fun decode(reason: String): ProviderContentEvent = OpenAiResponseDecoder(
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            streaming = true,
        ).feed(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\n"
                .toByteArray(),
        ).single()

        assertEquals(
            ProviderContentEvent.Error(
                message = "provider output reached its token limit",
                providerCode = "finish_reason_length",
            ),
            decode("length"),
        )
        assertEquals(
            ProviderContentEvent.Error(
                message = "provider has insufficient system resources",
                retryable = true,
                providerCode = "insufficient_system_resource",
            ),
            decode("insufficient_system_resource"),
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
    fun `non streaming Chat extracts a native tool call and usage`() {
        val decoder = OpenAiResponseDecoder(
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            streaming = false,
        )
        decoder.feed(
            """
            {"choices":[{"message":{"tool_calls":[{"id":"call-1","type":"function",
            "function":{"name":"sense_submit_patch","arguments":"{\"description\":\"完成\"}"}}],
            "content":null},"finish_reason":"tool_calls"}],
            "usage":{"prompt_tokens":12,"completion_tokens":3}}
            """.trimIndent().toByteArray(),
        )

        assertEquals(
            listOf(
                ProviderContentEvent.ToolCallDelta(
                    index = 0,
                    id = "call-1",
                    name = "sense_submit_patch",
                    arguments = "{\"description\":\"完成\"}",
                ),
                ProviderContentEvent.Usage(12, 3),
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
    fun `provider error retains only bounded classification metadata`() {
        val decoder = OpenAiResponseDecoder(
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            streaming = true,
        )
        val events = decoder.feed(
            (
                "data: {\"error\":{\"message\":\"Too many requests\"," +
                    "\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"," +
                    "\"status\":429}}\n\n"
                ).toByteArray(),
        )

        assertEquals(
            ProviderContentEvent.Error(
                message = "Too many requests",
                type = "rate_limit_error",
                providerCode = "rate_limit_exceeded",
                statusCode = 429,
            ),
            events.single(),
        )
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

    @Test
    fun `streaming preview scans one-character fragments exactly once`() {
        val preview = StreamingPatchPreview()
        val source = """{"operation":{"type":"replace","text":"先\n\ud83e\udde0思"}}"""
        val visible = buildString {
            source.forEach { char -> append(preview.append(char.toString())) }
        }

        assertEquals("先\n🧠思", visible)
        assertEquals(source.length.toLong(), preview.scannedCharCount)
    }

    @Test
    fun `native tool accumulator streams public description and patch text then closes contract`() {
        val accumulator = NativePatchToolAccumulator()
        val first = accumulator.append(
            """{"description":"已润色","patch":{"protocol":"sense.editor.patch.v1","text":"先""",
        )
        val second = accumulator.append("""思"}}""")
        val submission = accumulator.finish()

        assertEquals("已润色", first.description)
        assertEquals("先", first.patchText)
        assertEquals("", second.description)
        assertEquals("思", second.patchText)
        assertEquals("已润色", submission.description)
        assertTrue(submission.patchDocument.contains("\"text\":\"先思\""))
    }

    @Test
    fun `native tool accumulator supports reordered fields with one-character fragments`() {
        val accumulator = NativePatchToolAccumulator()
        val source =
            """{"patch":{"protocol":"sense.editor.patch.v1","text":"先\ud83e\udde0思"},"description":"后置描述"}"""
        val description = StringBuilder()
        val patch = StringBuilder()

        source.forEach { char ->
            val delta = accumulator.append(char.toString())
            description.append(delta.description)
            patch.append(delta.patchText)
        }
        val submission = accumulator.finish()

        assertEquals("后置描述", description.toString())
        assertEquals("先🧠思", patch.toString())
        assertEquals("后置描述", submission.description)
        assertTrue(submission.patchDocument.contains("\"text\":\"先🧠思\""))
        assertEquals(source.length.toLong(), accumulator.scannedCharCount)
    }

    @Test(expected = ProviderPayloadException::class)
    fun `native tool description rejects line separators and bidi controls`() {
        NativePatchToolAccumulator().append(
            "{\"description\":\"安全\u2028伪装\",\"patch\":{}}",
        )
    }
}
