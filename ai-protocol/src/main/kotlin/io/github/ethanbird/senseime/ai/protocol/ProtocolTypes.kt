package io.github.ethanbird.senseime.ai.protocol

object SenseAiProtocol {
    const val SNAPSHOT_V1 = "sense.editor.snapshot.v1"
    const val PATCH_V1 = "sense.editor.patch.v1"
    const val HARNESS_REQUEST_V1 = "sense.harness.request.v1"

    const val DEFAULT_MAX_OUTPUT_CHARS = 4_096
    const val ABSOLUTE_MAX_OUTPUT_CHARS = 65_536
    const val ABSOLUTE_MAX_SNAPSHOT_CHARS = 65_536
    const val MAX_ID_CHARS = 128
    const val SHA256_HEX_CHARS = 64
}

interface WireEnum {
    val wireValue: String
}

enum class SnapshotCapability(override val wireValue: String) : WireEnum {
    FULL_DOCUMENT("FULL_DOCUMENT"),
    SELECTION_ONLY("SELECTION_ONLY"),
    SURROUNDING_WINDOW("SURROUNDING_WINDOW"),
    UNAVAILABLE("UNAVAILABLE"),
}

enum class PatchTarget(override val wireValue: String) : WireEnum {
    WHOLE_FIELD("whole_field"),
    SELECTION("selection"),
    CONTEXT_WINDOW("context_window"),
}

enum class PatchOperationType(override val wireValue: String) : WireEnum {
    REPLACE("replace"),
    NO_CHANGE("no_change"),
}

enum class EditorIntent(override val wireValue: String) : WireEnum {
    SMART_EDIT("smart_edit"),
    ANSWER("answer"),
    REWRITE("rewrite"),
    CONTINUE("continue"),
    TRANSLATE("translate"),
    FORMAT("format"),
    NO_CHANGE("no_change"),
}

enum class SelectionAfter(override val wireValue: String) : WireEnum {
    START("start"),
    END("end"),
    SELECT_REPLACEMENT("select_replacement"),
}

enum class ProtocolErrorCode {
    MALFORMED_JSON,
    DOCUMENT_TOO_LARGE,
    DUPLICATE_PROPERTY,
    UNKNOWN_PROPERTY,
    TYPE_MISMATCH,
    UNKNOWN_ENUM,
    TRAILING_CONTENT,
    PROTOCOL_MISMATCH,
    REQUIRED_VALUE_MISSING,
    INVALID_ID,
    INVALID_GENERATION,
    INVALID_INTENT,
    INVALID_CAPABILITY,
    INVALID_OFFSET,
    INVALID_SELECTION,
    INVALID_TARGET,
    INVALID_HASH,
    INVALID_TIMESTAMP,
    INVALID_LIMIT,
    INVALID_TEXT,
    TEXT_TOO_LONG,
    SNAPSHOT_MISMATCH,
    TARGET_NOT_SUPPORTED,
    OPERATION_CONFLICT,
}
