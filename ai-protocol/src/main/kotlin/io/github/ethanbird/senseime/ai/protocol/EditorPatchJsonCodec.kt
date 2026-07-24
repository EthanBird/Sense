package io.github.ethanbird.senseime.ai.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface PatchDecodeResult {
    data class Success(val patch: EditorPatchV1) : PatchDecodeResult

    data class Failure(val errors: List<ProtocolError>) : PatchDecodeResult

    fun getOrThrow(): EditorPatchV1 = when (this) {
        is Success -> patch
        is Failure -> throw ProtocolValidationException(errors)
    }
}

/**
 * Dependency-free strict codec for the only model-controlled terminal document in M8.
 *
 * It accepts exactly one complete JSON object. Duplicate properties, unknown properties, invalid
 * UTF-8, invalid Unicode, and trailing values are rejected before an [EditorPatchV1] is exposed.
 */
object EditorPatchJsonCodec {
    private val rootProperties = setOf(
        "protocol",
        "request_id",
        "snapshot_id",
        "base_sha256",
        "intent",
        "operation",
    )
    private val operationProperties = setOf(
        "type",
        "target",
        "text",
        "selection_after",
    )

    fun decode(document: String): PatchDecodeResult {
        if (document.length > MAX_DOCUMENT_CHARS) {
            return PatchDecodeResult.Failure(
                listOf(
                    ProtocolError(
                        ProtocolErrorCode.DOCUMENT_TOO_LARGE,
                        "$",
                        "JSON document exceeds $MAX_DOCUMENT_CHARS UTF-16 code units",
                    ),
                ),
            )
        }
        val root = try {
            StrictJsonParser(document).parseDocument()
        } catch (error: JsonParseException) {
            return PatchDecodeResult.Failure(
                listOf(ProtocolError(error.code, error.path, error.message ?: "invalid JSON")),
            )
        }

        val errors = mutableListOf<ProtocolError>()
        val rootObject = root.asObject("$", errors)
            ?: return PatchDecodeResult.Failure(errors)
        rootObject.rejectUnknown(rootProperties, "$", errors)

        val protocol = rootObject.requiredString("protocol", "$.protocol", errors)
        val requestId = rootObject.requiredString("request_id", "$.request_id", errors)
        val snapshotId = rootObject.requiredString("snapshot_id", "$.snapshot_id", errors)
        val baseSha256 = rootObject.requiredString("base_sha256", "$.base_sha256", errors)
        val intent = rootObject.requiredEnum<EditorIntent>("intent", "$.intent", errors)

        val operationObject = rootObject.requiredObject("operation", "$.operation", errors)
        operationObject?.rejectUnknown(operationProperties, "$.operation", errors)
        val operationType = operationObject?.requiredEnum<PatchOperationType>(
            "type",
            "$.operation.type",
            errors,
        )
        val target = operationObject?.optionalEnum<PatchTarget>(
            "target",
            "$.operation.target",
            errors,
        )
        val text = operationObject?.optionalString("text", "$.operation.text", errors)
        val selectionAfter = operationObject?.optionalEnum<SelectionAfter>(
            "selection_after",
            "$.operation.selection_after",
            errors,
        )

        if (
            protocol == null ||
            requestId == null ||
            snapshotId == null ||
            baseSha256 == null ||
            intent == null ||
            operationType == null ||
            errors.isNotEmpty()
        ) {
            return PatchDecodeResult.Failure(errors)
        }

        val patch = EditorPatchV1(
            protocol = protocol,
            requestId = requestId,
            snapshotId = snapshotId,
            baseSha256 = baseSha256,
            intent = intent,
            operation = PatchOperationV1(
                type = operationType,
                target = target,
                text = text,
                selectionAfter = selectionAfter,
            ),
        )
        val semantic = ProtocolValidator.validate(patch)
        return if (semantic.isValid) {
            PatchDecodeResult.Success(patch)
        } else {
            PatchDecodeResult.Failure(semantic.errors)
        }
    }

    fun encode(patch: EditorPatchV1): String {
        ProtocolValidator.validate(patch).requireValid()
        return buildString {
            append('{')
            property("protocol", patch.protocol)
            append(',')
            property("request_id", patch.requestId)
            append(',')
            property("snapshot_id", patch.snapshotId)
            append(',')
            property("base_sha256", patch.baseSha256)
            append(',')
            property("intent", patch.intent.wireValue)
            append(',')
            appendJsonString("operation")
            append(':')
            append('{')
            property("type", patch.operation.type.wireValue)
            if (patch.operation.target != null) {
                append(',')
                property("target", patch.operation.target.wireValue)
            }
            if (patch.operation.text != null) {
                append(',')
                property("text", patch.operation.text)
            }
            if (patch.operation.selectionAfter != null) {
                append(',')
                property("selection_after", patch.operation.selectionAfter.wireValue)
            }
            append('}')
            append('}')
        }
    }

    private fun StringBuilder.property(name: String, value: String) {
        appendJsonString(name)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    const val MAX_DOCUMENT_CHARS =
        SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS * 6 + 8_192
}

/**
 * Collects arbitrary UTF-8 transport fragments and decodes only after the terminal document ends.
 *
 * Buffering bytes (rather than decoding each fragment independently) correctly handles a UTF-8
 * code point split across provider/SSE chunks.
 */
class EditorPatchJsonAccumulator(
    private val maxDocumentBytes: Int = DEFAULT_MAX_DOCUMENT_BYTES,
) {
    private val buffer = ByteArrayOutputStream()
    private var finished = false

    init {
        require(maxDocumentBytes > 0) { "maxDocumentBytes must be positive" }
    }

    val size: Int
        get() = buffer.size()

    fun append(fragment: ByteArray): EditorPatchJsonAccumulator =
        append(fragment, offset = 0, length = fragment.size)

    fun append(
        fragment: ByteArray,
        offset: Int,
        length: Int,
    ): EditorPatchJsonAccumulator {
        check(!finished) { "accumulator is already finished" }
        require(offset >= 0 && length >= 0 && offset <= fragment.size - length) {
            "invalid fragment range"
        }
        if (buffer.size().toLong() + length > maxDocumentBytes) {
            throw DocumentTooLargeException(maxDocumentBytes)
        }
        buffer.write(fragment, offset, length)
        return this
    }

    fun appendUtf8(fragment: String): EditorPatchJsonAccumulator =
        append(fragment.toByteArray(StandardCharsets.UTF_8))

    fun finish(): PatchDecodeResult {
        check(!finished) { "accumulator is already finished" }
        finished = true
        val document = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(buffer.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            return PatchDecodeResult.Failure(
                listOf(
                    ProtocolError(
                        ProtocolErrorCode.INVALID_TEXT,
                        "$",
                        "document is not valid UTF-8",
                    ),
                ),
            )
        }
        return EditorPatchJsonCodec.decode(document)
    }

    companion object {
        const val DEFAULT_MAX_DOCUMENT_BYTES =
            SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS * 6 + 8_192
    }
}

class DocumentTooLargeException(
    maxDocumentBytes: Int,
) : IllegalArgumentException("editor patch JSON exceeds $maxDocumentBytes bytes")

private sealed interface JsonValue {
    data class ObjectValue(val members: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val elements: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

private class JsonParseException(
    val code: ProtocolErrorCode,
    val path: String,
    message: String,
) : IllegalArgumentException(message)

private class StrictJsonParser(
    private val source: String,
) {
    private var index = 0
    private var nodeCount = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        if (index == source.length) {
            fail(ProtocolErrorCode.MALFORMED_JSON, "$", "empty JSON document")
        }
        val value = parseValue(depth = 0, path = "$")
        skipWhitespace()
        if (index != source.length) {
            fail(
                ProtocolErrorCode.TRAILING_CONTENT,
                "$",
                "trailing content after the JSON document at UTF-16 index $index",
            )
        }
        return value
    }

    private fun parseValue(depth: Int, path: String): JsonValue {
        if (depth > MAX_DEPTH) {
            fail(ProtocolErrorCode.MALFORMED_JSON, path, "JSON nesting exceeds $MAX_DEPTH")
        }
        nodeCount += 1
        if (nodeCount > MAX_NODES) {
            fail(
                ProtocolErrorCode.DOCUMENT_TOO_LARGE,
                path,
                "JSON node count exceeds $MAX_NODES",
            )
        }
        if (index >= source.length) {
            fail(ProtocolErrorCode.MALFORMED_JSON, path, "unexpected end of JSON")
        }
        return when (source[index]) {
            '{' -> parseObject(depth, path)
            '[' -> parseArray(depth, path)
            '"' -> JsonValue.StringValue(parseString(path))
            't' -> {
                consumeLiteral("true", path)
                JsonValue.BooleanValue(true)
            }
            'f' -> {
                consumeLiteral("false", path)
                JsonValue.BooleanValue(false)
            }
            'n' -> {
                consumeLiteral("null", path)
                JsonValue.NullValue
            }
            '-', in '0'..'9' -> JsonValue.NumberValue(parseNumber(path))
            else -> fail(
                ProtocolErrorCode.MALFORMED_JSON,
                path,
                "unexpected character '${source[index]}' at UTF-16 index $index",
            )
        }
    }

    private fun parseObject(depth: Int, path: String): JsonValue.ObjectValue {
        index += 1
        skipWhitespace()
        val members = linkedMapOf<String, JsonValue>()
        if (consumeIf('}')) return JsonValue.ObjectValue(members)
        while (true) {
            if (index >= source.length || source[index] != '"') {
                fail(ProtocolErrorCode.MALFORMED_JSON, path, "object property must be a string")
            }
            val name = parseString(path)
            if (members.containsKey(name)) {
                fail(
                    ProtocolErrorCode.DUPLICATE_PROPERTY,
                    "$path.${jsonPathName(name)}",
                    "duplicate property '$name'",
                )
            }
            skipWhitespace()
            expect(':', path)
            skipWhitespace()
            val memberPath = "$path.${jsonPathName(name)}"
            members[name] = parseValue(depth + 1, memberPath)
            skipWhitespace()
            when {
                consumeIf('}') -> return JsonValue.ObjectValue(members)
                consumeIf(',') -> skipWhitespace()
                else -> fail(ProtocolErrorCode.MALFORMED_JSON, path, "expected ',' or '}'")
            }
        }
    }

    private fun parseArray(depth: Int, path: String): JsonValue.ArrayValue {
        index += 1
        skipWhitespace()
        val elements = mutableListOf<JsonValue>()
        if (consumeIf(']')) return JsonValue.ArrayValue(elements)
        while (true) {
            elements += parseValue(depth + 1, "$path[${elements.size}]")
            skipWhitespace()
            when {
                consumeIf(']') -> return JsonValue.ArrayValue(elements)
                consumeIf(',') -> skipWhitespace()
                else -> fail(ProtocolErrorCode.MALFORMED_JSON, path, "expected ',' or ']'")
            }
        }
    }

    private fun parseString(path: String): String {
        expect('"', path)
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> parseEscape(path, result)
                character.code < 0x20 -> fail(
                    ProtocolErrorCode.MALFORMED_JSON,
                    path,
                    "unescaped control character in string",
                )
                Character.isHighSurrogate(character) -> {
                    if (
                        index >= source.length ||
                        !Character.isLowSurrogate(source[index])
                    ) {
                        fail(
                            ProtocolErrorCode.INVALID_TEXT,
                            path,
                            "unpaired high surrogate in string",
                        )
                    }
                    result.append(character)
                    result.append(source[index++])
                    enforceStringLimit(path, result)
                }
                Character.isLowSurrogate(character) -> fail(
                    ProtocolErrorCode.INVALID_TEXT,
                    path,
                    "unpaired low surrogate in string",
                )
                else -> {
                    result.append(character)
                    enforceStringLimit(path, result)
                }
            }
        }
        fail(ProtocolErrorCode.MALFORMED_JSON, path, "unterminated string")
    }

    private fun parseEscape(path: String, result: StringBuilder) {
        if (index >= source.length) {
            fail(ProtocolErrorCode.MALFORMED_JSON, path, "unterminated escape sequence")
        }
        when (val escaped = source[index++]) {
            '"' -> result.append('"')
            '\\' -> result.append('\\')
            '/' -> result.append('/')
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> appendUnicodeEscape(path, result)
            else -> fail(
                ProtocolErrorCode.MALFORMED_JSON,
                path,
                "invalid escape sequence '\\$escaped'",
            )
        }
        enforceStringLimit(path, result)
    }

    private fun appendUnicodeEscape(path: String, result: StringBuilder) {
        val first = readHexCodeUnit(path)
        when {
            Character.isHighSurrogate(first) -> {
                if (
                    index + 2 > source.length ||
                    source[index] != '\\' ||
                    source[index + 1] != 'u'
                ) {
                    fail(
                        ProtocolErrorCode.INVALID_TEXT,
                        path,
                        "escaped high surrogate is not followed by an escaped low surrogate",
                    )
                }
                index += 2
                val second = readHexCodeUnit(path)
                if (!Character.isLowSurrogate(second)) {
                    fail(
                        ProtocolErrorCode.INVALID_TEXT,
                        path,
                        "escaped high surrogate is not followed by a low surrogate",
                    )
                }
                result.append(first)
                result.append(second)
            }
            Character.isLowSurrogate(first) -> fail(
                ProtocolErrorCode.INVALID_TEXT,
                path,
                "unpaired escaped low surrogate",
            )
            else -> result.append(first)
        }
    }

    private fun readHexCodeUnit(path: String): Char {
        if (index + 4 > source.length) {
            fail(ProtocolErrorCode.MALFORMED_JSON, path, "incomplete Unicode escape")
        }
        var value = 0
        repeat(4) {
            val digit = source[index++].digitToIntOrNull(16)
                ?: fail(ProtocolErrorCode.MALFORMED_JSON, path, "invalid Unicode escape")
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun parseNumber(path: String): String {
        val start = index
        consumeIf('-')
        if (index >= source.length) {
            fail(ProtocolErrorCode.MALFORMED_JSON, path, "incomplete number")
        }
        if (source[index] == '0') {
            index += 1
            if (index < source.length && source[index].isDigit()) {
                fail(ProtocolErrorCode.MALFORMED_JSON, path, "leading zero in number")
            }
        } else {
            if (source[index] !in '1'..'9') {
                fail(ProtocolErrorCode.MALFORMED_JSON, path, "invalid number")
            }
            while (index < source.length && source[index].isDigit()) index += 1
        }
        if (consumeIf('.')) {
            if (index >= source.length || !source[index].isDigit()) {
                fail(ProtocolErrorCode.MALFORMED_JSON, path, "missing fraction digits")
            }
            while (index < source.length && source[index].isDigit()) index += 1
        }
        if (index < source.length && source[index] in "eE") {
            index += 1
            if (index < source.length && source[index] in "+-") index += 1
            if (index >= source.length || !source[index].isDigit()) {
                fail(ProtocolErrorCode.MALFORMED_JSON, path, "missing exponent digits")
            }
            while (index < source.length && source[index].isDigit()) index += 1
        }
        return source.substring(start, index)
    }

    private fun consumeLiteral(literal: String, path: String) {
        if (!source.regionMatches(index, literal, 0, literal.length)) {
            fail(ProtocolErrorCode.MALFORMED_JSON, path, "invalid literal")
        }
        index += literal.length
    }

    private fun expect(expected: Char, path: String) {
        if (index >= source.length || source[index] != expected) {
            fail(ProtocolErrorCode.MALFORMED_JSON, path, "expected '$expected'")
        }
        index += 1
    }

    private fun consumeIf(expected: Char): Boolean {
        if (index < source.length && source[index] == expected) {
            index += 1
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in JSON_WHITESPACE) index += 1
    }

    private fun fail(code: ProtocolErrorCode, path: String, message: String): Nothing {
        throw JsonParseException(code, path, message)
    }

    private fun enforceStringLimit(path: String, value: StringBuilder) {
        if (value.length > MAX_STRING_CODE_UNITS) {
            fail(
                ProtocolErrorCode.TEXT_TOO_LONG,
                path,
                "JSON string exceeds $MAX_STRING_CODE_UNITS UTF-16 code units",
            )
        }
    }

    private fun jsonPathName(name: String): String =
        if (name.matches(SAFE_PATH_NAME)) name else "['${name.replace("'", "\\'")}']"

    companion object {
        const val MAX_DEPTH = 32
        const val MAX_NODES = 4_096
        const val MAX_STRING_CODE_UNITS = SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS
        val JSON_WHITESPACE = charArrayOf(' ', '\t', '\r', '\n')
        val SAFE_PATH_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

private fun JsonValue.asObject(
    path: String,
    errors: MutableList<ProtocolError>,
): Map<String, JsonValue>? {
    if (this is JsonValue.ObjectValue) return members
    errors += ProtocolError(ProtocolErrorCode.TYPE_MISMATCH, path, "must be a JSON object")
    return null
}

private fun Map<String, JsonValue>.rejectUnknown(
    allowed: Set<String>,
    path: String,
    errors: MutableList<ProtocolError>,
) {
    keys.filterNot(allowed::contains).forEach { name ->
        errors += ProtocolError(
            ProtocolErrorCode.UNKNOWN_PROPERTY,
            "$path.$name",
            "unknown property '$name'",
        )
    }
}

private fun Map<String, JsonValue>.requiredString(
    name: String,
    path: String,
    errors: MutableList<ProtocolError>,
): String? {
    val value = this[name]
    if (value == null) {
        errors += ProtocolError(
            ProtocolErrorCode.REQUIRED_VALUE_MISSING,
            path,
            "required property is missing",
        )
        return null
    }
    if (value is JsonValue.StringValue) return value.value
    errors += ProtocolError(ProtocolErrorCode.TYPE_MISMATCH, path, "must be a JSON string")
    return null
}

private fun Map<String, JsonValue>.optionalString(
    name: String,
    path: String,
    errors: MutableList<ProtocolError>,
): String? {
    val value = this[name] ?: return null
    if (value is JsonValue.StringValue) return value.value
    errors += ProtocolError(ProtocolErrorCode.TYPE_MISMATCH, path, "must be a JSON string")
    return null
}

private fun Map<String, JsonValue>.requiredObject(
    name: String,
    path: String,
    errors: MutableList<ProtocolError>,
): Map<String, JsonValue>? {
    val value = this[name]
    if (value == null) {
        errors += ProtocolError(
            ProtocolErrorCode.REQUIRED_VALUE_MISSING,
            path,
            "required property is missing",
        )
        return null
    }
    return value.asObject(path, errors)
}

private inline fun <reified T> Map<String, JsonValue>.requiredEnum(
    name: String,
    path: String,
    errors: MutableList<ProtocolError>,
): T? where T : Enum<T>, T : WireEnum {
    val raw = requiredString(name, path, errors) ?: return null
    val result = enumValues<T>().firstOrNull { it.wireValue == raw }
    if (result == null) {
        errors += ProtocolError(
            ProtocolErrorCode.UNKNOWN_ENUM,
            path,
            "unknown ${T::class.simpleName} value '$raw'",
        )
    }
    return result
}

private inline fun <reified T> Map<String, JsonValue>.optionalEnum(
    name: String,
    path: String,
    errors: MutableList<ProtocolError>,
): T? where T : Enum<T>, T : WireEnum {
    val value = this[name] ?: return null
    if (value !is JsonValue.StringValue) {
        errors += ProtocolError(ProtocolErrorCode.TYPE_MISMATCH, path, "must be a JSON string")
        return null
    }
    val result = enumValues<T>().firstOrNull { it.wireValue == value.value }
    if (result == null) {
        errors += ProtocolError(
            ProtocolErrorCode.UNKNOWN_ENUM,
            path,
            "unknown ${T::class.simpleName} value '${value.value}'",
        )
    }
    return result
}
