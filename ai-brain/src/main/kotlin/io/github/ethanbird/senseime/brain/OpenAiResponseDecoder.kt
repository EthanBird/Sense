package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface ProviderContentEvent {
    data class TextDelta(val text: String) : ProviderContentEvent
    /** Provider-private reasoning occurred; its content is deliberately discarded. */
    data object ReasoningActivity : ProviderContentEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val arguments: String = "",
    ) : ProviderContentEvent
    data class Usage(val inputTokens: Long, val outputTokens: Long) : ProviderContentEvent
    data class Completed(val finalText: String? = null) : ProviderContentEvent
    data class Error(
        val message: String,
        val retryable: Boolean = false,
        val type: String? = null,
        val providerCode: String? = null,
        val statusCode: Int? = null,
    ) : ProviderContentEvent
}

/**
 * Normalizes OpenAI Responses and OpenAI-compatible Chat Completions into provider-neutral text.
 */
internal class OpenAiResponseDecoder(
    private val apiStyle: ProviderApiStyle,
    private val streaming: Boolean,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
) {
    private val sse = if (streaming) SseDecoder(maxEventBytes = maxResponseBytes) else null
    private val body = if (streaming) null else ByteArrayOutputStream()
    private var completed = false

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): List<ProviderContentEvent> {
        check(!completed) { "provider response decoder is complete" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        return if (streaming) {
            sse!!.feed(bytes, offset, length).flatMap(::normalizeSse)
        } else {
            if (body!!.size().toLong() + length > maxResponseBytes) {
                throw ProviderPayloadException("provider response exceeds $maxResponseBytes bytes")
            }
            body.write(bytes, offset, length)
            emptyList()
        }
    }

    fun finish(): List<ProviderContentEvent> {
        check(!completed) { "provider response decoder is complete" }
        val events = if (streaming) {
            sse!!.finish().flatMap(::normalizeSse).toMutableList()
        } else {
            normalizeDocument(decodeUtf8(body!!.toByteArray()), terminalDocument = true)
                .toMutableList()
        }
        if (events.none { it is ProviderContentEvent.Completed || it is ProviderContentEvent.Error }) {
            events += ProviderContentEvent.Completed()
        }
        completed = true
        return events
    }

    private fun normalizeSse(event: SseEvent): List<ProviderContentEvent> {
        if (event.data.trim() == "[DONE]") {
            return if (completed) emptyList() else {
                completed = true
                listOf(ProviderContentEvent.Completed())
            }
        }
        return normalizeDocument(event.data, event.event, terminalDocument = false)
    }

    private fun normalizeDocument(
        document: String,
        eventName: String? = null,
        terminalDocument: Boolean,
    ): List<ProviderContentEvent> {
        val root = ProviderJson.parse(document) as? JsonValue.ObjectValue
            ?: throw ProviderPayloadException("provider event root must be an object")
        val type = eventName ?: root.string("type")
        val error = root.objectValue("error")
        if (type == "error" || type == "response.failed" || error != null) {
            val errorRoot = error ?: root
            return listOf(
                ProviderContentEvent.Error(
                    message = errorRoot.string("message") ?: type ?: "provider error",
                    retryable = errorRoot.boolean("retryable") ?: false,
                    type = errorRoot.string("type"),
                    providerCode = errorRoot.scalarText("code"),
                    statusCode = (errorRoot.long("status") ?: root.long("status"))
                        ?.takeIf { it in 100..599 }
                        ?.toInt(),
                ),
            )
        }

        val result = mutableListOf<ProviderContentEvent>()
        when (apiStyle) {
            ProviderApiStyle.OPENAI_RESPONSES -> {
                when (type) {
                    "response.output_text.delta" -> {
                        root.string("delta")?.takeIf(String::isNotEmpty)?.let {
                            result += ProviderContentEvent.TextDelta(it)
                        }
                    }
                    "response.completed" -> {
                        usage(root.objectValue("response") ?: root)?.let(result::add)
                        if (!completed) {
                            completed = true
                            result += ProviderContentEvent.Completed(
                                extractResponsesText(root.objectValue("response") ?: root),
                            )
                        }
                    }
                    "response.incomplete" -> {
                        result += ProviderContentEvent.Error(
                            message = "provider response was incomplete",
                            retryable = true,
                        )
                    }
                    else -> if (terminalDocument) {
                        usage(root)?.let(result::add)
                        extractResponsesText(root)?.let { result += ProviderContentEvent.TextDelta(it) }
                    }
                }
            }

            ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS -> {
                val choices = root.arrayValue("choices")
                choices.orEmpty().forEach choiceLoop@ { choiceValue ->
                    val choice = choiceValue as? JsonValue.ObjectValue ?: return@choiceLoop
                    val delta = choice.objectValue("delta")
                    val message = choice.objectValue("message")
                    val reasoningContent =
                        delta?.string("reasoning_content") ?: message?.string("reasoning_content")
                    if (!reasoningContent.isNullOrEmpty()) {
                        // Never forward chain-of-thought bytes. The engine may turn this marker
                        // into a fixed, provider-neutral activity status.
                        result += ProviderContentEvent.ReasoningActivity
                    }
                    val content = delta?.string("content") ?: message?.string("content")
                    if (!content.isNullOrEmpty()) result += ProviderContentEvent.TextDelta(content)
                    val toolCalls =
                        delta?.arrayValue("tool_calls") ?: message?.arrayValue("tool_calls")
                    toolCalls.orEmpty().forEachIndexed toolLoop@ { toolPosition, toolValue ->
                        val tool = toolValue as? JsonValue.ObjectValue ?: return@toolLoop
                        val function = tool.objectValue("function")
                        val index = tool.long("index")?.takeIf { it in 0..Int.MAX_VALUE }
                            ?.toInt() ?: toolPosition
                        val id = tool.string("id")
                        val name = function?.string("name")
                        val arguments = function?.string("arguments").orEmpty()
                        if (id != null || name != null || arguments.isNotEmpty()) {
                            result += ProviderContentEvent.ToolCallDelta(
                                index = index,
                                id = id,
                                name = name,
                                arguments = arguments,
                            )
                        }
                    }
                    when (val finishReason = choice.string("finish_reason")) {
                        "length" -> result += ProviderContentEvent.Error(
                            message = "provider output reached its token limit",
                            providerCode = "finish_reason_length",
                        )
                        "insufficient_system_resource" -> result += ProviderContentEvent.Error(
                            message = "provider has insufficient system resources",
                            retryable = true,
                            providerCode = "insufficient_system_resource",
                        )
                        "content_filter" -> result += ProviderContentEvent.Error(
                            message = "provider content filter interrupted the response",
                            providerCode = "content_filter",
                        )
                        null, "stop", "tool_calls" -> Unit
                        else -> result += ProviderContentEvent.Error(
                            message = "unsupported provider finish reason",
                            providerCode = finishReason,
                        )
                    }
                }
                usage(root)?.let(result::add)
                if (terminalDocument && !completed) {
                    completed = true
                    result += ProviderContentEvent.Completed()
                }
            }
        }
        return result
    }

    private fun extractResponsesText(root: JsonValue.ObjectValue): String? {
        root.string("output_text")?.let { return it }
        val output = root.arrayValue("output") ?: return null
        val builder = StringBuilder()
        output.forEach { itemValue ->
            val item = itemValue as? JsonValue.ObjectValue ?: return@forEach
            item.arrayValue("content").orEmpty().forEach { contentValue ->
                val content = contentValue as? JsonValue.ObjectValue ?: return@forEach
                val type = content.string("type")
                if (type == null || type == "output_text" || type == "text") {
                    content.string("text")?.let(builder::append)
                }
            }
        }
        return builder.toString().takeIf(String::isNotEmpty)
    }

    private fun usage(root: JsonValue.ObjectValue): ProviderContentEvent.Usage? {
        val usage = root.objectValue("usage") ?: return null
        val input = usage.long("input_tokens") ?: usage.long("prompt_tokens") ?: 0L
        val output = usage.long("output_tokens") ?: usage.long("completion_tokens") ?: 0L
        return ProviderContentEvent.Usage(input.coerceAtLeast(0), output.coerceAtLeast(0))
    }

    private fun JsonValue.ObjectValue.string(name: String): String? =
        (members[name] as? JsonValue.StringValue)?.value

    private fun JsonValue.ObjectValue.long(name: String): Long? =
        (members[name] as? JsonValue.NumberValue)?.value?.toLongOrNull()

    private fun JsonValue.ObjectValue.scalarText(name: String): String? = when (
        val value = members[name]
    ) {
        is JsonValue.StringValue -> value.value
        is JsonValue.NumberValue -> value.value
        else -> null
    }

    private fun JsonValue.ObjectValue.boolean(name: String): Boolean? =
        (members[name] as? JsonValue.BooleanValue)?.value

    private fun JsonValue.ObjectValue.objectValue(name: String): JsonValue.ObjectValue? =
        members[name] as? JsonValue.ObjectValue

    private fun JsonValue.ObjectValue.arrayValue(name: String): List<JsonValue>? =
        (members[name] as? JsonValue.ArrayValue)?.values

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        throw ProviderPayloadException("provider response contains invalid UTF-8")
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}

/**
 * Best-effort, non-authoritative preview extractor for the patch operation's JSON `text` string.
 *
 * The strict complete-document decoder remains the only path to an editor mutation. This helper
 * only lets the keyboard display human text while the structured patch is still streaming.
 */
internal class StreamingPatchPreview {
    private val document = StringBuilder()
    private val visibleFields = IncrementalJsonStringFieldScanner(setOf(TEXT_FIELD))

    internal val scannedCharCount: Long
        get() = visibleFields.scannedCharCount

    fun append(fragment: String): String {
        if (document.length.toLong() + fragment.length > ProviderJson.MAX_DOCUMENT_CHARS) {
            throw ProviderPayloadException(
                "structured patch exceeds ${ProviderJson.MAX_DOCUMENT_CHARS} characters",
            )
        }
        document.append(fragment)
        return visibleFields.append(fragment)[TEXT_FIELD].orEmpty()
    }

    fun fullDocument(): String = document.toString()

    private companion object {
        const val TEXT_FIELD = "text"
    }
}
