package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.AgentProgressKind
import io.github.ethanbird.senseime.ai.protocol.AgentProgressState
import io.github.ethanbird.senseime.ai.protocol.AgentSessionStateMachine
import io.github.ethanbird.senseime.ai.protocol.AgentSessionTransition
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
import io.github.ethanbird.senseime.brain.api.BrainRunHandle
import io.github.ethanbird.senseime.brain.api.BrainTraceEvent
import io.github.ethanbird.senseime.brain.api.BrainTraceSink
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolExecutionResult
import io.github.ethanbird.senseime.brain.api.AgentToolExecutor
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalogSnapshot
import io.github.ethanbird.senseime.brain.api.AgentSkillSummary
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBrainEngineTest {
    @Test
    fun `Brain admission rejects mismatched frozen Skill generations`() {
        val profile = ProviderProfile(
            id = "test",
            displayName = "Test",
            apiStyle = ProviderApiStyle.OPENAI_RESPONSES,
            baseUrl = "https://provider.test/v1",
            model = "test-model",
        )
        val request = harness().copy(skillCatalogGeneration = 7)

        assertThrows(IllegalArgumentException::class.java) {
            BrainRunSpec(
                harnessRequest = request,
                provider = profile,
                credential = ProviderCredential.None,
                skillCatalogGeneration = 8,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrainRunSpec(
                harnessRequest = request.copy(
                    activeSkill = ActiveSkillInstructionV1(
                        id = "brief",
                        revision = 1,
                        catalogGeneration = 6,
                        name = "Brief",
                        description = "Create a brief",
                        content = "# Brief",
                    ),
                ),
                provider = profile,
                credential = ProviderCredential.None,
                skillCatalogGeneration = 7,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrainRunSpec(
                harnessRequest = harness(),
                provider = profile,
                credential = ProviderCredential.None,
                skillCatalog = listOf(briefSummary()),
            )
        }
    }

    @Test
    fun `enabled Agent tool executes and is replayed into the next provider turn`() {
        var executions = 0
        var executedCall: AgentToolCall? = null
        val traces = mutableListOf<BrainTraceEvent>()
        val executor = AgentToolExecutor { call ->
            executions += 1
            executedCall = call
            AgentToolExecutionResult("{\"ok\":true,\"result\":\"42\"}")
        }
        val fixture = Fixture(
            deepSeekNative = true,
            enabledTools = setOf(AgentToolId.CALCULATOR),
            toolExecutor = executor,
            traceSink = { traces += it },
        )
        val handle = fixture.start()

        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            agentToolDelta("calculator", "{\"expression\":\"6*7\"}"),
        )
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertFalse(handle.isTerminal)
        assertEquals(1, executions)
        assertEquals("request-1", executedCall?.requestId)
        assertEquals(1L, executedCall?.runGeneration)
        assertEquals(2, fixture.transport.requests.size)
        val continuation = fixture.transport.requests[1].body.toString(StandardCharsets.UTF_8)
        assertTrue(continuation.contains("\"name\":\"calculator\""))
        assertTrue(continuation.contains("\\\"result\\\":\\\"42\\\""))

        fixture.transport.open(1)
        fixture.transport.bytes(
            1,
            nativeToolDelta(
                "{\"description\":\"完成\",\"patch\":${fixture.patch("42")}}",
                callId = "call-final",
            ),
        )
        fixture.transport.bytes(
            1,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )
        assertTrue(handle.isTerminal)
        assertEquals("42", fixture.events.filterIsInstance<AiEvent.FinalPatch>().single()
            .patch.operation.text)
        assertEquals(2, traces.filterIsInstance<BrainTraceEvent.ProviderInput>().size)
        assertEquals(2, traces.filterIsInstance<BrainTraceEvent.ProviderCompleted>().size)
        assertTrue(
            traces.filterIsInstance<BrainTraceEvent.ProviderOutput>()
                .flatMap { it.bytes.asIterable() }
                .isNotEmpty(),
        )
        assertEquals(
            listOf("calculator", "sense_submit_patch"),
            traces.filterIsInstance<BrainTraceEvent.ToolCall>().map { it.toolName },
        )
        assertEquals(
            listOf(false, false),
            traces.filterIsInstance<BrainTraceEvent.ToolResult>().map { it.isError },
        )
    }

    @Test
    fun `generic Chat executes Skill tool then accepts structured Patch content`() {
        var executions = 0
        val fixture = Fixture(
            enabledTools = setOf(AgentToolId.SKILL_READ),
            skillCatalog = listOf(briefSummary()),
            skillCatalogGeneration = 7,
            toolExecutor = AgentToolExecutor { call ->
                executions += 1
                assertEquals(AgentToolId.SKILL_READ, call.tool)
                AgentToolExecutionResult(
                    "{\"ok\":true,\"data\":{\"content\":\"# Brief\",\"eof\":true}}",
                )
            },
        )
        val handle = fixture.start()

        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            agentToolDelta(
                "skill_read",
                "{\"skill_id\":\"brief\",\"revision\":3}",
            ),
        )
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertEquals(1, executions)
        assertFalse(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)
        val continuation = fixture.transport.requests[1].body.toString(StandardCharsets.UTF_8)
        assertTrue(continuation.contains("\"role\":\"tool\""))
        assertTrue(continuation.contains("\"tool_call_id\":\"call-agent-tool\""))
        assertTrue(continuation.contains("\"response_format\":{\"type\":\"json_object\"}"))

        fixture.transport.open(1)
        fixture.transport.bytes(1, chatDelta(fixture.patch("generic skill result")))
        fixture.transport.bytes(1, "data: [DONE]\n\n")

        assertTrue(handle.isTerminal)
        assertEquals(
            "generic skill result",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>().single().patch.operation.text,
        )
    }

    @Test
    fun `Responses executes Skill tool and replays function protocol items`() {
        var executions = 0
        val fixture = Fixture(
            apiStyle = ProviderApiStyle.OPENAI_RESPONSES,
            enabledTools = setOf(AgentToolId.SKILL_READ),
            skillCatalog = listOf(briefSummary()),
            skillCatalogGeneration = 7,
            toolExecutor = AgentToolExecutor {
                executions += 1
                AgentToolExecutionResult(
                    "{\"ok\":true,\"data\":{\"content\":\"# Brief\",\"eof\":true}}",
                )
            },
        )
        val handle = fixture.start()

        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            responsesToolCall(
                name = "skill_read",
                arguments = "{\"skill_id\":\"brief\",\"revision\":3}",
            ),
        )

        assertEquals(1, executions)
        assertFalse(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)
        val continuation = fixture.transport.requests[1].body.toString(StandardCharsets.UTF_8)
        assertTrue(continuation.contains("\"type\":\"function_call\""))
        assertTrue(continuation.contains("\"type\":\"function_call_output\""))
        assertTrue(continuation.contains("\"call_id\":\"call-response-tool\""))
        assertTrue(continuation.contains("\"encrypted_content\":\"opaque-state\""))

        fixture.transport.open(1)
        fixture.transport.bytes(
            1,
            "event: response.output_text.delta\n" +
                "data: {\"type\":\"response.output_text.delta\",\"delta\":" +
                "${jsonString(fixture.patch("responses skill result"))}}\n\n" +
                "event: response.completed\n" +
                "data: {\"type\":\"response.completed\",\"response\":{}}\n\n",
        )

        assertTrue(handle.isTerminal)
        assertEquals(
            "responses skill result",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>().single().patch.operation.text,
        )
    }

    @Test
    fun `skill read rejects historical future and unadvertised revisions before executor`() {
        listOf(
            "brief" to 2L,
            "brief" to 4L,
            "unadvertised" to 1L,
        ).forEach { (skillId, revision) ->
            var executions = 0
            val fixture = Fixture(
                enabledTools = setOf(AgentToolId.SKILL_READ),
                skillCatalog = listOf(briefSummary()),
                skillCatalogGeneration = 7,
                toolExecutor = AgentToolExecutor {
                    executions += 1
                    AgentToolExecutionResult("{}")
                },
            )
            fixture.start()
            fixture.transport.open(0)
            fixture.transport.bytes(
                0,
                agentToolDelta(
                    "skill_read",
                    "{\"skill_id\":\"$skillId\",\"revision\":$revision}",
                ),
            )
            fixture.transport.bytes(
                0,
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
            )

            assertEquals("$skillId@$revision must not execute", 0, executions)
            assertEquals(2, fixture.transport.requests.size)
        }
    }

    @Test
    fun `one run accepts skipped generation then authorizes its exact successor`() {
        val expectedGenerations = mutableListOf<Long>()
        val fixture = Fixture(
            enabledTools = setOf(AgentToolId.SKILL_MANAGE),
            skillCatalogGeneration = 7,
            toolExecutor = AgentToolExecutor { call ->
                val mutation = call.arguments as AgentToolArguments.SkillManage.Unbind
                expectedGenerations += mutation.expectedCatalogGeneration
                val nextGeneration = if (mutation.expectedCatalogGeneration == 7L) 9L else 12L
                AgentToolExecutionResult(
                    "{\"ok\":true,\"data\":{\"expected_catalog_generation\":" +
                        "${mutation.expectedCatalogGeneration},\"catalog_generation\":" +
                        "$nextGeneration}}",
                    skillCatalogSnapshot = AgentSkillCatalogSnapshot(
                        generation = nextGeneration,
                        skills = emptyList(),
                    ),
                )
            },
        )
        val handle = fixture.start()

        listOf(7L, 9L).forEachIndexed { turn, generation ->
            fixture.transport.open(turn)
            fixture.transport.bytes(
                turn,
                agentToolDelta(
                    "skill_manage",
                    "{\"operation\":\"unbind\",\"expected_catalog_generation\":$generation," +
                        "\"key_code\":${'a'.code + turn},\"direction\":\"up\"}",
                    callId = "call-manage-$generation",
                ),
            )
            fixture.transport.bytes(
                turn,
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
            )
        }

        assertEquals(listOf(7L, 9L), expectedGenerations)
        assertEquals(3, fixture.transport.requests.size)
        val thirdRequest = fixture.transport.requests[2].body.toString(StandardCharsets.UTF_8)
        assertTrue(thirdRequest.contains("\\\"catalog_generation\\\":12"))
        assertTrue(thirdRequest.contains("expected_catalog_generation=12"))

        fixture.transport.open(2)
        fixture.transport.bytes(2, chatDelta(fixture.patch("mutations complete")))
        fixture.transport.bytes(2, "data: [DONE]\n\n")

        assertTrue(handle.isTerminal)
        assertEquals(
            "mutations complete",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>().single().patch.operation.text,
        )
    }

    @Test
    fun `Skill create and update atomically refresh discovery and exact read authority`() {
        val calls = mutableListOf<AgentToolCall>()
        val original = briefSummary()
        val created = AgentSkillSummary(
            id = "new_skill",
            revision = 1,
            name = "New Skill",
            description = "Original discovery",
        )
        val updated = AgentSkillSummary(
            id = "new_skill",
            revision = 2,
            name = "Updated Skill",
            description = "Updated discovery",
        )
        val fixture = Fixture(
            enabledTools = setOf(AgentToolId.SKILL_MANAGE, AgentToolId.SKILL_READ),
            skillCatalog = listOf(original),
            skillCatalogGeneration = 7,
            toolExecutor = AgentToolExecutor { call ->
                calls += call
                when (val arguments = call.arguments) {
                    is AgentToolArguments.SkillManage.Create -> {
                        assertEquals(7L, arguments.expectedCatalogGeneration)
                        AgentToolExecutionResult(
                            content = "{\"ok\":true,\"data\":{\"catalog_generation\":9}}",
                            skillCatalogSnapshot = AgentSkillCatalogSnapshot(
                                generation = 9,
                                skills = listOf(original, created),
                            ),
                        )
                    }
                    is AgentToolArguments.SkillManage.Update -> {
                        assertEquals(9L, arguments.expectedCatalogGeneration)
                        AgentToolExecutionResult(
                            content = "{\"ok\":true,\"data\":{\"catalog_generation\":12}}",
                            skillCatalogSnapshot = AgentSkillCatalogSnapshot(
                                generation = 12,
                                skills = listOf(original, updated),
                            ),
                        )
                    }
                    is AgentToolArguments.SkillRead -> {
                        assertEquals("new_skill", arguments.skillId)
                        assertEquals(2L, arguments.revision)
                        AgentToolExecutionResult(
                            "{\"ok\":true,\"data\":{\"content\":\"# Updated\",\"eof\":true}}",
                        )
                    }
                    else -> error("Unexpected call: $arguments")
                }
            },
        )
        val handle = fixture.start()

        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            agentToolDelta(
                "skill_manage",
                "{\"operation\":\"create\",\"expected_catalog_generation\":7," +
                    "\"skill_id\":\"new_skill\",\"name\":\"New Skill\"," +
                    "\"description\":\"Original discovery\",\"content\":\"# Original\"," +
                    "\"base_intent\":\"rewrite\"}",
                callId = "call-create",
            ),
        )
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        val afterCreate = fixture.transport.requests[1].body.toString(StandardCharsets.UTF_8)
        assertTrue(afterCreate.contains("\\\"catalog_generation\\\":9"))
        assertTrue(afterCreate.contains("expected_catalog_generation=9"))
        assertTrue(afterCreate.contains("new_skill@1 | New Skill | Original discovery"))

        fixture.transport.open(1)
        fixture.transport.bytes(
            1,
            agentToolDelta(
                "skill_manage",
                "{\"operation\":\"update\",\"expected_catalog_generation\":9," +
                    "\"skill_id\":\"new_skill\",\"name\":\"Updated Skill\"," +
                    "\"description\":\"Updated discovery\",\"content\":\"# Updated\"}",
                callId = "call-update",
            ),
        )
        fixture.transport.bytes(
            1,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        val afterUpdate = fixture.transport.requests[2].body.toString(StandardCharsets.UTF_8)
        assertTrue(afterUpdate.contains("\\\"catalog_generation\\\":12"))
        assertTrue(afterUpdate.contains("expected_catalog_generation=12"))
        assertTrue(afterUpdate.contains("new_skill@2 | Updated Skill | Updated discovery"))
        assertFalse(afterUpdate.contains("new_skill@1 | New Skill | Original discovery"))

        fixture.transport.open(2)
        fixture.transport.bytes(
            2,
            agentToolDelta(
                "skill_read",
                "{\"skill_id\":\"new_skill\",\"revision\":2}",
                callId = "call-read-updated",
            ),
        )
        fixture.transport.bytes(
            2,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertEquals(3, calls.size)
        assertEquals(4, fixture.transport.requests.size)
        val afterRead = fixture.transport.requests[3].body.toString(StandardCharsets.UTF_8)
        assertTrue(afterRead.contains("new_skill@2 | Updated Skill | Updated discovery"))
        assertFalse(afterRead.contains("new_skill@1 | New Skill | Original discovery"))

        fixture.transport.open(3)
        fixture.transport.bytes(3, chatDelta(fixture.patch("catalog refreshed")))
        fixture.transport.bytes(3, "data: [DONE]\n\n")

        assertTrue(handle.isTerminal)
        assertEquals(
            "catalog refreshed",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>().single().patch.operation.text,
        )
    }

    @Test
    fun `Skill mutation cannot relabel stale summaries with a newer generation`() {
        val original = briefSummary()
        val fixture = Fixture(
            enabledTools = setOf(AgentToolId.SKILL_MANAGE),
            skillCatalog = listOf(original),
            skillCatalogGeneration = 7,
            toolExecutor = AgentToolExecutor {
                AgentToolExecutionResult(
                    content = "{\"ok\":true,\"data\":{\"catalog_generation\":9}}",
                    skillCatalogSnapshot = AgentSkillCatalogSnapshot(
                        generation = 9,
                        skills = listOf(original),
                    ),
                )
            },
        )
        fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            agentToolDelta(
                "skill_manage",
                "{\"operation\":\"update\",\"expected_catalog_generation\":7," +
                    "\"skill_id\":\"brief\",\"description\":\"New discovery\"}",
                callId = "call-invalid-projection",
            ),
        )
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        val continuation = fixture.transport.requests[1].body.toString(StandardCharsets.UTF_8)
        assertTrue(continuation.contains("Sense Skill catalog generation 7"))
        assertTrue(continuation.contains("expected_catalog_generation=7"))
        assertTrue(continuation.contains("brief@3 | Brief | Create a concise brief"))
        assertFalse(continuation.contains("brief@3 | Brief | New discovery"))
        assertTrue(continuation.contains("invalid catalog snapshot"))
    }

    @Test
    fun `guessed future Skill generation never reaches mutation executor`() {
        var executions = 0
        val fixture = Fixture(
            enabledTools = setOf(AgentToolId.SKILL_MANAGE),
            skillCatalogGeneration = 7,
            toolExecutor = AgentToolExecutor {
                executions += 1
                AgentToolExecutionResult(
                    "{}",
                    skillCatalogSnapshot = AgentSkillCatalogSnapshot(9, emptyList()),
                )
            },
        )
        fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            agentToolDelta(
                "skill_manage",
                "{\"operation\":\"unbind\",\"expected_catalog_generation\":8," +
                    "\"key_code\":97,\"direction\":\"up\"}",
            ),
        )
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertEquals(0, executions)
        assertEquals(2, fixture.transport.requests.size)
        assertTrue(
            fixture.events.filterIsInstance<AiEvent.AgentProgress>()
                .any { it.kind == AgentProgressKind.TOOL && it.state == AgentProgressState.FAILED },
        )
    }

    @Test
    fun `cancellation linearized before tool start prevents executor side effect`() {
        var executions = 0
        lateinit var handle: BrainRunHandle
        val fixture = Fixture(
            deepSeekNative = true,
            enabledTools = setOf(AgentToolId.CALCULATOR),
            toolExecutor = AgentToolExecutor {
                executions += 1
                AgentToolExecutionResult("{}")
            },
            eventObserver = { event ->
                if (
                    event is AiEvent.AgentProgress &&
                    event.kind == AgentProgressKind.TOOL &&
                    event.state == AgentProgressState.RUNNING
                ) {
                    handle.cancel(HarnessCancelReason.CALLER_REQUESTED)
                }
            },
        )
        handle = fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            agentToolDelta("calculator", "{\"expression\":\"6*7\"}"),
        )
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertEquals(0, executions)
        assertTrue(handle.isTerminal)
        assertEquals(1, fixture.events.filterIsInstance<AiEvent.Cancelled>().size)
        assertEquals(1, fixture.transport.requests.size)
    }

    @Test
    fun `provider completion trace failure terminates before a patch can be accepted`() {
        val fixture = Fixture(
            deepSeekNative = true,
            traceSink = BrainTraceSink { trace ->
                if (trace is BrainTraceEvent.ProviderCompleted) {
                    error("durable trace failed")
                }
            },
        )
        val handle = fixture.start()

        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            nativeToolDelta(
                "{\"description\":\"完成\",\"patch\":${fixture.patch("must-not-commit")}}",
            ),
        )
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertTrue(handle.isTerminal)
        assertTrue(fixture.events.none { it is AiEvent.FinalPatch })
        assertEquals(
            HarnessErrorCode.INTERNAL_FAILURE,
            fixture.events.filterIsInstance<AiEvent.Failed>().single().code,
        )
    }

    @Test
    fun `guessing a disabled Agent tool never reaches its executor`() {
        var executions = 0
        val fixture = Fixture(
            deepSeekNative = true,
            toolExecutor = AgentToolExecutor {
                executions += 1
                AgentToolExecutionResult("{}")
            },
        )
        fixture.start()
        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            agentToolDelta("web_search", "{\"query\":\"must not run\"}"),
        )
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertEquals(0, executions)
        assertEquals(2, fixture.transport.requests.size)
        assertTrue(
            fixture.events.filterIsInstance<AiEvent.Status>()
                .any { it.label == "provider_repairing" },
        )
    }

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
        assertTrue(HarnessPhase.TOOL_RUNNING in phases)
        assertTrue(HarnessPhase.VALIDATING in phases)
        assertTrue(fixture.events.none {
            it is AiEvent.DescriptionDelta && it.text.contains("private")
        })
    }

    @Test
    fun `DeepSeek progress tool creates a real second Agent turn with private reasoning replay`() {
        val fixture = Fixture(deepSeekNative = true)
        val handle = fixture.start()

        fixture.transport.open(0)
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private-plan\"}}]}\n\n",
        )
        fixture.transport.bytes(
            0,
            progressToolDelta("{\"message\":\"已理解内容，正在组织可直接写入的答案\"}"),
        )
        fixture.transport.bytes(
            0,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertFalse(handle.isTerminal)
        assertEquals(2, fixture.transport.requests.size)
        val secondBody = fixture.transport.requests[1].body.toString(StandardCharsets.UTF_8)
        assertTrue(secondBody.contains("\"role\":\"tool\""))
        assertTrue(secondBody.contains("\"tool_call_id\":\"call-progress\""))
        assertTrue(secondBody.contains("\"reasoning_content\":\"private-plan\""))
        assertTrue(
            fixture.events.filterIsInstance<AiEvent.AgentProgress>()
                .any {
                    it.kind == AgentProgressKind.ASSISTANT_UPDATE &&
                        it.title == "已理解内容，正在组织可直接写入的答案"
                },
        )
        assertTrue(fixture.events.none {
            it is AiEvent.DescriptionDelta && it.text.contains("private-plan")
        })

        val arguments =
            "{\"description\":\"已完成处理\",\"patch\":${fixture.patch("多轮结果")}}"
        fixture.transport.open(1)
        fixture.transport.bytes(1, nativeToolDelta(arguments, callId = "call-final"))
        fixture.transport.bytes(
            1,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertTrue(handle.isTerminal)
        assertEquals(
            "多轮结果",
            fixture.events.filterIsInstance<AiEvent.FinalPatch>().single().patch.operation.text,
        )
        val publicStateMachine = AgentSessionStateMachine("request-1", 1L)
        val dropped = fixture.events
            .map(publicStateMachine::accept)
            .filterIsInstance<AgentSessionTransition.Dropped>()
        assertTrue("engine emitted an invalid public state transition: $dropped", dropped.isEmpty())
    }

    @Test
    fun `DeepSeek cannot create an unbounded loop by hallucinating a third progress call`() {
        val fixture = Fixture(deepSeekNative = true)
        val handle = fixture.start()

        repeat(2) { turn ->
            fixture.transport.open(turn)
            fixture.transport.bytes(
                turn,
                progressToolDelta(
                    arguments = "{\"message\":\"公开进度 ${turn + 1}\"}",
                    callId = "call-progress-${turn + 1}",
                ),
            )
            fixture.transport.bytes(
                turn,
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
            )
        }

        assertEquals(3, fixture.transport.requests.size)
        val terminalOnlyBody =
            fixture.transport.requests[2].body.toString(StandardCharsets.UTF_8)
        val terminalOnlyTools = with(ProviderJson) {
            parse(terminalOnlyBody).member("tools")?.array().orEmpty()
        }
        val terminalOnlyToolNames = terminalOnlyTools.mapNotNull { tool ->
            with(ProviderJson) {
                tool.member("function")?.member("name")?.string()
            }
        }
        assertEquals(listOf("sense_submit_patch"), terminalOnlyToolNames)

        fixture.transport.open(2)
        fixture.transport.bytes(
            2,
            progressToolDelta(
                arguments = "{\"message\":\"不应继续循环\"}",
                callId = "call-progress-3",
            ),
        )
        fixture.transport.bytes(
            2,
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        assertFalse(handle.isTerminal)
        assertEquals(4, fixture.transport.requests.size)
        assertEquals(
            2,
            fixture.events.filterIsInstance<AiEvent.AgentProgress>()
                .count { it.kind == AgentProgressKind.ASSISTANT_UPDATE },
        )
        assertTrue(
            fixture.events.filterIsInstance<AiEvent.AgentProgress>()
                .any {
                    it.kind == AgentProgressKind.TOOL &&
                        it.state == AgentProgressState.FAILED
                },
        )
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
        apiStyle: ProviderApiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
        private val enabledTools: Set<AgentToolId> = emptySet(),
        private val skillCatalog: List<AgentSkillSummary> = emptyList(),
        private val skillCatalogGeneration: Long? = null,
        private val toolExecutor: AgentToolExecutor = AgentToolExecutor.UNAVAILABLE,
        private val traceSink: BrainTraceSink = BrainTraceSink.NONE,
        private val eventObserver: (AiEvent) -> Unit = {},
    ) {
        val clock = MutableClock()
        val transport = FakeTransport()
        val events = mutableListOf<AiEvent>()
        private val request = harness()
        private val profile = ProviderProfile(
            id = if (deepSeekNative) "deepseek" else "test",
            displayName = if (deepSeekNative) "DeepSeek" else "Test",
            apiStyle = apiStyle,
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
        ) = AiBrainEngine(transport, clock, toolExecutor).start(
            BrainRunSpec(
                request,
                profile,
                ProviderCredential.None,
                skillCatalog = skillCatalog,
                skillCatalogGeneration = skillCatalogGeneration,
                enabledTools = enabledTools,
                traceSink = traceSink,
            ),
            {
                events += it
                eventObserver(it)
            },
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
        private fun briefSummary(
            revision: Long = 3,
            name: String = "Brief",
            description: String = "Create a concise brief",
        ) = AgentSkillSummary(
            id = "brief",
            revision = revision,
            name = name,
            description = description,
        )

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

        private fun nativeToolDelta(
            arguments: String,
            callId: String = "call-1",
        ): String =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                "\"id\":\"$callId\",\"type\":\"function\",\"function\":{" +
                "\"name\":\"sense_submit_patch\",\"arguments\":${jsonString(arguments)}}}]}}]}\n\n"

        private fun progressToolDelta(
            arguments: String,
            callId: String = "call-progress",
        ): String =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                "\"id\":\"$callId\",\"type\":\"function\",\"function\":{" +
                "\"name\":\"sense_report_progress\",\"arguments\":" +
                "${jsonString(arguments)}}}]}}]}\n\n"

        private fun agentToolDelta(
            toolName: String,
            arguments: String,
            callId: String = "call-agent-tool",
        ): String =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                "\"id\":\"$callId\",\"type\":\"function\",\"function\":{" +
                "\"name\":\"$toolName\",\"arguments\":${jsonString(arguments)}}}]}}]}\n\n"

        private fun responsesToolCall(
            name: String,
            arguments: String,
            callId: String = "call-response-tool",
        ): String =
            "event: response.output_item.done\n" +
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0," +
                "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[]," +
                "\"encrypted_content\":\"opaque-state\"}}\n\n" +
                "event: response.output_item.added\n" +
                "data: {\"type\":\"response.output_item.added\",\"output_index\":1," +
                "\"item\":{\"type\":\"function_call\",\"call_id\":\"$callId\"," +
                "\"name\":\"$name\",\"arguments\":\"\"}}\n\n" +
                "event: response.function_call_arguments.delta\n" +
                "data: {\"type\":\"response.function_call_arguments.delta\"," +
                "\"output_index\":1,\"delta\":${jsonString(arguments)}}\n\n" +
                "event: response.completed\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"

        private fun jsonString(value: String): String =
            JsonWriter().string(value).toString()
    }
}
