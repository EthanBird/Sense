package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.EditorPatchV1
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.brain.memory.AgentEventJournal
import io.github.ethanbird.senseime.brain.memory.AgentJournalKind
import io.github.ethanbird.senseime.brain.memory.AgentJournalRecord
import io.github.ethanbird.senseime.brain.memory.AgentJournalRun
import java.nio.charset.StandardCharsets

/**
 * Request-scoped adapter from the existing Agent protocol into [AgentEventJournal].
 *
 * It has no recording switch: construction durably writes the complete request and every method
 * writes its complete argument. High-frequency provider/public/preview frames defer only their
 * storage force; terminal and tool boundaries force all preceding frames in order.
 * `memory_search` enablement belongs only to the recall call site.
 */
class AgentRunRecorder private constructor(
    private val run: AgentJournalRun,
) {
    val requestId: String
        get() = run.requestId

    val runGeneration: Long
        get() = run.runGeneration

    fun record(event: AiEvent): AgentJournalRecord {
        require(event.requestId == requestId) { "event requestId does not match recorder" }
        require(event.runGeneration == runGeneration) {
            "event runGeneration does not match recorder"
        }
        val encoded = AgentRunJournalCodec.encodeEvent(event)
        val kind = event.journalKind()
        return run.appendText(
            kind = kind,
            text = encoded,
            contentType = JSON_CONTENT_TYPE,
            attributes = mapOf("event_type" to AgentRunJournalCodec.eventType(event)),
            durable = kind.isTerminal(),
        )
    }

    fun recordProviderInput(
        rawBytes: ByteArray,
        contentType: String = JSON_CONTENT_TYPE,
        lexicalText: String = rawBytes.toString(StandardCharsets.UTF_8),
        attributes: Map<String, String> = emptyMap(),
    ): AgentJournalRecord = run.append(
        kind = AgentJournalKind.PROVIDER_INPUT,
        payload = rawBytes,
        contentType = contentType,
        lexicalText = lexicalText,
        attributes = attributes,
    )

    fun recordProviderOutput(
        rawBytes: ByteArray,
        contentType: String,
        lexicalText: String = rawBytes.toString(StandardCharsets.UTF_8),
        attributes: Map<String, String> = emptyMap(),
        durable: Boolean = false,
    ): AgentJournalRecord = run.append(
        kind = AgentJournalKind.PROVIDER_OUTPUT,
        payload = rawBytes,
        contentType = contentType,
        lexicalText = lexicalText,
        attributes = attributes,
        durable = durable,
    )

    fun flush() = run.flush()

    fun recordPrivateEvent(
        eventType: String,
        rawBytes: ByteArray,
        contentType: String = "text/plain; charset=utf-8",
        lexicalText: String = rawBytes.toString(StandardCharsets.UTF_8),
        attributes: Map<String, String> = emptyMap(),
    ): AgentJournalRecord {
        require(eventType.isNotBlank()) { "private event type must be non-blank" }
        return run.append(
            kind = AgentJournalKind.PRIVATE_AGENT_EVENT,
            payload = rawBytes,
            contentType = contentType,
            lexicalText = lexicalText,
            attributes = attributes + ("event_type" to eventType),
        )
    }

    fun recordToolCall(
        toolCallId: String?,
        toolName: String?,
        arguments: String,
    ): AgentJournalRecord {
        val encoded = AgentRunJournalCodec.encodeToolCall(toolCallId, toolName, arguments)
        return run.appendText(
            kind = AgentJournalKind.TOOL_CALL,
            text = encoded,
            contentType = JSON_CONTENT_TYPE,
            attributes = mapOf(
                "tool_call_id" to toolCallId.orEmpty(),
                "tool_name" to toolName.orEmpty(),
            ),
        )
    }

    fun recordToolResult(
        toolCallId: String?,
        toolName: String?,
        result: String,
        isError: Boolean? = null,
    ): AgentJournalRecord {
        val encoded = AgentRunJournalCodec.encodeToolResult(
            toolCallId,
            toolName,
            result,
            isError,
        )
        return run.appendText(
            kind = AgentJournalKind.TOOL_RESULT,
            text = encoded,
            contentType = JSON_CONTENT_TYPE,
            attributes = buildMap {
                put("tool_call_id", toolCallId.orEmpty())
                put("tool_name", toolName.orEmpty())
                isError?.let { put("is_error", it.toString()) }
            },
        )
    }

    companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

        fun begin(
            journal: AgentEventJournal,
            request: HarnessRequestV1,
        ): AgentRunRecorder {
            val encoded = AgentRunJournalCodec.encodeRequest(request)
            val run = journal.beginRun(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                payload = encoded.toByteArray(StandardCharsets.UTF_8),
                contentType = JSON_CONTENT_TYPE,
                lexicalText = encoded,
                attributes = buildMap {
                    put("protocol", request.protocol)
                    put("skill", request.skill.wireValue)
                    put("result_mode", request.resultMode.wireValue)
                    put("snapshot_id", request.snapshot.snapshotId)
                    request.skillCatalogGeneration?.let { generation ->
                        put("skill_catalog_generation", generation.toString())
                    }
                    request.activeSkill?.let { activeSkill ->
                        put("active_skill_id", activeSkill.id)
                        put("active_skill_revision", activeSkill.revision.toString())
                        put(
                            "active_skill_catalog_generation",
                            activeSkill.catalogGeneration.toString(),
                        )
                    }
                },
            )
            return AgentRunRecorder(run)
        }
    }
}

/** Fixed-order, dependency-free JSON encoding for journal identity and replay. */
object AgentRunJournalCodec {
    fun encodeRequest(request: HarnessRequestV1): String = canonicalObject {
        string("protocol", request.protocol)
        string("request_id", request.requestId)
        long("run_generation", request.runGeneration)
        string("skill", request.skill.wireValue)
        string("result_mode", request.resultMode.wireValue)
        request.skillCatalogGeneration?.let {
            long("skill_catalog_generation", it)
        } ?: nullValue("skill_catalog_generation")
        val activeSkill = request.activeSkill
        if (activeSkill == null) {
            nullValue("active_skill")
        } else {
            objectValue("active_skill") {
                string("protocol", activeSkill.protocol)
                string("id", activeSkill.id)
                long("revision", activeSkill.revision)
                long("catalog_generation", activeSkill.catalogGeneration)
                string("name", activeSkill.name)
                string("description", activeSkill.description)
                string("content", activeSkill.content)
            }
        }
        objectValue("snapshot") { snapshot(request.snapshot) }
        int("max_output_chars", request.maxOutputChars)
    }

    fun encodeEvent(event: AiEvent): String = canonicalObject {
        string("type", eventType(event))
        string("request_id", event.requestId)
        long("run_generation", event.runGeneration)
        when (event) {
            is AiEvent.Started ->
                long("started_at_monotonic_ms", event.startedAtMonotonicMs)
            is AiEvent.Status -> {
                string("phase", event.phase.name)
                string("label", event.label)
            }
            is AiEvent.DescriptionDelta ->
                string("text", event.text)
            is AiEvent.PreviewReset ->
                int("attempt", event.attempt)
            is AiEvent.PreviewDelta ->
                string("text", event.text)
            is AiEvent.PreviewReplace -> {
                int("attempt", event.attempt)
                string("text", event.text)
                string("description", event.description)
            }
            is AiEvent.AgentProgress -> {
                long("revision", event.revision)
                string("step_id", event.stepId)
                string("kind", event.kind.name)
                string("state", event.state.name)
                string("title", event.title)
                string("detail", event.detail)
                nullableString("tool_call_id", event.toolCallId)
                nullableString("tool_name", event.toolName)
            }
            is AiEvent.Usage -> {
                long("input_tokens", event.inputTokens)
                long("output_tokens", event.outputTokens)
            }
            is AiEvent.FinalPatch ->
                objectValue("patch") { patch(event.patch) }
            is AiEvent.FinalAnswer ->
                string("text", event.text)
            is AiEvent.Cancelled ->
                string("reason", event.reason.name)
            is AiEvent.Failed -> {
                string("code", event.code.name)
                boolean("retryable", event.retryable)
            }
        }
    }

    fun encodeToolCall(
        toolCallId: String?,
        toolName: String?,
        arguments: String,
    ): String = canonicalObject {
        nullableString("tool_call_id", toolCallId)
        nullableString("tool_name", toolName)
        string("arguments", arguments)
    }

    fun encodeToolResult(
        toolCallId: String?,
        toolName: String?,
        result: String,
        isError: Boolean? = null,
    ): String = canonicalObject {
        nullableString("tool_call_id", toolCallId)
        nullableString("tool_name", toolName)
        string("result", result)
        isError?.let { boolean("is_error", it) }
    }

    fun eventType(event: AiEvent): String = when (event) {
        is AiEvent.Started -> "started"
        is AiEvent.Status -> "status"
        is AiEvent.DescriptionDelta -> "description_delta"
        is AiEvent.PreviewReset -> "preview_reset"
        is AiEvent.PreviewDelta -> "preview_delta"
        is AiEvent.PreviewReplace -> "preview_replace"
        is AiEvent.AgentProgress -> "agent_progress"
        is AiEvent.Usage -> "usage"
        is AiEvent.FinalPatch -> "final_patch"
        is AiEvent.FinalAnswer -> "final_answer"
        is AiEvent.Cancelled -> "cancelled"
        is AiEvent.Failed -> "failed"
    }

    private fun CanonicalJsonObject.snapshot(snapshot: EditorSnapshotV1) {
        string("protocol", snapshot.protocol)
        string("request_id", snapshot.requestId)
        string("snapshot_id", snapshot.snapshotId)
        long("editor_generation", snapshot.editorGeneration)
        string("field_identity", snapshot.fieldIdentity)
        string("capability", snapshot.capability.wireValue)
        string("text", snapshot.text)
        int("text_start_offset", snapshot.textStartOffset)
        val selection = snapshot.selection
        if (selection == null) {
            nullValue("selection")
        } else {
            objectValue("selection") {
                int("start", selection.start)
                int("end", selection.end)
            }
        }
        nullableString("target", snapshot.target?.wireValue)
        string("base_sha256", snapshot.baseSha256)
        long("captured_at_monotonic_ms", snapshot.capturedAtMonotonicMs)
        boolean("truncated", snapshot.truncated)
        int("max_output_chars", snapshot.maxOutputChars)
    }

    private fun CanonicalJsonObject.patch(patch: EditorPatchV1) {
        string("protocol", patch.protocol)
        string("request_id", patch.requestId)
        string("snapshot_id", patch.snapshotId)
        string("base_sha256", patch.baseSha256)
        string("intent", patch.intent.wireValue)
        objectValue("operation") {
            string("type", patch.operation.type.wireValue)
            nullableString("target", patch.operation.target?.wireValue)
            nullableString("text", patch.operation.text)
            nullableString("selection_after", patch.operation.selectionAfter?.wireValue)
        }
    }

    private fun canonicalObject(block: CanonicalJsonObject.() -> Unit): String =
        buildString {
            val writer = CanonicalJsonObject(this)
            writer.block()
            writer.finish()
        }

    private class CanonicalJsonObject(
        private val output: StringBuilder,
    ) {
        private var first = true

        init {
            output.append('{')
        }

        fun string(name: String, value: String) {
            property(name)
            output.appendJsonString(value)
        }

        fun nullableString(name: String, value: String?) {
            if (value == null) {
                nullValue(name)
            } else {
                string(name, value)
            }
        }

        fun int(name: String, value: Int) {
            property(name)
            output.append(value)
        }

        fun long(name: String, value: Long) {
            property(name)
            output.append(value)
        }

        fun boolean(name: String, value: Boolean) {
            property(name)
            output.append(value)
        }

        fun nullValue(name: String) {
            property(name)
            output.append("null")
        }

        fun objectValue(name: String, block: CanonicalJsonObject.() -> Unit) {
            property(name)
            val child = CanonicalJsonObject(output)
            child.block()
            child.finish()
        }

        fun finish() {
            output.append('}')
        }

        private fun property(name: String) {
            if (!first) output.append(',')
            first = false
            output.appendJsonString(name)
            output.append(':')
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> when {
                    character.code < 0x20 ||
                        Character.isLowSurrogate(character) ||
                        (
                            Character.isHighSurrogate(character) &&
                                (
                                    index + 1 >= value.length ||
                                        !Character.isLowSurrogate(value[index + 1])
                                )
                        ) -> {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    }
                    Character.isHighSurrogate(character) -> {
                        append(character)
                        index += 1
                        append(value[index])
                    }
                    else -> append(character)
                }
            }
            index += 1
        }
        append('"')
    }
}

private fun AiEvent.journalKind(): AgentJournalKind = when (this) {
    is AiEvent.PreviewReset,
    is AiEvent.PreviewDelta,
    is AiEvent.PreviewReplace,
    -> AgentJournalKind.PREVIEW
    is AiEvent.FinalPatch,
    is AiEvent.FinalAnswer,
    -> AgentJournalKind.FINAL
    is AiEvent.Failed -> AgentJournalKind.ERROR
    is AiEvent.Cancelled -> AgentJournalKind.CANCELLED
    else -> AgentJournalKind.PUBLIC_AGENT_EVENT
}

private fun AgentJournalKind.isTerminal(): Boolean = when (this) {
    AgentJournalKind.FINAL,
    AgentJournalKind.ERROR,
    AgentJournalKind.CANCELLED,
    -> true
    else -> false
}
