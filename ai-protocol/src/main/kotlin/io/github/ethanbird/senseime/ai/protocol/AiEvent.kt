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

    /**
     * A short, user-facing description of current agent progress.
     *
     * This is deliberately not a reasoning or chain-of-thought channel. Producers may stream only
     * a concise public summary that is safe to show directly in the keyboard UI.
     */
    data class DescriptionDelta(
        override val requestId: String,
        override val runGeneration: Long,
        val text: String,
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

    /**
     * Atomically reconciles a regenerated retry with the preview already visible in the IME.
     *
     * This is presentation state only and can never authorize an editor mutation. [FinalPatch]
     * remains the sole model proposal that may proceed to the local editor guard.
     */
    data class PreviewReplace(
        override val requestId: String,
        override val runGeneration: Long,
        val attempt: Int,
        val text: String,
        val description: String = "",
    ) : AiEvent

    /**
     * One public, structured Agent activity update.
     *
     * This is the keyboard equivalent of an in-place Hermes status message: [stepId] identifies a
     * stable row, [state] advances that row, and tool work is correlated by [toolCallId]. The
     * payload is deliberately public-summary-only and must never contain private model reasoning.
     */
    data class AgentProgress(
        override val requestId: String,
        override val runGeneration: Long,
        val revision: Long,
        val stepId: String,
        val kind: AgentProgressKind,
        val state: AgentProgressState,
        val title: String,
        val detail: String = "",
        val toolCallId: String? = null,
        val toolName: String? = null,
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

    /**
     * A complete user-facing answer that stays inside the Agent surface.
     *
     * This payload carries no editor authority. If the user later chooses an apply action, the IME
     * creates a fresh local [EditorPatchV1] bound to its immutable request snapshot and passes it
     * through the existing editor guard.
     */
    data class FinalAnswer(
        override val requestId: String,
        override val runGeneration: Long,
        val text: String,
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
    THINKING,
    TOOL_RUNNING,
    GENERATING,
    VALIDATING,
    APPLYING,
}

enum class AgentProgressKind {
    OBSERVATION,
    CONNECTION,
    THINKING,
    DRAFTING,
    ASSISTANT_UPDATE,
    TOOL,
    VALIDATION,
    APPLICATION,
    HEARTBEAT,
    RECOVERY,
}

enum class AgentProgressState {
    RUNNING,
    COMPLETED,
    FAILED,
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
    IPC_ENVELOPE_TOO_LARGE,
    PROTOCOL_INVALID,
    FIRST_EVENT_TIMEOUT,
    STREAM_IDLE_TIMEOUT,
    TOTAL_TIMEOUT,
    PROVIDER_NOT_CONFIGURED,
    PROVIDER_AUTHENTICATION,
    PROVIDER_QUOTA,
    PROVIDER_CONFIGURATION,
    PROVIDER_RATE_LIMIT,
    PROVIDER_UNAVAILABLE,
    PROVIDER_CONTENT_FILTER,
    PROVIDER_FAILURE,
    INTERNAL_FAILURE,
    EVENT_LIMIT_EXCEEDED,
    PREVIEW_LIMIT_EXCEEDED,
    DESCRIPTION_LIMIT_EXCEEDED,
    OUTPUT_TRUNCATED,
    REPAIR_LIMIT_EXCEEDED,
    INVALID_EVENT,
}

val AiEvent.isTerminal: Boolean
    get() =
        this is AiEvent.FinalPatch ||
            this is AiEvent.FinalAnswer ||
            this is AiEvent.Cancelled ||
            this is AiEvent.Failed
