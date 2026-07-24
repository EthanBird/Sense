package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.EditorPatchV1
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.ProtocolError
import io.github.ethanbird.senseime.ai.protocol.ProtocolValidator
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1

/** Frozen local authority created when a hold-to-AI run starts. */
data class ActiveEditorPatchLease(
    val snapshot: EditorSnapshotV1,
    val runGeneration: Long,
    val pointerId: Int,
    /** Monotonic local token, not a MotionEvent timestamp supplied by another process. */
    val pointerOwnershipToken: Long,
)

/** Current pointer owner read synchronously on the IME thread. */
data class EditorPointerOwner(
    val pointerId: Int,
    val pointerOwnershipToken: Long,
    val isDown: Boolean,
)

/**
 * A fresh editor read taken immediately before apply.
 *
 * Its text/window semantics are identical to EditorSnapshotV1. The Android adapter must not reuse
 * the original CharSequence or an earlier ExtractedText instance.
 */
data class LiveEditorRead(
    val editorGeneration: Long,
    val fieldIdentity: String,
    val capability: SnapshotCapability,
    val text: String,
    val textStartOffset: Int,
    val selection: TextSelectionV1?,
)

data class EditorPatchGuardInput(
    val lease: ActiveEditorPatchLease,
    val eventRequestId: String,
    val eventRunGeneration: Long,
    val pointerOwner: EditorPointerOwner?,
    /** Null means InputConnection disappeared or the re-read failed. */
    val liveEditor: LiveEditorRead?,
    val patch: EditorPatchV1,
)

enum class EditorPatchRejectReason {
    INVALID_LEASE_SNAPSHOT,
    INVALID_PATCH_PROTOCOL,
    REQUEST_MISMATCH,
    RUN_GENERATION_MISMATCH,
    POINTER_NOT_DOWN,
    POINTER_OWNER_MISMATCH,
    INPUT_CONNECTION_UNAVAILABLE,
    EDITOR_GENERATION_CHANGED,
    FIELD_IDENTITY_CHANGED,
    CAPABILITY_CHANGED,
    CAPABILITY_NOT_EDITABLE,
    TEXT_WINDOW_CHANGED,
    BASE_TEXT_CHANGED,
    SELECTION_CHANGED,
    INVALID_TARGET_RANGE,
    EMPTY_REPLACEMENT_NOT_ALLOWED,
    RESULT_RANGE_OVERFLOW,
    RESULT_TEXT_LIMIT_EXCEEDED,
}

sealed interface EditorPatchGuardDecision {
    data class Accepted(
        val guardedPatch: GuardedEditorPatch,
    ) : EditorPatchGuardDecision

    data class Rejected(
        val reasons: List<EditorPatchRejectReason>,
        val protocolErrors: List<ProtocolError> = emptyList(),
    ) : EditorPatchGuardDecision
}

/**
 * A patch plus the exact target range proven against a fresh editor read.
 *
 * Only this type is accepted by [EditorPatchPlanner], preventing callers from bypassing the guard
 * with a raw provider proposal.
 */
class GuardedEditorPatch internal constructor(
    val patch: EditorPatchV1,
    val snapshot: EditorSnapshotV1,
    val liveEditor: LiveEditorRead,
    val targetRange: TextSelectionV1?,
    val originalTargetText: String?,
)

/**
 * Final compare-and-set gate between a validated provider proposal and InputConnection commands.
 */
object EditorPatchGuard {
    fun evaluate(input: EditorPatchGuardInput): EditorPatchGuardDecision {
        val reasons = linkedSetOf<EditorPatchRejectReason>()
        val snapshot = input.lease.snapshot
        val snapshotValidation = ProtocolValidator.validate(snapshot)
        if (!snapshotValidation.isValid) {
            reasons += EditorPatchRejectReason.INVALID_LEASE_SNAPSHOT
        }
        if (
            input.lease.runGeneration <= 0 ||
            input.lease.pointerId < 0 ||
            input.lease.pointerOwnershipToken <= 0
        ) {
            reasons += EditorPatchRejectReason.INVALID_LEASE_SNAPSHOT
        }
        val patchValidation = ProtocolValidator.validate(input.patch, snapshot)
        if (!patchValidation.isValid) {
            reasons += EditorPatchRejectReason.INVALID_PATCH_PROTOCOL
        }

        if (
            input.eventRequestId != snapshot.requestId ||
            input.patch.requestId != snapshot.requestId
        ) {
            reasons += EditorPatchRejectReason.REQUEST_MISMATCH
        }
        if (input.eventRunGeneration != input.lease.runGeneration) {
            reasons += EditorPatchRejectReason.RUN_GENERATION_MISMATCH
        }

        val owner = input.pointerOwner
        if (owner == null || !owner.isDown) {
            reasons += EditorPatchRejectReason.POINTER_NOT_DOWN
        }
        if (
            owner != null &&
            (
                owner.pointerId != input.lease.pointerId ||
                    owner.pointerOwnershipToken != input.lease.pointerOwnershipToken
                )
        ) {
            reasons += EditorPatchRejectReason.POINTER_OWNER_MISMATCH
        }

        val live = input.liveEditor
        if (live == null) {
            reasons += EditorPatchRejectReason.INPUT_CONNECTION_UNAVAILABLE
            return EditorPatchGuardDecision.Rejected(
                reasons = reasons.toList(),
                protocolErrors = snapshotValidation.errors + patchValidation.errors,
            )
        }

        if (live.editorGeneration != snapshot.editorGeneration) {
            reasons += EditorPatchRejectReason.EDITOR_GENERATION_CHANGED
        }
        if (live.fieldIdentity != snapshot.fieldIdentity) {
            reasons += EditorPatchRejectReason.FIELD_IDENTITY_CHANGED
        }
        if (live.capability != snapshot.capability) {
            reasons += EditorPatchRejectReason.CAPABILITY_CHANGED
        }
        if (live.textStartOffset != snapshot.textStartOffset) {
            reasons += EditorPatchRejectReason.TEXT_WINDOW_CHANGED
        }
        if (EditorTextDigest.sha256Utf8(live.text) != snapshot.baseSha256) {
            reasons += EditorPatchRejectReason.BASE_TEXT_CHANGED
        }
        if (live.selection != snapshot.selection) {
            reasons += EditorPatchRejectReason.SELECTION_CHANGED
        }

        val rangeAndText = resolveTarget(input.patch, snapshot, live, reasons)
        if (reasons.isNotEmpty()) {
            return EditorPatchGuardDecision.Rejected(
                reasons = reasons.toList(),
                protocolErrors = snapshotValidation.errors + patchValidation.errors,
            )
        }

        return EditorPatchGuardDecision.Accepted(
            GuardedEditorPatch(
                patch = input.patch,
                snapshot = snapshot,
                liveEditor = live,
                targetRange = rangeAndText?.first,
                originalTargetText = rangeAndText?.second,
            ),
        )
    }

    private fun resolveTarget(
        patch: EditorPatchV1,
        snapshot: EditorSnapshotV1,
        live: LiveEditorRead,
        reasons: MutableSet<EditorPatchRejectReason>,
    ): Pair<TextSelectionV1, String>? {
        if (patch.operation.type == PatchOperationType.NO_CHANGE) return null

        val target = patch.operation.target
        val range = when (target) {
            PatchTarget.WHOLE_FIELD -> {
                if (live.capability != SnapshotCapability.FULL_DOCUMENT) {
                    reasons += EditorPatchRejectReason.CAPABILITY_NOT_EDITABLE
                }
                TextSelectionV1(0, live.text.length)
            }

            PatchTarget.SELECTION -> {
                if (
                    live.capability != SnapshotCapability.FULL_DOCUMENT &&
                    live.capability != SnapshotCapability.SELECTION_ONLY
                ) {
                    reasons += EditorPatchRejectReason.CAPABILITY_NOT_EDITABLE
                }
                live.selection ?: run {
                    reasons += EditorPatchRejectReason.INVALID_TARGET_RANGE
                    return null
                }
            }

            null -> {
                reasons += EditorPatchRejectReason.CAPABILITY_NOT_EDITABLE
                return null
            }
        }

        if (target != snapshot.target) {
            reasons += EditorPatchRejectReason.CAPABILITY_NOT_EDITABLE
        }
        val replacementLength = patch.operation.text?.length ?: 0
        if (range.start.toLong() + replacementLength > Int.MAX_VALUE.toLong()) {
            reasons += EditorPatchRejectReason.RESULT_RANGE_OVERFLOW
        }
        val localStart = range.start - live.textStartOffset
        val localEnd = range.end - live.textStartOffset
        if (
            localStart < 0 ||
            localEnd < localStart ||
            localEnd > live.text.length ||
            !live.text.isUtf16Boundary(localStart) ||
            !live.text.isUtf16Boundary(localEnd)
        ) {
            reasons += EditorPatchRejectReason.INVALID_TARGET_RANGE
            return null
        }
        val originalTargetText = live.text.substring(localStart, localEnd)
        if (originalTargetText.isNotEmpty() && patch.operation.text.isNullOrEmpty()) {
            reasons += EditorPatchRejectReason.EMPTY_REPLACEMENT_NOT_ALLOWED
        }
        val resultingWindowLength =
            live.text.length.toLong() - (localEnd - localStart) + replacementLength
        if (resultingWindowLength > SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS) {
            reasons += EditorPatchRejectReason.RESULT_TEXT_LIMIT_EXCEEDED
        }
        return range to originalTargetText
    }

    private fun String.isUtf16Boundary(index: Int): Boolean =
        index <= 0 ||
            index >= length ||
            !(
                Character.isHighSurrogate(this[index - 1]) &&
                    Character.isLowSurrogate(this[index])
                )
}
