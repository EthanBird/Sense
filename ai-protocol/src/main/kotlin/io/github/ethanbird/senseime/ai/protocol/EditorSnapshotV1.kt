package io.github.ethanbird.senseime.ai.protocol

data class TextSelectionV1(
    val start: Int,
    val end: Int,
) {
    val isCollapsed: Boolean
        get() = start == end
}

/**
 * Immutable editor state captured before an AI run starts.
 *
 * [textStartOffset] and [selection] use absolute host-editor offsets in Android's UTF-16 indexing
 * convention. A model must never calculate or return offsets; [target] maps its symbolic patch
 * target back to this frozen snapshot locally.
 */
data class EditorSnapshotV1(
    val protocol: String = SenseAiProtocol.SNAPSHOT_V1,
    val requestId: String,
    val snapshotId: String,
    val editorGeneration: Long,
    val fieldIdentity: String,
    val capability: SnapshotCapability,
    val text: String,
    val textStartOffset: Int = 0,
    val selection: TextSelectionV1?,
    /**
     * Symbolic editor scope this snapshot authorizes, or `null` when it is preview-only.
     *
     * `SURROUNDING_WINDOW` and `UNAVAILABLE` snapshots must never authorize a patch target.
     */
    val target: PatchTarget?,
    val baseSha256: String,
    val capturedAtMonotonicMs: Long,
    val truncated: Boolean,
    val maxOutputChars: Int = SenseAiProtocol.DEFAULT_MAX_OUTPUT_CHARS,
)
