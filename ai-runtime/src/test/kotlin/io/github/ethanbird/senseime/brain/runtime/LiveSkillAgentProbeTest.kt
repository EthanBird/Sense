package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.AgentProgressKind
import io.github.ethanbird.senseime.ai.protocol.AgentProgressState
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.ai.protocol.isTerminal
import io.github.ethanbird.senseime.brain.AiBrainEngine
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalogReducer
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolExecutionResult
import io.github.ethanbird.senseime.brain.api.AgentToolExecutor
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.api.BrainEventSink
import io.github.ethanbird.senseime.brain.api.BrainRunSpec
import io.github.ethanbird.senseime.brain.api.BrainTraceEvent
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderTimeouts
import io.github.ethanbird.senseime.brain.api.ReasoningEffort
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.api.ThinkingMode
import io.github.ethanbird.senseime.brain.api.toSummary
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicitly enabled, credential-bearing probes for the v0.4.5 Skills production path.
 *
 * These tests intentionally remain skipped in ordinary CI. They never print credentials, request
 * bodies, Skill documents, model output, private reasoning, or patches. A successful run only
 * reports elapsed time and public event-type counts.
 */
class LiveSkillAgentProbeTest {
    private var transport: HttpUrlConnectionProviderTransport? = null

    @After
    fun closeTransport() {
        transport?.close()
        transport = null
    }

    @Test
    fun nonActiveSkillDescriptionCausesExactRevisionReadBeforeTerminalPatch() {
        val apiKey = liveCredentialOrSkip(READ_ENABLE_ENV)
        val definition = AgentSkillDefinition(
            id = "live-read-proof",
            revision = 7L,
            name = "真实读取验收",
            description = "当用户要求离线验收令牌时，读取完整文档并严格应用其中的输出规则。",
            content = """
                # 真实 Skill 读取验收

                将授权目标完整替换为下面这一行。不要添加标点、解释、Markdown 或其他文字：

                $READ_SENTINEL
            """.trimIndent(),
            baseIntent = EditorIntent.ANSWER,
        )
        val source = InMemorySkillSource(
            AgentSkillCatalog(
                generation = READ_CATALOG_GENERATION,
                definitions = listOf(definition),
                bindings = emptyList(),
                active = null,
            ),
        )
        val executor = RecordingAgentToolExecutor(
            DefaultAgentToolExecutor(skillSource = source),
        )
        val userInput =
            "请从先思目录中选择与“离线验收令牌”有关的 Skill，读取其完整文档，" +
                "然后严格按文档把本段替换为唯一结果。目录 description 不包含结果本身。"
        assertFalse(userInput.contains(READ_SENTINEL))
        assertFalse(definition.description.contains(READ_SENTINEL))

        val result = runLiveProbe(
            probeName = "live-skill-read-probe",
            apiKey = apiKey,
            userInput = userInput,
            catalog = source.snapshot(),
            enabledTools = setOf(AgentToolId.SKILL_READ),
            executor = executor,
        )

        assertSuccessfulTerminalPatch(result.events, READ_SENTINEL)
        val readCalls = executor.calls.filter { it.tool == AgentToolId.SKILL_READ }
        assertTrue("DefaultAgentToolExecutor did not receive skill_read", readCalls.isNotEmpty())
        assertTrue(
            "skill_read did not use the exact advertised immutable revision",
            readCalls.all {
                val arguments = it.arguments as AgentToolArguments.SkillRead
                arguments.skillId == definition.id &&
                    arguments.revision == definition.revision
            },
        )
        assertToolLifecycle(result.events, AgentToolId.SKILL_READ, readCalls)
        assertNoPrivateReasoningSurface(result, READ_SENTINEL)
        printSafeResult("live-skill-read-probe", result)
    }

    /**
     * Higher-variance live mutation probe. It has a separate enable switch so it never becomes an
     * implicit release/CI gate. A read of revision 4 can reach the executor only after Brain has
     * accepted skill_manage's authoritative generation-92 catalog snapshot.
     */
    @Test
    fun manageThenReadUsesNewCatalogGenerationAndRevision() {
        val apiKey = liveCredentialOrSkip(MANAGE_ENABLE_ENV)
        val initial = AgentSkillDefinition(
            id = "live-manage-proof",
            revision = 3L,
            name = "修改前名称",
            description = "用于显式验证 Skill 修改后继续读取新 revision 的多轮工具协议。",
            content = """
                # 修改后读取验收

                完成名称更新后，必须读取当前 revision，再将授权目标完整替换为下面一行：

                $MANAGE_SENTINEL
            """.trimIndent(),
            baseIntent = EditorIntent.ANSWER,
        )
        val source = InMemorySkillSource(
            AgentSkillCatalog(
                generation = MANAGE_CATALOG_GENERATION,
                definitions = listOf(initial),
                bindings = emptyList(),
                active = null,
            ),
        )
        val executor = RecordingAgentToolExecutor(
            DefaultAgentToolExecutor(skillSource = source),
        )
        val userInput =
            "请先用 skill_manage 把目录中的 live-manage-proof 名称精确修改为“修改后名称”，" +
                "其他字段保持不变；成功后用返回的新 revision 调用 skill_read，读取完整正文并" +
                "严格按正文返回最终结果。不要先读取旧 revision。"
        assertFalse(userInput.contains(MANAGE_SENTINEL))
        assertFalse(initial.description.contains(MANAGE_SENTINEL))

        val result = runLiveProbe(
            probeName = "live-skill-manage-probe",
            apiKey = apiKey,
            userInput = userInput,
            catalog = source.snapshot(),
            enabledTools = setOf(AgentToolId.SKILL_MANAGE, AgentToolId.SKILL_READ),
            executor = executor,
        )

        assertSuccessfulTerminalPatch(result.events, MANAGE_SENTINEL)
        val calls = executor.calls.toList()
        val manageIndexes = calls.indices.filter {
            calls[it].tool == AgentToolId.SKILL_MANAGE
        }
        assertEquals("live mutation probe must perform one controlled mutation", 1, manageIndexes.size)
        val manageIndex = manageIndexes.single()
        val manage = calls[manageIndex].arguments as AgentToolArguments.SkillManage.Update
        assertEquals(initial.id, manage.skillId)
        assertEquals(MANAGE_CATALOG_GENERATION, manage.expectedCatalogGeneration)
        assertEquals("修改后名称", manage.name)

        val readIndexes = calls.indices.filter { calls[it].tool == AgentToolId.SKILL_READ }
        assertTrue("skill_read did not follow the successful mutation", readIndexes.isNotEmpty())
        assertTrue("an old revision was read before skill_manage", readIndexes.all { it > manageIndex })
        assertTrue(
            "the post-mutation read did not use the newly advertised revision",
            readIndexes.all {
                val arguments = calls[it].arguments as AgentToolArguments.SkillRead
                arguments.skillId == initial.id && arguments.revision == initial.revision + 1L
            },
        )
        assertEquals(MANAGE_CATALOG_GENERATION + 1L, source.snapshot().generation)
        assertEquals(initial.revision + 1L, source.snapshot().definition(initial.id)?.revision)
        assertToolLifecycle(
            result.events,
            AgentToolId.SKILL_MANAGE,
            listOf(calls[manageIndex]),
        )
        assertToolLifecycle(
            result.events,
            AgentToolId.SKILL_READ,
            readIndexes.map(calls::get),
        )
        assertNoPrivateReasoningSurface(result, MANAGE_SENTINEL)
        printSafeResult("live-skill-manage-probe", result)
    }

    private fun runLiveProbe(
        probeName: String,
        apiKey: String,
        userInput: String,
        catalog: AgentSkillCatalog,
        enabledTools: Set<AgentToolId>,
        executor: AgentToolExecutor,
    ): ProbeResult {
        val requestId = probeName
        val request = HarnessRequestV1(
            requestId = requestId,
            runGeneration = 1L,
            skill = EditorIntent.ANSWER,
            skillCatalogGeneration = catalog.generation,
            snapshot = EditorSnapshotV1(
                requestId = requestId,
                snapshotId = "$probeName-snapshot",
                editorGeneration = 1L,
                fieldIdentity = "$probeName-field",
                capability = SnapshotCapability.FULL_DOCUMENT,
                text = userInput,
                selection = TextSelectionV1(userInput.length, userInput.length),
                target = PatchTarget.WHOLE_FIELD,
                baseSha256 = EditorTextDigest.sha256Utf8(userInput),
                capturedAtMonotonicMs = 1L,
                truncated = false,
            ),
        )
        val events = CopyOnWriteArrayList<AiEvent>()
        val traces = CopyOnWriteArrayList<BrainTraceEvent>()
        val liveTransport = HttpUrlConnectionProviderTransport().also { transport = it }
        val startedAt = System.nanoTime()
        val handle = AiBrainEngine(
            transport = liveTransport,
            toolExecutor = executor,
        ).start(
            BrainRunSpec(
                harnessRequest = request,
                provider = liveProviderProfile(probeName),
                credential = ProviderCredential.Bearer(apiKey),
                skillCatalog = catalog.definitions.map(AgentSkillDefinition::toSummary),
                skillCatalogGeneration = catalog.generation,
                enabledTools = enabledTools,
                traceSink = traces::add,
            ),
            BrainEventSink(events::add),
        )

        val deadline = System.nanoTime() + LIVE_DEADLINE_MS * 1_000_000L
        try {
            while (!handle.isTerminal && System.nanoTime() < deadline) {
                Thread.sleep(TICK_INTERVAL_MS)
                handle.tick()
            }
        } finally {
            if (!handle.isTerminal) {
                handle.cancel(HarnessCancelReason.CALLER_REQUESTED)
            }
        }
        return ProbeResult(
            events = events.toList(),
            traces = traces.toList(),
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
        )
    }

    private fun liveProviderProfile(probeName: String): ProviderProfile = ProviderProfile(
        id = probeName,
        displayName = "Skills live provider probe",
        apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
        baseUrl = System.getenv(BASE_URL_ENV)
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_BASE_URL,
        model = System.getenv(MODEL_ENV)
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_MODEL,
        thinkingMode = ThinkingMode.ENABLED,
        reasoningEffort = ReasoningEffort.DEFAULT,
        streaming = true,
        structuredOutput = StructuredOutputMode.PROMPT_ONLY,
        timeouts = ProviderTimeouts(
            connectTimeoutMs = 15_000,
            firstEventTimeoutMs = 60_000,
            streamIdleTimeoutMs = 150_000,
            totalTimeoutMs = LIVE_DEADLINE_MS,
        ),
    )

    private fun liveCredentialOrSkip(enableEnvironment: String): String {
        val enabled = System.getenv(enableEnvironment) == "1"
        val apiKey = System.getenv(KEY_ENV).orEmpty()
        if (!enabled || apiKey.isBlank()) {
            println("$enableEnvironment SKIPPED: explicit opt-in credential was not supplied")
        }
        assumeTrue(
            "SKIPPED: set $enableEnvironment=1 and provide $KEY_ENV to run this live probe.",
            enabled && apiKey.isNotBlank(),
        )
        return apiKey
    }

    private fun assertSuccessfulTerminalPatch(events: List<AiEvent>, sentinel: String) {
        assertTrue("live run did not reach a terminal event", events.any(AiEvent::isTerminal))
        val failures = events.filterIsInstance<AiEvent.Failed>()
        assertTrue("live run failed with codes ${failures.map(AiEvent.Failed::code)}", failures.isEmpty())
        val terminalPatches = events.filterIsInstance<AiEvent.FinalPatch>()
        assertEquals("the local Patch gate must emit exactly one terminal Patch", 1, terminalPatches.size)
        val operation = terminalPatches.single().patch.operation
        assertTrue("terminal Patch was not a whole-field replacement", operation.type == PatchOperationType.REPLACE)
        assertTrue("terminal Patch target changed", operation.target == PatchTarget.WHOLE_FIELD)
        assertTrue("terminal Patch did not apply the Skill-only sentinel", operation.text == sentinel)
    }

    private fun assertToolLifecycle(
        events: List<AiEvent>,
        tool: AgentToolId,
        calls: List<AgentToolCall>,
    ) {
        val callIds = calls.map(AgentToolCall::callId).toSet()
        val progress = events.filterIsInstance<AiEvent.AgentProgress>().filter {
            it.kind == AgentProgressKind.TOOL &&
                it.toolName == tool.wireValue &&
                it.toolCallId in callIds
        }
        assertTrue(
            "${tool.wireValue} did not publish a correlated running/completed lifecycle",
            callIds.isNotEmpty() && callIds.all { callId ->
                progress.any {
                    it.toolCallId == callId && it.state == AgentProgressState.RUNNING
                } &&
                    progress.any {
                        it.toolCallId == callId && it.state == AgentProgressState.COMPLETED
                    }
            },
        )
    }

    private fun assertNoPrivateReasoningSurface(
        result: ProbeResult,
        approvedFinalPatchText: String,
    ) {
        val publicText: List<String> = buildList {
            result.events.forEach { event ->
                when (event) {
                    is AiEvent.Status -> add(event.label)
                    is AiEvent.AgentProgress -> {
                        add(event.title)
                        add(event.detail)
                    }
                    is AiEvent.DescriptionDelta -> add(event.text)
                    is AiEvent.PreviewDelta -> add(event.text)
                    is AiEvent.PreviewReplace -> {
                        add(event.text)
                        add(event.description)
                    }
                    is AiEvent.FinalPatch -> {
                        // The exact final text is independently constrained to the document-only
                        // sentinel. Include any unexpected text here in the privacy comparison,
                        // while avoiding a false positive when private planning names that same
                        // authorized result.
                        event.patch.operation.text
                            ?.takeIf { it != approvedFinalPatchText }
                            ?.let(::add)
                    }
                    else -> Unit
                }
            }
        }
        val privateReasoning = result.traces
            .filterIsInstance<BrainTraceEvent.ToolCall>()
            .map(BrainTraceEvent.ToolCall::privateReasoning)
            .filter(String::isNotBlank)
        assertTrue(
            "thinking-mode probe captured no private reasoning and cannot prove non-disclosure",
            privateReasoning.isNotEmpty(),
        )
        val privateMarkers = listOf("reasoning_content", "<think>", "</think>")
        assertFalse(
            "private reasoning protocol markers leaked into a public event",
            publicText.any { text ->
                privateMarkers.any { marker -> text.contains(marker, ignoreCase = true) }
            },
        )
        assertFalse(
            "a private Brain reasoning fragment leaked verbatim into a public event",
            privateReasoning
                .flatMap(::privateLeakDetectionFragments)
                .any { privateFragment ->
                    publicText.any { publicValue ->
                        normalizeProbeText(publicValue).contains(privateFragment)
                    }
                },
        )
    }

    private fun privateLeakDetectionFragments(value: String): List<String> {
        val normalized = normalizeProbeText(value)
        if (normalized.length < PRIVATE_FRAGMENT_MIN_CHARS) return emptyList()
        return buildList {
            var offset = 0
            while (offset + PRIVATE_FRAGMENT_MIN_CHARS <= normalized.length) {
                add(normalized.substring(offset, offset + PRIVATE_FRAGMENT_MIN_CHARS))
                offset += PRIVATE_FRAGMENT_STRIDE_CHARS
            }
            val tailOffset = normalized.length - PRIVATE_FRAGMENT_MIN_CHARS
            if (tailOffset >= 0) add(normalized.substring(tailOffset))
        }.distinct()
    }

    private fun normalizeProbeText(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun printSafeResult(name: String, result: ProbeResult) {
        val eventTypes = result.events
            .groupingBy { it.javaClass.simpleName }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(separator = ",") { (type, count) -> "$type:$count" }
        println("$name elapsed_ms=${result.elapsedMs} event_types=$eventTypes")
    }

    private data class ProbeResult(
        val events: List<AiEvent>,
        val traces: List<BrainTraceEvent>,
        val elapsedMs: Long,
    )

    private class RecordingAgentToolExecutor(
        private val delegate: DefaultAgentToolExecutor,
    ) : AgentToolExecutor {
        val calls = CopyOnWriteArrayList<AgentToolCall>()

        override fun execute(call: AgentToolCall): AgentToolExecutionResult {
            calls += call
            return delegate.execute(call)
        }
    }

    private class InMemorySkillSource(
        initial: AgentSkillCatalog,
    ) : AgentSkillToolSource {
        private var catalog = initial
        private val revisions = initial.definitions.associateBy {
            it.id to it.revision
        }.toMutableMap()

        @Synchronized
        override fun read(skillId: String, revision: Long): AgentSkillDefinition? =
            revisions[skillId to revision]

        @Synchronized
        override fun apply(mutation: AgentSkillMutation): AgentSkillCatalog {
            val result = AgentSkillCatalogReducer.apply(catalog, mutation)
            result.newRevision?.let { definition ->
                revisions[definition.id to definition.revision] = definition
            }
            catalog = result.catalog
            return catalog
        }

        @Synchronized
        fun snapshot(): AgentSkillCatalog = catalog
    }

    private companion object {
        const val READ_ENABLE_ENV = "SENSE_RUN_LIVE_SKILL_READ_TEST"
        const val MANAGE_ENABLE_ENV = "SENSE_RUN_LIVE_SKILL_MANAGE_TEST"
        const val KEY_ENV = "SENSE_TEST_API_KEY"
        const val BASE_URL_ENV = "SENSE_TEST_API_BASE"
        const val MODEL_ENV = "SENSE_TEST_MODEL"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-v4-pro"
        const val TICK_INTERVAL_MS = 100L
        const val LIVE_DEADLINE_MS = 300_000L
        const val PRIVATE_FRAGMENT_MIN_CHARS = 32
        const val PRIVATE_FRAGMENT_STRIDE_CHARS = 16
        const val READ_CATALOG_GENERATION = 41L
        const val MANAGE_CATALOG_GENERATION = 91L
        const val READ_SENTINEL = "SENSE_SKILL_READ_PROBE_7Q4M2"
        const val MANAGE_SENTINEL = "SENSE_SKILL_MANAGE_PROBE_9K6R3"
    }
}
