package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.memory.AgentEventJournal
import io.github.ethanbird.senseime.brain.memory.AgentMemorySearchAccess
import io.github.ethanbird.senseime.brain.memory.AgentMemorySearchBounds

/** Adapts the durable complete-event journal to the model-facing memory tool. */
internal class JournalAgentMemorySearchSource(
    private val journal: () -> AgentEventJournal?,
) : AgentMemorySearchSource {
    override fun search(
        query: String,
        maxResults: Int,
        excludeRequestId: String?,
        excludeRunGeneration: Long?,
    ): List<AgentMemorySearchHit> {
        val source = journal() ?: return emptyList()
        return source.search(
            query = query,
            access = AgentMemorySearchAccess.ENABLED,
            bounds = AgentMemorySearchBounds(maxResults = maxResults),
            excludeRequestId = excludeRequestId,
            excludeRunGeneration = excludeRunGeneration,
        ).hits.map { hit ->
            AgentMemorySearchHit(
                id = hit.sequence.toString(),
                text = hit.excerpt,
                source = "${hit.kind.name}:${hit.requestId}",
            )
        }
    }
}
