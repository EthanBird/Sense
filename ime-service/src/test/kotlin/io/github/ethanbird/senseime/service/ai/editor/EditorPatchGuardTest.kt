package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPatchGuardTest {
    @Test
    fun acceptsFreshWholeFieldPatch() {
        val decision = EditorPatchGuard.evaluate(EditorAiTestFixtures.guardInput())

        val guarded = (decision as EditorPatchGuardDecision.Accepted).guardedPatch
        assertEquals(TextSelectionV1(0, 11), guarded.targetRange)
        assertEquals("hello world", guarded.originalTargetText)
    }

    @Test
    fun requestAndGenerationMustMatchLocalEventBinding() {
        assertRejected(
            EditorAiTestFixtures.guardInput().copy(eventRequestId = "late-request"),
            EditorPatchRejectReason.REQUEST_MISMATCH,
        )
        assertRejected(
            EditorAiTestFixtures.guardInput().copy(eventRunGeneration = 10),
            EditorPatchRejectReason.RUN_GENERATION_MISMATCH,
        )
    }

    @Test
    fun patchProtocolMismatchIsReportedWithClosedProtocolErrors() {
        val input = EditorAiTestFixtures.guardInput()
        val decision = EditorPatchGuard.evaluate(
            input.copy(patch = input.patch.copy(snapshotId = "other-snapshot")),
        )

        val rejected = decision as EditorPatchGuardDecision.Rejected
        assertTrue(rejected.reasons.contains(EditorPatchRejectReason.INVALID_PATCH_PROTOCOL))
        assertTrue(rejected.protocolErrors.isNotEmpty())
    }

    @Test
    fun pointerMustStillBeOwnedAndDown() {
        assertRejected(
            EditorAiTestFixtures.guardInput().copy(pointerOwner = null),
            EditorPatchRejectReason.POINTER_NOT_DOWN,
        )
        assertRejected(
            EditorAiTestFixtures.guardInput().copy(
                pointerOwner = EditorAiTestFixtures.pointer().copy(isDown = false),
            ),
            EditorPatchRejectReason.POINTER_NOT_DOWN,
        )
        assertRejected(
            EditorAiTestFixtures.guardInput().copy(
                pointerOwner = EditorAiTestFixtures.pointer().copy(pointerId = 99),
            ),
            EditorPatchRejectReason.POINTER_OWNER_MISMATCH,
        )
        assertRejected(
            EditorAiTestFixtures.guardInput().copy(
                pointerOwner =
                    EditorAiTestFixtures.pointer().copy(pointerOwnershipToken = 99),
            ),
            EditorPatchRejectReason.POINTER_OWNER_MISMATCH,
        )
    }

    @Test
    fun invalidLocalLeaseTokenFailsClosed() {
        val input = EditorAiTestFixtures.guardInput()
        assertRejected(
            input.copy(
                lease = input.lease.copy(pointerOwnershipToken = 0),
                pointerOwner =
                    requireNotNull(input.pointerOwner).copy(pointerOwnershipToken = 0),
            ),
            EditorPatchRejectReason.INVALID_LEASE_SNAPSHOT,
        )
    }

    @Test
    fun missingFreshEditorReadFailsClosed() {
        assertRejected(
            EditorAiTestFixtures.guardInput().copy(liveEditor = null),
            EditorPatchRejectReason.INPUT_CONNECTION_UNAVAILABLE,
        )
    }

    @Test
    fun editorGenerationAndFieldIdentityAreCompared() {
        val input = EditorAiTestFixtures.guardInput()
        assertRejected(
            input.copy(
                liveEditor = requireNotNull(input.liveEditor).copy(editorGeneration = 5),
            ),
            EditorPatchRejectReason.EDITOR_GENERATION_CHANGED,
        )
        assertRejected(
            input.copy(
                liveEditor = requireNotNull(input.liveEditor).copy(fieldIdentity = "new-field"),
            ),
            EditorPatchRejectReason.FIELD_IDENTITY_CHANGED,
        )
    }

    @Test
    fun textWindowOffsetAndHashMustRemainFrozen() {
        val input = EditorAiTestFixtures.guardInput()
        assertRejected(
            input.copy(
                liveEditor = requireNotNull(input.liveEditor).copy(textStartOffset = 1),
            ),
            EditorPatchRejectReason.TEXT_WINDOW_CHANGED,
        )
        assertRejected(
            input.copy(
                liveEditor = requireNotNull(input.liveEditor).copy(text = "edited"),
            ),
            EditorPatchRejectReason.BASE_TEXT_CHANGED,
        )
    }

    @Test
    fun selectionMustRemainExactlyFrozenEvenForWholeFieldPatch() {
        val input = EditorAiTestFixtures.guardInput()
        assertRejected(
            input.copy(
                liveEditor = requireNotNull(input.liveEditor).copy(
                    selection = TextSelectionV1(0, 0),
                ),
            ),
            EditorPatchRejectReason.SELECTION_CHANGED,
        )
    }

    @Test
    fun capabilityCannotChangeBetweenCaptureAndApply() {
        val input = EditorAiTestFixtures.guardInput()
        assertRejected(
            input.copy(
                liveEditor = requireNotNull(input.liveEditor).copy(
                    capability = SnapshotCapability.SURROUNDING_WINDOW,
                ),
            ),
            EditorPatchRejectReason.CAPABILITY_CHANGED,
        )
        assertRejected(
            input.copy(
                liveEditor = requireNotNull(input.liveEditor).copy(
                    capability = SnapshotCapability.SURROUNDING_WINDOW,
                ),
            ),
            EditorPatchRejectReason.CAPABILITY_NOT_EDITABLE,
        )
    }

    @Test
    fun selectionOnlyPatchMapsAbsoluteRangeToLocalText() {
        val snapshot = EditorAiTestFixtures.selectionSnapshot()
        val input = EditorAiTestFixtures.guardInput(
            snapshot = snapshot,
            patch = EditorAiTestFixtures.replacePatch(snapshot),
        )

        val guarded =
            (EditorPatchGuard.evaluate(input) as EditorPatchGuardDecision.Accepted).guardedPatch
        assertEquals(TextSelectionV1(6, 11), guarded.targetRange)
        assertEquals("world", guarded.originalTargetText)
    }

    @Test
    fun fullDocumentSelectionPatchUsesFrozenSubstring() {
        val snapshot = EditorAiTestFixtures.fullSnapshot(
            selection = TextSelectionV1(6, 11),
            target = PatchTarget.SELECTION,
        )
        val input = EditorAiTestFixtures.guardInput(
            snapshot = snapshot,
            patch = EditorAiTestFixtures.replacePatch(snapshot),
        )

        val guarded =
            (EditorPatchGuard.evaluate(input) as EditorPatchGuardDecision.Accepted).guardedPatch
        assertEquals("world", guarded.originalTargetText)
    }

    @Test
    fun emptyReplacementCannotSilentlyDeleteWholeFieldOrSelection() {
        val whole = EditorAiTestFixtures.fullSnapshot()
        assertRejected(
            EditorAiTestFixtures.guardInput(
                snapshot = whole,
                patch = EditorAiTestFixtures.replacePatch(whole, text = ""),
            ),
            EditorPatchRejectReason.EMPTY_REPLACEMENT_NOT_ALLOWED,
        )

        val selection = EditorAiTestFixtures.selectionSnapshot()
        assertRejected(
            EditorAiTestFixtures.guardInput(
                snapshot = selection,
                patch = EditorAiTestFixtures.replacePatch(selection, text = ""),
            ),
            EditorPatchRejectReason.EMPTY_REPLACEMENT_NOT_ALLOWED,
        )
    }

    @Test
    fun noChangeStillPassesFreshnessGateButHasNoMutationRange() {
        val snapshot = EditorAiTestFixtures.fullSnapshot()
        val input = EditorAiTestFixtures.guardInput(
            snapshot,
            EditorAiTestFixtures.noChangePatch(snapshot),
        )

        val guarded =
            (EditorPatchGuard.evaluate(input) as EditorPatchGuardDecision.Accepted).guardedPatch
        assertNull(guarded.targetRange)
        assertNull(guarded.originalTargetText)
    }

    @Test
    fun invalidSnapshotCanNeverMintGuardedPatch() {
        val invalid = EditorAiTestFixtures.fullSnapshot().copy(target = null)
        val decision = EditorPatchGuard.evaluate(
            EditorAiTestFixtures.guardInput(
                snapshot = invalid,
                patch = EditorAiTestFixtures.noChangePatch(invalid),
            ),
        )

        assertTrue(
            (decision as EditorPatchGuardDecision.Rejected).reasons.contains(
                EditorPatchRejectReason.INVALID_LEASE_SNAPSHOT,
            ),
        )
    }

    @Test
    fun replacementWhoseAbsoluteSelectionWouldOverflowIsRejected() {
        val selection = TextSelectionV1(Int.MAX_VALUE - 1, Int.MAX_VALUE)
        val text = "a"
        val snapshot = EditorSnapshotV1(
            requestId = EditorAiTestFixtures.REQUEST_ID,
            snapshotId = EditorAiTestFixtures.SNAPSHOT_ID,
            editorGeneration = EditorAiTestFixtures.EDITOR_GENERATION,
            fieldIdentity = EditorAiTestFixtures.FIELD_ID,
            capability = SnapshotCapability.SELECTION_ONLY,
            text = text,
            textStartOffset = selection.start,
            selection = selection,
            target = PatchTarget.SELECTION,
            baseSha256 = EditorTextDigest.sha256Utf8(text),
            capturedAtMonotonicMs = 100,
            truncated = false,
        )
        val patch = EditorAiTestFixtures.replacePatch(snapshot, text = "xx")

        assertRejected(
            EditorAiTestFixtures.guardInput(snapshot, patch),
            EditorPatchRejectReason.RESULT_RANGE_OVERFLOW,
        )
    }

    @Test
    fun replacementWhoseResultCannotBeRecapturedIsRejectedBeforeMutation() {
        val text = "a".repeat(SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS)
        val snapshot = EditorAiTestFixtures.fullSnapshot(
            text = text,
            selection = TextSelectionV1(text.length - 1, text.length),
            target = PatchTarget.SELECTION,
        )
        val patch = EditorAiTestFixtures.replacePatch(snapshot, text = "xx")

        assertRejected(
            EditorAiTestFixtures.guardInput(snapshot, patch),
            EditorPatchRejectReason.RESULT_TEXT_LIMIT_EXCEEDED,
        )
    }

    private fun assertRejected(
        input: EditorPatchGuardInput,
        reason: EditorPatchRejectReason,
    ) {
        val result = EditorPatchGuard.evaluate(input)
        assertTrue(
            "expected $reason but got $result",
            (result as EditorPatchGuardDecision.Rejected).reasons.contains(reason),
        )
    }
}
