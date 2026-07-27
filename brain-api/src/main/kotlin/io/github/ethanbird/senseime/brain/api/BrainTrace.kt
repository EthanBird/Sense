package io.github.ethanbird.senseime.brain.api

/**
 * Complete request-scoped execution trace emitted inside the private Brain process.
 *
 * These events are not sent to the keyboard UI. They exist so the product journal can retain the
 * exact Provider and Tool path, including private model output, without trying to reconstruct it
 * from public progress messages.
 */
sealed interface BrainTraceEvent {
    val requestId: String
    val runGeneration: Long

    data class ProviderInput(
        override val requestId: String,
        override val runGeneration: Long,
        val attempt: Int,
        val endpoint: String,
        val body: String,
    ) : BrainTraceEvent

    data class ProviderOutput(
        override val requestId: String,
        override val runGeneration: Long,
        val attempt: Int,
        val bytes: ByteArray,
    ) : BrainTraceEvent

    data class ProviderOpened(
        override val requestId: String,
        override val runGeneration: Long,
        val attempt: Int,
        val statusCode: Int,
        val contentType: String?,
    ) : BrainTraceEvent

    data class ProviderCompleted(
        override val requestId: String,
        override val runGeneration: Long,
        val attempt: Int,
    ) : BrainTraceEvent

    data class ProviderFailed(
        override val requestId: String,
        override val runGeneration: Long,
        val attempt: Int,
        val kind: ProviderFailureKind,
        val statusCode: Int?,
        val message: String,
    ) : BrainTraceEvent

    data class ToolCall(
        override val requestId: String,
        override val runGeneration: Long,
        val callId: String,
        val toolName: String,
        val arguments: String,
        val privateReasoning: String,
        val assistantContent: String,
    ) : BrainTraceEvent

    data class ToolResult(
        override val requestId: String,
        override val runGeneration: Long,
        val callId: String,
        val toolName: String,
        val content: String,
        val isError: Boolean,
    ) : BrainTraceEvent
}

fun interface BrainTraceSink {
    /**
     * Implementations may throw when durable retention fails. The Engine then terminates the Run
     * instead of silently continuing with an incomplete history.
     */
    fun onTrace(event: BrainTraceEvent)

    companion object {
        val NONE = BrainTraceSink {}
    }
}
