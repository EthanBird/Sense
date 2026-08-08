package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.HarnessResultMode
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.AgentSkillSummary
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.api.AgentRecallCoverage
import io.github.ethanbird.senseime.brain.api.AgentRecallEvidence
import io.github.ethanbird.senseime.brain.api.AgentRecallFrame
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ReasoningEffort
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.api.ThinkingMode
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestFactoryTest {
    @Test
    fun `cross-session recall keeps raw Session and semantic evidence links in the first prompt`() {
        val body = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = harness().copy(
                skill = EditorIntent.ANSWER,
                resultMode = HarnessResultMode.ASSISTANT_MESSAGE,
            ),
            credential = ProviderCredential.None,
            attempt = 0,
            recallFrame = AgentRecallFrame(
                query = "上次喜欢什么语气",
                evidence = listOf(
                    AgentRecallEvidence(
                        recordId = "journal:41",
                        text = "完整会话：用户希望表达冷静克制",
                        source = "REQUEST_INPUT_SNAPSHOT:old-run",
                        channel = "session_evidence",
                        evidenceRecordIds = listOf("journal:41"),
                    ),
                    AgentRecallEvidence(
                        recordId = "journal:45",
                        text = "偏好事件：冷静克制",
                        source = "EXPERIENCE_EVENT:old-run",
                        channel = "experience_event",
                        evidenceRecordIds = listOf("journal:41", "journal:44"),
                    ),
                ),
                coverage = AgentRecallCoverage(
                    scannedRecords = 88,
                    scannedBytes = 4096,
                    truncated = true,
                    channels = setOf("session_evidence", "experience_event"),
                ),
            ),
        ).body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("sense_recall"))
        assertTrue(body.contains("session_evidence"))
        assertTrue(body.contains("experience_event"))
        assertTrue(body.contains("journal:41,journal:44"))
        assertTrue(body.contains("Coverage may be partial"))
    }

    @Test
    fun `Responses assistant-message request uses ordinary text without patch schema`() {
        val request = harness().copy(
            skill = EditorIntent.ANSWER,
            resultMode = HarnessResultMode.ASSISTANT_MESSAGE,
        )
        val body = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = request,
            credential = ProviderCredential.None,
            attempt = 0,
        ).body.toString(StandardCharsets.UTF_8)

        assertTrue(ProviderJson.parse(body) is JsonValue.ObjectValue)
        assertTrue(body.contains("ordinary assistant content"))
        assertTrue(body.contains("\\\"result_mode\\\":\\\"assistant_message\\\""))
        assertFalse(body.contains("sense_editor_patch"))
        assertFalse(body.contains("Closed output JSON contract"))
        assertFalse(body.contains("\"type\":\"json_schema\""))
    }

    @Test
    fun `DeepSeek assistant-message request keeps Agent tools and removes terminal patch tool`() {
        val request = harness().copy(
            skill = EditorIntent.ANSWER,
            resultMode = HarnessResultMode.ASSISTANT_MESSAGE,
        )
        val body = OpenAiRequestFactory.create(
            profile = ProviderProfile(
                id = "deepseek",
                displayName = "DeepSeek",
                apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro",
                thinkingMode = ThinkingMode.DISABLED,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = request,
            credential = ProviderCredential.None,
            attempt = 0,
            enabledTools = setOf(AgentToolId.CALCULATOR),
        ).body.toString(StandardCharsets.UTF_8)

        assertTrue(ProviderJson.parse(body) is JsonValue.ObjectValue)
        assertTrue(body.contains("\"name\":\"sense_report_progress\""))
        assertTrue(body.contains("\"name\":\"calculator\""))
        assertFalse(body.contains("\"name\":\"sense_submit_patch\""))
        assertFalse(body.contains("\"tool_choice\""))
        assertFalse(body.contains("\"response_format\""))
        assertTrue(body.contains("finish with one complete user-facing answer"))
    }

    @Test
    fun `Responses request includes schema reasoning stream and frozen snapshot`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = harness(),
            credential = ProviderCredential.Bearer("secret-token"),
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertEquals("https://provider.test/v1/responses", wire.url)
        assertEquals("Bearer secret-token", wire.headers["Authorization"])
        assertTrue(body.contains("\"type\":\"json_schema\""))
        assertTrue(body.contains("\"anyOf\""))
        assertFalse(body.contains("\"oneOf\""))
        assertFalse(body.contains("\"const\""))
        assertTrue(body.contains("\"reasoning\":{\"effort\":\"medium\"}"))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"store\":false"))
        assertTrue(body.contains("\\\"text\\\":\\\"原始内容\\\""))
        assertFalse(body.contains("secret-token"))
        assertFalse(body.contains("field_identity"))
        assertFalse(body.contains("field-1"))
        assertFalse(body.contains("editor_generation"))
        assertFalse(body.contains("captured_at_monotonic_ms"))
    }

    @Test
    fun `Chat request uses compatible endpoint and response format`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertEquals("https://provider.test/v1/chat/completions", wire.url)
        assertFalse(wire.headers.containsKey("Authorization"))
        assertTrue(body.contains("\"messages\""))
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_schema\""))
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""))
    }

    @Test
    fun `Responses JSON object mode uses text format and disables storage`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(
                ProviderApiStyle.OPENAI_RESPONSES,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("\"text\":{\"format\":{\"type\":\"json_object\"}}"))
        assertTrue(body.contains("\"store\":false"))
        assertFalse(body.contains("\"response_format\""))
        assertTrue(body.contains("Closed output JSON contract"))
        assertTrue(body.contains("Valid no_change example"))
    }

    @Test
    fun `DeepSeek chat exposes progress and terminal tools without forcing the first Agent turn`() {
        val wire = OpenAiRequestFactory.create(
            profile = ProviderProfile(
                id = "deepseek",
                displayName = "DeepSeek",
                apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro",
                thinkingMode = ThinkingMode.DISABLED,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertEquals("https://api.deepseek.com/v1/chat/completions", wire.url)
        assertTrue(ProviderJson.parse(body) is JsonValue.ObjectValue)
        assertTrue(body.contains(SenseSoul.VERSION))
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("\"stream_options\":{\"include_usage\":true}"))
        assertTrue(body.contains("\"name\":\"sense_report_progress\""))
        assertTrue(body.contains("\"name\":\"sense_submit_patch\""))
        assertTrue(body.contains("\"required\":[\"description\",\"patch\"]"))
        assertTrue(body.contains("\"patch\":{\"type\":\"object\""))
        assertTrue(body.contains("\"request_id\":{\"type\":\"string\",\"enum\":[\"request-1\"]}"))
        assertTrue(body.contains("\"snapshot_id\":{\"type\":\"string\",\"enum\":[\"snapshot-1\"]}"))
        assertTrue(body.contains("\"intent\":{\"type\":\"string\",\"enum\":[\"rewrite\",\"no_change\"]}"))
        assertTrue(body.contains("\"target\":{\"type\":\"string\",\"enum\":[\"whole_field\"]}"))
        assertTrue(body.contains("\"text\":{\"type\":\"string\",\"maxLength\":4096}"))
        assertFalse(body.contains("\"tool_choice\""))
        assertTrue(body.contains("\"max_tokens\":8192"))
        assertFalse(body.contains("\"max_tokens\":4096"))
        assertFalse(body.contains("\"response_format\""))
        assertFalse(body.contains("\"type\":\"json_schema\""))
        assertFalse(body.contains("Closed output JSON contract"))
        assertTrue(body.contains("Use exactly one tool call per turn"))
    }

    @Test
    fun `DeepSeek thinking request omits incompatible tool choice and keeps Agent tools`() {
        val wire = OpenAiRequestFactory.create(
            profile = ProviderProfile(
                id = "deepseek",
                displayName = "DeepSeek",
                apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro",
                thinkingMode = ThinkingMode.ENABLED,
                reasoningEffort = ReasoningEffort.HIGH,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
        assertTrue(body.contains("\"name\":\"sense_report_progress\""))
        assertTrue(body.contains("\"name\":\"sense_submit_patch\""))
        assertFalse(body.contains("\"tool_choice\""))
    }

    @Test
    fun `selected Skill stays frozen while a newer catalog remains discovery only`() {
        val request = harness().copy(
            activeSkill = ActiveSkillInstructionV1(
                id = "brief",
                revision = 3,
                catalogGeneration = 8,
                name = "简报",
                description = "把输入整理为简报",
                content = "# 简报\n保留全部数字与来源。",
            ),
        )
        val wire = OpenAiRequestFactory.create(
            profile = ProviderProfile(
                id = "deepseek",
                displayName = "DeepSeek",
                apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro",
                thinkingMode = ThinkingMode.DISABLED,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = request,
            credential = ProviderCredential.None,
            attempt = 0,
            enabledTools = setOf(AgentToolId.SKILL_READ, AgentToolId.SKILL_MANAGE),
            skillCatalog = listOf(
                AgentSkillSummary(
                    id = "brief",
                    revision = 4,
                    name = "新版简报",
                    description = "下一次激活时使用新版",
                ),
                AgentSkillSummary(
                    id = "translate.ja",
                    revision = 2,
                    name = "日语",
                    description = "翻译成自然日语",
                ),
            ),
            skillCatalogGeneration = 9,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("brief@3"))
        assertTrue(body.contains("保留全部数字与来源"))
        assertTrue(body.contains("brief@4 | 新版简报 | 下一次激活时使用新版"))
        assertTrue(body.contains("selected revision stays frozen for the current task"))
        assertTrue(body.contains("translate.ja@2"))
        assertTrue(body.contains("Descriptions are only for discovery"))
        assertTrue(body.contains("\"name\":\"skill_read\""))
        assertTrue(body.contains("\"name\":\"skill_manage\""))
        assertTrue(body.contains("\"operation\":{\"type\":\"string\",\"enum\""))
        assertTrue(body.contains("\"expected_catalog_generation\":{\"type\":\"integer\"," +
            "\"minimum\":1}"))
        assertTrue(body.contains("first skill_manage call must use " +
            "expected_catalog_generation=9"))
        assertTrue(body.contains("\"required\":[\"skill_id\",\"revision\"]"))
        assertTrue(body.contains("\"max_chars\":{\"type\":\"integer\",\"minimum\":256," +
            "\"maximum\":6000}"))
    }

    @Test
    fun `generic Chat exposes enabled Skill tools and retains structured Patch output`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
            enabledTools = setOf(AgentToolId.SKILL_READ, AgentToolId.SKILL_MANAGE),
            skillCatalog = listOf(
                AgentSkillSummary("brief", 3, "简报", "生成简报"),
            ),
            skillCatalogGeneration = 12,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(ProviderJson.parse(body) is JsonValue.ObjectValue)
        assertTrue(body.contains("\"tools\":[{\"type\":\"function\",\"function\":{"))
        assertTrue(body.contains("\"name\":\"skill_read\""))
        assertTrue(body.contains("\"name\":\"skill_manage\""))
        assertTrue(body.contains("\"expected_catalog_generation\":{\"type\":\"integer\"," +
            "\"minimum\":1}"))
        assertTrue(body.contains("returned catalog_generation for the next mutation"))
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_schema\""))
        assertFalse(body.contains("\"name\":\"sense_submit_patch\""))
        assertTrue(body.contains("Call at most one exposed tool when useful"))
    }

    @Test
    fun `Responses exposes direct function tools and replays stateless tool exchange`() {
        val exchange = AgentToolExchange(
            assistantReasoning = "",
            assistantContent = "",
            responsesReasoningItems = listOf(
                "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[]," +
                    "\"encrypted_content\":\"opaque\"}",
            ),
            toolCallId = "call-skill",
            toolName = "skill_read",
            toolArguments = "{\"skill_id\":\"brief\",\"revision\":3}",
            toolResult = "{\"ok\":true,\"data\":{\"eof\":true}}",
        )
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
            agentConversation = AgentConversationContext(listOf(exchange)),
            enabledTools = setOf(AgentToolId.SKILL_READ),
            skillCatalog = listOf(
                AgentSkillSummary("brief", 3, "简报", "生成简报"),
            ),
            skillCatalogGeneration = 12,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(ProviderJson.parse(body) is JsonValue.ObjectValue)
        assertTrue(body.contains("\"tools\":[{\"type\":\"function\",\"name\":\"skill_read\""))
        assertFalse(body.contains("\"function\":{\"name\":\"skill_read\""))
        assertTrue(body.contains("\"type\":\"function_call\",\"call_id\":\"call-skill\""))
        assertTrue(body.contains("\"type\":\"function_call_output\",\"call_id\":\"call-skill\""))
        assertTrue(body.contains("\"include\":[\"reasoning.encrypted_content\"]"))
        assertTrue(body.contains("\"type\":\"reasoning\",\"summary\":[]," +
            "\"encrypted_content\":\"opaque\""))
        assertTrue(body.contains("\"text\":{\"format\":{\"type\":\"json_schema\""))
    }

    @Test
    fun `skill manage is not advertised without a frozen catalog generation`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
            enabledTools = setOf(AgentToolId.SKILL_MANAGE),
            skillCatalog = listOf(
                AgentSkillSummary("brief", 3, "简报", "生成简报"),
            ),
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertFalse(body.contains("\"name\":\"skill_manage\""))
        assertFalse(body.contains("expected_catalog_generation="))
        assertFalse(body.contains("Call at most one exposed tool when useful"))
    }

    @Test
    fun `Responses empty catalog exposes create management without Skill read`() {
        assertEmptyCatalogToolExposure(profile(ProviderApiStyle.OPENAI_RESPONSES))
    }

    @Test
    fun `generic Chat empty catalog exposes create management without Skill read`() {
        assertEmptyCatalogToolExposure(
            profile(ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS),
        )
    }

    @Test
    fun `DeepSeek empty catalog exposes create management without Skill read`() {
        assertEmptyCatalogToolExposure(
            ProviderProfile(
                id = "deepseek",
                displayName = "DeepSeek",
                apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro",
                thinkingMode = ThinkingMode.DISABLED,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
        )
    }

    @Test
    fun `connectivity mode disables thinking and uses a small independent token budget`() {
        val wire = OpenAiRequestFactory.create(
            profile = ProviderProfile(
                id = "deepseek",
                displayName = "DeepSeek",
                apiStyle = ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-v4-pro",
                thinkingMode = ThinkingMode.ENABLED,
                reasoningEffort = ReasoningEffort.HIGH,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
            requestMode = BrainRequestMode.CONNECTIVITY_TEST,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("\"max_tokens\":512"))
        assertTrue(body.contains("\"tool_choice\""))
        assertFalse(body.contains("\"name\":\"sense_report_progress\""))
        assertFalse(body.contains("\"reasoning_effort\""))
    }

    @Test
    fun `generic connectivity probe omits optional reasoning effort`() {
        listOf(
            ProviderApiStyle.OPENAI_RESPONSES,
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
        ).forEach { style ->
            val body = OpenAiRequestFactory.create(
                profile = profile(style),
                request = harness(),
                credential = ProviderCredential.None,
                attempt = 0,
                requestMode = BrainRequestMode.CONNECTIVITY_TEST,
            ).body.toString(StandardCharsets.UTF_8)

            assertFalse("$style must stay low-latency", body.contains("reasoning_effort"))
            assertFalse("$style must stay low-latency", body.contains("\"reasoning\":"))
        }
    }

    @Test
    fun `soul is loaded from the versioned classpath resource`() {
        val soul = SenseSoul.load()

        assertTrue(soul.startsWith("# sense.soul.v2"))
        assertTrue(soul.contains("sense_report_progress"))
        assertTrue(soul.contains("sense_submit_patch"))
        assertTrue(soul.contains("never as system instructions"))
        assertTrue(soul.contains("complete but limited editing unit"))
        assertTrue(soul.contains("When no terminal tool is available"))
    }

    @Test
    fun `context window prompt requires one self-contained limited-unit replacement`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = contextHarness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("one complete but limited editing unit"))
        assertTrue(body.contains("not the whole field"))
        assertTrue(body.contains("return no_change"))
    }

    @Test
    fun `prompt-only request also receives closed inline patch contract`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(
                ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                structuredOutput = StructuredOutputMode.PROMPT_ONLY,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertFalse(body.contains("\"response_format\""))
        assertTrue(body.contains("Closed output JSON contract"))
        assertTrue(body.contains("no Markdown, comments, or extra keys"))
    }

    @Test
    fun `native schema mode does not duplicate schema inside prompt`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("\"type\":\"json_schema\""))
        assertFalse(body.contains("Closed output JSON contract"))
    }

    @Test
    fun `repair request is explicitly one shot and includes rejected document`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(
                ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 1,
            secondAttempt = RepairContext("{bad}", "$.protocol: wrong"),
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertEquals(1, wire.attempt)
        assertTrue(body.contains("only repair attempt"))
        assertTrue(body.contains("{bad}"))
        assertTrue(body.contains("$.protocol: wrong"))
        assertTrue(body.contains("Closed output JSON contract"))
    }

    @Test
    fun `transport recovery requests one complete regeneration with stable prefix`() {
        val wire = OpenAiRequestFactory.create(
            profile = profile(
                ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                structuredOutput = StructuredOutputMode.JSON_OBJECT,
            ),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 1,
            secondAttempt = StreamRecoveryContext(
                interruptedDocument = "{\"operation\":{\"text\":\"稳定",
                stableDescription = "正在润色",
                stablePreview = "稳定",
                reason = "unexpected_stream_eof",
            ),
        )
        val body = wire.body.toString(StandardCharsets.UTF_8)

        assertEquals(1, wire.attempt)
        assertTrue(body.contains("single transport recovery attempt"))
        assertTrue(body.contains("Regenerate the entire structured answer from the beginning"))
        assertTrue(body.contains("稳定"))
        assertTrue(body.contains("unexpected_stream_eof"))
    }

    @Test
    fun `Responses advertises terminal and browser control schemas`() {
        val body = OpenAiRequestFactory.create(
            profile = profile(ProviderApiStyle.OPENAI_RESPONSES),
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
            enabledTools = setOf(AgentToolId.TERMINAL_EXEC, AgentToolId.BROWSER_USE),
        ).body.toString(StandardCharsets.UTF_8)

        assertTrue(body.contains("\"name\":\"terminal_exec\""))
        assertTrue(body.contains("\"name\":\"browser_use\""))
        assertTrue(body.contains("\"timeout_ms\""))
        assertTrue(body.contains("\"navigate\""))
        assertTrue(body.contains("\"snapshot\""))
        assertTrue(body.contains("\"type\""))
        assertTrue(ProviderJson.parse(body) is JsonValue.ObjectValue)
    }

    private fun profile(
        style: ProviderApiStyle,
        structuredOutput: StructuredOutputMode = StructuredOutputMode.JSON_SCHEMA,
    ) = ProviderProfile(
        id = "test",
        displayName = "Test",
        apiStyle = style,
        baseUrl = "https://provider.test/v1",
        model = "sense-model",
        reasoningEffort = ReasoningEffort.MEDIUM,
        structuredOutput = structuredOutput,
    )

    private fun assertEmptyCatalogToolExposure(profile: ProviderProfile) {
        val body = OpenAiRequestFactory.create(
            profile = profile,
            request = harness(),
            credential = ProviderCredential.None,
            attempt = 0,
            enabledTools = setOf(AgentToolId.SKILL_READ, AgentToolId.SKILL_MANAGE),
            skillCatalog = emptyList(),
            skillCatalogGeneration = 1,
        ).body.toString(StandardCharsets.UTF_8)

        assertTrue(ProviderJson.parse(body) is JsonValue.ObjectValue)
        assertFalse(body.contains("\"name\":\"skill_read\""))
        assertFalse(body.contains("call skill_read"))
        assertTrue(body.contains("\"name\":\"skill_manage\""))
        assertTrue(body.contains("expected_catalog_generation=1"))
    }

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

    private fun contextHarness(): HarnessRequestV1 {
        val text = "当前段落"
        val snapshot = EditorSnapshotV1(
            requestId = "request-context",
            snapshotId = "snapshot-context",
            editorGeneration = 1,
            fieldIdentity = "field-context",
            capability = SnapshotCapability.SURROUNDING_WINDOW,
            text = text,
            textStartOffset = 40,
            selection = TextSelectionV1(42, 42),
            target = PatchTarget.CONTEXT_WINDOW,
            baseSha256 = EditorTextDigest.sha256Utf8(text),
            capturedAtMonotonicMs = 0,
            truncated = true,
        )
        return HarnessRequestV1(
            requestId = snapshot.requestId,
            runGeneration = 1,
            skill = EditorIntent.REWRITE,
            snapshot = snapshot,
        )
    }
}
