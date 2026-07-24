package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface ProviderContentEvent {
    data class TextDelta(val text: String) : ProviderContentEvent
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
                choices.orEmpty().forEach { choiceValue ->
                    val choice = choiceValue as? JsonValue.ObjectValue ?: return@forEach
                    val delta = choice.objectValue("delta")
                    val message = choice.objectValue("message")
                    val content = delta?.string("content") ?: message?.string("content")
                    if (!content.isNullOrEmpty()) result += ProviderContentEvent.TextDelta(content)
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
    private var emittedChars = 0

    fun append(fragment: String): String {
        if (document.length.toLong() + fragment.length > ProviderJson.MAX_DOCUMENT_CHARS) {
            throw ProviderPayloadException(
                "structured patch exceeds ${ProviderJson.MAX_DOCUMENT_CHARS} characters",
            )
        }
        document.append(fragment)
        val preview = extractTextPrefix(document)
        if (preview.length <= emittedChars) return ""
        return preview.substring(emittedChars).also { emittedChars = preview.length }
    }

    fun fullDocument(): String = document.toString()

    private fun extractTextPrefix(source: CharSequence): String {
        var index = 0
        while (index < source.length) {
            if (source[index] != '"') {
                index += 1
                continue
            }
            val token = readCompleteString(source, index) ?: return ""
            index = token.next
            if (token.value != "text") continue
            while (index < source.length && source[index].isWhitespace()) index += 1
            if (index >= source.length || source[index] != ':') continue
            index += 1
            while (index < source.length && source[index].isWhitespace()) index += 1
            if (index >= source.length || source[index] != '"') continue
            return readStringPrefix(source, index + 1)
        }
        return ""
    }

    private data class Token(val value: String, val next: Int)

    private fun readCompleteString(source: CharSequence, quote: Int): Token? {
        val value = StringBuilder()
        var index = quote + 1
        while (index < source.length) {
            when (val char = source[index++]) {
                '"' -> return Token(value.toString(), index)
                '\\' -> {
                    if (index >= source.length) return null
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> value.append(escaped)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (index + 4 > source.length) return null
                            val code = source.subSequence(index, index + 4)
                                .toString()
                                .toIntOrNull(16) ?: return null
                            value.append(code.toChar())
                            index += 4
                        }
                    }
                }
                else -> value.append(char)
            }
        }
        return null
    }

    private fun readStringPrefix(source: CharSequence, start: Int): String {
        val value = StringBuilder()
        var index = start
        while (index < source.length) {
            when (val char = source[index++]) {
                '"' -> return value.toSafeUnicodePrefix()
                '\\' -> {
                    if (index >= source.length) return value.toSafeUnicodePrefix()
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> value.append(escaped)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (index + 4 > source.length) return value.toSafeUnicodePrefix()
                            val code = source.subSequence(index, index + 4)
                                .toString()
                                .toIntOrNull(16) ?: return value.toSafeUnicodePrefix()
                            value.append(code.toChar())
                            index += 4
                        }
                        else -> return value.toSafeUnicodePrefix()
                    }
                }
                else -> value.append(char)
            }
        }
        return value.toSafeUnicodePrefix()
    }

    /**
     * Never expose half a surrogate pair to AiEvent's strict Unicode gate. A pair split between
     * model deltas remains buffered until both escaped UTF-16 units have arrived.
     */
    private fun StringBuilder.toSafeUnicodePrefix(): String {
        var index = 0
        while (index < length) {
            val char = this[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) {
                        return substring(0, index)
                    }
                    index += 2
                }
                char.isLowSurrogate() -> return substring(0, index)
                else -> index += 1
            }
        }
        return toString()
    }
}
