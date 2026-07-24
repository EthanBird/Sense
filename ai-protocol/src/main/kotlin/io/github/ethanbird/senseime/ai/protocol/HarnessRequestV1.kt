package io.github.ethanbird.senseime.ai.protocol

/**
 * Provider-neutral input to a single bounded AI editing run.
 *
 * [runGeneration] is generated locally and is never trusted when echoed by a model. It lets
 * callers discard late provider events after cancellation or editor replacement.
 */
data class HarnessRequestV1(
    val protocol: String = SenseAiProtocol.HARNESS_REQUEST_V1,
    val requestId: String,
    val runGeneration: Long,
    val skill: EditorIntent = EditorIntent.SMART_EDIT,
    val snapshot: EditorSnapshotV1,
    val maxOutputChars: Int = snapshot.maxOutputChars,
)
