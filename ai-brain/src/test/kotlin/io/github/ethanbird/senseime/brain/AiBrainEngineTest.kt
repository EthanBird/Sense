package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessPhase
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.api.BrainRunSpec
import io.github.ethanbird.senseime.brain.api.MonotonicClock
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCall
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderFailureKind
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderResponseMetadata
import io.github.ethanbird.senseime.brain.api.ProviderStreamSink
import io.github.ethanbird.senseime.brain.api.ProviderTimeouts
import io.github.ethanbird.senseime.brain.api.ProviderTransport
import io.github.ethanbird.senseime.brain.api.ProviderTransportFailure
import io.github.ethanbird.senseime.brain.api.ProviderWireRequest
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.api.ThinkingMode
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBrainEngineTest {
    @Test
    fun `valid fragmented stream emits human preview then validated patch`() {
        val fixture = Fixture()
        val handle = fixture.start()
        val patch = fixture.patch("改写后的文字")

        fixture.transport.open(0)
        fixture.transport.bytes(0, chatDelta(patch), oneByteAtATime = true)
        fixture.transport.bytes(0, "data: [DONE]\n\n")

        assertTrue(handle.isTerminal)
        assertEquals("改写后的文字", fixture.events.filterIsInstance<AiEvent.PreviewDelta>()
            .joinToString("") { it.text })
        assertEquals(
            "改写后的文字",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>().single().patch.operation.text,
        )
        assertFalse(fixture.events.any { it is AiEvent.Failed })
    }

    @Test
    fun `DeepSeek native tool streams public description and validates its nested patch`() {
        val fixture = Fixture(deepSeekNative = true)
        val handle = fixture.start()
        val arguments =
            "{\"description\":\"已完成润色\",\"patch\":${fixture.patch("原生工具结果")}}"

        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":" +
                "\"this must remain private\"}}]}\n\n",
        )
        fixture.transport.bytes(0, nativeToolDelta(arguments), oneByteAtATime = true)
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertTrue(handle.isTerminal)
        assertEquals(1, fixture.events.filterIsInstance<AiEvent.FinalPatch>().size)
        assertEquals(
            "已完成润色",
            fixture.events.filterIsInstance<AiEvent.DescriptionDelta>()
                .joinToString("") { it.text },
        )
        assertEquals(
            "原生工具结果",
            fixture.events.filterIsInstance<AiEvent.PreviewDelta>()
                .joinToString("") { it.text },
        )
        assertEquals(
            "原生工具结果",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>()
                .single().patch.operation.text,
        )
        val phases = fixture.events.filterIsInstance<AiEvent.Status>().map { it.phase }
        assertTrue(HarnessPhase.GENERATING in phases)
        assertTrue(HarnessPhase.VALIDATING in phases)
        assertTrue(fixture.events.none {
            it is AiEvent.DescriptionDelta && it.text.contains("private")
        })
    }

    @Test
    fun `DeepSeek ordinary assistant content cannot bypass native terminal tool`() {
        val fixture = Fixture(deepSeekNative = true)
        val handle = fixture.start()

        fixture.transport.open(0)
        fixture.transport.bytes(0, chatDelta(fixture.patch("不得直接采用")))
        fixture.transport.bytes(0, "data: [DONE]\n\n")

        assertFalse(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)
        assertTrue(fixture.events.none { it is AiEvent.FinalPatch })
        assertTrue(fixture.events.none { it is AiEvent.PreviewDelta })
    }

    @Test
    fun `replace intent must match the requested harness skill`() {
        val fixture = Fixture(deepSeekNative = true)
        val handle = fixture.start()
        val arguments =
            "{\"description\":\"错误任务\",\"patch\":" +
                "${fixture.patch("不应采用", intent = "translate")}}"

        fixture.transport.open(0)
        fixture.transport.bytes(0, nativeToolDelta(arguments))
        fixture.transport.bytes(0, "data: [DONE]\n\n")

        assertFalse(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)
        assertTrue(fixture.events.none { it is AiEvent.FinalPatch })
        assertTrue(fixture.events.none { it is AiEvent.PreviewReset })
        assertTrue(
            fixture.events.filterIsInstance<AiEvent.Status>()
                .any { it.label == "provider_repairing" },
        )
    }

    @Test
    fun `token truncation fails explicitly without spending the repair attempt`() {
        val fixture = Fixture(deepSeekNative = true)
        val handle = fixture.start()

        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n",
        )

        assertTrue(handle.isTerminal)
        assertEquals(1, fixture.transport.requests.size)
        assertEquals(
            HarnessErrorCode.OUTPUT_TRUNCATED,
            fixture.events.filterIsInstance<AiEvent.Failed>().single().code,
        )
    }

    @Test
    fun `connectivity request mode is forwarded to DeepSeek wire request`() {
        val fixture = Fixture(deepSeekNative = true)
        fixture.start(BrainRequestMode.CONNECTIVITY_TEST)
        val body = fixture.transport.requests.single().body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("\"max_tokens\":512"))
        assertTrue(body.contains("\"tool_choice\""))
    }

    @Test
    fun `pointer release synchronously cancels call and drops late final patch`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(0, chatDelta(fixture.patch("迟到")))

        handle.cancel(HarnessCancelReason.POINTER_RELEASED)
        fixture.transport.bytes(0, "data: [DONE]\n\n")
        fixture.transport.complete(0)

        assertTrue(handle.isTerminal)
        assertTrue(fixture.transport.calls[0].cancelled)
        assertEquals(1, fixture.events.filterIsInstance<AiEvent.Cancelled>().size)
        assertTrue(fixture.events.none { it is AiEvent.FinalPatch })
    }

    @Test
    fun `first event timeout cancels transport and spends one bounded recovery`() {
        val fixture = Fixture()
        val handle = fixture.start()

        fixture.clock.now = 8_001
        handle.tick()

        assertFalse(handle.isTerminal)
        assertTrue(fixture.transport.calls[0].cancelled)
        assertEquals(2, fixture.transport.requests.size)
        assertTrue(
            fixture.events.filterIsInstance<AiEvent.Status>()
                .any { it.label == "provider_recovering" },
        )
    }

    @Test
    fun `stream idle timeout starts one bounded recovery after first provider event`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.open(0)

        fixture.clock.now = 8_001
        handle.tick()

        assertFalse(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)
        assertTrue(fixture.transport.calls[0].cancelled)
    }

    @Test
    fun `wire activity keeps reasoning stream alive without exposing private content`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.open(0)

        fixture.clock.now = 7_000
        fixture.transport.bytes(0, ": reasoning activity\n\n")
        fixture.clock.now = 14_000
        fixture.transport.bytes(0, ": reasoning activity\n\n")
        fixture.clock.now = 21_000
        handle.tick()

        assertFalse(handle.isTerminal)
        assertTrue(fixture.events.none { it is AiEvent.PreviewDelta })

        fixture.clock.now = 22_000
        handle.tick()
        assertFalse(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)

        fixture.clock.now = 30_000
        handle.tick()
        assertEquals(
            HarnessErrorCode.TOTAL_TIMEOUT,
            fixture.events.filterIsInstance<AiEvent.Failed>().single().code,
        )
    }

    @Test
    fun `streaming request accepts compatible server JSON fallback`() {
        val fixture = Fixture()
        val handle = fixture.start()
        val patch = fixture.patch("普通 JSON 回退")

        fixture.transport.open(0, contentType = "application/json; charset=utf-8")
        fixture.transport.bytes(
            0,
            "{\"choices\":[{\"message\":{\"content\":${jsonString(patch)}}}]}",
        )
        fixture.transport.complete(0)

        assertTrue(handle.isTerminal)
        assertEquals(
            "普通 JSON 回退",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>().single().patch.operation.text,
        )
    }

    @Test
    fun `HTTP failures map to actionable provider errors with bounded retry policy`() {
        val cases = listOf(
            Triple(400, HarnessErrorCode.PROVIDER_CONFIGURATION, false),
            Triple(401, HarnessErrorCode.PROVIDER_AUTHENTICATION, false),
            Triple(403, HarnessErrorCode.PROVIDER_AUTHENTICATION, false),
            Triple(402, HarnessErrorCode.PROVIDER_QUOTA, false),
            Triple(404, HarnessErrorCode.PROVIDER_CONFIGURATION, false),
            Triple(422, HarnessErrorCode.PROVIDER_CONFIGURATION, false),
            Triple(429, HarnessErrorCode.PROVIDER_RATE_LIMIT, true),
        )

        cases.forEach { (statusCode, expectedCode, expectedRetryable) ->
            val fixture = Fixture()
            val handle = fixture.start()
            fixture.transport.open(0, statusCode = statusCode)

            val failure = fixture.events.filterIsInstance<AiEvent.Failed>().single()
            assertTrue("HTTP $statusCode must terminate", handle.isTerminal)
            assertEquals("HTTP $statusCode", expectedCode, failure.code)
            assertEquals("HTTP $statusCode", expectedRetryable, failure.retryable)
            assertTrue("HTTP $statusCode must cancel its call", fixture.transport.calls[0].cancelled)
        }
    }

    @Test
    fun `transient HTTP failures spend one automatic recovery instead of failing immediately`() {
        listOf(408, 500, 503).forEach { statusCode ->
            val fixture = Fixture()
            val handle = fixture.start()

            fixture.transport.open(0, statusCode = statusCode)

            assertFalse("HTTP $statusCode should recover", handle.isTerminal)
            assertEquals("HTTP $statusCode", 2, fixture.transport.requests.size)
            assertTrue("HTTP $statusCode", fixture.transport.calls[0].cancelled)
            assertTrue(
                fixture.events.filterIsInstance<AiEvent.Status>()
                    .any { it.label == "provider_recovering" },
            )
        }
    }

    @Test
    fun `provider stream envelope maps quota error without exposing payload`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            "data: {\"error\":{\"message\":\"Insufficient balance\"," +
                "\"type\":\"billing_error\",\"code\":\"insufficient_quota\"}}\n\n",
        )

        val failure = fixture.events.filterIsInstance<AiEvent.Failed>().single()
        assertTrue(handle.isTerminal)
        assertEquals(HarnessErrorCode.PROVIDER_QUOTA, failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun `provider stream rate-limit error is retryable`() {
        val fixture = Fixture()
        fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            "data: {\"error\":{\"message\":\"Too many requests\"," +
                "\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}\n\n",
        )

        val failure = fixture.events.filterIsInstance<AiEvent.Failed>().single()
        assertEquals(HarnessErrorCode.PROVIDER_RATE_LIMIT, failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun `invalid first document gets exactly one repair and succeeds`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(0, chatDelta("{not-json}"))
        fixture.transport.bytes(0, "data: [DONE]\n\n")

        assertEquals(2, fixture.transport.requests.size)
        assertTrue(fixture.transport.calls[0].cancelled)
        assertTrue(fixture.events.none { it is AiEvent.PreviewReset })
        assertTrue(
            fixture.events.filterIsInstance<AiEvent.Status>()
                .any { it.label == "provider_repairing" },
        )
        assertFalse(handle.isTerminal)

        fixture.transport.open(1)
        fixture.transport.bytes(1, chatDelta(fixture.patch("修复成功")))
        fixture.transport.bytes(1, "data: [DONE]\n\n")

        assertTrue(handle.isTerminal)
        assertEquals(1, fixture.events.filterIsInstance<AiEvent.FinalPatch>().size)
        assertTrue(fixture.events.none { it is AiEvent.Failed })
    }

    @Test
    fun `second invalid document fails without a third request`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(0, chatDelta("bad"))
        fixture.transport.bytes(0, "data: [DONE]\n\n")
        fixture.transport.open(1)
        fixture.transport.bytes(1, chatDelta("still bad"))
        fixture.transport.bytes(1, "data: [DONE]\n\n")

        assertTrue(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)
        assertEquals(
            HarnessErrorCode.PROTOCOL_INVALID,
            fixture.events.filterIsInstance<AiEvent.Failed>().single().code,
        )
    }

    @Test
    fun `snapshot identity mismatch cannot pass and is repaired`() {
        val fixture = Fixture()
        fixture.start()
        fixture.transport.open(0)
        val wrong = fixture.patch("wrong").replace("\"snapshot-1\"", "\"snapshot-other\"")
        fixture.transport.bytes(0, chatDelta(wrong))
        fixture.transport.bytes(0, "data: [DONE]\n\n")

        assertEquals(2, fixture.transport.requests.size)
        assertTrue(fixture.events.none { it is AiEvent.FinalPatch })
    }

    @Test
    fun `callbacks from first attempt are dropped after repair begins`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(0, chatDelta("bad"))
        fixture.transport.bytes(0, "data: [DONE]\n\n")

        fixture.transport.bytes(0, chatDelta(fixture.patch("must drop")))
        fixture.transport.complete(0)
        assertFalse(handle.isTerminal)
        assertTrue(fixture.events.none { it is AiEvent.FinalPatch })

        fixture.transport.open(1)
        fixture.transport.bytes(1, chatDelta(fixture.patch("accepted")))
        fixture.transport.bytes(1, "data: [DONE]\n\n")
        assertEquals(
            "accepted",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>().single().patch.operation.text,
        )
    }

    @Test
    fun `network recovery suppresses regenerated prefix and appends only unseen suffix`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            chatDelta("""{"operation":{"text":"稳定前缀"""),
        )

        fixture.transport.failure(
            0,
            ProviderTransportFailure(
                kind = ProviderFailureKind.IO,
                message = "mobile network changed",
                retryable = true,
            ),
        )

        assertFalse(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)
        fixture.transport.open(1)
        fixture.transport.bytes(1, chatDelta(fixture.patch("稳定前缀继续")))
        fixture.transport.bytes(1, "data: [DONE]\n\n")

        assertTrue(handle.isTerminal)
        assertEquals(
            "稳定前缀继续",
            fixture.events.filterIsInstance<AiEvent.PreviewDelta>()
                .joinToString("") { it.text },
        )
        assertTrue(fixture.events.none { it is AiEvent.PreviewReplace })
        assertEquals(
            "稳定前缀继续",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>()
                .single().patch.operation.text,
        )
    }

    @Test
    fun `clean premature SSE EOF is recovered instead of misclassified as format repair`() {
        val fixture = Fixture()
        fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            chatDelta("""{"operation":{"text":"半截"""),
        )

        fixture.transport.complete(0)

        assertEquals(2, fixture.transport.requests.size)
        assertTrue(
            fixture.events.filterIsInstance<AiEvent.Status>()
                .any { it.label == "provider_recovering" },
        )
        assertTrue(
            fixture.events.filterIsInstance<AiEvent.Status>()
                .none { it.label == "provider_repairing" },
        )
        assertTrue(fixture.events.none { it is AiEvent.PreviewReset })
        val retryBody = fixture.transport.requests[1].body.toString(StandardCharsets.UTF_8)
        assertTrue(retryBody.contains("single transport recovery attempt"))
    }

    @Test
    fun `divergent retry uses one nonempty atomic preview replacement`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            chatDelta("""{"operation":{"text":"旧结果"""),
        )
        fixture.transport.failure(
            0,
            ProviderTransportFailure(
                kind = ProviderFailureKind.READ_TIMEOUT,
                message = "stalled",
                retryable = true,
            ),
        )

        fixture.transport.open(1)
        fixture.transport.bytes(1, chatDelta(fixture.patch("全新答案")))
        fixture.transport.bytes(1, "data: [DONE]\n\n")

        assertTrue(handle.isTerminal)
        val replacement = fixture.events.filterIsInstance<AiEvent.PreviewReplace>().single()
        assertEquals(2, replacement.attempt)
        assertEquals("全新答案", replacement.text)
        assertTrue(replacement.text.isNotEmpty())
        assertTrue(fixture.events.none { it is AiEvent.PreviewReset })
    }

    @Test
    fun `second transport interruption fails without a third request`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.failure(
            0,
            ProviderTransportFailure(
                kind = ProviderFailureKind.CONNECT_TIMEOUT,
                message = "first timeout",
                retryable = true,
            ),
        )
        fixture.transport.failure(
            1,
            ProviderTransportFailure(
                kind = ProviderFailureKind.READ_TIMEOUT,
                message = "second timeout",
                retryable = true,
            ),
        )

        assertTrue(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)
        assertEquals(
            HarnessErrorCode.STREAM_IDLE_TIMEOUT,
            fixture.events.filterIsInstance<AiEvent.Failed>().single().code,
        )
    }

    @Test
    fun `cancellation after recovery revokes the retry before late final patch`() {
        val fixture = Fixture()
        val handle = fixture.start()
        fixture.transport.failure(
            0,
            ProviderTransportFailure(
                kind = ProviderFailureKind.IO,
                message = "switch network",
                retryable = true,
            ),
        )

        handle.cancel(HarnessCancelReason.POINTER_RELEASED)
        fixture.transport.open(1)
        fixture.transport.bytes(1, chatDelta(fixture.patch("不得写入")))
        fixture.transport.bytes(1, "data: [DONE]\n\n")

        assertTrue(handle.isTerminal)
        assertTrue(fixture.transport.calls[1].cancelled)
        assertEquals(1, fixture.events.filterIsInstance<AiEvent.Cancelled>().size)
        assertTrue(fixture.events.none { it is AiEvent.FinalPatch })
    }

    @Test
    fun `cancel versus final callback race emits exactly one terminal event`() {
        repeat(100) {
            val fixture = Fixture()
            val handle = fixture.start()
            fixture.transport.open(0)
            fixture.transport.bytes(0, chatDelta(fixture.patch("race")))
            val start = CountDownLatch(1)
            val cancelThread = Thread {
                start.await()
                handle.cancel(HarnessCancelReason.POINTER_RELEASED)
            }
            val finalThread = Thread {
                start.await()
                fixture.transport.bytes(0, "data: [DONE]\n\n")
            }
            cancelThread.start()
            finalThread.start()
            start.countDown()
            cancelThread.join()
            finalThread.join()

            val terminalCount = fixture.events.count {
                it is AiEvent.Cancelled || it is AiEvent.FinalPatch || it is AiEvent.Failed
            }
            assertEquals(1, terminalCount)
            assertTrue(handle.isTerminal)
        }
    }

    private class Fixture(
        deepSeekNative: Boolean = false,
    ) {
        val clock = MutableClock()
        val transport = FakeTransport()
        val events = mutableListOf<AiEvent>()
        private val request = harness()
        private val profile = ProviderProfile(
            id = if (deepSeekNative) "deepseek" else "test",
            displayName = if (deepSeekNative) "DeepSeek" else "Test",
            apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            baseUrl = if (deepSeekNative) {
                "https://api.deepseek.com/v1"
            } else {
                "https://provider.test/v1"
            },
            model = if (deepSeekNative) "deepseek-v4-pro" else "test-model",
            thinkingMode = ThinkingMode.DISABLED,
            structuredOutput = StructuredOutputMode.JSON_OBJECT,
            timeouts = ProviderTimeouts(
                connectTimeoutMs = 1_000,
                firstEventTimeoutMs = 8_000,
                streamIdleTimeoutMs = 8_000,
                totalTimeoutMs = 30_000,
            ),
        )

        fun start(
            requestMode: BrainRequestMode = BrainRequestMode.NORMAL,
        ) = AiBrainEngine(transport, clock).start(
            BrainRunSpec(request, profile, ProviderCredential.None),
            { events += it },
            requestMode,
        )

        fun patch(text: String, intent: String = "rewrite"): String = """
            {"protocol":"sense.editor.patch.v1","request_id":"request-1",
            "snapshot_id":"snapshot-1","base_sha256":"${request.snapshot.baseSha256}",
            "intent":"$intent","operation":{"type":"replace","target":"whole_field",
            "text":${jsonString(text)},"selection_after":"end"}}
        """.trimIndent().replace("\n", "")
    }

    private class MutableClock(var now: Long = 0) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    private class FakeTransport : ProviderTransport {
        val requests = mutableListOf<ProviderWireRequest>()
        val sinks = mutableListOf<ProviderStreamSink>()
        val calls = mutableListOf<FakeCall>()

        override fun open(request: ProviderWireRequest, sink: ProviderStreamSink): ProviderCall {
            requests += request
            sinks += sink
            return FakeCall().also(calls::add)
        }

        fun open(
            index: Int,
            contentType: String = "text/event-stream",
            statusCode: Int = 200,
        ) {
            sinks[index].onOpen(ProviderResponseMetadata(statusCode, contentType))
        }

        fun bytes(index: Int, text: String, oneByteAtATime: Boolean = false) {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            if (oneByteAtATime) {
                bytes.forEach { sinks[index].onBytes(byteArrayOf(it)) }
            } else {
                sinks[index].onBytes(bytes)
            }
        }

        fun complete(index: Int) = sinks[index].onComplete()

        fun failure(index: Int, failure: ProviderTransportFailure) =
            sinks[index].onFailure(failure)
    }

    private class FakeCall : ProviderCall {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    companion object {
        private fun harness(): HarnessRequestV1 {
            val text = "原始内容"
            val snapshot = EditorSnapshotV1(
                requestId = "request-1",
                snapshotId = "snapshot-1",
                editorGeneration = 1,
                fieldIdentity = "field-1",
                capability = SnapshotCapability.FULL_DOCUMENT,
                text = text,
                selection = TextSelectionV1(text.length, text.length),
                target = PatchTarget.WHOLE_FIELD,
                baseSha256 = EditorTextDigest.sha256Utf8(text),
                capturedAtMonotonicMs = 0,
                truncated = false,
            )
            return HarnessRequestV1(
                requestId = snapshot.requestId,
                runGeneration = 1,
                skill = EditorIntent.REWRITE,
                snapshot = snapshot,
            )
        }

        private fun chatDelta(content: String): String =
            "data: {\"choices\":[{\"delta\":{\"content\":${jsonString(content)}}}]}\n\n"

        private fun nativeToolDelta(arguments: String): String =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                "\"id\":\"call-1\",\"type\":\"function\",\"function\":{" +
                "\"name\":\"sense_submit_patch\",\"arguments\":${jsonString(arguments)}}}]}}]}\n\n"

        private fun jsonString(value: String): String =
            JsonWriter().string(value).toString()
    }
}
