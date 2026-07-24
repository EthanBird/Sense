package io.github.ethanbird.senseime.brain

internal data class NativeToolVisibleDelta(
    val description: String = "",
    val patchText: String = "",
)

internal data class NativePatchToolSubmission(
    val description: String,
    val patchDocument: String,
)

/**
 * Bounded accumulator for the JSON string carried by DeepSeek's streamed function arguments.
 *
 * Prefix extraction is display-only. [finish] reparses the complete document and enforces the
 * closed outer contract before the patch reaches the authoritative protocol codec.
 */
internal class NativePatchToolAccumulator {
    private val document = StringBuilder()
    private var emittedDescriptionChars = 0
    private var emittedPatchChars = 0

    fun append(fragment: String): NativeToolVisibleDelta {
        if (document.length.toLong() + fragment.length > ProviderJson.MAX_DOCUMENT_CHARS) {
            throw ProviderPayloadException(
                "native tool arguments exceed ${ProviderJson.MAX_DOCUMENT_CHARS} characters",
            )
        }
        document.append(fragment)
        val description = extractStringPrefix(document, "description")
        validateDescriptionPrefix(description)
        val patchText = extractStringPrefix(document, "text")
        val descriptionDelta = description.deltaAfter(emittedDescriptionChars)
        val patchDelta = patchText.deltaAfter(emittedPatchChars)
        emittedDescriptionChars = description.length
        emittedPatchChars = patchText.length
        return NativeToolVisibleDelta(descriptionDelta, patchDelta)
    }

    fun fullDocument(): String = document.toString()

    fun finish(): NativePatchToolSubmission {
        val root = ProviderJson.parse(document.toString()) as? JsonValue.ObjectValue
            ?: throw ProviderPayloadException("native tool arguments must be an object")
        if (root.members.keys != setOf("description", "patch")) {
            throw ProviderPayloadException(
                "native tool arguments must contain exactly description and patch",
            )
        }
        val description = (root.members["description"] as? JsonValue.StringValue)?.value
            ?: throw ProviderPayloadException("native tool description must be a string")
        validateDescription(description)
        val patch = root.members["patch"] as? JsonValue.ObjectValue
            ?: throw ProviderPayloadException("native tool patch must be an object")
        return NativePatchToolSubmission(
            description = description,
            patchDocument = ProviderJson.stringify(patch),
        )
    }

    private fun validateDescriptionPrefix(value: String) {
        if (value.length > MAX_DESCRIPTION_CHARS || value.any(::isUnsafeDescriptionCharacter)) {
            throw ProviderPayloadException("native tool description is not a safe single line")
        }
    }

    private fun validateDescription(value: String) {
        validateDescriptionPrefix(value)
        if (value.isBlank()) {
            throw ProviderPayloadException("native tool description must not be blank")
        }
    }

    private fun String.deltaAfter(emittedChars: Int): String =
        if (length <= emittedChars) "" else substring(emittedChars)

    private fun extractStringPrefix(source: CharSequence, fieldName: String): String {
        var index = 0
        while (index < source.length) {
            if (source[index] != '"') {
                index += 1
                continue
            }
            val token = readCompleteString(source, index) ?: return ""
            index = token.next
            if (token.value != fieldName) continue
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
                            val decoded = readUnicodeEscape(source, index) ?: return null
                            value.append(decoded.value)
                            index = decoded.next
                        }
                        else -> return null
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
                            val decoded = readUnicodeEscape(source, index)
                                ?: return value.toSafeUnicodePrefix()
                            value.append(decoded.value)
                            index = decoded.next
                        }
                        else -> return value.toSafeUnicodePrefix()
                    }
                }
                else -> value.append(char)
            }
        }
        return value.toSafeUnicodePrefix()
    }

    private data class UnicodeEscape(val value: String, val next: Int)

    private fun readUnicodeEscape(source: CharSequence, start: Int): UnicodeEscape? {
        if (start + 4 > source.length) return null
        val first = source.subSequence(start, start + 4).toString().toIntOrNull(16)?.toChar()
            ?: return null
        var next = start + 4
        if (first.isHighSurrogate()) {
            if (
                next + 6 > source.length ||
                source[next] != '\\' ||
                source[next + 1] != 'u'
            ) {
                return null
            }
            val second = source.subSequence(next + 2, next + 6)
                .toString()
                .toIntOrNull(16)
                ?.toChar()
                ?.takeIf(Char::isLowSurrogate)
                ?: return null
            next += 6
            return UnicodeEscape("$first$second", next)
        }
        if (first.isLowSurrogate()) return null
        return UnicodeEscape(first.toString(), next)
    }

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

    private fun isUnsafeDescriptionCharacter(value: Char): Boolean =
        Character.isISOControl(value) ||
            value == '\u2028' ||
            value == '\u2029' ||
            value == '\u061c' ||
            value == '\u200e' ||
            value == '\u200f' ||
            value in '\u202a'..'\u202e' ||
            value in '\u2066'..'\u2069'

    private companion object {
        const val MAX_DESCRIPTION_CHARS = 160
    }
}
