package io.github.ethanbird.senseime.brain

import java.lang.StringBuilder

internal sealed interface JsonValue {
    data class ObjectValue(val members: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal class ProviderPayloadException(message: String) : IllegalArgumentException(message)

internal object ProviderJson {
    const val MAX_DOCUMENT_CHARS = 1_048_576

    fun parse(document: String): JsonValue {
        if (document.length > MAX_DOCUMENT_CHARS) {
            throw ProviderPayloadException("provider JSON exceeds $MAX_DOCUMENT_CHARS characters")
        }
        return Parser(document).parseDocument()
    }

    fun JsonValue.member(name: String): JsonValue? =
        (this as? JsonValue.ObjectValue)?.members?.get(name)

    fun JsonValue.string(): String? = (this as? JsonValue.StringValue)?.value

    fun JsonValue.array(): List<JsonValue>? = (this as? JsonValue.ArrayValue)?.values

    fun JsonValue.objectMembers(): Map<String, JsonValue>? =
        (this as? JsonValue.ObjectValue)?.members

    fun JsonValue.long(): Long? = (this as? JsonValue.NumberValue)?.value?.toLongOrNull()

    /** Serializes an already parsed provider value without accepting raw JSON fragments. */
    fun stringify(value: JsonValue): String = buildString {
        appendJson(value)
    }

    private fun StringBuilder.appendJson(value: JsonValue) {
        when (value) {
            is JsonValue.ObjectValue -> {
                append('{')
                value.members.entries.forEachIndexed { index, (name, member) ->
                    if (index > 0) append(',')
                    append(JsonWriter().string(name))
                    append(':')
                    appendJson(member)
                }
                append('}')
            }
            is JsonValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, member ->
                    if (index > 0) append(',')
                    appendJson(member)
                }
                append(']')
            }
            is JsonValue.StringValue -> append(JsonWriter().string(value.value))
            is JsonValue.NumberValue -> append(value.value)
            is JsonValue.BooleanValue -> append(value.value)
            JsonValue.NullValue -> append("null")
        }
    }

    private class Parser(private val source: String) {
        private var index = 0
        private var depth = 0

        fun parseDocument(): JsonValue {
            whitespace()
            val value = value()
            whitespace()
            if (index != source.length) fail("trailing JSON content")
            return value
        }

        private fun value(): JsonValue {
            if (index >= source.length) fail("unexpected end of JSON")
            return when (source[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> JsonValue.StringValue(string())
                't' -> {
                    literal("true")
                    JsonValue.BooleanValue(true)
                }
                'f' -> {
                    literal("false")
                    JsonValue.BooleanValue(false)
                }
                'n' -> {
                    literal("null")
                    JsonValue.NullValue
                }
                '-', in '0'..'9' -> JsonValue.NumberValue(number())
                else -> fail("unexpected JSON token")
            }
        }

        private fun objectValue(): JsonValue.ObjectValue = nested {
            index += 1
            whitespace()
            val members = linkedMapOf<String, JsonValue>()
            if (consume('}')) return@nested JsonValue.ObjectValue(members)
            while (true) {
                if (index >= source.length || source[index] != '"') fail("expected object key")
                val key = string()
                if (members.containsKey(key)) fail("duplicate object key")
                whitespace()
                expect(':')
                whitespace()
                members[key] = value()
                whitespace()
                if (consume('}')) return@nested JsonValue.ObjectValue(members)
                expect(',')
                whitespace()
            }
            @Suppress("UNREACHABLE_CODE")
            JsonValue.ObjectValue(members)
        }

        private fun arrayValue(): JsonValue.ArrayValue = nested {
            index += 1
            whitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return@nested JsonValue.ArrayValue(values)
            while (true) {
                values += value()
                whitespace()
                if (consume(']')) return@nested JsonValue.ArrayValue(values)
                expect(',')
                whitespace()
            }
            @Suppress("UNREACHABLE_CODE")
            JsonValue.ArrayValue(values)
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> result.append(escape())
                    char.code < 0x20 -> fail("control character in JSON string")
                    char.isHighSurrogate() -> {
                        if (index >= source.length || !source[index].isLowSurrogate()) {
                            fail("unpaired high surrogate")
                        }
                        result.append(char)
                        result.append(source[index++])
                    }
                    char.isLowSurrogate() -> fail("unpaired low surrogate")
                    else -> result.append(char)
                }
            }
            fail("unterminated JSON string")
        }

        private fun escape(): String {
            if (index >= source.length) fail("unterminated JSON escape")
            return when (source[index++]) {
                '"' -> "\""
                '\\' -> "\\"
                '/' -> "/"
                'b' -> "\b"
                'f' -> "\u000c"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' -> {
                    val first = hexCodeUnit()
                    when {
                        first.isHighSurrogate() -> {
                            if (
                                index + 1 >= source.length ||
                                source[index] != '\\' ||
                                source[index + 1] != 'u'
                            ) {
                                fail("unpaired escaped high surrogate")
                            }
                            index += 2
                            val second = hexCodeUnit()
                            if (!second.isLowSurrogate()) fail("invalid escaped surrogate pair")
                            "$first$second"
                        }
                        first.isLowSurrogate() -> fail("unpaired escaped low surrogate")
                        else -> first.toString()
                    }
                }
                else -> fail("invalid JSON escape")
            }
        }

        private fun hexCodeUnit(): Char {
            if (index + 4 > source.length) fail("truncated unicode escape")
            var value = 0
            repeat(4) {
                val digit = source[index++].digitToIntOrNull(16)
                    ?: fail("invalid unicode escape")
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun number(): String {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < source.length && source[index].isDigit()) {
                    fail("leading zero in JSON number")
                }
            } else {
                digits(required = true)
            }
            if (consume('.')) digits(required = true)
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index += 1
                if (index < source.length && (source[index] == '+' || source[index] == '-')) {
                    index += 1
                }
                digits(required = true)
            }
            return source.substring(start, index)
        }

        private fun digits(required: Boolean) {
            val start = index
            while (index < source.length && source[index].isDigit()) index += 1
            if (required && start == index) fail("expected digit")
        }

        private fun literal(expected: String) {
            if (!source.startsWith(expected, index)) fail("invalid JSON literal")
            index += expected.length
        }

        private fun whitespace() {
            while (
                index < source.length &&
                (source[index] == ' ' || source[index] == '\t' ||
                    source[index] == '\r' || source[index] == '\n')
            ) {
                index += 1
            }
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) fail("expected '$expected'")
        }

        private fun consume(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index += 1
            return true
        }

        private inline fun <T> nested(block: () -> T): T {
            depth += 1
            if (depth > 48) fail("provider JSON nesting limit exceeded")
            return try {
                block()
            } finally {
                depth -= 1
            }
        }

        private fun fail(message: String): Nothing =
            throw ProviderPayloadException("$message at character $index")
    }
}

internal class JsonWriter {
    private val builder = StringBuilder()

    fun raw(value: String): JsonWriter = apply { builder.append(value) }

    fun string(value: String): JsonWriter = apply {
        builder.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000c' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (char.code < 0x20) {
                    builder.append("\\u")
                    builder.append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
        builder.append('"')
    }

    override fun toString(): String = builder.toString()
}
