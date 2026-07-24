package io.github.ethanbird.senseime.ai.protocol

data class ProtocolError(
    val code: ProtocolErrorCode,
    val path: String,
    val message: String,
)

data class ValidationResult(
    val errors: List<ProtocolError>,
) {
    val isValid: Boolean
        get() = errors.isEmpty()

    fun requireValid() {
        if (!isValid) throw ProtocolValidationException(errors)
    }

    companion object {
        val VALID = ValidationResult(emptyList())
    }
}

class ProtocolValidationException(
    val errors: List<ProtocolError>,
) : IllegalArgumentException(
    errors.joinToString(prefix = "Invalid Sense AI protocol: ", separator = "; ") {
        "${it.path}: ${it.message}"
    },
)

/**
 * Strict semantic validator shared by the provider process and the IME commit gate.
 *
 * Parsing and validation are intentionally separate: a transport decoder must reject unknown or
 * duplicate JSON members before constructing these closed Kotlin models. This validator then
 * enforces all cross-field and snapshot-bound invariants.
 */
object ProtocolValidator {
    private val sha256Regex = Regex("^[0-9a-f]{${SenseAiProtocol.SHA256_HEX_CHARS}}$")
    private val safeIdRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")

    fun validate(snapshot: EditorSnapshotV1): ValidationResult = validation {
        exactProtocol(snapshot.protocol, SenseAiProtocol.SNAPSHOT_V1, "$.protocol")
        id(snapshot.requestId, "$.request_id")
        id(snapshot.snapshotId, "$.snapshot_id")
        positive(snapshot.editorGeneration, "$.editor_generation", ProtocolErrorCode.INVALID_GENERATION)
        id(snapshot.fieldIdentity, "$.field_identity")
        validUnicode(snapshot.text, "$.text")
        if (snapshot.textStartOffset < 0) {
            error(
                ProtocolErrorCode.INVALID_OFFSET,
                "$.text_start_offset",
                "must not be negative",
            )
        }
        if (
            snapshot.textStartOffset.toLong() + snapshot.text.length >
            Int.MAX_VALUE.toLong()
        ) {
            error(
                ProtocolErrorCode.INVALID_OFFSET,
                "$.text_start_offset",
                "absolute UTF-16 text window exceeds the host offset range",
            )
        }
        if (snapshot.text.length > SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS) {
            error(
                ProtocolErrorCode.TEXT_TOO_LONG,
                "$.text",
                "snapshot exceeds the absolute snapshot limit",
            )
        }
        sha256(snapshot.baseSha256, "$.base_sha256")
        if (
            sha256Regex.matches(snapshot.baseSha256) &&
            snapshot.baseSha256 != EditorTextDigest.sha256Utf8(snapshot.text)
        ) {
            error(
                ProtocolErrorCode.INVALID_HASH,
                "$.base_sha256",
                "must equal SHA-256(UTF-8(snapshot.text))",
            )
        }
        nonNegative(
            snapshot.capturedAtMonotonicMs,
            "$.captured_at_monotonic_ms",
            ProtocolErrorCode.INVALID_TIMESTAMP,
        )
        outputLimit(snapshot.maxOutputChars, "$.max_output_chars")

        val selection = snapshot.selection
        if (selection != null) {
            selection(
                selection,
                snapshot.textStartOffset,
                snapshot.text,
                "$.selection",
            )
        }

        when (snapshot.capability) {
            SnapshotCapability.FULL_DOCUMENT -> {
                if (snapshot.textStartOffset != 0) {
                    error(
                        ProtocolErrorCode.INVALID_CAPABILITY,
                        "$.text_start_offset",
                        "FULL_DOCUMENT must start at absolute offset zero",
                    )
                }
                if (snapshot.truncated) {
                    error(
                        ProtocolErrorCode.INVALID_CAPABILITY,
                        "$.truncated",
                        "FULL_DOCUMENT cannot be marked as truncated",
                    )
                }
                if (selection == null) {
                    error(
                        ProtocolErrorCode.REQUIRED_VALUE_MISSING,
                        "$.selection",
                        "FULL_DOCUMENT requires a frozen selection",
                    )
                }
            }

            SnapshotCapability.SELECTION_ONLY -> {
                if (selection == null || selection.isCollapsed) {
                    error(
                        ProtocolErrorCode.INVALID_SELECTION,
                        "$.selection",
                        "SELECTION_ONLY requires a non-empty frozen selection",
                    )
                }
            }

            SnapshotCapability.SURROUNDING_WINDOW -> {
                if (!snapshot.truncated) {
                    error(
                        ProtocolErrorCode.INVALID_CAPABILITY,
                        "$.truncated",
                        "SURROUNDING_WINDOW must be marked as truncated",
                    )
                }
            }

            SnapshotCapability.UNAVAILABLE -> {
                if (snapshot.textStartOffset != 0) {
                    error(
                        ProtocolErrorCode.INVALID_CAPABILITY,
                        "$.text_start_offset",
                        "UNAVAILABLE must start at absolute offset zero",
                    )
                }
                if (snapshot.text.isNotEmpty()) {
                    error(
                        ProtocolErrorCode.INVALID_CAPABILITY,
                        "$.text",
                        "UNAVAILABLE cannot carry editor text",
                    )
                }
                if (selection != null) {
                    error(
                        ProtocolErrorCode.INVALID_CAPABILITY,
                        "$.selection",
                        "UNAVAILABLE cannot carry a selection",
                    )
                }
            }
        }

        when (snapshot.target) {
            PatchTarget.WHOLE_FIELD -> if (snapshot.capability != SnapshotCapability.FULL_DOCUMENT) {
                error(
                    ProtocolErrorCode.TARGET_NOT_SUPPORTED,
                    "$.target",
                    "whole_field requires FULL_DOCUMENT",
                )
            }

            PatchTarget.SELECTION -> {
                if (
                    snapshot.capability != SnapshotCapability.FULL_DOCUMENT &&
                    snapshot.capability != SnapshotCapability.SELECTION_ONLY
                ) {
                    error(
                        ProtocolErrorCode.TARGET_NOT_SUPPORTED,
                        "$.target",
                        "selection requires FULL_DOCUMENT or SELECTION_ONLY",
                    )
                }
                if (selection == null || selection.isCollapsed) {
                    error(
                        ProtocolErrorCode.TARGET_NOT_SUPPORTED,
                        "$.target",
                        "selection requires a non-empty frozen selection",
                    )
                }
            }

            PatchTarget.CONTEXT_WINDOW -> if (
                snapshot.capability != SnapshotCapability.SURROUNDING_WINDOW
            ) {
                error(
                    ProtocolErrorCode.TARGET_NOT_SUPPORTED,
                    "$.target",
                    "context_window requires SURROUNDING_WINDOW",
                )
            }

            null -> if (
                snapshot.capability == SnapshotCapability.FULL_DOCUMENT ||
                snapshot.capability == SnapshotCapability.SELECTION_ONLY
            ) {
                error(
                    ProtocolErrorCode.REQUIRED_VALUE_MISSING,
                    "$.target",
                    "editable snapshots require an authorized target",
                )
            }
        }
    }

    fun validate(request: HarnessRequestV1): ValidationResult = validation {
        exactProtocol(request.protocol, SenseAiProtocol.HARNESS_REQUEST_V1, "$.protocol")
        id(request.requestId, "$.request_id")
        positive(request.runGeneration, "$.run_generation", ProtocolErrorCode.INVALID_GENERATION)
        outputLimit(request.maxOutputChars, "$.max_output_chars")
        include(validate(request.snapshot), "$.snapshot")

        if (request.skill == EditorIntent.NO_CHANGE) {
            error(
                ProtocolErrorCode.INVALID_INTENT,
                "$.skill",
                "no_change is a terminal result, not a runnable skill",
            )
        }
        if (request.requestId != request.snapshot.requestId) {
            error(
                ProtocolErrorCode.SNAPSHOT_MISMATCH,
                "$.snapshot.request_id",
                "snapshot request_id must match harness request_id",
            )
        }
        if (request.maxOutputChars != request.snapshot.maxOutputChars) {
            error(
                ProtocolErrorCode.INVALID_LIMIT,
                "$.max_output_chars",
                "harness and snapshot output limits must match",
            )
        }
    }

    fun validate(patch: EditorPatchV1): ValidationResult = validation {
        validatePatchShape(patch)
    }

    fun validate(patch: EditorPatchV1, snapshot: EditorSnapshotV1): ValidationResult = validation {
        include(validate(snapshot), "$.snapshot")
        validatePatchShape(patch)

        if (patch.requestId != snapshot.requestId) {
            error(
                ProtocolErrorCode.SNAPSHOT_MISMATCH,
                "$.request_id",
                "patch request_id does not match the snapshot",
            )
        }
        if (patch.snapshotId != snapshot.snapshotId) {
            error(
                ProtocolErrorCode.SNAPSHOT_MISMATCH,
                "$.snapshot_id",
                "patch snapshot_id does not match the snapshot",
            )
        }
        if (patch.baseSha256 != snapshot.baseSha256) {
            error(
                ProtocolErrorCode.SNAPSHOT_MISMATCH,
                "$.base_sha256",
                "patch base_sha256 does not match the snapshot",
            )
        }

        val operation = patch.operation
        if (operation.type == PatchOperationType.REPLACE) {
            when (operation.target) {
                PatchTarget.WHOLE_FIELD -> if (snapshot.capability != SnapshotCapability.FULL_DOCUMENT) {
                    error(
                        ProtocolErrorCode.TARGET_NOT_SUPPORTED,
                        "$.operation.target",
                        "whole_field requires FULL_DOCUMENT",
                    )
                }

                PatchTarget.SELECTION -> {
                    val selection = snapshot.selection
                    if (selection == null || selection.isCollapsed) {
                        error(
                            ProtocolErrorCode.TARGET_NOT_SUPPORTED,
                            "$.operation.target",
                            "selection requires a non-empty frozen selection",
                        )
                    }
                }

                PatchTarget.CONTEXT_WINDOW -> if (
                    snapshot.capability != SnapshotCapability.SURROUNDING_WINDOW
                ) {
                    error(
                        ProtocolErrorCode.TARGET_NOT_SUPPORTED,
                        "$.operation.target",
                        "context_window requires SURROUNDING_WINDOW",
                    )
                }

                null -> Unit
            }

            if (snapshot.target == null) {
                error(
                    ProtocolErrorCode.TARGET_NOT_SUPPORTED,
                    "$.operation.target",
                    "preview-only snapshots cannot authorize replacement",
                )
            } else if (operation.target != null && operation.target != snapshot.target) {
                error(
                    ProtocolErrorCode.SNAPSHOT_MISMATCH,
                    "$.operation.target",
                    "patch target differs from the target authorized by the snapshot",
                )
            }
            val text = operation.text
            if (text != null && text.length > snapshot.maxOutputChars) {
                error(
                    ProtocolErrorCode.TEXT_TOO_LONG,
                    "$.operation.text",
                    "replacement exceeds snapshot max_output_chars",
                )
            }
        }
    }

    private fun ValidationBuilder.validatePatchShape(patch: EditorPatchV1) {
        exactProtocol(patch.protocol, SenseAiProtocol.PATCH_V1, "$.protocol")
        id(patch.requestId, "$.request_id")
        id(patch.snapshotId, "$.snapshot_id")
        sha256(patch.baseSha256, "$.base_sha256")

        when (patch.operation.type) {
            PatchOperationType.REPLACE -> {
                if (patch.intent == EditorIntent.NO_CHANGE) {
                    error(
                        ProtocolErrorCode.OPERATION_CONFLICT,
                        "$.intent",
                        "replace operation cannot use no_change intent",
                    )
                }
                if (patch.operation.target == null) {
                    error(
                        ProtocolErrorCode.REQUIRED_VALUE_MISSING,
                        "$.operation.target",
                        "replace requires target",
                    )
                }
                val text = patch.operation.text
                if (text == null) {
                    error(
                        ProtocolErrorCode.REQUIRED_VALUE_MISSING,
                        "$.operation.text",
                        "replace requires text",
                    )
                } else {
                    validUnicode(text, "$.operation.text")
                    if (text.length > SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS) {
                        error(
                            ProtocolErrorCode.TEXT_TOO_LONG,
                            "$.operation.text",
                            "replacement exceeds the absolute protocol limit",
                        )
                    }
                }
                if (patch.operation.selectionAfter == null) {
                    error(
                        ProtocolErrorCode.REQUIRED_VALUE_MISSING,
                        "$.operation.selection_after",
                        "replace requires selection_after",
                    )
                }
            }

            PatchOperationType.NO_CHANGE -> {
                if (patch.operation.target != null) {
                    error(
                        ProtocolErrorCode.OPERATION_CONFLICT,
                        "$.operation.target",
                        "no_change cannot carry target",
                    )
                }
                if (patch.operation.text != null) {
                    error(
                        ProtocolErrorCode.OPERATION_CONFLICT,
                        "$.operation.text",
                        "no_change cannot carry text",
                    )
                }
                if (patch.operation.selectionAfter != null) {
                    error(
                        ProtocolErrorCode.OPERATION_CONFLICT,
                        "$.operation.selection_after",
                        "no_change cannot carry selection_after",
                    )
                }
                if (patch.intent != EditorIntent.NO_CHANGE) {
                    error(
                        ProtocolErrorCode.OPERATION_CONFLICT,
                        "$.intent",
                        "no_change operation requires no_change intent",
                    )
                }
            }
        }
    }

    private inline fun validation(block: ValidationBuilder.() -> Unit): ValidationResult {
        val builder = ValidationBuilder()
        builder.block()
        return if (builder.errors.isEmpty()) ValidationResult.VALID else ValidationResult(builder.errors)
    }

    private class ValidationBuilder {
        val errors = mutableListOf<ProtocolError>()

        fun error(code: ProtocolErrorCode, path: String, message: String) {
            errors += ProtocolError(code, path, message)
        }

        fun include(result: ValidationResult, prefix: String) {
            result.errors.forEach { child ->
                val childPath = child.path.removePrefix("$")
                error(child.code, "$prefix$childPath", child.message)
            }
        }

        fun exactProtocol(actual: String, expected: String, path: String) {
            if (actual != expected) {
                error(
                    ProtocolErrorCode.PROTOCOL_MISMATCH,
                    path,
                    "expected '$expected'",
                )
            }
        }

        fun id(value: String, path: String) {
            if (
                value.isBlank() ||
                value.length > SenseAiProtocol.MAX_ID_CHARS ||
                !safeIdRegex.matches(value)
            ) {
                error(
                    ProtocolErrorCode.INVALID_ID,
                    path,
                    "must be 1..${SenseAiProtocol.MAX_ID_CHARS} safe ASCII characters",
                )
            }
        }

        fun sha256(value: String, path: String) {
            if (!sha256Regex.matches(value)) {
                error(
                    ProtocolErrorCode.INVALID_HASH,
                    path,
                    "must be a lowercase 64-character SHA-256 hex digest",
                )
            }
        }

        fun positive(value: Long, path: String, code: ProtocolErrorCode) {
            if (value <= 0L) error(code, path, "must be greater than zero")
        }

        fun nonNegative(value: Long, path: String, code: ProtocolErrorCode) {
            if (value < 0L) error(code, path, "must not be negative")
        }

        fun outputLimit(value: Int, path: String) {
            if (value !in 1..SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS) {
                error(
                    ProtocolErrorCode.INVALID_LIMIT,
                    path,
                    "must be in 1..${SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS}",
                )
            }
        }

        fun selection(
            value: TextSelectionV1,
            textStartOffset: Int,
            text: String,
            path: String,
        ) {
            val windowStart = textStartOffset.toLong()
            val windowEnd = windowStart + text.length
            val isOutsideWindow =
                value.start.toLong() < windowStart ||
                value.end < value.start ||
                value.end.toLong() > windowEnd
            if (isOutsideWindow) {
                error(
                    ProtocolErrorCode.INVALID_SELECTION,
                    path,
                    "must fall within the absolute UTF-16 text window",
                )
                return
            }

            val localStart = value.start - textStartOffset
            val localEnd = value.end - textStartOffset
            if (!text.isUtf16Boundary(localStart) || !text.isUtf16Boundary(localEnd)) {
                error(
                    ProtocolErrorCode.INVALID_SELECTION,
                    path,
                    "must not split a Unicode surrogate pair",
                )
            }
        }

        private fun String.isUtf16Boundary(index: Int): Boolean =
            index <= 0 ||
                index >= length ||
                !(
                    Character.isHighSurrogate(this[index - 1]) &&
                        Character.isLowSurrogate(this[index])
                    )

        fun validUnicode(value: String, path: String) {
            if (value.indexOf('\u0000') >= 0) {
                error(ProtocolErrorCode.INVALID_TEXT, path, "must not contain NUL")
            }
            var index = 0
            while (index < value.length) {
                val current = value[index]
                when {
                    Character.isHighSurrogate(current) -> {
                        if (
                            index + 1 >= value.length ||
                            !Character.isLowSurrogate(value[index + 1])
                        ) {
                            error(
                                ProtocolErrorCode.INVALID_TEXT,
                                path,
                                "contains an unpaired high surrogate at UTF-16 index $index",
                            )
                            return
                        }
                        index += 2
                    }

                    Character.isLowSurrogate(current) -> {
                        error(
                            ProtocolErrorCode.INVALID_TEXT,
                            path,
                            "contains an unpaired low surrogate at UTF-16 index $index",
                        )
                        return
                    }

                    else -> index += 1
                }
            }
        }
    }
}
