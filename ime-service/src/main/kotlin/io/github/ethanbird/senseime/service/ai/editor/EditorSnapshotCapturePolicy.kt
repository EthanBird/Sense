package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.ProtocolValidator
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1

/**
 * Security facts derived from EditorInfo before any InputConnection text call is made.
 *
 * The Android adapter deliberately passes facts instead of raw bit masks so this policy remains
 * host-testable. A caller that could not classify the editor must set
 * [classificationComplete] to false; uncertainty is not treated as permission.
 */
data class EditorSecurityContext(
    val isTextPassword: Boolean = false,
    val isVisibleTextPassword: Boolean = false,
    val isWebPassword: Boolean = false,
    val isNumberPassword: Boolean = false,
    val isOneTimeCode: Boolean = false,
    val noPersonalizedLearning: Boolean = false,
    val classificationComplete: Boolean = true,
)

enum class EditorCaptureBlockReason {
    TEXT_PASSWORD,
    VISIBLE_TEXT_PASSWORD,
    WEB_PASSWORD,
    NUMBER_PASSWORD,
    ONE_TIME_CODE,
    NO_PERSONALIZED_LEARNING,
    SECURITY_CLASSIFICATION_UNAVAILABLE,
    INVALID_CAPTURE_METADATA,
}

enum class EditorCaptureUnavailableReason {
    NO_READABLE_TEXT,
    INVALID_EXTRACTED_TEXT,
    INCONSISTENT_SELECTION,
    TEXT_LIMIT_EXCEEDED,
}

/**
 * Pure representation of Android ExtractedText.
 *
 * [selectionStartInText] and [selectionEndInText] are relative to [text], exactly like Android's
 * ExtractedText fields. [completeDocument] must only be true when the adapter requested and
 * received a non-partial whole-document result.
 */
data class ExtractedEditorText(
    val text: String,
    val startOffset: Int,
    val selectionStartInText: Int,
    val selectionEndInText: Int,
    val completeDocument: Boolean,
    val partialUpdate: Boolean = false,
) {
    val absoluteSelection: TextSelectionV1
        get() = TextSelectionV1(
            start = startOffset + selectionStartInText,
            end = startOffset + selectionEndInText,
        )
}

/**
 * Android's explicit full-text contract for `ExtractedText`.
 *
 * `partialStartOffset == -1` marks a full payload. Optional cursor-context calls are allowed to
 * return a shorter window, so they must not be used as a second full-document requirement.
 */
object EditorExtractedTextContract {
    fun isCompleteDocument(
        startOffset: Int,
        partialStartOffset: Int,
        partialEndOffset: Int,
        selectionStartInText: Int,
        selectionEndInText: Int,
        textLength: Int,
    ): Boolean =
        startOffset == 0 &&
            partialStartOffset < 0 &&
            partialEndOffset < 0 &&
            selectionStartInText >= 0 &&
            selectionEndInText >= selectionStartInText &&
            selectionEndInText <= textLength
}

/** Result of calling InputConnection.getSelectedText(). */
data class SelectedEditorText(
    /** False when the call failed or the InputConnection disappeared. */
    val readSucceeded: Boolean,
    /** Null and empty are both accepted for a collapsed selection. */
    val text: String?,
)

/** Bounded getTextBeforeCursor()/getTextAfterCursor() fallback. */
data class SurroundingEditorText(
    val beforeCursor: String,
    val afterCursor: String,
)

data class EditorSnapshotCaptureInput(
    val requestId: String,
    val snapshotId: String,
    val editorGeneration: Long,
    val fieldIdentity: String,
    val capturedAtMonotonicMs: Long,
    val security: EditorSecurityContext,
    val preferredTarget: PatchTarget = PatchTarget.WHOLE_FIELD,
    val currentSelection: TextSelectionV1?,
    val extracted: ExtractedEditorText?,
    val selected: SelectedEditorText?,
    val surrounding: SurroundingEditorText?,
    val maxOutputChars: Int = SenseAiProtocol.DEFAULT_MAX_OUTPUT_CHARS,
)

sealed interface EditorCaptureDecision {
    data class Captured(
        val snapshot: EditorSnapshotV1,
    ) : EditorCaptureDecision

    /**
     * A protocol-valid UNAVAILABLE snapshot is retained for deterministic UI/error handling, but
     * must never be sent to a provider or used to construct a patch.
     */
    data class Unavailable(
        val snapshot: EditorSnapshotV1,
        val reason: EditorCaptureUnavailableReason,
    ) : EditorCaptureDecision

    /** No snapshot is produced so sensitive text cannot cross the preflight boundary. */
    data class Blocked(
        val reason: EditorCaptureBlockReason,
    ) : EditorCaptureDecision
}

/**
 * Deterministic capture policy implementing FULL_DOCUMENT -> SELECTION_ONLY ->
 * SURROUNDING_WINDOW -> UNAVAILABLE degradation.
 */
object EditorSnapshotCapturePolicy {
    /**
     * Call this before getExtractedText/getSelectedText/getTextBeforeCursor/getTextAfterCursor.
     */
    fun preflight(security: EditorSecurityContext): EditorCaptureBlockReason? = when {
        !security.classificationComplete ->
            EditorCaptureBlockReason.SECURITY_CLASSIFICATION_UNAVAILABLE
        security.isTextPassword -> EditorCaptureBlockReason.TEXT_PASSWORD
        security.isVisibleTextPassword -> EditorCaptureBlockReason.VISIBLE_TEXT_PASSWORD
        security.isWebPassword -> EditorCaptureBlockReason.WEB_PASSWORD
        security.isNumberPassword -> EditorCaptureBlockReason.NUMBER_PASSWORD
        security.isOneTimeCode -> EditorCaptureBlockReason.ONE_TIME_CODE
        security.noPersonalizedLearning ->
            EditorCaptureBlockReason.NO_PERSONALIZED_LEARNING
        else -> null
    }

    fun capture(input: EditorSnapshotCaptureInput): EditorCaptureDecision {
        preflight(input.security)?.let { return EditorCaptureDecision.Blocked(it) }
        if (!hasValidMetadata(input)) {
            return EditorCaptureDecision.Blocked(
                EditorCaptureBlockReason.INVALID_CAPTURE_METADATA,
            )
        }

        val extracted = input.extracted
        if (extracted != null) {
            validateExtracted(extracted, input.currentSelection, input.selected)?.let { reason ->
                return unavailable(input, reason)
            }

            if (
                extracted.completeDocument &&
                !extracted.partialUpdate &&
                extracted.startOffset == 0
            ) {
                if (extracted.text.length > SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS) {
                    return captureSelectionOrSurrounding(
                        input,
                        EditorCaptureUnavailableReason.TEXT_LIMIT_EXCEEDED,
                    )
                }
                val selection = extracted.absoluteSelection
                val target = when (input.preferredTarget) {
                    PatchTarget.WHOLE_FIELD -> PatchTarget.WHOLE_FIELD
                    PatchTarget.SELECTION -> {
                        if (selection.isCollapsed) {
                            return unavailable(
                                input,
                                EditorCaptureUnavailableReason.INCONSISTENT_SELECTION,
                            )
                        }
                        PatchTarget.SELECTION
                    }
                    PatchTarget.CONTEXT_WINDOW -> PatchTarget.CONTEXT_WINDOW
                }
                val capability = if (target == PatchTarget.CONTEXT_WINDOW) {
                    // A later verification read is allowed to expose more text than the original
                    // bounded context read. Keep the originally authorized target kind stable:
                    // context authority must never silently widen into whole-field authority.
                    SnapshotCapability.SURROUNDING_WINDOW
                } else {
                    SnapshotCapability.FULL_DOCUMENT
                }
                return captured(
                    input = input,
                    capability = capability,
                    text = extracted.text,
                    textStartOffset = 0,
                    selection = selection,
                    target = target,
                    truncated = capability == SnapshotCapability.SURROUNDING_WINDOW,
                )
            }
        }

        return captureSelectionOrSurrounding(
            input,
            if (extracted == null) {
                EditorCaptureUnavailableReason.NO_READABLE_TEXT
            } else {
                EditorCaptureUnavailableReason.INVALID_EXTRACTED_TEXT
            },
        )
    }

    private fun captureSelectionOrSurrounding(
        input: EditorSnapshotCaptureInput,
        fallbackReason: EditorCaptureUnavailableReason,
    ): EditorCaptureDecision {
        val selection = input.currentSelection
        val selected = input.selected
        if (
            selection != null &&
            !selection.isCollapsed &&
            selected?.readSucceeded == true &&
            selected.text != null &&
            selected.text.length == selection.end - selection.start
        ) {
            if (selected.text.length > SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS) {
                return unavailable(input, EditorCaptureUnavailableReason.TEXT_LIMIT_EXCEEDED)
            }
            return captured(
                input = input,
                capability = SnapshotCapability.SELECTION_ONLY,
                text = selected.text,
                textStartOffset = selection.start,
                selection = selection,
                target = PatchTarget.SELECTION,
                truncated = false,
            )
        }

        val partial = input.extracted
        if (
            partial != null &&
            (
                !partial.completeDocument ||
                    partial.partialUpdate ||
                    partial.startOffset != 0
                ) &&
            partial.text.length <= SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS
        ) {
            return captured(
                input = input,
                capability = SnapshotCapability.SURROUNDING_WINDOW,
                text = partial.text,
                textStartOffset = partial.startOffset,
                selection = partial.absoluteSelection,
                target = PatchTarget.CONTEXT_WINDOW,
                truncated = true,
            )
        }

        val surrounding = input.surrounding
        if (selection != null && surrounding != null) {
            val selectedText = selected?.takeIf { it.readSucceeded }?.text.orEmpty()
            if (selectedText.length != selection.end - selection.start) {
                return unavailable(
                    input,
                    EditorCaptureUnavailableReason.INCONSISTENT_SELECTION,
                )
            }
            val startOffset = selection.start - surrounding.beforeCursor.length
            val text =
                surrounding.beforeCursor + selectedText + surrounding.afterCursor
            if (startOffset < 0) {
                return unavailable(input, EditorCaptureUnavailableReason.INVALID_EXTRACTED_TEXT)
            }
            val boundedContext = EditorContextWindowPolicy.constrain(
                context = ExtractedEditorText(
                    text = text,
                    startOffset = startOffset,
                    selectionStartInText = surrounding.beforeCursor.length,
                    selectionEndInText =
                        surrounding.beforeCursor.length + selectedText.length,
                    completeDocument = false,
                ),
                maxContextChars = minOf(
                    EditorContextWindowPolicy.DEFAULT_MAX_CONTEXT_CHARS,
                    input.maxOutputChars,
                ),
            )
            if (boundedContext.text.length > SenseAiProtocol.ABSOLUTE_MAX_SNAPSHOT_CHARS) {
                return unavailable(input, EditorCaptureUnavailableReason.TEXT_LIMIT_EXCEEDED)
            }
            return captured(
                input = input,
                capability = SnapshotCapability.SURROUNDING_WINDOW,
                text = boundedContext.text,
                textStartOffset = boundedContext.startOffset,
                selection = boundedContext.absoluteSelection,
                target = PatchTarget.CONTEXT_WINDOW,
                truncated = true,
            )
        }

        return unavailable(input, fallbackReason)
    }

    private fun validateExtracted(
        extracted: ExtractedEditorText,
        currentSelection: TextSelectionV1?,
        selected: SelectedEditorText?,
    ): EditorCaptureUnavailableReason? {
        val selectionStart = extracted.selectionStartInText
        val selectionEnd = extracted.selectionEndInText
        if (
            extracted.startOffset < 0 ||
            selectionStart < 0 ||
            selectionEnd < selectionStart ||
            selectionEnd > extracted.text.length ||
            extracted.startOffset.toLong() + extracted.text.length > Int.MAX_VALUE
        ) {
            return EditorCaptureUnavailableReason.INVALID_EXTRACTED_TEXT
        }

        val absoluteSelection = extracted.absoluteSelection
        if (currentSelection != null && currentSelection != absoluteSelection) {
            return EditorCaptureUnavailableReason.INCONSISTENT_SELECTION
        }

        val expectedSelected = extracted.text.substring(selectionStart, selectionEnd)
        if (expectedSelected.isEmpty()) {
            if (
                selected?.readSucceeded == true &&
                !selected.text.isNullOrEmpty()
            ) {
                return EditorCaptureUnavailableReason.INCONSISTENT_SELECTION
            }
        } else if (
            selected?.readSucceeded != true ||
            selected.text != expectedSelected
        ) {
            return EditorCaptureUnavailableReason.INCONSISTENT_SELECTION
        }
        return null
    }

    private fun hasValidMetadata(input: EditorSnapshotCaptureInput): Boolean {
        if (input.maxOutputChars !in 1..SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS) return false
        val unavailable = unavailableSnapshot(input)
        return ProtocolValidator.validate(unavailable).isValid
    }

    private fun captured(
        input: EditorSnapshotCaptureInput,
        capability: SnapshotCapability,
        text: String,
        textStartOffset: Int,
        selection: TextSelectionV1?,
        target: PatchTarget?,
        truncated: Boolean,
    ): EditorCaptureDecision {
        val snapshot = EditorSnapshotV1(
            requestId = input.requestId,
            snapshotId = input.snapshotId,
            editorGeneration = input.editorGeneration,
            fieldIdentity = input.fieldIdentity,
            capability = capability,
            text = text,
            textStartOffset = textStartOffset,
            selection = selection,
            target = target,
            baseSha256 = EditorTextDigest.sha256Utf8(text),
            capturedAtMonotonicMs = input.capturedAtMonotonicMs,
            truncated = truncated,
            maxOutputChars = input.maxOutputChars,
        )
        return if (ProtocolValidator.validate(snapshot).isValid) {
            EditorCaptureDecision.Captured(snapshot)
        } else {
            unavailable(input, EditorCaptureUnavailableReason.INVALID_EXTRACTED_TEXT)
        }
    }

    private fun unavailable(
        input: EditorSnapshotCaptureInput,
        reason: EditorCaptureUnavailableReason,
    ): EditorCaptureDecision = EditorCaptureDecision.Unavailable(
        snapshot = unavailableSnapshot(input),
        reason = reason,
    )

    private fun unavailableSnapshot(input: EditorSnapshotCaptureInput): EditorSnapshotV1 =
        EditorSnapshotV1(
            requestId = input.requestId,
            snapshotId = input.snapshotId,
            editorGeneration = input.editorGeneration,
            fieldIdentity = input.fieldIdentity,
            capability = SnapshotCapability.UNAVAILABLE,
            text = "",
            textStartOffset = 0,
            selection = null,
            target = null,
            baseSha256 = EditorTextDigest.sha256Utf8(""),
            capturedAtMonotonicMs = input.capturedAtMonotonicMs,
            truncated = false,
            maxOutputChars = input.maxOutputChars,
        )
}
