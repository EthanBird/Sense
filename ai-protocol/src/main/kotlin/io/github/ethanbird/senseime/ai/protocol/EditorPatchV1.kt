package io.github.ethanbird.senseime.ai.protocol

data class PatchOperationV1(
    val type: PatchOperationType,
    val target: PatchTarget? = null,
    val text: String? = null,
    val selectionAfter: SelectionAfter? = null,
)

/**
 * The only terminal output an M8 harness may propose.
 *
 * This is a proposal, not permission to mutate an editor. The IME must validate it against the
 * original [EditorSnapshotV1] and re-read the editor immediately before applying it.
 */
data class EditorPatchV1(
    val protocol: String = SenseAiProtocol.PATCH_V1,
    val requestId: String,
    val snapshotId: String,
    val baseSha256: String,
    val intent: EditorIntent,
    val operation: PatchOperationV1,
)
