package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderWireRequest
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.api.ThinkingMode
import java.nio.charset.StandardCharsets

internal data class RepairContext(
    val rejectedDocument: String,
    val validationSummary: String,
)

internal object OpenAiRequestFactory {
    fun create(
        profile: ProviderProfile,
        request: HarnessRequestV1,
        credential: ProviderCredential,
        attempt: Int,
        repair: RepairContext? = null,
        requestMode: BrainRequestMode = BrainRequestMode.NORMAL,
    ): ProviderWireRequest {
        profile.requireValid()
        require(attempt in 0..1)
        require((attempt == 0) == (repair == null))

        val nativePatchTool = usesNativePatchTool(profile)
        val includeInlineContract =
            !nativePatchTool && profile.structuredOutput != StructuredOutputMode.JSON_SCHEMA
        val prompt = if (repair == null) {
            buildHarnessInput(request, includeInlineContract, nativePatchTool)
        } else {
            buildRepairInput(request, repair, includeInlineContract, nativePatchTool)
        }
        val body = when (profile.apiStyle) {
            ProviderApiStyle.OPENAI_RESPONSES ->
                responsesBody(profile, prompt, requestMode)
            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS ->
                chatCompletionsBody(
                    profile,
                    prompt,
                    request,
                    requestMode,
                    nativePatchTool,
                )
        }
        val headers = linkedMapOf(
            "Accept" to if (profile.streaming) "text/event-stream" else "application/json",
            "Content-Type" to "application/json; charset=utf-8",
            "User-Agent" to "Sense-IME/0.3 AI-Brain",
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
    ): String = buildString {
        append('{')
        property("model", profile.model)
        append(',')
        property("instructions", SenseSoul.text)
        append(",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":")
        jsonString(prompt)
        append("}]}]")
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
        appendStructuredOutput(profile, responses = true)
        append('}')
    }

    private fun chatCompletionsBody(
        profile: ProviderProfile,
        prompt: String,
        request: HarnessRequestV1,
        requestMode: BrainRequestMode,
        nativePatchTool: Boolean,
    ): String = buildString {
        append('{')
        property("model", profile.model)
        append(",\"messages\":[{\"role\":\"system\",\"content\":")
        jsonString(SenseSoul.text)
        append("},{\"role\":\"user\",\"content\":")
        jsonString(prompt)
        append("}]")
        append(",\"stream\":").append(profile.streaming)
        if (nativePatchTool && profile.streaming) {
            append(",\"stream_options\":{\"include_usage\":true}")
        }
        append(",\"max_tokens\":").append(providerTokenBudget(requestMode))
        if (nativePatchTool) {
            appendDeepSeekThinking(profile, requestMode)
            appendNativePatchTool(
                request = request,
                forceChoice = requestMode == BrainRequestMode.CONNECTIVITY_TEST ||
                    effectiveThinkingMode(profile, requestMode) == ThinkingMode.DISABLED,
            )
        } else if (requestMode == BrainRequestMode.NORMAL) {
            profile.reasoningEffort.wireValue?.let {
                append(",\"reasoning_effort\":")
                jsonString(it)
            }
        }
        if (!nativePatchTool) {
            appendStructuredOutput(profile, responses = false)
        }
        append('}')
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

    private fun StringBuilder.appendNativePatchTool(
        request: HarnessRequestV1,
        forceChoice: Boolean,
    ) {
        append(",\"tools\":[{\"type\":\"function\",\"function\":{")
        property("name", NATIVE_PATCH_TOOL_NAME)
        append(',')
        property(
            "description",
            "Submit the single terminal Sense editor patch and its safe one-line public summary.",
        )
        append(",\"parameters\":")
        append(nativePatchToolSchema(request))
        append("}}]")
        if (forceChoice) {
            append(",\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":")
            jsonString(NATIVE_PATCH_TOOL_NAME)
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
        nativePatchTool: Boolean,
    ): String = buildString {
        appendSkillContract(request.skill)
        appendContextWindowContract(request)
        if (includeInlineContract) {
            append('\n')
            appendInlinePatchContract(request)
        }
        append('\n')
        if (nativePatchTool) {
            append("Finish by calling sense_submit_patch exactly once. Snapshot JSON:\n")
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
        if (nativePatchTool) {
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
        appendSkillContract(request.skill)
        appendContextWindowContract(request)
        if (includeInlineContract) {
            append('\n')
            appendInlinePatchContract(request)
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

    private fun StringBuilder.appendSkillContract(skill: EditorIntent) {
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

    private fun usesNativePatchTool(profile: ProviderProfile): Boolean =
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
    internal const val NORMAL_MAX_TOKENS = 8_192
    internal const val CONNECTIVITY_TEST_MAX_TOKENS = 512
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
