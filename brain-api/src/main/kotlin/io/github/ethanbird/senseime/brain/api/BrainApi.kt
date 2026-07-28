package io.github.ethanbird.senseime.brain.api

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1

data class BrainRunSpec(
    val harnessRequest: HarnessRequestV1,
    val provider: ProviderProfile,
    val credential: ProviderCredential,
    /**
     * Compact, generation-frozen Skill discovery catalog. Full text for the selected Skill lives
     * in [HarnessRequestV1.activeSkill]; other documents are loaded explicitly with skill_read.
     */
    val skillCatalog: List<AgentSkillSummary> = emptyList(),
    /**
     * Immutable catalog generation paired with [skillCatalog].
     *
     * Brain only exposes `skill_manage` when this token is present, and every mutation must echo
     * it. This prevents a stale model turn from overwriting a newer settings or Agent mutation.
     */
    val skillCatalogGeneration: Long? = null,
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
) {
    init {
        require(skillCatalogGeneration == null || skillCatalogGeneration > 0L)
        require(skillCatalog.size <= AgentSkillPolicy.MAX_SKILLS)
        require(skillCatalog.map(AgentSkillSummary::id).toSet().size == skillCatalog.size) {
            "Duplicate Skill ids in Brain run catalog"
        }
        require(skillCatalog.isEmpty() || skillCatalogGeneration != null) {
            "Skill summaries require an exact catalog generation"
        }
        harnessRequest.skillCatalogGeneration?.let { requestGeneration ->
            require(requestGeneration == skillCatalogGeneration) {
                "Harness and Brain Skill catalog generations differ"
            }
        }
        harnessRequest.activeSkill?.let { activeSkill ->
            require(activeSkill.catalogGeneration == skillCatalogGeneration) {
                "Active Skill and Brain Skill catalog generations differ"
            }
        }
    }
}

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
