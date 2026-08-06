package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.HarnessResultMode
import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.api.AgentBrowserAction
import io.github.ethanbird.senseime.brain.api.AgentSkillSummary
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderWireRequest
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.api.ThinkingMode
import java.nio.charset.StandardCharsets

internal sealed interface SecondAttemptContext

internal data class RepairContext(
    val rejectedDocument: String,
    val validationSummary: String,
) : SecondAttemptContext

internal data class StreamRecoveryContext(
    val interruptedDocument: String,
    val stableDescription: String,
    val stablePreview: String,
    val reason: String,
) : SecondAttemptContext

/**
 * One provider-visible tool exchange retained for a bounded Agent sub-turn.
 *
 * Reasoning is replayed only because DeepSeek requires it after a thinking-mode tool call. It
 * never leaves private Brain and is never exposed to the IME process.
 */
internal data class AgentToolExchange(
    val assistantReasoning: String,
    val assistantContent: String,
    val responsesReasoningItems: List<String> = emptyList(),
    val toolCallId: String,
    val toolName: String,
    val toolArguments: String,
    val toolResult: String,
)

internal data class AgentConversationContext(
    val exchanges: List<AgentToolExchange>,
    val forceTerminalTool: Boolean = false,
)

internal object OpenAiRequestFactory {
    fun create(
        profile: ProviderProfile,
        request: HarnessRequestV1,
        credential: ProviderCredential,
        attempt: Int,
        secondAttempt: SecondAttemptContext? = null,
        agentConversation: AgentConversationContext? = null,
        requestMode: BrainRequestMode = BrainRequestMode.NORMAL,
        enabledTools: Set<AgentToolId> = emptySet(),
        skillCatalog: List<AgentSkillSummary> = emptyList(),
        skillCatalogGeneration: Long? = null,
    ): ProviderWireRequest {
        profile.requireValid()
        require(attempt in 0..1)
        require((attempt == 0) == (secondAttempt == null))
        require(secondAttempt == null || agentConversation == null)

        val nativeToolProtocol = usesNativeToolProtocol(profile)
        val requiresEditorPatch = request.resultMode == HarnessResultMode.EDITOR_PATCH
        val nativePatchTool = nativeToolProtocol && requiresEditorPatch
        val frozenCatalogGeneration =
            skillCatalogGeneration
                ?: request.skillCatalogGeneration
                ?: request.activeSkill?.catalogGeneration
        val exposedAgentTools = exposedAgentTools(
            enabledTools = enabledTools,
            requestMode = requestMode,
            secondAttempt = secondAttempt,
            conversation = agentConversation,
            skillCatalogGeneration = frozenCatalogGeneration,
            hasReadableSkills = skillCatalog.isNotEmpty(),
        )
        val includeInlineContract =
            requiresEditorPatch &&
                !nativePatchTool &&
                profile.structuredOutput != StructuredOutputMode.JSON_SCHEMA
        val prompt = when (secondAttempt) {
            null -> buildHarnessInput(
                request,
                includeInlineContract,
                nativeToolProtocol,
                nativePatchTool,
                exposedAgentTools,
                skillCatalog,
                frozenCatalogGeneration,
            )
            is RepairContext ->
                buildRepairInput(request, secondAttempt, includeInlineContract, nativePatchTool)
            is StreamRecoveryContext ->
                buildRecoveryInput(request, secondAttempt, includeInlineContract, nativePatchTool)
        }
        val body = when (profile.apiStyle) {
            ProviderApiStyle.OPENAI_RESPONSES ->
                responsesBody(
                    profile = profile,
                    prompt = prompt,
                    requestMode = requestMode,
                    conversation = agentConversation,
                    enabledTools = exposedAgentTools,
                    skillCatalogGeneration = frozenCatalogGeneration,
                    requiresEditorPatch = requiresEditorPatch,
                )
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS ->
                chatCompletionsBody(
                    profile,
                    prompt,
                    request,
                    requestMode,
                    nativeToolProtocol,
                    nativePatchTool,
                    agentConversation,
                    secondAttempt != null,
                    exposedAgentTools,
                    frozenCatalogGeneration,
                )
        }
        val headers = linkedMapOf(
            "Accept" to if (profile.streaming) "text/event-stream" else "application/json",
            "Content-Type" to "application/json; charset=utf-8",
            "User-Agent" to "Sense-IME/0.4.8 AI-Brain",
        )
        when (credential) {
            is ProviderCredential.Bearer -> headers["Authorization"] = "Bearer ${credential.token}"
            ProviderCredential.None -> Unit
        }
        return ProviderWireRequest(
            requestId = request.requestId,
            attempt = attempt,
            url = profile.endpointUrl(),
            headers = headers,
            body = body.toByteArray(StandardCharsets.UTF_8),
            connectTimeoutMs = profile.timeouts.connectTimeoutMs.toSafeInt(),
            readTimeoutMs = profile.timeouts.streamIdleTimeoutMs.toSafeInt(),
        )
    }

    private fun responsesBody(
        profile: ProviderProfile,
        prompt: String,
        requestMode: BrainRequestMode,
        conversation: AgentConversationContext?,
        enabledTools: Set<AgentToolId>,
        skillCatalogGeneration: Long?,
        requiresEditorPatch: Boolean,
    ): String = buildString {
        append('{')
        property("model", profile.model)
        append(',')
        property("instructions", SenseSoul.text)
        append(",\"input\":")
        appendResponsesInput(prompt, conversation)
        append(",\"stream\":").append(profile.streaming)
        append(",\"store\":false")
        append(",\"max_output_tokens\":").append(providerTokenBudget(requestMode))
        if (requestMode == BrainRequestMode.NORMAL) {
            profile.reasoningEffort.wireValue?.let {
                append(",\"reasoning\":{\"effort\":")
                jsonString(it)
                append('}')
            }
        }
        if (enabledTools.isNotEmpty()) {
            append(",\"include\":[\"reasoning.encrypted_content\"]")
            appendResponsesAgentTools(enabledTools, skillCatalogGeneration)
        }
        if (requiresEditorPatch) {
            appendStructuredOutput(profile, responses = true)
        }
        append('}')
    }

    private fun chatCompletionsBody(
        profile: ProviderProfile,
        prompt: String,
        request: HarnessRequestV1,
        requestMode: BrainRequestMode,
        nativeToolProtocol: Boolean,
        nativePatchTool: Boolean,
        agentConversation: AgentConversationContext?,
        repairOrRecovery: Boolean,
        enabledTools: Set<AgentToolId>,
        skillCatalogGeneration: Long?,
    ): String = buildString {
        append('{')
        property("model", profile.model)
        append(",\"messages\":")
        appendChatMessages(prompt, agentConversation)
        append(",\"stream\":").append(profile.streaming)
        if (nativeToolProtocol && profile.streaming) {
            append(",\"stream_options\":{\"include_usage\":true}")
        }
        append(",\"max_tokens\":").append(providerTokenBudget(requestMode))
        if (nativeToolProtocol) {
            appendDeepSeekThinking(profile, requestMode)
            val includeProgressTool =
                requestMode == BrainRequestMode.NORMAL &&
                    !repairOrRecovery &&
                    agentConversation?.forceTerminalTool != true
            val nativeAgentTools = if (
                requestMode == BrainRequestMode.NORMAL &&
                !repairOrRecovery &&
                agentConversation?.forceTerminalTool != true
            ) {
                enabledTools
            } else {
                emptySet()
            }
            if (includeProgressTool || nativeAgentTools.isNotEmpty() || nativePatchTool) {
                appendNativeAgentTools(
                    request = request,
                    includeProgressTool = includeProgressTool,
                    enabledTools = nativeAgentTools,
                    includePatchTool = nativePatchTool,
                    forceChoice = nativePatchTool &&
                        (
                            requestMode == BrainRequestMode.CONNECTIVITY_TEST ||
                                (
                                    repairOrRecovery &&
                                        effectiveThinkingMode(profile, requestMode) ==
                                        ThinkingMode.DISABLED
                                    )
                            ),
                    skillCatalogGeneration = skillCatalogGeneration,
                )
            }
        } else {
            if (enabledTools.isNotEmpty()) {
                appendChatAgentTools(enabledTools, skillCatalogGeneration)
            }
            if (requestMode == BrainRequestMode.NORMAL) {
                profile.reasoningEffort.wireValue?.let {
                    append(",\"reasoning_effort\":")
                    jsonString(it)
                }
            }
        }
        if (request.resultMode == HarnessResultMode.EDITOR_PATCH && !nativePatchTool) {
            appendStructuredOutput(profile, responses = false)
        }
        append('}')
    }

    private fun StringBuilder.appendChatMessages(
        prompt: String,
        conversation: AgentConversationContext?,
    ) {
        append("[{\"role\":\"system\",\"content\":")
        jsonString(SenseSoul.text)
        append("},{\"role\":\"user\",\"content\":")
        jsonString(prompt)
        append('}')
        conversation?.exchanges.orEmpty().forEach { exchange ->
            append(",{\"role\":\"assistant\",\"content\":")
            jsonString(exchange.assistantContent)
            if (exchange.assistantReasoning.isNotEmpty()) {
                append(",\"reasoning_content\":")
                jsonString(exchange.assistantReasoning)
            }
            append(",\"tool_calls\":[{\"id\":")
            jsonString(exchange.toolCallId)
            append(",\"type\":\"function\",\"function\":{\"name\":")
            jsonString(exchange.toolName)
            append(",\"arguments\":")
            jsonString(exchange.toolArguments)
            append("}}]}")
            append(",{\"role\":\"tool\",\"tool_call_id\":")
            jsonString(exchange.toolCallId)
            append(",\"content\":")
            jsonString(exchange.toolResult)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendResponsesInput(
        prompt: String,
        conversation: AgentConversationContext?,
    ) {
        append("[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":")
        jsonString(prompt)
        append("}]}]")
        conversation?.exchanges.orEmpty().forEach { exchange ->
            // Stateless Responses replay. Function calls/results are immutable provider protocol
            // items, not natural-language prompt interpolation.
            insert(length - 1, buildString {
                exchange.responsesReasoningItems.forEach { itemDocument ->
                    val item = ProviderJson.parse(itemDocument) as? JsonValue.ObjectValue
                        ?: error("Responses reasoning replay item must be an object")
                    require(
                        item.members["type"] == JsonValue.StringValue("reasoning") &&
                            item.members["encrypted_content"] is JsonValue.StringValue,
                    ) { "Responses reasoning replay item is invalid" }
                    append(',')
                    append(ProviderJson.stringify(item))
                }
                append(",{\"type\":\"function_call\",\"call_id\":")
                jsonString(exchange.toolCallId)
                append(",\"name\":")
                jsonString(exchange.toolName)
                append(",\"arguments\":")
                jsonString(exchange.toolArguments)
                append("},{\"type\":\"function_call_output\",\"call_id\":")
                jsonString(exchange.toolCallId)
                append(",\"output\":")
                jsonString(exchange.toolResult)
                append('}')
            })
        }
    }

    private fun StringBuilder.appendDeepSeekThinking(
        profile: ProviderProfile,
        requestMode: BrainRequestMode,
    ) {
        val mode = effectiveThinkingMode(profile, requestMode)
        when (mode) {
            ThinkingMode.AUTO -> Unit
            ThinkingMode.DISABLED -> append(",\"thinking\":{\"type\":\"disabled\"}")
            ThinkingMode.ENABLED -> append(",\"thinking\":{\"type\":\"enabled\"}")
        }
        if (mode != ThinkingMode.DISABLED) {
            profile.reasoningEffort.wireValue
                ?.takeUnless { it == "none" }
                ?.let {
                    append(",\"reasoning_effort\":")
                    jsonString(it)
                }
        }
    }

    private fun StringBuilder.appendNativeAgentTools(
        request: HarnessRequestV1,
        includeProgressTool: Boolean,
        enabledTools: Set<AgentToolId>,
        includePatchTool: Boolean,
        forceChoice: Boolean,
        skillCatalogGeneration: Long?,
    ) {
        append(",\"tools\":[")
        var needsComma = false
        if (includeProgressTool) {
            append("{\"type\":\"function\",\"function\":{")
            property("name", NATIVE_PROGRESS_TOOL_NAME)
            append(',')
            property(
                "description",
                "Publish one concise progress update, then continue the task in the next Agent turn.",
            )
            append(",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,")
            append("\"required\":[\"message\"],\"properties\":{")
            append("\"message\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160}}}")
            append("}}")
            needsComma = true
        }
        AgentToolId.entries.filter { it in enabledTools }.forEach { tool ->
            if (needsComma) append(',')
            appendAgentToolDefinition(
                tool = tool,
                responses = false,
                skillCatalogGeneration = skillCatalogGeneration,
            )
            needsComma = true
        }
        if (includePatchTool) {
            if (needsComma) append(',')
            append("{\"type\":\"function\",\"function\":{")
            property("name", NATIVE_PATCH_TOOL_NAME)
            append(',')
            property(
                "description",
                "Submit the single terminal Sense editor patch and its safe one-line public summary.",
            )
            append(",\"parameters\":")
            append(nativePatchToolSchema(request))
            append("}}")
        }
        append(']')
        if (forceChoice) {
            append(",\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":")
            jsonString(NATIVE_PATCH_TOOL_NAME)
            append("}}")
        }
    }

    private fun StringBuilder.appendResponsesAgentTools(
        enabledTools: Set<AgentToolId>,
        skillCatalogGeneration: Long?,
    ) {
        append(",\"tools\":[")
        AgentToolId.entries.filter { it in enabledTools }.forEachIndexed { index, tool ->
            if (index > 0) append(',')
            appendAgentToolDefinition(
                tool = tool,
                responses = true,
                skillCatalogGeneration = skillCatalogGeneration,
            )
        }
        append(']')
    }

    private fun StringBuilder.appendChatAgentTools(
        enabledTools: Set<AgentToolId>,
        skillCatalogGeneration: Long?,
    ) {
        append(",\"tools\":[")
        AgentToolId.entries.filter { it in enabledTools }.forEachIndexed { index, tool ->
            if (index > 0) append(',')
            appendAgentToolDefinition(
                tool = tool,
                responses = false,
                skillCatalogGeneration = skillCatalogGeneration,
            )
        }
        append(']')
    }

    private fun StringBuilder.appendAgentToolDefinition(
        tool: AgentToolId,
        responses: Boolean,
        skillCatalogGeneration: Long?,
    ) {
        if (responses) {
            append("{\"type\":\"function\",")
        } else {
            append("{\"type\":\"function\",\"function\":{")
        }
        property("name", tool.wireValue)
        append(',')
        when (tool) {
            AgentToolId.WEB_SEARCH -> {
                property(
                    "description",
                    "Search the live public web when current or external information is needed.",
                )
                append(",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,")
                append("\"required\":[\"query\"],\"properties\":{")
                append("\"query\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},")
                append("\"max_results\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}}}")
            }
            AgentToolId.WEB_FETCH -> {
                property(
                    "description",
                    "Read bounded text from one exact public HTTPS page.",
                )
                append(",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,")
                append("\"required\":[\"url\"],\"properties\":{")
                append("\"url\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2048},")
                append("\"max_chars\":{\"type\":\"integer\",\"minimum\":256,\"maximum\":12000}}}")
            }
            AgentToolId.BROWSER_USE -> {
                property(
                    "description",
                    "Control the session browser. Navigate, inspect numbered interactive " +
                        "elements, click, type, go back/forward, or reload. Inspect with " +
                        "snapshot before using element refs.",
                )
                append(",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,")
                append("\"required\":[\"action\"],\"properties\":{")
                append("\"action\":{\"type\":\"string\",\"enum\":[")
                AgentBrowserAction.entries.forEachIndexed { index, action ->
                    if (index > 0) append(',')
                    jsonString(action.wireValue)
                }
                append("]},")
                append("\"url\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2048},")
                append("\"ref\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200},")
                append("\"text\":{\"type\":\"string\",\"maxLength\":4096},")
                append("\"submit\":{\"type\":\"boolean\"},")
                append("\"max_chars\":{\"type\":\"integer\",\"minimum\":512,")
                append("\"maximum\":12000}}}")
            }
            AgentToolId.TERMINAL_EXEC -> {
                property(
                    "description",
                    "Run one timeout-bounded Android shell command in the session workspace. " +
                        "Use cwd for a workspace-relative directory; stdout, stderr and exit " +
                        "status are returned.",
                )
                append(",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,")
                append("\"required\":[\"command\"],\"properties\":{")
                append("\"command\":{\"type\":\"string\",\"minLength\":1,")
                append("\"maxLength\":4096},")
                append("\"cwd\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},")
                append("\"timeout_ms\":{\"type\":\"integer\",\"minimum\":1000,")
                append("\"maximum\":60000}}}")
            }
            AgentToolId.CALCULATOR -> {
                property(
                    "description",
                    "Evaluate a bounded mathematical expression deterministically.",
                )
                append(",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,")
                append("\"required\":[\"expression\"],\"properties\":{")
                append("\"expression\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512}}}")
            }
            AgentToolId.MEMORY_SEARCH -> {
                property(
                    "description",
                    "Search retained user memory for relevant prior facts or events.",
                )
                append(",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,")
                append("\"required\":[\"query\"],\"properties\":{")
                append("\"query\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},")
                append("\"max_results\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}}}")
            }
            AgentToolId.SKILL_READ -> {
                property(
                    "description",
                    "Read one bounded page from the exact immutable revision of a Sense Skill.",
                )
                append(",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,")
                append("\"required\":[\"skill_id\",\"revision\"],\"properties\":{")
                append("\"skill_id\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64},")
                append("\"revision\":{\"type\":\"integer\",\"minimum\":1},")
                append("\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":65536},")
                append("\"max_chars\":{\"type\":\"integer\",\"minimum\":")
                append(AgentToolArguments.SkillRead.MIN_MAX_CHARS)
                append(",\"maximum\":")
                append(AgentToolArguments.SkillRead.MAX_MAX_CHARS)
                append("}}}")
            }
            AgentToolId.SKILL_MANAGE -> {
                requireNotNull(skillCatalogGeneration) {
                    "skill_manage cannot be exposed without a frozen catalog generation"
                }
                property(
                    "description",
                    "Create or revise a Sense Skill, or bind/unbind it to a keyboard direction. " +
                        "Every revision and catalog generation is retained. Echo the exact " +
                        "frozen catalog generation to prevent stale writes.",
                )
                append(",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,")
                append("\"required\":[\"operation\",\"expected_catalog_generation\"],")
                append("\"properties\":{")
                append("\"operation\":{\"type\":\"string\",\"enum\":[")
                append("\"create\",\"update\",\"bind\",\"unbind\",\"unbind_skill\"]},")
                append("\"expected_catalog_generation\":{\"type\":\"integer\",\"minimum\":1},")
                append("\"skill_id\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64},")
                append("\"name\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64},")
                append("\"description\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":240},")
                append("\"content\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":65536},")
                append("\"base_intent\":{\"type\":\"string\",\"enum\":[")
                append(
                    "\"smart_edit\",\"answer\",\"rewrite\",\"continue\",\"translate\",\"format\"]},",
                )
                append("\"key_code\":{\"type\":\"integer\",\"minimum\":-1024,")
                append("\"maximum\":1114111},")
                append("\"direction\":{\"type\":\"string\",\"enum\":[")
                append("\"up\",\"right\",\"down\",\"left\"]}}}")
            }
        }
        if (responses) {
            append('}')
        } else {
            append("}}")
        }
    }

    /**
     * Freezes every value known from the immutable request into the Provider-side schema.
     *
     * This is only a generation aid: the dependency-free local decoder and protocol validator
     * remain authoritative. Narrowing the schema avoids spending the one repair attempt on an ID,
     * target, intent, or length that Brain already knows cannot be accepted.
     */
    private fun nativePatchToolSchema(request: HarnessRequestV1): String {
        val snapshot = request.snapshot
        return buildString {
            append("{\"type\":\"object\",\"additionalProperties\":false,")
            append("\"required\":[\"description\",\"patch\"],\"properties\":{")
            append("\"description\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160,")
            append("\"description\":\"One short public single-line summary; ")
            append("never private reasoning.\"},")
            append("\"patch\":{\"type\":\"object\",\"additionalProperties\":false,")
            append("\"required\":[\"protocol\",\"request_id\",\"snapshot_id\",")
            append("\"base_sha256\",\"intent\",\"operation\"],\"properties\":{")
            append("\"protocol\":{\"type\":\"string\",\"enum\":[\"sense.editor.patch.v1\"]},")
            append("\"request_id\":{\"type\":\"string\",\"enum\":[")
            jsonString(request.requestId)
            append("]},\"snapshot_id\":{\"type\":\"string\",\"enum\":[")
            jsonString(snapshot.snapshotId)
            append("]},\"base_sha256\":{\"type\":\"string\",\"enum\":[")
            jsonString(snapshot.baseSha256)
            append("]},\"intent\":{\"type\":\"string\",\"enum\":[")
            jsonString(request.skill.wireValue)
            append(',')
            jsonString("no_change")
            append("]},\"operation\":")
            appendNativeOperationSchema(snapshot.target, request.maxOutputChars)
            append("}}}}")
        }
    }

    private fun StringBuilder.appendNativeOperationSchema(
        target: io.github.ethanbird.senseime.ai.protocol.PatchTarget?,
        maxOutputChars: Int,
    ) {
        if (target == null) {
            append(NO_CHANGE_OPERATION_SCHEMA)
            return
        }
        append("{\"anyOf\":[")
        append("{\"type\":\"object\",\"additionalProperties\":false,")
        append("\"required\":[\"type\",\"target\",\"text\",\"selection_after\"],")
        append("\"properties\":{\"type\":{\"type\":\"string\",\"enum\":[\"replace\"]},")
        append("\"target\":{\"type\":\"string\",\"enum\":[")
        jsonString(target.wireValue)
        append("]},\"text\":{\"type\":\"string\",\"maxLength\":")
        append(maxOutputChars)
        append("},\"selection_after\":{\"type\":\"string\",")
        append("\"enum\":[\"start\",\"end\",\"select_replacement\"]}}},")
        append(NO_CHANGE_OPERATION_SCHEMA)
        append("]}")
    }

    private fun StringBuilder.appendStructuredOutput(
        profile: ProviderProfile,
        responses: Boolean,
    ) {
        when (profile.structuredOutput) {
            StructuredOutputMode.JSON_SCHEMA -> if (responses) {
                append(",\"text\":{\"format\":{\"type\":\"json_schema\",")
                append("\"name\":\"sense_editor_patch\",\"strict\":true,\"schema\":")
                append(PATCH_JSON_SCHEMA)
                append("}}")
            } else {
                append(",\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{")
                append("\"name\":\"sense_editor_patch\",\"strict\":true,\"schema\":")
                append(PATCH_JSON_SCHEMA)
                append("}}")
            }
            StructuredOutputMode.JSON_OBJECT -> if (responses) {
                append(",\"text\":{\"format\":{\"type\":\"json_object\"}}")
            } else {
                append(",\"response_format\":{\"type\":\"json_object\"}")
            }
            StructuredOutputMode.PROMPT_ONLY -> Unit
        }
    }

    private fun buildHarnessInput(
        request: HarnessRequestV1,
        includeInlineContract: Boolean,
        nativeToolProtocol: Boolean,
        nativePatchTool: Boolean,
        enabledTools: Set<AgentToolId>,
        skillCatalog: List<AgentSkillSummary>,
        skillCatalogGeneration: Long?,
    ): String = buildString {
        appendSkillContract(request)
        appendSkillCatalog(
            catalog = skillCatalog,
            skillReadEnabled = AgentToolId.SKILL_READ in enabledTools,
            skillManageEnabled = AgentToolId.SKILL_MANAGE in enabledTools,
            generation = skillCatalogGeneration,
        )
        if (request.resultMode == HarnessResultMode.EDITOR_PATCH) {
            appendContextWindowContract(request)
        }
        if (includeInlineContract) {
            append('\n')
            appendInlinePatchContract(request)
        }
        append('\n')
        if (request.resultMode == HarnessResultMode.ASSISTANT_MESSAGE) {
            when {
                nativeToolProtocol && enabledTools.isNotEmpty() -> append(
                    "Use an exposed tool only when it improves the answer, continue from every " +
                        "tool result, then finish with one complete user-facing answer in ordinary " +
                        "assistant content. Do not emit an editor-patch JSON object. Snapshot JSON:\n",
                )
                nativeToolProtocol -> append(
                    "You may publish one concise progress update, then finish with one complete " +
                        "user-facing answer in ordinary assistant content. Do not emit an " +
                        "editor-patch JSON object. Snapshot JSON:\n",
                )
                enabledTools.isNotEmpty() -> append(
                    "Call at most one exposed tool when useful, continue from its result, then " +
                        "return one complete user-facing answer in ordinary assistant content. " +
                        "Snapshot JSON:\n",
                )
                else -> append(
                    "Return one complete, directly useful answer in ordinary assistant content. " +
                        "Snapshot JSON:\n",
                )
            }
        } else if (nativePatchTool) {
            if (enabledTools.isEmpty()) {
                append(
                    "Use exactly one tool call per turn. First call sense_report_progress with " +
                        "one useful public update; after its result, finish by calling " +
                        "sense_submit_patch exactly once. Snapshot JSON:\n",
                )
            } else {
                append(
                    "Use exactly one tool call per turn. Call enabled tools only when useful, " +
                        "continue from every tool result, and finish by calling " +
                        "sense_submit_patch exactly once. Snapshot JSON:\n",
                )
            }
        } else if (enabledTools.isNotEmpty()) {
            append(
                "Call at most one exposed tool when useful, then continue from its result. " +
                    "When no further tool is needed, return only one sense.editor.patch.v1 " +
                    "object. Snapshot JSON:\n",
            )
        } else {
            append("Return only one sense.editor.patch.v1 object. Snapshot JSON:\n")
        }
        appendSnapshot(request)
    }

    private fun buildRepairInput(
        request: HarnessRequestV1,
        repair: RepairContext,
        includeInlineContract: Boolean,
        nativePatchTool: Boolean,
    ): String = buildString {
        append("Your previous answer was rejected by the local protocol gate. ")
        append("This is the only repair attempt. ")
        if (request.resultMode == HarnessResultMode.ASSISTANT_MESSAGE) {
            append("Return one corrected, complete user-facing answer in ordinary content.\n")
            append("Validation errors: ")
        } else if (nativePatchTool) {
            append("Call sense_submit_patch exactly once with corrected arguments; ")
            append("do not answer in ordinary content.\nValidation errors: ")
        } else {
            append("Return only a corrected sense.editor.patch.v1 object; ")
            append("do not explain.\nValidation errors: ")
        }
        append(repair.validationSummary.take(2_048))
        append("\nRejected document:\n")
        append(repair.rejectedDocument.take(OpenAiResponseDecoder.MAX_RESPONSE_BYTES))
        append("\nTask contract: ")
        appendSkillContract(request)
        if (request.resultMode == HarnessResultMode.EDITOR_PATCH) {
            appendContextWindowContract(request)
        }
        if (includeInlineContract) {
            append('\n')
            appendInlinePatchContract(request)
        }
        append("\nImmutable snapshot JSON:\n")
        appendSnapshot(request)
    }

    /**
     * Replays a transport-interrupted request as one complete structured answer.
     *
     * Compatible streaming APIs have no portable resume cursor. Supplying the bounded partial
     * document and already-visible public prefix makes deterministic providers much more likely to
     * regenerate the same prefix, while Brain still validates the second complete document from
     * scratch before any editor mutation is authorized.
     */
    private fun buildRecoveryInput(
        request: HarnessRequestV1,
        recovery: StreamRecoveryContext,
        includeInlineContract: Boolean,
        nativePatchTool: Boolean,
    ): String = buildString {
        append("The previous provider stream was interrupted before terminal completion. ")
        append(
            if (request.resultMode == HarnessResultMode.ASSISTANT_MESSAGE) {
                "Regenerate the entire user-facing answer from the beginning; do not return only "
            } else {
                "Regenerate the entire structured answer from the beginning; do not return only "
            },
        )
        append("the missing suffix. Preserve the stable public prefix exactly when it remains ")
        append("correct. This is the single transport recovery attempt.\nInterruption: ")
        append(recovery.reason.take(MAX_RECOVERY_REASON_CHARS))
        if (recovery.stableDescription.isNotEmpty()) {
            append("\nStable public description prefix:\n")
            append(recovery.stableDescription.take(MAX_RECOVERY_PREFIX_CHARS))
        }
        if (recovery.stablePreview.isNotEmpty()) {
            append("\nStable replacement-text prefix:\n")
            append(recovery.stablePreview.take(MAX_RECOVERY_PREFIX_CHARS))
        }
        if (recovery.interruptedDocument.isNotEmpty()) {
            append(
                if (request.resultMode == HarnessResultMode.ASSISTANT_MESSAGE) {
                    "\nInterrupted answer (untrusted and incomplete):\n"
                } else {
                    "\nInterrupted structured document (untrusted and incomplete):\n"
                },
            )
            append(recovery.interruptedDocument.take(OpenAiResponseDecoder.MAX_RESPONSE_BYTES))
        }
        append("\nTask contract: ")
        appendSkillContract(request)
        if (request.resultMode == HarnessResultMode.EDITOR_PATCH) {
            appendContextWindowContract(request)
        }
        if (includeInlineContract) {
            append('\n')
            appendInlinePatchContract(request)
        }
        append('\n')
        if (request.resultMode == HarnessResultMode.ASSISTANT_MESSAGE) {
            append("Return one complete user-facing answer in ordinary assistant content.")
        } else if (nativePatchTool) {
            append("Call sense_submit_patch exactly once with the complete regenerated result.")
        } else {
            append("Return only one complete sense.editor.patch.v1 object.")
        }
        append("\nImmutable snapshot JSON:\n")
        appendSnapshot(request)
    }

    /**
     * JSON Object and prompt-only providers do not receive [PATCH_JSON_SCHEMA] out of band.
     * Keep a closed, concrete contract in the prompt so OpenAI-compatible providers such as
     * DeepSeek can produce a document accepted by the dependency-free local decoder.
     */
    private fun StringBuilder.appendInlinePatchContract(request: HarnessRequestV1) {
        val snapshot = request.snapshot
        val authorizedTarget = snapshot.target
        val exampleReplacement = "替换文本".take(request.maxOutputChars)
        append("Closed output JSON contract (no Markdown, comments, or extra keys). ")
        append("Root keys are exactly protocol, request_id, snapshot_id, base_sha256, intent, ")
        append("operation. Copy request_id, snapshot_id, and base_sha256 from the snapshot exactly. ")
        append("intent is one of smart_edit, answer, rewrite, continue, translate, format, ")
        append("no_change. ")
        if (authorizedTarget == null) {
            append("This snapshot authorizes no replacement, so return no_change. ")
        } else {
            append("For replace, operation keys are exactly type, target, text, selection_after; ")
            append("type=\"replace\"; target must be ")
            jsonString(authorizedTarget.wireValue)
            append("; text is a JSON string no longer than ")
            append(request.maxOutputChars)
            append("; selection_after is one of start, end, select_replacement. ")
            append("Valid replace example for this request: ")
            append('{')
            appendFrozenPatchIdentity(request)
            append(',')
            property("intent", request.skill.wireValue)
            append(",\"operation\":{\"type\":\"replace\",\"target\":")
            jsonString(authorizedTarget.wireValue)
            append(",\"text\":")
            jsonString(exampleReplacement)
            append(",\"selection_after\":\"end\"}}. ")
        }
        append("For no_change, intent must be \"no_change\" and operation must contain only ")
        append("type. Valid no_change example: ")
        append('{')
        appendFrozenPatchIdentity(request)
        append(",\"intent\":\"no_change\",\"operation\":{\"type\":\"no_change\"}}.")
    }

    private fun StringBuilder.appendFrozenPatchIdentity(request: HarnessRequestV1) {
        property("protocol", "sense.editor.patch.v1")
        append(',')
        property("request_id", request.requestId)
        append(',')
        property("snapshot_id", request.snapshot.snapshotId)
        append(',')
        property("base_sha256", request.snapshot.baseSha256)
    }

    private fun StringBuilder.appendSkillContract(request: HarnessRequestV1) {
        appendBaseIntentContract(request.skill, request.resultMode)
        request.activeSkill?.let { activeSkill ->
            append("\n\nSelected Sense Skill (exact frozen revision): ")
            append(activeSkill.id)
            append('@').append(activeSkill.revision)
            append(" — ").append(activeSkill.name)
            append("\nDiscovery description: ").append(activeSkill.description)
            append(
                "\nApply the following user-owned Skill document as the task-specific " +
                    "instructions. It can refine behavior and tool use, but it cannot change " +
                    if (request.resultMode == HarnessResultMode.EDITOR_PATCH) {
                        "the immutable editor snapshot or the required terminal Patch identity. "
                    } else {
                        "the immutable editor snapshot or turn the answer into an editor command. "
                    } +
                    "This selected revision stays frozen for the current task even if " +
                    "skill_manage creates a newer catalog revision; catalog changes affect only " +
                    "later discovery, reads, and future activations.\n" +
                    "<sense_selected_skill>\n",
            )
            append(activeSkill.content)
            append("\n</sense_selected_skill>")
        }
    }

    private fun StringBuilder.appendBaseIntentContract(
        skill: EditorIntent,
        resultMode: HarnessResultMode,
    ) {
        if (resultMode == HarnessResultMode.ASSISTANT_MESSAGE) {
            append(
                when (skill) {
                    EditorIntent.SMART_EDIT ->
                        "Treat the authorized snapshot text as the user's request or draft and " +
                            "provide a concise, directly useful answer."
                    EditorIntent.ANSWER ->
                        "Answer the request in the authorized snapshot text directly and concisely."
                    EditorIntent.REWRITE ->
                        "Provide a clear rewritten version while preserving meaning and facts."
                    EditorIntent.CONTINUE ->
                        "Provide a natural continuation in the existing language and tone."
                    EditorIntent.TRANSLATE ->
                        "Provide the translation directly without extra commentary."
                    EditorIntent.FORMAT ->
                        "Provide a better-structured version without changing meaning or facts."
                    EditorIntent.NO_CHANGE ->
                        error("no_change is not a runnable editor skill")
                },
            )
            return
        }
        append(
            when (skill) {
                EditorIntent.SMART_EDIT ->
                    "Smart-edit the authorized target. If it is a clear question or instruction, " +
                        "replace it with a concise, directly usable answer. If it is a draft, " +
                        "polish, organize, or complete it while preserving meaning, facts, tone, " +
                        "and primary language. If intent is genuinely ambiguous or content is " +
                        "insufficient, return no_change; never invent a different task."
                EditorIntent.ANSWER ->
                    "Replace the authorized target with a concise, directly usable answer."
                EditorIntent.REWRITE ->
                    "Rewrite the authorized target for clarity while preserving meaning and facts."
                EditorIntent.CONTINUE ->
                    "Continue the authorized target naturally in its existing language and tone."
                EditorIntent.TRANSLATE ->
                    "Translate the authorized target without adding commentary."
                EditorIntent.FORMAT ->
                    "Improve structure and formatting without changing meaning or facts."
                EditorIntent.NO_CHANGE ->
                    error("no_change is not a runnable editor skill")
            },
        )
    }

    private fun StringBuilder.appendSkillCatalog(
        catalog: List<AgentSkillSummary>,
        skillReadEnabled: Boolean,
        skillManageEnabled: Boolean,
        generation: Long?,
    ) {
        if (catalog.isEmpty() && !skillManageEnabled) return
        append("\n\nSense Skill catalog")
        generation?.let { append(" generation ").append(it) }
        append(" (short discovery records):")
        catalog.take(MAX_DISCOVERABLE_SKILLS).forEach { summary ->
            append("\n- ")
            append(summary.id)
            append('@').append(summary.revision)
            append(" | ")
            append(summary.name.replaceLineBreaks())
            append(" | ")
            append(summary.description.replaceLineBreaks())
        }
        append(
            if (skillReadEnabled) {
                "\nDescriptions are only for discovery. When an unselected Skill is useful, " +
                    "call skill_read with the exact advertised revision before applying its full " +
                    "instructions; follow next_offset until eof=true."
            } else {
                "\nDescriptions are only for discovery; do not claim to have read unexposed " +
                    "Skill contents."
            },
        )
        if (skillManageEnabled) {
            append(
                "\nThe first skill_manage call must use expected_catalog_generation=",
            )
            append(requireNotNull(generation))
            append(
                ". After each successful mutation, use the returned catalog_generation for the " +
                    "next mutation. A stale generation is a conflict, never a request to overwrite.",
            )
        }
    }

    private fun String.replaceLineBreaks(): String =
        replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    private fun StringBuilder.appendContextWindowContract(request: HarnessRequestV1) {
        if (request.snapshot.target != io.github.ethanbird.senseime.ai.protocol.PatchTarget.CONTEXT_WINDOW) {
            return
        }
        append(
            "\nThe context_window is one complete but limited editing unit, not the whole field. " +
                "Replace that entire unit with a self-contained result; if unseen text would be " +
                "needed to do so safely, return no_change.",
        )
    }

    private fun StringBuilder.appendSnapshot(request: HarnessRequestV1) {
        val snapshot = request.snapshot
        append('{')
        property("request_id", request.requestId)
        append(',')
        property("skill", request.skill.wireValue)
        append(',')
        property("result_mode", request.resultMode.wireValue)
        append(",\"max_output_chars\":").append(request.maxOutputChars)
        append(",\"snapshot\":")
        appendSnapshot(snapshot)
        append('}')
    }

    private fun StringBuilder.appendSnapshot(snapshot: EditorSnapshotV1) {
        append('{')
        property("protocol", snapshot.protocol)
        append(',')
        property("request_id", snapshot.requestId)
        append(',')
        property("snapshot_id", snapshot.snapshotId)
        append(',')
        property("capability", snapshot.capability.wireValue)
        append(',')
        property("text", snapshot.text)
        append(",\"text_start_offset\":").append(snapshot.textStartOffset)
        append(",\"selection\":")
        val selection = snapshot.selection
        if (selection == null) {
            append("null")
        } else {
            append("{\"start\":").append(selection.start)
            append(",\"end\":").append(selection.end).append('}')
        }
        append(",\"target\":")
        val target = snapshot.target
        if (target == null) append("null") else jsonString(target.wireValue)
        append(',')
        property("base_sha256", snapshot.baseSha256)
        append(",\"truncated\":").append(snapshot.truncated)
        append(",\"max_output_chars\":").append(snapshot.maxOutputChars)
        append('}')
    }

    private fun StringBuilder.property(name: String, value: String) {
        jsonString(name)
        append(':')
        jsonString(value)
    }

    private fun StringBuilder.jsonString(value: String) {
        append(JsonWriter().string(value).toString())
    }

    private fun Long.toSafeInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    internal fun exposedAgentTools(
        enabledTools: Set<AgentToolId>,
        requestMode: BrainRequestMode,
        secondAttempt: SecondAttemptContext?,
        conversation: AgentConversationContext?,
        skillCatalogGeneration: Long?,
        hasReadableSkills: Boolean,
    ): Set<AgentToolId> {
        if (
            requestMode != BrainRequestMode.NORMAL ||
            secondAttempt != null ||
            conversation?.forceTerminalTool == true
        ) {
            return emptySet()
        }
        return enabledTools.filterTo(linkedSetOf()) { tool ->
            when (tool) {
                AgentToolId.SKILL_READ -> hasReadableSkills
                AgentToolId.SKILL_MANAGE -> skillCatalogGeneration != null
                else -> true
            }
        }
    }

    private fun usesNativeToolProtocol(profile: ProviderProfile): Boolean =
        profile.apiStyle == ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS &&
            ProviderCompatibility.isOfficialDeepSeek(profile.baseUrl)

    private fun effectiveThinkingMode(
        profile: ProviderProfile,
        requestMode: BrainRequestMode,
    ): ThinkingMode = if (requestMode == BrainRequestMode.CONNECTIVITY_TEST) {
        ThinkingMode.DISABLED
    } else {
        profile.thinkingMode
    }

    private fun providerTokenBudget(requestMode: BrainRequestMode): Int = when (requestMode) {
        BrainRequestMode.NORMAL -> NORMAL_MAX_TOKENS
        BrainRequestMode.CONNECTIVITY_TEST -> CONNECTIVITY_TEST_MAX_TOKENS
    }

    internal const val NATIVE_PATCH_TOOL_NAME = "sense_submit_patch"
    internal const val NATIVE_PROGRESS_TOOL_NAME = "sense_report_progress"
    internal const val NORMAL_MAX_TOKENS = 8_192
    internal const val CONNECTIVITY_TEST_MAX_TOKENS = 512
    private const val MAX_RECOVERY_PREFIX_CHARS = 4_096
    private const val MAX_RECOVERY_REASON_CHARS = 256
    private const val MAX_DISCOVERABLE_SKILLS = 64
    private const val NO_CHANGE_OPERATION_SCHEMA =
        "{\"type\":\"object\",\"additionalProperties\":false," +
            "\"required\":[\"type\"],\"properties\":{" +
            "\"type\":{\"type\":\"string\",\"enum\":[\"no_change\"]}}}"

    /**
     * Closed schema. Cross-field rules and frozen-snapshot identity are checked locally afterward.
     */
    private val PATCH_JSON_SCHEMA = """
        {
          "type":"object",
          "additionalProperties":false,
          "required":["protocol","request_id","snapshot_id","base_sha256","intent","operation"],
          "properties":{
            "protocol":{"type":"string","enum":["sense.editor.patch.v1"]},
            "request_id":{"type":"string"},
            "snapshot_id":{"type":"string"},
            "base_sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"},
            "intent":{"type":"string","enum":["smart_edit","answer","rewrite","continue","translate","format","no_change"]},
            "operation":{
              "anyOf":[
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["type","target","text","selection_after"],
                  "properties":{
                    "type":{"type":"string","enum":["replace"]},
                    "target":{"type":"string","enum":["whole_field","selection","context_window"]},
                    "text":{"type":"string"},
                    "selection_after":{"type":"string","enum":["start","end","select_replacement"]}
                  }
                },
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["type"],
                  "properties":{"type":{"type":"string","enum":["no_change"]}}
                }
              ]
            }
          }
        }
    """.trimIndent().replace("\n", "").replace("  ", "")

}
