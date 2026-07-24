package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.ProtocolValidator
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSnapshotCapturePolicyTest {
    @Test
    fun completeExtractedTextCreatesWholeFieldSnapshot() {
        val decision = EditorSnapshotCapturePolicy.capture(input())

        val snapshot = (decision as EditorCaptureDecision.Captured).snapshot
        assertEquals(SnapshotCapability.FULL_DOCUMENT, snapshot.capability)
        assertEquals(PatchTarget.WHOLE_FIELD, snapshot.target)
        assertEquals("hello world", snapshot.text)
        assertEquals(TextSelectionV1(11, 11), snapshot.selection)
        assertFalse(snapshot.truncated)
        assertTrue(ProtocolValidator.validate(snapshot).isValid)
    }

    @Test
    fun nonEmptySelectionCanExplicitlyAuthorizeSelectionTarget() {
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                preferredTarget = PatchTarget.SELECTION,
                currentSelection = TextSelectionV1(6, 11),
                extracted = extracted(selectionStart = 6, selectionEnd = 11),
                selected = SelectedEditorText(true, "world"),
            ),
        )

        val snapshot = (decision as EditorCaptureDecision.Captured).snapshot
        assertEquals(SnapshotCapability.FULL_DOCUMENT, snapshot.capability)
        assertEquals(PatchTarget.SELECTION, snapshot.target)
        assertEquals(TextSelectionV1(6, 11), snapshot.selection)
    }

    @Test
    fun collapsedSelectionCannotSilentlyChangePreferredSelectionIntoWholeField() {
        val decision = EditorSnapshotCapturePolicy.capture(
            input(preferredTarget = PatchTarget.SELECTION),
        )

        assertEquals(
            EditorCaptureUnavailableReason.INCONSISTENT_SELECTION,
            (decision as EditorCaptureDecision.Unavailable).reason,
        )
    }

    @Test
    fun everyPasswordSignalFailsClosed() {
        val cases = listOf(
            EditorSecurityContext(isTextPassword = true) to
                EditorCaptureBlockReason.TEXT_PASSWORD,
            EditorSecurityContext(isVisibleTextPassword = true) to
                EditorCaptureBlockReason.VISIBLE_TEXT_PASSWORD,
            EditorSecurityContext(isWebPassword = true) to
                EditorCaptureBlockReason.WEB_PASSWORD,
            EditorSecurityContext(isNumberPassword = true) to
                EditorCaptureBlockReason.NUMBER_PASSWORD,
        )

        cases.forEach { (security, reason) ->
            assertEquals(reason, EditorSnapshotCapturePolicy.preflight(security))
            val decision = EditorSnapshotCapturePolicy.capture(input(security = security))
            assertEquals(reason, (decision as EditorCaptureDecision.Blocked).reason)
        }
    }

    @Test
    fun otpFailsClosedBeforeCaptureMetadataIsInspected() {
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                requestId = "",
                security = EditorSecurityContext(isOneTimeCode = true),
            ),
        )

        assertEquals(
            EditorCaptureBlockReason.ONE_TIME_CODE,
            (decision as EditorCaptureDecision.Blocked).reason,
        )
    }

    @Test
    fun noPersonalizedLearningFailsClosed() {
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                security = EditorSecurityContext(noPersonalizedLearning = true),
            ),
        )

        assertEquals(
            EditorCaptureBlockReason.NO_PERSONALIZED_LEARNING,
            (decision as EditorCaptureDecision.Blocked).reason,
        )
    }

    @Test
    fun unknownSecurityClassificationFailsClosed() {
        assertEquals(
            EditorCaptureBlockReason.SECURITY_CLASSIFICATION_UNAVAILABLE,
            EditorSnapshotCapturePolicy.preflight(
                EditorSecurityContext(classificationComplete = false),
            ),
        )
    }

    @Test
    fun mismatchedSelectedTextRejectsWholeDocumentAuthority() {
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                currentSelection = TextSelectionV1(6, 11),
                extracted = extracted(selectionStart = 6, selectionEnd = 11),
                selected = SelectedEditorText(true, "other"),
            ),
        )

        assertEquals(
            EditorCaptureUnavailableReason.INCONSISTENT_SELECTION,
            (decision as EditorCaptureDecision.Unavailable).reason,
        )
    }

    @Test
    fun failedSelectedTextReadRejectsNonEmptyExtractedSelection() {
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                currentSelection = TextSelectionV1(6, 11),
                extracted = extracted(selectionStart = 6, selectionEnd = 11),
                selected = SelectedEditorText(false, null),
            ),
        )

        assertTrue(decision is EditorCaptureDecision.Unavailable)
    }

    @Test
    fun staleCurrentSelectionRejectsExtractedText() {
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                currentSelection = TextSelectionV1(5, 5),
                extracted = extracted(selectionStart = 11, selectionEnd = 11),
            ),
        )

        assertEquals(
            EditorCaptureUnavailableReason.INCONSISTENT_SELECTION,
            (decision as EditorCaptureDecision.Unavailable).reason,
        )
    }

    @Test
    fun directSelectedTextFallsBackToSelectionOnly() {
        val selection = TextSelectionV1(20, 25)
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                currentSelection = selection,
                extracted = null,
                selected = SelectedEditorText(true, "hello"),
                surrounding = null,
            ),
        )

        val snapshot = (decision as EditorCaptureDecision.Captured).snapshot
        assertEquals(SnapshotCapability.SELECTION_ONLY, snapshot.capability)
        assertEquals(selection.start, snapshot.textStartOffset)
        assertEquals("hello", snapshot.text)
        assertEquals(PatchTarget.SELECTION, snapshot.target)
        assertTrue(ProtocolValidator.validate(snapshot).isValid)
    }

    @Test
    fun partialExtractedTextIsPreviewOnly() {
        val partial = ExtractedEditorText(
            text = "context",
            startOffset = 40,
            selectionStartInText = 3,
            selectionEndInText = 3,
            completeDocument = false,
        )
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                currentSelection = TextSelectionV1(43, 43),
                extracted = partial,
                selected = SelectedEditorText(true, null),
            ),
        )

        val snapshot = (decision as EditorCaptureDecision.Captured).snapshot
        assertEquals(SnapshotCapability.SURROUNDING_WINDOW, snapshot.capability)
        assertNull(snapshot.target)
        assertTrue(snapshot.truncated)
        assertTrue(ProtocolValidator.validate(snapshot).isValid)
    }

    @Test
    fun nonZeroStartOffsetCanNeverMasqueradeAsCompleteDocument() {
        val partial = ExtractedEditorText(
            text = "context",
            startOffset = 40,
            selectionStartInText = 3,
            selectionEndInText = 3,
            completeDocument = true,
        )
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                currentSelection = TextSelectionV1(43, 43),
                extracted = partial,
                selected = SelectedEditorText(true, null),
            ),
        )

        val snapshot = (decision as EditorCaptureDecision.Captured).snapshot
        assertEquals(SnapshotCapability.SURROUNDING_WINDOW, snapshot.capability)
        assertNull(snapshot.target)
    }

    @Test
    fun beforeAfterFallbackBuildsAbsoluteWindow() {
        val selection = TextSelectionV1(10, 12)
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                currentSelection = selection,
                extracted = null,
                selected = SelectedEditorText(true, "中间"),
                surrounding = SurroundingEditorText("before", "after"),
            ),
        )

        // A real selection is intentionally classified as SELECTION_ONLY before window fallback.
        val snapshot = (decision as EditorCaptureDecision.Captured).snapshot
        assertEquals(SnapshotCapability.SELECTION_ONLY, snapshot.capability)
    }

    @Test
    fun collapsedBeforeAfterFallbackBuildsSurroundingWindow() {
        val selection = TextSelectionV1(10, 10)
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                currentSelection = selection,
                extracted = null,
                selected = SelectedEditorText(true, null),
                surrounding = SurroundingEditorText("before", "after"),
            ),
        )

        val snapshot = (decision as EditorCaptureDecision.Captured).snapshot
        assertEquals(SnapshotCapability.SURROUNDING_WINDOW, snapshot.capability)
        assertEquals(4, snapshot.textStartOffset)
        assertEquals("beforeafter", snapshot.text)
        assertEquals(selection, snapshot.selection)
        assertNull(snapshot.target)
    }

    @Test
    fun noReadableTextReturnsProtocolValidUnavailableSnapshot() {
        val decision = EditorSnapshotCapturePolicy.capture(
            input(extracted = null, selected = null, surrounding = null),
        )

        val unavailable = decision as EditorCaptureDecision.Unavailable
        assertEquals(EditorCaptureUnavailableReason.NO_READABLE_TEXT, unavailable.reason)
        assertEquals(SnapshotCapability.UNAVAILABLE, unavailable.snapshot.capability)
        assertTrue(ProtocolValidator.validate(unavailable.snapshot).isValid)
    }

    @Test
    fun oversizedSurroundingWindowIsUnavailable() {
        val decision = EditorSnapshotCapturePolicy.capture(
            input(
                currentSelection = TextSelectionV1(
                    SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS + 1,
                    SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS + 1,
                ),
                extracted = null,
                selected = SelectedEditorText(true, null),
                surrounding = SurroundingEditorText(
                    "a".repeat(SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS + 1),
                    "",
                ),
            ),
        )

        assertEquals(
            EditorCaptureUnavailableReason.TEXT_LIMIT_EXCEEDED,
            (decision as EditorCaptureDecision.Unavailable).reason,
        )
    }

    @Test
    fun malformedIdsAreBlockedAsInvalidMetadata() {
        val decision = EditorSnapshotCapturePolicy.capture(input(requestId = "bad id"))

        assertEquals(
            EditorCaptureBlockReason.INVALID_CAPTURE_METADATA,
            (decision as EditorCaptureDecision.Blocked).reason,
        )
    }

    private fun input(
        requestId: String = EditorAiTestFixtures.REQUEST_ID,
        security: EditorSecurityContext = EditorSecurityContext(),
        preferredTarget: PatchTarget = PatchTarget.WHOLE_FIELD,
        currentSelection: TextSelectionV1? = TextSelectionV1(11, 11),
        extracted: ExtractedEditorText? = extracted(),
        selected: SelectedEditorText? = SelectedEditorText(true, null),
        surrounding: SurroundingEditorText? = SurroundingEditorText("hello ", ""),
    ): EditorSnapshotCaptureInput = EditorSnapshotCaptureInput(
        requestId = requestId,
        snapshotId = EditorAiTestFixtures.SNAPSHOT_ID,
        editorGeneration = EditorAiTestFixtures.EDITOR_GENERATION,
        fieldIdentity = EditorAiTestFixtures.FIELD_ID,
        capturedAtMonotonicMs = 100,
        security = security,
        preferredTarget = preferredTarget,
        currentSelection = currentSelection,
        extracted = extracted,
        selected = selected,
        surrounding = surrounding,
    )

    private fun extracted(
        selectionStart: Int = 11,
        selectionEnd: Int = 11,
    ): ExtractedEditorText = ExtractedEditorText(
        text = "hello world",
        startOffset = 0,
        selectionStartInText = selectionStart,
        selectionEndInText = selectionEnd,
        completeDocument = true,
    )
}
