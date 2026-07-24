package io.github.ethanbird.senseime.brain

/**
 * Single-pass, incremental extractor for selected JSON string properties.
 *
 * This scanner is deliberately not an authoritative JSON parser. It only exposes safe, decoded
 * prefixes for streaming UI while the existing strict complete-document parser remains the sole
 * terminal validation path. Every appended UTF-16 code unit is inspected exactly once, including
 * when a key, escape, or surrogate pair is split across arbitrary provider fragments.
 */
internal class IncrementalJsonStringFieldScanner(
    fieldNames: Set<String>,
) {
    private val fields = fieldNames
        .onEach { name ->
            require(name.isNotEmpty()) { "streamed JSON field names must not be empty" }
            require(name.none(Character::isISOControl)) {
                "streamed JSON field names must not contain controls"
            }
        }
        .associateWith { FieldState() }
    private val token = StringBuilder(fieldNames.maxOfOrNull(String::length) ?: 0)

    private var scanState = ScanState.SEEKING_TOKEN
    private var stringMode = StringMode.GENERIC_TOKEN
    private var stringState = StringState.NORMAL
    private var tokenCouldMatch = false
    private var pendingTokenName: String? = null
    private var pendingKeyName: String? = null
    private var activeFieldName: String? = null
    private var unicodeValue = 0
    private var unicodeDigits = 0
    private var pendingHighSurrogate: Char? = null

    internal var scannedCharCount: Long = 0L
        private set

    init {
        require(fields.isNotEmpty()) { "at least one streamed JSON field is required" }
    }

    /**
     * Returns only newly decoded text for fields advanced by [fragment].
     */
    fun append(fragment: String): Map<String, String> {
        if (fragment.isEmpty()) return emptyMap()
        val deltas = linkedMapOf<String, StringBuilder>()
        fragment.forEach { char ->
            scannedCharCount = Math.addExact(scannedCharCount, 1L)
            if (scanState == ScanState.IN_STRING) {
                consumeStringCharacter(char, deltas)
            } else {
                consumeStructuralCharacter(char)
            }
        }
        if (deltas.isEmpty()) return emptyMap()
        return deltas.mapValues { (_, value) -> value.toString() }
    }

    private fun consumeStructuralCharacter(char: Char) {
        var reprocess = true
        while (reprocess) {
            reprocess = false
            when (scanState) {
                ScanState.SEEKING_TOKEN -> {
                    if (char == '"') beginString(StringMode.GENERIC_TOKEN)
                }

                ScanState.AFTER_STRING_TOKEN -> when {
                    char.isWhitespace() -> Unit
                    char == ':' -> {
                        pendingKeyName = pendingTokenName
                        pendingTokenName = null
                        scanState = ScanState.AWAITING_PROPERTY_VALUE
                    }
                    else -> {
                        pendingTokenName = null
                        scanState = ScanState.SEEKING_TOKEN
                        reprocess = true
                    }
                }

                ScanState.AWAITING_PROPERTY_VALUE -> when {
                    char.isWhitespace() -> Unit
                    char == '"' -> {
                        val fieldName = pendingKeyName
                        pendingKeyName = null
                        if (
                            fieldName != null &&
                            fields.getValue(fieldName).completed.not()
                        ) {
                            activeFieldName = fieldName
                            beginString(StringMode.TRACKED_VALUE)
                        } else {
                            beginString(StringMode.SKIPPED_VALUE)
                        }
                    }
                    else -> {
                        pendingKeyName = null
                        scanState = ScanState.SEEKING_TOKEN
                        reprocess = true
                    }
                }

                ScanState.IN_STRING -> error("string characters are handled separately")
            }
        }
    }

    private fun beginString(mode: StringMode) {
        stringMode = mode
        stringState = StringState.NORMAL
        unicodeValue = 0
        unicodeDigits = 0
        pendingHighSurrogate = null
        if (mode == StringMode.GENERIC_TOKEN) {
            token.setLength(0)
            tokenCouldMatch = true
        }
        scanState = ScanState.IN_STRING
    }

    private fun consumeStringCharacter(
        char: Char,
        deltas: MutableMap<String, StringBuilder>,
    ) {
        when (stringState) {
            StringState.NORMAL -> when {
                char == '"' -> finishString()
                char == '\\' -> stringState = StringState.ESCAPED
                char.code < 0x20 -> malformed("unescaped control character in JSON string")
                char.isHighSurrogate() -> {
                    pendingHighSurrogate = char
                    stringState = StringState.EXPECT_RAW_LOW_SURROGATE
                }
                char.isLowSurrogate() -> malformed("unpaired low surrogate in JSON string")
                else -> emitDecoded(char, deltas)
            }

            StringState.ESCAPED -> when (char) {
                '"', '\\', '/' -> {
                    emitDecoded(char, deltas)
                    stringState = StringState.NORMAL
                }
                'b' -> {
                    emitDecoded('\b', deltas)
                    stringState = StringState.NORMAL
                }
                'f' -> {
                    emitDecoded('\u000c', deltas)
                    stringState = StringState.NORMAL
                }
                'n' -> {
                    emitDecoded('\n', deltas)
                    stringState = StringState.NORMAL
                }
                'r' -> {
                    emitDecoded('\r', deltas)
                    stringState = StringState.NORMAL
                }
                't' -> {
                    emitDecoded('\t', deltas)
                    stringState = StringState.NORMAL
                }
                'u' -> beginUnicode(StringState.UNICODE)
                else -> malformed("invalid JSON string escape")
            }

            StringState.UNICODE -> consumeUnicodeDigit(
                char = char,
                completedState = StringState.NORMAL,
                deltas = deltas,
                requireLowSurrogate = false,
            )

            StringState.EXPECT_ESCAPED_LOW_BACKSLASH -> {
                if (char != '\\') malformed("escaped high surrogate has no low surrogate")
                stringState = StringState.EXPECT_ESCAPED_LOW_U
            }

            StringState.EXPECT_ESCAPED_LOW_U -> {
                if (char != 'u') malformed("escaped high surrogate has no low surrogate")
                beginUnicode(StringState.LOW_SURROGATE_UNICODE)
            }

            StringState.LOW_SURROGATE_UNICODE -> consumeUnicodeDigit(
                char = char,
                completedState = StringState.NORMAL,
                deltas = deltas,
                requireLowSurrogate = true,
            )

            StringState.EXPECT_RAW_LOW_SURROGATE -> {
                if (!char.isLowSurrogate()) malformed("raw high surrogate has no low surrogate")
                val high = pendingHighSurrogate
                    ?: malformed("missing raw high surrogate")
                emitDecoded(high, deltas)
                emitDecoded(char, deltas)
                pendingHighSurrogate = null
                stringState = StringState.NORMAL
            }
        }
    }

    private fun beginUnicode(state: StringState) {
        unicodeValue = 0
        unicodeDigits = 0
        stringState = state
    }

    private fun consumeUnicodeDigit(
        char: Char,
        completedState: StringState,
        deltas: MutableMap<String, StringBuilder>,
        requireLowSurrogate: Boolean,
    ) {
        val digit = char.digitToIntOrNull(16)
            ?: malformed("invalid JSON unicode escape")
        unicodeValue = (unicodeValue shl 4) or digit
        unicodeDigits += 1
        if (unicodeDigits < 4) return

        val decoded = unicodeValue.toChar()
        unicodeValue = 0
        unicodeDigits = 0
        if (requireLowSurrogate) {
            if (!decoded.isLowSurrogate()) {
                malformed("escaped high surrogate has invalid low surrogate")
            }
            val high = pendingHighSurrogate
                ?: malformed("missing escaped high surrogate")
            emitDecoded(high, deltas)
            emitDecoded(decoded, deltas)
            pendingHighSurrogate = null
            stringState = completedState
            return
        }

        when {
            decoded.isHighSurrogate() -> {
                pendingHighSurrogate = decoded
                stringState = StringState.EXPECT_ESCAPED_LOW_BACKSLASH
            }
            decoded.isLowSurrogate() -> malformed("unpaired escaped low surrogate")
            else -> {
                emitDecoded(decoded, deltas)
                stringState = completedState
            }
        }
    }

    private fun emitDecoded(
        char: Char,
        deltas: MutableMap<String, StringBuilder>,
    ) {
        when (stringMode) {
            StringMode.GENERIC_TOKEN -> appendTokenCandidate(char)
            StringMode.TRACKED_VALUE -> {
                val fieldName = activeFieldName
                    ?: malformed("tracked JSON value has no field")
                deltas.getOrPut(fieldName, ::StringBuilder).append(char)
            }
            StringMode.SKIPPED_VALUE -> Unit
        }
    }

    private fun appendTokenCandidate(char: Char) {
        if (!tokenCouldMatch) return
        token.append(char)
        tokenCouldMatch = fields.keys.any { fieldName ->
            if (token.length > fieldName.length) {
                false
            } else {
                token.indices.all { index -> token[index] == fieldName[index] }
            }
        }
        if (!tokenCouldMatch) token.setLength(0)
    }

    private fun finishString() {
        when (stringMode) {
            StringMode.GENERIC_TOKEN -> {
                pendingTokenName = token
                    .takeIf { tokenCouldMatch && fields.containsKey(it.toString()) }
                    ?.toString()
                scanState = ScanState.AFTER_STRING_TOKEN
            }

            StringMode.TRACKED_VALUE -> {
                val fieldName = activeFieldName
                    ?: malformed("tracked JSON value has no field")
                fields.getValue(fieldName).completed = true
                activeFieldName = null
                scanState = ScanState.SEEKING_TOKEN
            }

            StringMode.SKIPPED_VALUE -> {
                scanState = ScanState.SEEKING_TOKEN
            }
        }
        stringState = StringState.NORMAL
    }

    private fun malformed(message: String): Nothing =
        throw ProviderPayloadException(message)

    private data class FieldState(
        var completed: Boolean = false,
    )

    private enum class ScanState {
        SEEKING_TOKEN,
        AFTER_STRING_TOKEN,
        AWAITING_PROPERTY_VALUE,
        IN_STRING,
    }

    private enum class StringMode {
        GENERIC_TOKEN,
        TRACKED_VALUE,
        SKIPPED_VALUE,
    }

    private enum class StringState {
        NORMAL,
        ESCAPED,
        UNICODE,
        EXPECT_ESCAPED_LOW_BACKSLASH,
        EXPECT_ESCAPED_LOW_U,
        LOW_SURROGATE_UNICODE,
        EXPECT_RAW_LOW_SURROGATE,
    }
}
