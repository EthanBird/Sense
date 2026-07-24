package io.github.ethanbird.senseime.ai.protocol

/**
 * Provider-neutral events emitted by one bounded AI editing run.
 *
 * Every event is bound to a locally-created request and generation. Neither value is sourced from
 * model output. Consumers must reject events that do not match their active generation before
 * inspecting a patch or rendering a preview.
 */
sealed interface AiEvent {
    val requestId: String
    val runGeneration: Long

    data class Started(
        override val requestId: String,
        override val runGeneration: Long,
        val startedAtMonotonicMs: Long,
    ) : AiEvent

    data class Status(
        override val requestId: String,
        override val runGeneration: Long,
        val phase: HarnessPhase,
        val label: String,
    ) : AiEvent

    data class PreviewReset(
        override val requestId: String,
        override val runGeneration: Long,
        val attempt: Int,
    ) : AiEvent

    data class PreviewDelta(
        override val requestId: String,
        override val runGeneration: Long,
        val text: String,
    ) : AiEvent

    data class Usage(
        override val requestId: String,
        override val runGeneration: Long,
        val inputTokens: Long,
        val outputTokens: Long,
    ) : AiEvent

    /**
     * A validated proposal, not permission to mutate an editor.
     *
     * The IME still has to check pointer ownership, editor generation, snapshot hash, and selection
     * immediately before applying [patch].
     */
    data class FinalPatch(
        override val requestId: String,
        override val runGeneration: Long,
        val patch: EditorPatchV1,
    ) : AiEvent

    data class Cancelled(
        override val requestId: String,
        override val runGeneration: Long,
        val reason: HarnessCancelReason,
    ) : AiEvent

    data class Failed(
        override val requestId: String,
        override val runGeneration: Long,
        val code: HarnessErrorCode,
        val retryable: Boolean = false,
    ) : AiEvent
}

enum class HarnessPhase {
    CONNECTING,
    UNDERSTANDING,
    GENERATING,
    VALIDATING,
}

enum class HarnessCancelReason {
    POINTER_RELEASED,
    POINTER_CANCELLED,
    EDITOR_CHANGED,
    INPUT_CONNECTION_LOST,
    WINDOW_HIDDEN,
    CONFIGURATION_CHANGED,
    BRAIN_DIED,
    CALLER_REQUESTED,
}

enum class HarnessErrorCode {
    REQUEST_INVALID,
    PROTOCOL_INVALID,
    FIRST_EVENT_TIMEOUT,
    STREAM_IDLE_TIMEOUT,
    TOTAL_TIMEOUT,
    PROVIDER_FAILURE,
    INTERNAL_FAILURE,
    EVENT_LIMIT_EXCEEDED,
    PREVIEW_LIMIT_EXCEEDED,
    REPAIR_LIMIT_EXCEEDED,
    INVALID_EVENT,
}

val AiEvent.isTerminal: Boolean
    get() = this is AiEvent.FinalPatch || this is AiEvent.Cancelled || this is AiEvent.Failed
