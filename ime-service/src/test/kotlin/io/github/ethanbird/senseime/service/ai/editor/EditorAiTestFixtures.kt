package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorPatchV1
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.PatchOperationV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SelectionAfter
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1

internal object EditorAiTestFixtures {
    const val REQUEST_ID = "request-1"
    const val SNAPSHOT_ID = "snapshot-1"
    const val FIELD_ID = "field-1"
    const val RUN_GENERATION = 9L
    const val EDITOR_GENERATION = 4L
    const val POINTER_ID = 7
    const val POINTER_TOKEN = 88L

    fun fullSnapshot(
        text: String = "hello world",
        selection: TextSelectionV1 = TextSelectionV1(text.length, text.length),
        target: PatchTarget = PatchTarget.WHOLE_FIELD,
    ): EditorSnapshotV1 = EditorSnapshotV1(
        requestId = REQUEST_ID,
        snapshotId = SNAPSHOT_ID,
        editorGeneration = EDITOR_GENERATION,
        fieldIdentity = FIELD_ID,
        capability = SnapshotCapability.FULL_DOCUMENT,
        text = text,
        textStartOffset = 0,
        selection = selection,
        target = target,
        baseSha256 = EditorTextDigest.sha256Utf8(text),
        capturedAtMonotonicMs = 100,
        truncated = false,
    )

    fun selectionSnapshot(
        text: String = "world",
        selection: TextSelectionV1 = TextSelectionV1(6, 11),
    ): EditorSnapshotV1 = EditorSnapshotV1(
        requestId = REQUEST_ID,
        snapshotId = SNAPSHOT_ID,
        editorGeneration = EDITOR_GENERATION,
        fieldIdentity = FIELD_ID,
        capability = SnapshotCapability.SELECTION_ONLY,
        text = text,
        textStartOffset = selection.start,
        selection = selection,
        target = PatchTarget.SELECTION,
        baseSha256 = EditorTextDigest.sha256Utf8(text),
        capturedAtMonotonicMs = 100,
        truncated = false,
    )

    fun replacePatch(
        snapshot: EditorSnapshotV1 = fullSnapshot(),
        text: String = "updated",
        selectionAfter: SelectionAfter = SelectionAfter.END,
    ): EditorPatchV1 = EditorPatchV1(
        requestId = snapshot.requestId,
        snapshotId = snapshot.snapshotId,
        baseSha256 = snapshot.baseSha256,
        intent = EditorIntent.REWRITE,
        operation = PatchOperationV1(
            type = PatchOperationType.REPLACE,
            target = snapshot.target,
            text = text,
            selectionAfter = selectionAfter,
        ),
    )

    fun noChangePatch(snapshot: EditorSnapshotV1 = fullSnapshot()): EditorPatchV1 =
        EditorPatchV1(
            requestId = snapshot.requestId,
            snapshotId = snapshot.snapshotId,
            baseSha256 = snapshot.baseSha256,
            intent = EditorIntent.NO_CHANGE,
            operation = PatchOperationV1(type = PatchOperationType.NO_CHANGE),
        )

    fun lease(snapshot: EditorSnapshotV1 = fullSnapshot()): ActiveEditorPatchLease =
        ActiveEditorPatchLease(
            snapshot = snapshot,
            runGeneration = RUN_GENERATION,
            pointerId = POINTER_ID,
            pointerOwnershipToken = POINTER_TOKEN,
        )

    fun pointer(): EditorPointerOwner = EditorPointerOwner(
        pointerId = POINTER_ID,
        pointerOwnershipToken = POINTER_TOKEN,
        isDown = true,
    )

    fun live(snapshot: EditorSnapshotV1 = fullSnapshot()): LiveEditorRead = LiveEditorRead(
        editorGeneration = snapshot.editorGeneration,
        fieldIdentity = snapshot.fieldIdentity,
        capability = snapshot.capability,
        text = snapshot.text,
        textStartOffset = snapshot.textStartOffset,
        selection = snapshot.selection,
    )

    fun guardInput(
        snapshot: EditorSnapshotV1 = fullSnapshot(),
        patch: EditorPatchV1 = replacePatch(snapshot),
    ): EditorPatchGuardInput = EditorPatchGuardInput(
        lease = lease(snapshot),
        eventRequestId = REQUEST_ID,
        eventRunGeneration = RUN_GENERATION,
        pointerOwner = pointer(),
        liveEditor = live(snapshot),
        patch = patch,
    )

    fun accepted(
        input: EditorPatchGuardInput = guardInput(),
    ): GuardedEditorPatch =
        (EditorPatchGuard.evaluate(input) as EditorPatchGuardDecision.Accepted).guardedPatch
}
