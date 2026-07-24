package io.github.ethanbird.senseime.ai.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolValidatorTest {
    @Test
    fun fullDocumentSnapshotIsValid() {
        val result = ProtocolValidator.validate(snapshot())

        assertTrue(result.errors.toString(), result.isValid)
    }

    @Test
    fun fullDocumentCannotPretendTruncatedTextIsComplete() {
        val result = ProtocolValidator.validate(snapshot().copy(truncated = true))

        assertError(result, ProtocolErrorCode.INVALID_CAPABILITY, "$.truncated")
    }

    @Test
    fun wholeFieldRequiresFullDocumentCapability() {
        val partial = snapshot().copy(
            capability = SnapshotCapability.SURROUNDING_WINDOW,
            truncated = true,
        )

        val result = ProtocolValidator.validate(partial)

        assertError(result, ProtocolErrorCode.TARGET_NOT_SUPPORTED, "$.target")
    }

    @Test
    fun surroundingWindowIsPreviewOnlyAndCannotAuthorizeSelectionReplacement() {
        val preview = snapshot(
            capability = SnapshotCapability.SURROUNDING_WINDOW,
            text = "上下文窗口",
            selection = TextSelectionV1(100, 102),
            target = null,
        ).copy(textStartOffset = 100)
        val unauthorized = preview.copy(target = PatchTarget.SELECTION)
        val patch = replacePatch().copy(
            operation = replacePatch().operation.copy(target = PatchTarget.SELECTION),
        )

        assertTrue(ProtocolValidator.validate(preview).isValid)
        assertError(
            ProtocolValidator.validate(unauthorized),
            ProtocolErrorCode.TARGET_NOT_SUPPORTED,
            "$.target",
        )
        assertError(
            ProtocolValidator.validate(patch, preview),
            ProtocolErrorCode.TARGET_NOT_SUPPORTED,
            "$.operation.target",
        )
    }

    @Test
    fun selectionOffsetsUseUtf16AndMustRemainInRange() {
        val valid = snapshot(
            text = "A😀B",
            selection = TextSelectionV1(1, 3),
            target = PatchTarget.SELECTION,
        )
        val invalid = valid.copy(selection = TextSelectionV1(1, 5))

        assertTrue(ProtocolValidator.validate(valid).isValid)
        assertError(
            ProtocolValidator.validate(invalid),
            ProtocolErrorCode.INVALID_SELECTION,
            "$.selection",
        )
    }

    @Test
    fun selectionCannotSplitASurrogatePair() {
        val invalid = snapshot(
            text = "A😀B",
            selection = TextSelectionV1(2, 2),
            target = PatchTarget.SELECTION,
        )

        assertError(
            ProtocolValidator.validate(invalid),
            ProtocolErrorCode.INVALID_SELECTION,
            "$.selection",
        )
    }

    @Test
    fun selectionOnlyRequiresNonEmptySelection() {
        val invalid = snapshot(
            capability = SnapshotCapability.SELECTION_ONLY,
            text = "文本",
            selection = TextSelectionV1(1, 1),
            target = PatchTarget.SELECTION,
        )

        val result = ProtocolValidator.validate(invalid)

        assertTrue(result.errors.any { it.code == ProtocolErrorCode.INVALID_SELECTION })
        assertTrue(result.errors.any { it.code == ProtocolErrorCode.TARGET_NOT_SUPPORTED })
    }

    @Test
    fun partialSnapshotSelectionUsesAbsoluteHostOffsets() {
        val valid = snapshot(
            capability = SnapshotCapability.SELECTION_ONLY,
            text = "冻结选区",
            selection = TextSelectionV1(100, 104),
            target = PatchTarget.SELECTION,
        ).copy(textStartOffset = 100)
        val beforeWindow = valid.copy(selection = TextSelectionV1(99, 103))
        val afterWindow = valid.copy(selection = TextSelectionV1(100, 105))

        assertTrue(ProtocolValidator.validate(valid).isValid)
        assertError(
            ProtocolValidator.validate(beforeWindow),
            ProtocolErrorCode.INVALID_SELECTION,
            "$.selection",
        )
        assertError(
            ProtocolValidator.validate(afterWindow),
            ProtocolErrorCode.INVALID_SELECTION,
            "$.selection",
        )
    }

    @Test
    fun fullDocumentMustStartAtAbsoluteZero() {
        val result = ProtocolValidator.validate(snapshot().copy(textStartOffset = 10))

        assertError(
            result,
            ProtocolErrorCode.INVALID_CAPABILITY,
            "$.text_start_offset",
        )
    }

    @Test
    fun absoluteTextWindowMustFitAndroidHostOffsets() {
        val invalid = snapshot(
            capability = SnapshotCapability.SURROUNDING_WINDOW,
            text = "ab",
            selection = null,
            target = null,
        ).copy(
            textStartOffset = Int.MAX_VALUE,
            truncated = true,
        )

        assertError(
            ProtocolValidator.validate(invalid),
            ProtocolErrorCode.INVALID_OFFSET,
            "$.text_start_offset",
        )
    }

    @Test
    fun unavailableSnapshotCannotCarryEditorData() {
        val invalid = snapshot().copy(
            capability = SnapshotCapability.UNAVAILABLE,
            target = PatchTarget.SELECTION,
        )

        val result = ProtocolValidator.validate(invalid)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.path == "$.text" })
        assertTrue(result.errors.any { it.path == "$.selection" })
    }

    @Test
    fun unavailableSnapshotCanRepresentAReadFailureWithoutPatchAuthority() {
        val text = ""
        val unavailable = snapshot(
            capability = SnapshotCapability.UNAVAILABLE,
            text = text,
            selection = null,
            target = null,
        ).copy(
            baseSha256 = EditorTextDigest.sha256Utf8(text),
            truncated = false,
        )

        assertTrue(ProtocolValidator.validate(unavailable).isValid)
    }

    @Test
    fun harnessRequestMustBindSameRequestAndLimit() {
        val request = HarnessRequestV1(
            requestId = "different-request",
            runGeneration = 3,
            snapshot = snapshot(),
            maxOutputChars = 100,
        )

        val result = ProtocolValidator.validate(request)

        assertEquals(
            2,
            result.errors.count {
                it.code == ProtocolErrorCode.SNAPSHOT_MISMATCH ||
                    it.code == ProtocolErrorCode.INVALID_LIMIT
            },
        )
    }

    @Test
    fun noChangeCannotBeRequestedAsAHarnessSkill() {
        val request = HarnessRequestV1(
            requestId = REQUEST_ID,
            runGeneration = 3,
            skill = EditorIntent.NO_CHANGE,
            snapshot = snapshot(),
        )

        assertError(
            ProtocolValidator.validate(request),
            ProtocolErrorCode.INVALID_INTENT,
            "$.skill",
        )
    }

    @Test
    fun patchMustMatchFrozenSnapshotIdentity() {
        val patch = replacePatch().copy(
            requestId = "wrong-request",
            snapshotId = "wrong-snapshot",
            baseSha256 = "b".repeat(64),
        )

        val result = ProtocolValidator.validate(patch, snapshot())

        assertEquals(
            3,
            result.errors.count { it.code == ProtocolErrorCode.SNAPSHOT_MISMATCH },
        )
    }

    @Test
    fun selectionPatchIsRejectedWithoutFrozenSelection() {
        val patch = replacePatch().copy(
            operation = replacePatch().operation.copy(target = PatchTarget.SELECTION),
        )

        val result = ProtocolValidator.validate(patch, snapshot())

        assertError(result, ProtocolErrorCode.TARGET_NOT_SUPPORTED, "$.operation.target")
    }

    @Test
    fun snapshotOutputLimitGatesReplacement() {
        val snapshot = snapshot().copy(maxOutputChars = 3)
        val patch = replacePatch(text = "四个字符")

        val result = ProtocolValidator.validate(patch, snapshot)

        assertError(result, ProtocolErrorCode.TEXT_TOO_LONG, "$.operation.text")
    }

    @Test
    fun defaultOutputLimitAccepts4096AndRejects4097Utf16Units() {
        val snapshot = snapshot()

        assertTrue(
            ProtocolValidator.validate(replacePatch(text = "a".repeat(4_096)), snapshot).isValid,
        )
        assertError(
            ProtocolValidator.validate(replacePatch(text = "a".repeat(4_097)), snapshot),
            ProtocolErrorCode.TEXT_TOO_LONG,
            "$.operation.text",
        )
    }

    @Test
    fun snapshotTextHasInclusiveAbsoluteBound() {
        val atLimit = snapshot(
            text = "a".repeat(SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS),
            selection = TextSelectionV1(
                SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS,
                SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS,
            ),
        )
        val overLimit = atLimit.copy(
            text = atLimit.text + "b",
            selection = TextSelectionV1(atLimit.text.length + 1, atLimit.text.length + 1),
        )

        assertTrue(ProtocolValidator.validate(atLimit).isValid)
        assertError(
            ProtocolValidator.validate(overLimit),
            ProtocolErrorCode.TEXT_TOO_LONG,
            "$.text",
        )
    }

    @Test
    fun noChangeCannotSmuggleReplacementFields() {
        val patch = replacePatch().copy(
            intent = EditorIntent.NO_CHANGE,
            operation = PatchOperationV1(
                type = PatchOperationType.NO_CHANGE,
                target = PatchTarget.WHOLE_FIELD,
                text = "unexpected",
                selectionAfter = SelectionAfter.END,
            ),
        )

        val result = ProtocolValidator.validate(patch)

        assertEquals(
            3,
            result.errors.count { it.code == ProtocolErrorCode.OPERATION_CONFLICT },
        )
    }

    @Test
    fun replaceCannotMasqueradeAsNoChangeIntent() {
        val patch = replacePatch().copy(intent = EditorIntent.NO_CHANGE)

        assertError(
            ProtocolValidator.validate(patch),
            ProtocolErrorCode.OPERATION_CONFLICT,
            "$.intent",
        )
    }

    @Test
    fun nulAndUnpairedSurrogateAreRejected() {
        val nul = replacePatch(text = "a\u0000b")
        val surrogate = replacePatch(text = "a\uD800b")

        assertError(
            ProtocolValidator.validate(nul),
            ProtocolErrorCode.INVALID_TEXT,
            "$.operation.text",
        )
        assertError(
            ProtocolValidator.validate(surrogate),
            ProtocolErrorCode.INVALID_TEXT,
            "$.operation.text",
        )
    }

    @Test
    fun requireValidThrowsStableValidationException() {
        val result = ProtocolValidator.validate(replacePatch().copy(protocol = "wrong"))

        val exception = assertThrows(ProtocolValidationException::class.java) {
            result.requireValid()
        }

        assertEquals(ProtocolErrorCode.PROTOCOL_MISMATCH, exception.errors.single().code)
    }

    private fun snapshot(
        capability: SnapshotCapability = SnapshotCapability.FULL_DOCUMENT,
        text: String = "原始文本",
        selection: TextSelectionV1? = TextSelectionV1(4, 4),
        target: PatchTarget? = PatchTarget.WHOLE_FIELD,
    ) = EditorSnapshotV1(
        requestId = REQUEST_ID,
        snapshotId = SNAPSHOT_ID,
        editorGeneration = 4,
        fieldIdentity = "field-1",
        capability = capability,
        text = text,
        selection = selection,
        target = target,
        baseSha256 = EditorTextDigest.sha256Utf8(text),
        capturedAtMonotonicMs = 1_234,
        truncated = capability == SnapshotCapability.SURROUNDING_WINDOW,
    )

    private fun replacePatch(text: String = "最终文本") = EditorPatchV1(
        requestId = REQUEST_ID,
        snapshotId = SNAPSHOT_ID,
        baseSha256 = BASE_HASH,
        intent = EditorIntent.REWRITE,
        operation = PatchOperationV1(
            type = PatchOperationType.REPLACE,
            target = PatchTarget.WHOLE_FIELD,
            text = text,
            selectionAfter = SelectionAfter.END,
        ),
    )

    private fun assertError(
        result: ValidationResult,
        code: ProtocolErrorCode,
        path: String,
    ) {
        assertTrue(
            "Expected $code at $path but got ${result.errors}",
            result.errors.any { it.code == code && it.path == path },
        )
    }

    private companion object {
        const val REQUEST_ID = "request-1"
        const val SNAPSHOT_ID = "snapshot-1"
        const val BASE_HASH = "2bedf3a376e78d39beaa7a2ad188975daaec3c3f0d2f7a5050d1d34ed597ea76"
    }
}
