package io.github.ethanbird.senseime.brain.api

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1

data class BrainRunSpec(
    val harnessRequest: HarnessRequestV1,
    val provider: ProviderProfile,
    val credential: ProviderCredential,
    /**
     * Exact user-enabled tool set captured by the private Brain service at run admission.
     *
     * Tool availability is deliberately not part of the editor/Binder protocol. The model only
     * sees tools in this set, and the Brain router checks the same frozen set again before
     * execution.
     */
    val enabledTools: Set<AgentToolId> = emptySet(),
    /**
     * Private execution trace used by the complete-history journal.
     *
     * This sink never crosses Binder and is orthogonal to the public [BrainEventSink].
     */
    val traceSink: BrainTraceSink = BrainTraceSink.NONE,
)

fun interface BrainEventSink {
    fun onEvent(event: AiEvent)
}

/**
 * A request-scoped gate owned by the calling Service/Binder session.
 *
 * `cancel` is synchronous: once it returns, later network callbacks cannot produce a final patch.
 * Callers should invoke [tick] from their monotonic timeout scheduler.
 */
interface BrainRunHandle {
    val requestId: String
    val runGeneration: Long
    val isTerminal: Boolean

    fun tick()

    fun cancel(reason: HarnessCancelReason)
}

fun interface MonotonicClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = MonotonicClock { System.nanoTime() / 1_000_000L }
    }
}
