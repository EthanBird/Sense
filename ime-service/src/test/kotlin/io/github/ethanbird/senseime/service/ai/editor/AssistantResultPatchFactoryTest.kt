package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SelectionAfter
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantResultPatchFactoryTest {
    @Test
    fun `creates patch identity only from the immutable local request`() {
        val request = request()

        val patch = requireNotNull(AssistantResultPatchFactory.create(request, "最终回答"))

        assertEquals(request.requestId, patch.requestId)
        assertEquals(request.snapshot.snapshotId, patch.snapshotId)
        assertEquals(request.snapshot.baseSha256, patch.baseSha256)
        assertEquals(EditorIntent.ANSWER, patch.intent)
        assertEquals(PatchOperationType.REPLACE, patch.operation.type)
        assertEquals(PatchTarget.SELECTION, patch.operation.target)
        assertEquals("最终回答", patch.operation.text)
        assertEquals(SelectionAfter.END, patch.operation.selectionAfter)
    }

    @Test
    fun `rejects empty oversized or targetless result actions`() {
        val request = request()

        assertNull(AssistantResultPatchFactory.create(request, "   "))
        assertNull(AssistantResultPatchFactory.create(request, "x".repeat(33)))
        assertNull(
            AssistantResultPatchFactory.create(
                request.copy(snapshot = request.snapshot.copy(target = null)),
                "回答",
            ),
        )
    }

    private fun request(): HarnessRequestV1 {
        val text = "请回答这个问题"
        return HarnessRequestV1(
            requestId = "request-local",
            runGeneration = 3,
            skill = EditorIntent.ANSWER,
            snapshot = EditorSnapshotV1(
                requestId = "request-local",
                snapshotId = "snapshot-local",
                editorGeneration = 4,
                fieldIdentity = "field-local",
                capability = SnapshotCapability.FULL_DOCUMENT,
                text = text,
                selection = TextSelectionV1(0, text.length),
                target = PatchTarget.SELECTION,
                baseSha256 = EditorTextDigest.sha256Utf8(text),
                capturedAtMonotonicMs = 0,
                truncated = false,
                maxOutputChars = 32,
            ),
            maxOutputChars = 32,
        )
    }
}
