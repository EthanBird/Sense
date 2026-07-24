package io.github.ethanbird.senseime.speech

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class CloudSpeechResponseDecodingException(
    val failureKind: CloudSpeechFailureKind,
    message: String,
) : IllegalArgumentException(message)

object CloudSpeechResponseDecoder {
    const val MAX_RESPONSE_BYTES = 256 * 1_024
    const val MAX_TRANSCRIPT_CHARS = 16_384

    fun decode(
        protocol: SpeechProviderProtocol,
        responseBytes: ByteArray,
    ): Result<CloudSpeechTranscript> = runCatching {
        if (responseBytes.size > MAX_RESPONSE_BYTES) {
            throw CloudSpeechResponseDecodingException(
                CloudSpeechFailureKind.RESPONSE_TOO_LARGE,
                "speech response exceeded the size ceiling",
            )
        }
        val json = decodeUtf8(responseBytes)
        val root = SpeechJsonParser(json).parse() as? SpeechJsonValue.ObjectValue
            ?: protocolError("speech response root must be an object")
        when (protocol) {
            SpeechProviderProtocol.OPENAI_TRANSCRIPTIONS -> decodeOpenAi(root)
            SpeechProviderProtocol.DEEPGRAM_LISTEN -> decodeDeepgram(root)
            SpeechProviderProtocol.ANDROID_SYSTEM,
            SpeechProviderProtocol.DASHSCOPE_REALTIME,
            -> protocolError("speech response protocol is not supported by the HTTPS decoder")
        }
    }

    private fun decodeOpenAi(root: SpeechJsonValue.ObjectValue): CloudSpeechTranscript {
        val text = root.string("text")
            ?: protocolError("OpenAI-compatible response is missing text")
        return transcript(text)
    }

    private fun decodeDeepgram(root: SpeechJsonValue.ObjectValue): CloudSpeechTranscript {
        val channels = root.objectValue("results")
            ?.array("channels")
            ?: protocolError("Deepgram response is missing results.channels")
        val firstChannel = channels.values.firstOrNull() as? SpeechJsonValue.ObjectValue
            ?: protocolError("Deepgram response has no channel")
        val alternatives = firstChannel.array("alternatives")
            ?: protocolError("Deepgram response is missing alternatives")
        val firstAlternative = alternatives.values.firstOrNull()
            as? SpeechJsonValue.ObjectValue
            ?: protocolError("Deepgram response has no first alternative")
        val first = firstAlternative.string("transcript")
            ?.let(::boundedTranscript)
            ?: protocolError("Deepgram first alternative is missing transcript")
        if (first.isEmpty()) {
            throw CloudSpeechResponseDecodingException(
                CloudSpeechFailureKind.NO_AUDIO,
                "Deepgram did not return a transcript",
            )
        }
        val decodedAlternatives = alternatives.values
            .drop(1)
            .take(MAX_ALTERNATIVES - 1)
            .mapNotNull { alternative ->
                (alternative as? SpeechJsonValue.ObjectValue)?.string("transcript")
                    ?.let(::boundedTranscript)
                    ?.takeIf(String::isNotEmpty)
            }
        return CloudSpeechTranscript(
            text = first,
            alternatives = decodedAlternatives.filterNot { it == first }.distinct(),
        )
    }

    private fun transcript(value: String): CloudSpeechTranscript {
        val text = boundedTranscript(value)
        if (text.isEmpty()) {
            throw CloudSpeechResponseDecodingException(
                CloudSpeechFailureKind.NO_AUDIO,
                "speech provider returned an empty transcript",
            )
        }
        return CloudSpeechTranscript(text)
    }

    private fun boundedTranscript(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length <= MAX_TRANSCRIPT_CHARS) return trimmed
        var end = MAX_TRANSCRIPT_CHARS
        if (
            trimmed[end - 1].isHighSurrogate() &&
            trimmed.getOrNull(end)?.isLowSurrogate() == true
        ) {
            end -= 1
        }
        return trimmed.substring(0, end)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        protocolError("speech response is not valid UTF-8")
    }

    private fun protocolError(message: String): Nothing =
        throw CloudSpeechResponseDecodingException(
            CloudSpeechFailureKind.PROTOCOL,
            message,
        )

    private const val MAX_ALTERNATIVES = 5
}

private sealed interface SpeechJsonValue {
    data class ObjectValue(
        val fields: Map<String, SpeechJsonValue>,
    ) : SpeechJsonValue {
        fun string(name: String): String? =
            (fields[name] as? StringValue)?.value

        fun objectValue(name: String): ObjectValue? =
            fields[name] as? ObjectValue

        fun array(name: String): ArrayValue? =
            fields[name] as? ArrayValue
    }

    data class ArrayValue(
        val values: List<SpeechJsonValue>,
    ) : SpeechJsonValue

    data class StringValue(
        val value: String,
    ) : SpeechJsonValue

    data object ScalarValue : SpeechJsonValue
    data object NullValue : SpeechJsonValue
}

/**
 * Small strict JSON parser used to keep the speech runtime dependency-free.
 *
 * It deliberately rejects duplicate object keys, excessive nesting/tokens, malformed surrogate
 * pairs, and trailing content. Only string/object/array values are retained because transcription
 * adapters do not need numeric metadata.
 */
private class SpeechJsonParser(
    private val source: String,
) {
    private var cursor = 0
    private var tokenCount = 0

    fun parse(): SpeechJsonValue {
        require(source.length <= MAX_JSON_CHARS) { "speech JSON exceeds character ceiling" }
        skipWhitespace()
        val value = parseValue(depth = 0)
        skipWhitespace()
        if (cursor != source.length) fail("trailing JSON content")
        return value
    }

    private fun parseValue(depth: Int): SpeechJsonValue {
        if (depth > MAX_DEPTH) fail("JSON nesting is too deep")
        tokenCount += 1
        if (tokenCount > MAX_TOKENS) fail("JSON has too many values")
        return when (peek()) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> SpeechJsonValue.StringValue(parseString())
            't' -> parseLiteral("true", SpeechJsonValue.ScalarValue)
            'f' -> parseLiteral("false", SpeechJsonValue.ScalarValue)
            'n' -> parseLiteral("null", SpeechJsonValue.NullValue)
            '-', in '0'..'9' -> parseNumber()
            else -> fail("unexpected JSON token")
        }
    }

    private fun parseObject(depth: Int): SpeechJsonValue.ObjectValue {
        consume('{')
        skipWhitespace()
        if (tryConsume('}')) return SpeechJsonValue.ObjectValue(emptyMap())
        val fields = LinkedHashMap<String, SpeechJsonValue>()
        while (true) {
            if (peek() != '"') fail("object key must be a string")
            val key = parseString()
            if (fields.containsKey(key)) fail("duplicate object key")
            skipWhitespace()
            consume(':')
            skipWhitespace()
            fields[key] = parseValue(depth)
            skipWhitespace()
            if (tryConsume('}')) break
            consume(',')
            skipWhitespace()
        }
        return SpeechJsonValue.ObjectValue(fields)
    }

    private fun parseArray(depth: Int): SpeechJsonValue.ArrayValue {
        consume('[')
        skipWhitespace()
        if (tryConsume(']')) return SpeechJsonValue.ArrayValue(emptyList())
        val values = ArrayList<SpeechJsonValue>()
        while (true) {
            values += parseValue(depth)
            skipWhitespace()
            if (tryConsume(']')) break
            consume(',')
            skipWhitespace()
        }
        return SpeechJsonValue.ArrayValue(values)
    }

    private fun parseString(): String {
        consume('"')
        val output = StringBuilder()
        while (cursor < source.length) {
            val character = source[cursor++]
            when {
                character == '"' -> return output.toString()
                character == '\\' -> appendEscape(output)
                character.code < 0x20 -> fail("control character in JSON string")
                character.isHighSurrogate() -> {
                    if (cursor >= source.length || !source[cursor].isLowSurrogate()) {
                        fail("unpaired high surrogate")
                    }
                    output.append(character)
                    output.append(source[cursor++])
                }
                character.isLowSurrogate() -> fail("unpaired low surrogate")
                else -> output.append(character)
            }
            if (output.length > MAX_STRING_CHARS) fail("JSON string is too long")
        }
        fail("unterminated JSON string")
    }

    private fun appendEscape(output: StringBuilder) {
        if (cursor >= source.length) fail("unterminated JSON escape")
        when (val escape = source[cursor++]) {
            '"' -> output.append('"')
            '\\' -> output.append('\\')
            '/' -> output.append('/')
            'b' -> output.append('\b')
            'f' -> output.append('\u000c')
            'n' -> output.append('\n')
            'r' -> output.append('\r')
            't' -> output.append('\t')
            'u' -> {
                val first = parseHexCodeUnit()
                when {
                    first.isHighSurrogate() -> {
                        if (
                            cursor + 2 > source.length ||
                            source[cursor] != '\\' ||
                            source[cursor + 1] != 'u'
                        ) {
                            fail("high surrogate must be followed by a low surrogate")
                        }
                        cursor += 2
                        val second = parseHexCodeUnit()
                        if (!second.isLowSurrogate()) fail("invalid low surrogate")
                        output.append(first)
                        output.append(second)
                    }
                    first.isLowSurrogate() -> fail("unpaired low surrogate")
                    else -> output.append(first)
                }
            }
            else -> fail("invalid JSON escape: $escape")
        }
    }

    private fun parseHexCodeUnit(): Char {
        if (cursor + 4 > source.length) fail("short unicode escape")
        var value = 0
        repeat(4) {
            val digit = source[cursor++].digitToIntOrNull(16)
                ?: fail("invalid unicode escape")
            value = (value shl 4) or digit
        }
        return value.toChar()
    }

    private fun parseNumber(): SpeechJsonValue {
        if (tryConsume('-') && cursor >= source.length) fail("incomplete number")
        when (peek()) {
            '0' -> cursor += 1
            in '1'..'9' -> while (peekOrNull()?.isDigit() == true) cursor += 1
            else -> fail("invalid number")
        }
        if (tryConsume('.')) {
            if (peekOrNull()?.isDigit() != true) fail("invalid number fraction")
            while (peekOrNull()?.isDigit() == true) cursor += 1
        }
        if (peekOrNull() == 'e' || peekOrNull() == 'E') {
            cursor += 1
            if (peekOrNull() == '+' || peekOrNull() == '-') cursor += 1
            if (peekOrNull()?.isDigit() != true) fail("invalid number exponent")
            while (peekOrNull()?.isDigit() == true) cursor += 1
        }
        return SpeechJsonValue.ScalarValue
    }

    private fun parseLiteral(
        literal: String,
        value: SpeechJsonValue,
    ): SpeechJsonValue {
        if (!source.regionMatches(cursor, literal, 0, literal.length)) {
            fail("invalid JSON literal")
        }
        cursor += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (peekOrNull() == ' ' || peekOrNull() == '\n' ||
            peekOrNull() == '\r' || peekOrNull() == '\t'
        ) {
            cursor += 1
        }
    }

    private fun consume(expected: Char) {
        if (!tryConsume(expected)) fail("expected '$expected'")
    }

    private fun tryConsume(expected: Char): Boolean {
        if (peekOrNull() != expected) return false
        cursor += 1
        return true
    }

    private fun peek(): Char = peekOrNull() ?: fail("unexpected end of JSON")

    private fun peekOrNull(): Char? = source.getOrNull(cursor)

    private fun fail(message: String): Nothing =
        throw CloudSpeechResponseDecodingException(
            CloudSpeechFailureKind.PROTOCOL,
            message,
        )

    private companion object {
        const val MAX_JSON_CHARS = CloudSpeechResponseDecoder.MAX_RESPONSE_BYTES
        const val MAX_STRING_CHARS = 64 * 1_024
        const val MAX_DEPTH = 32
        const val MAX_TOKENS = 16 * 1_024
    }
}
