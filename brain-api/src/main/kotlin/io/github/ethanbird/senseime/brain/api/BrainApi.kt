package io.github.ethanbird.senseime.brain.api

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1

data class BrainRunSpec(
    val harnessRequest: HarnessRequestV1,
    val provider: ProviderProfile,
    val credential: ProviderCredential,
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
