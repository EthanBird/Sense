package io.github.ethanbird.senseime.service.ai.editor

import io.github.ethanbird.senseime.ai.protocol.EditorPatchV1
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.PatchOperationV1
import io.github.ethanbird.senseime.ai.protocol.SelectionAfter

/** Creates a locally-authorized editor proposal from a user-selected Agent answer action. */
internal object AssistantResultPatchFactory {
    fun create(request: HarnessRequestV1, answer: String): EditorPatchV1? {
        val target = request.snapshot.target ?: return null
        if (answer.isBlank() || answer.length > request.maxOutputChars) return null
        return EditorPatchV1(
            requestId = request.requestId,
            snapshotId = request.snapshot.snapshotId,
            baseSha256 = request.snapshot.baseSha256,
            intent = request.skill,
            operation = PatchOperationV1(
                type = PatchOperationType.REPLACE,
                target = target,
                text = answer,
                selectionAfter = SelectionAfter.END,
            ),
        )
    }
}
