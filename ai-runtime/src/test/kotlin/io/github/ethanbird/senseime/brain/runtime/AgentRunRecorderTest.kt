package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.AgentProgressKind
import io.github.ethanbird.senseime.ai.protocol.AgentProgressState
import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorPatchV1
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessPhase
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.PatchOperationV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SelectionAfter
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.memory.AgentEventJournal
import io.github.ethanbird.senseime.brain.memory.AgentJournalKind
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecorderTest {
    @Test
    fun `request codec emits every field in fixed canonical order`() {
        val request = request()
        assertEquals(
            """{"protocol":"sense.harness.request.v1","request_id":"request-1","run_generation":9,"skill":"translate","skill_catalog_generation":null,"active_skill":null,"snapshot":{"protocol":"sense.editor.snapshot.v1","request_id":"request-1","snapshot_id":"snapshot-1","editor_generation":17,"field_identity":"field\nidentity","capability":"SELECTION_ONLY","text":"完整输入 \"text\" 🧠","text_start_offset":41,"selection":{"start":43,"end":47},"target":"selection","base_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","captured_at_monotonic_ms":123456,"truncated":false,"max_output_chars":8192},"max_output_chars":4096}""",
            AgentRunJournalCodec.encodeRequest(request),
        )

        val nullable = request.copy(
            snapshot = request.snapshot.copy(selection = null, target = null),
        )
        val encoded = AgentRunJournalCodec.encodeRequest(nullable)
        assertTrue(encoded.contains("\"selection\":null"))
        assertTrue(encoded.contains("\"target\":null"))
        assertTrue(encoded.endsWith("\"max_output_chars\":4096}"))
    }

    @Test
    fun `request journal retains exact selected Skill revision and complete document`() {
        val encoded = AgentRunJournalCodec.encodeRequest(
            request().copy(
                skillCatalogGeneration = 11,
                activeSkill = ActiveSkillInstructionV1(
                    id = "weekly_report",
                    revision = 4,
                    catalogGeneration = 11,
                    name = "周报",
                    description = "整理一周工作",
                    content = "# 周报\n\n保留数字、负责人和未完成事项。",
                ),
            ),
        )

        assertTrue(encoded.contains("\"id\":\"weekly_report\""))
        assertTrue(encoded.contains("\"revision\":4"))
        assertTrue(encoded.contains("\"catalog_generation\":11"))
        assertTrue(encoded.contains("保留数字、负责人和未完成事项"))
    }

    @Test
    fun `event codec preserves every subtype field including explicit nulls`() {
        val events = events()
        val encoded = events.map(AgentRunJournalCodec::encodeEvent)

        assertEquals(
            """{"type":"started","request_id":"request-1","run_generation":9,"started_at_monotonic_ms":101}""",
            encoded[0],
        )
        assertEquals(
            """{"type":"status","request_id":"request-1","run_generation":9,"phase":"TOOL_RUNNING","label":"tool_active"}""",
            encoded[1],
        )
        assertEquals(
            """{"type":"preview_replace","request_id":"request-1","run_generation":9,"attempt":2,"text":"替换预览","description":"公开说明"}""",
            encoded[5],
        )
        assertEquals(
            """{"type":"agent_progress","request_id":"request-1","run_generation":9,"revision":3,"step_id":"tool-1","kind":"TOOL","state":"COMPLETED","title":"工具完成","detail":"完整细节","tool_call_id":null,"tool_name":"memory_search"}""",
            encoded[6],
        )
        assertEquals(
            """{"type":"final_patch","request_id":"request-1","run_generation":9,"patch":{"protocol":"sense.editor.patch.v1","request_id":"request-1","snapshot_id":"snapshot-1","base_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","intent":"no_change","operation":{"type":"no_change","target":null,"text":null,"selection_after":null}}}""",
            encoded[8],
        )
        assertEquals(11, encoded.size)
        assertEquals(
            listOf(
                "started",
                "status",
                "description_delta",
                "preview_reset",
                "preview_delta",
                "preview_replace",
                "agent_progress",
                "usage",
                "final_patch",
                "cancelled",
                "failed",
            ),
            events.map(AgentRunJournalCodec::eventType),
        )
    }

    @Test
    fun `recorder persists complete request events raw provider data and tool exchange`() =
        withJournalDirectory { directory ->
            val rawInput = byteArrayOf(0x7b, 0, -1, 0x7d)
            val rawOutput = "reasoning 私有输出".toByteArray(StandardCharsets.UTF_8)
            val privateBytes = byteArrayOf(0, 4, 8, 15, 16, 23, 42)

            AgentEventJournal.open(directory).use { journal ->
                val recorder = AgentRunRecorder.begin(journal, request())
                recorder.recordProviderInput(
                    rawBytes = rawInput,
                    contentType = "application/octet-stream",
                    lexicalText = "provider request raw",
                    attributes = mapOf("attempt" to "0"),
                )
                recorder.recordProviderOutput(
                    rawBytes = rawOutput,
                    contentType = "text/event-stream",
                )
                recorder.recordPrivateEvent(
                    eventType = "reasoning_delta",
                    rawBytes = privateBytes,
                    contentType = "application/octet-stream",
                    lexicalText = "private exact bytes",
                )
                recorder.recordToolCall(
                    toolCallId = null,
                    toolName = "memory_search",
                    arguments = """{"q":"偏好"}""",
                )
                recorder.recordToolResult(
                    toolCallId = "call-1",
                    toolName = null,
                    result = """{"hits":[1,2]}""",
                )
                events().forEach(recorder::record)

                val records = journal.readLatest(limit = 100).records
                assertEquals(17, records.size)
                assertEquals((1L..17L).toList(), records.map { it.sequence })
                assertEquals(AgentJournalKind.REQUEST_INPUT_SNAPSHOT, records[0].kind)
                assertEquals(
                    AgentRunJournalCodec.encodeRequest(request()),
                    records[0].payload.toString(StandardCharsets.UTF_8),
                )
                assertArrayEquals(rawInput, records[1].payload)
                assertArrayEquals(rawOutput, records[2].payload)
                assertArrayEquals(privateBytes, records[3].payload)
                assertEquals(AgentJournalKind.PROVIDER_INPUT, records[1].kind)
                assertEquals(AgentJournalKind.PROVIDER_OUTPUT, records[2].kind)
                assertEquals(AgentJournalKind.PRIVATE_AGENT_EVENT, records[3].kind)
                assertEquals(AgentJournalKind.TOOL_CALL, records[4].kind)
                assertEquals(AgentJournalKind.TOOL_RESULT, records[5].kind)
                assertEquals(
                    """{"tool_call_id":null,"tool_name":"memory_search","arguments":"{\"q\":\"偏好\"}"}""",
                    records[4].lexicalText,
                )
                assertEquals(
                    listOf(
                        AgentJournalKind.PUBLIC_AGENT_EVENT,
                        AgentJournalKind.PUBLIC_AGENT_EVENT,
                        AgentJournalKind.PUBLIC_AGENT_EVENT,
                        AgentJournalKind.PREVIEW,
                        AgentJournalKind.PREVIEW,
                        AgentJournalKind.PREVIEW,
                        AgentJournalKind.PUBLIC_AGENT_EVENT,
                        AgentJournalKind.PUBLIC_AGENT_EVENT,
                        AgentJournalKind.FINAL,
                        AgentJournalKind.CANCELLED,
                        AgentJournalKind.ERROR,
                    ),
                    records.drop(6).map { it.kind },
                )
            }
        }

    @Test
    fun `recorder supports explicit flush and durable boundaries after deferred stream events`() =
        withJournalDirectory { directory ->
            val deferredRaw = byteArrayOf(0, -1, 7, 0, 9)
            val durableRaw = byteArrayOf(11, 12, 13)
            AgentEventJournal.open(directory).use { journal ->
                val recorder = AgentRunRecorder.begin(journal, request())
                recorder.recordProviderOutput(
                    rawBytes = deferredRaw,
                    contentType = "application/octet-stream",
                )
                recorder.record(events()[0])
                recorder.record(events()[4])
                recorder.flush()
                recorder.recordProviderOutput(
                    rawBytes = durableRaw,
                    contentType = "application/octet-stream",
                    durable = true,
                )
                recorder.record(events()[8])

                val records = journal.readLatest(limit = 10).records
                assertEquals((1L..6L).toList(), records.map { it.sequence })
                assertEquals(
                    listOf(
                        AgentJournalKind.REQUEST_INPUT_SNAPSHOT,
                        AgentJournalKind.PROVIDER_OUTPUT,
                        AgentJournalKind.PUBLIC_AGENT_EVENT,
                        AgentJournalKind.PREVIEW,
                        AgentJournalKind.PROVIDER_OUTPUT,
                        AgentJournalKind.FINAL,
                    ),
                    records.map { it.kind },
                )
                assertArrayEquals(deferredRaw, records[1].payload)
                assertArrayEquals(durableRaw, records[4].payload)
            }

            AgentEventJournal.open(directory).use { reopened ->
                assertEquals(6L, reopened.stats().lastSequence)
                assertArrayEquals(
                    deferredRaw,
                    reopened.readLatest(limit = 6).records[1].payload,
                )
            }
        }

    @Test
    fun `recorder rejects foreign identity before append`() = withJournalDirectory { directory ->
        AgentEventJournal.open(directory).use { journal ->
            val recorder = AgentRunRecorder.begin(journal, request())
            assertThrows(IllegalArgumentException::class.java) {
                recorder.record(
                    AiEvent.PreviewDelta(
                        requestId = "foreign-request",
                        runGeneration = 9,
                        text = "must not be written",
                    ),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                recorder.record(
                    AiEvent.PreviewDelta(
                        requestId = "request-1",
                        runGeneration = 10,
                        text = "must not be written",
                    ),
                )
            }
            assertEquals(1L, journal.stats().records)
        }
    }

    @Test
    fun `canonical string encoder preserves pairs and escapes invalid code units`() {
        val unpairedHigh = '\uD83D'.toString()
        val encoded = AgentRunJournalCodec.encodeToolResult(
            toolCallId = "call\n1",
            toolName = "tool",
            result = "🧠$unpairedHigh\u0000",
        )
        assertEquals(
            """{"tool_call_id":"call\n1","tool_name":"tool","result":"🧠\ud83d\u0000"}""",
            encoded,
        )
    }

    private fun request(): HarnessRequestV1 = HarnessRequestV1(
        requestId = "request-1",
        runGeneration = 9,
        skill = EditorIntent.TRANSLATE,
        snapshot = EditorSnapshotV1(
            requestId = "request-1",
            snapshotId = "snapshot-1",
            editorGeneration = 17,
            fieldIdentity = "field\nidentity",
            capability = SnapshotCapability.SELECTION_ONLY,
            text = "完整输入 \"text\" 🧠",
            textStartOffset = 41,
            selection = TextSelectionV1(start = 43, end = 47),
            target = PatchTarget.SELECTION,
            baseSha256 = "a".repeat(64),
            capturedAtMonotonicMs = 123_456,
            truncated = false,
            maxOutputChars = 8_192,
        ),
        maxOutputChars = 4_096,
    )

    private fun events(): List<AiEvent> = listOf(
        AiEvent.Started("request-1", 9, startedAtMonotonicMs = 101),
        AiEvent.Status("request-1", 9, HarnessPhase.TOOL_RUNNING, "tool_active"),
        AiEvent.DescriptionDelta("request-1", 9, "公开进度"),
        AiEvent.PreviewReset("request-1", 9, attempt = 2),
        AiEvent.PreviewDelta("request-1", 9, "增量"),
        AiEvent.PreviewReplace(
            "request-1",
            9,
            attempt = 2,
            text = "替换预览",
            description = "公开说明",
        ),
        AiEvent.AgentProgress(
            requestId = "request-1",
            runGeneration = 9,
            revision = 3,
            stepId = "tool-1",
            kind = AgentProgressKind.TOOL,
            state = AgentProgressState.COMPLETED,
            title = "工具完成",
            detail = "完整细节",
            toolCallId = null,
            toolName = "memory_search",
        ),
        AiEvent.Usage("request-1", 9, inputTokens = 12, outputTokens = 34),
        AiEvent.FinalPatch(
            "request-1",
            9,
            EditorPatchV1(
                requestId = "request-1",
                snapshotId = "snapshot-1",
                baseSha256 = "a".repeat(64),
                intent = EditorIntent.NO_CHANGE,
                operation = PatchOperationV1(
                    type = PatchOperationType.NO_CHANGE,
                    target = null,
                    text = null,
                    selectionAfter = null,
                ),
            ),
        ),
        AiEvent.Cancelled("request-1", 9, HarnessCancelReason.CALLER_REQUESTED),
        AiEvent.Failed("request-1", 9, HarnessErrorCode.PROVIDER_FAILURE, retryable = true),
    )

    private fun withJournalDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("sense-agent-recorder-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
