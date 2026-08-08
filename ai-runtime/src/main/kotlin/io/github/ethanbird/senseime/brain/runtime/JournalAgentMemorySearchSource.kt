package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.memory.AgentEventJournal
import io.github.ethanbird.senseime.brain.memory.AgentMemorySearchAccess
import io.github.ethanbird.senseime.brain.memory.AgentMemorySearchBounds

/** Adapts the durable complete-event journal to the model-facing memory tool. */
internal class JournalAgentMemorySearchSource(
    private val actionHistory: () -> ActionHistoryStore? = { null },
    private val journal: () -> AgentEventJournal?,
) : AgentMemorySearchSource {
    override fun search(
        query: String,
        maxResults: Int,
        excludeRequestId: String?,
        excludeRunGeneration: Long?,
    ): List<AgentMemorySearchHit> = searchPage(
        query = query,
        maxResults = maxResults,
        excludeRequestId = excludeRequestId,
        excludeRunGeneration = excludeRunGeneration,
    ).hits

    override fun searchPage(
        query: String,
        maxResults: Int,
        excludeRequestId: String?,
        excludeRunGeneration: Long?,
    ): AgentMemorySearchPage {
        val journalSource = journal()
        val result = journalSource?.search(
                query = query,
                access = AgentMemorySearchAccess.ENABLED,
                bounds = AgentMemorySearchBounds(maxResults = (maxResults * 2).coerceAtMost(50)),
                excludeRequestId = excludeRequestId,
                excludeRunGeneration = excludeRunGeneration,
            )
        val journalHits = result?.hits.orEmpty().map { hit ->
            val channel = hit.attributes["memory_channel"] ?: "session_evidence"
            val sourceRecordIds = hit.attributes["source_record_ids"]
                ?.split(',')
                ?.filter(String::isNotBlank)
                .orEmpty()
                .ifEmpty { listOf("journal:${hit.sequence}") }
            AgentMemorySearchHit(
                id = "journal:${hit.sequence}",
                text = hit.excerpt,
                source = "${hit.kind.name}:${hit.requestId}",
                channel = channel,
                evidenceRecordIds = sourceRecordIds,
            )
        }
        val actionSource = actionHistory()
        val actionPage = actionSource?.search(query, maxResults.coerceAtMost(4))
            ?: ActionHistorySearchPage(emptyList(), 0, 0L, false)
        val actionHits = actionPage.hits
            .filterNot { hit ->
                excludeRequestId != null && hit.requestId == excludeRequestId
            }
            .map { hit ->
                AgentMemorySearchHit(
                    id = hit.id,
                    text = hit.text,
                    source = hit.source,
                    channel = "action_skill_history",
                    evidenceRecordIds = listOf(hit.id),
                )
            }
        val reservedActionSlots = actionHits.size.coerceAtMost(minOf(2, maxResults))
        val journalSlots = maxResults - reservedActionSlots
        val rawHits = journalHits.filter { it.channel == "session_evidence" }
        val semanticHits = journalHits.filter { it.channel != "session_evidence" }
        val rawQuota = rawHits.size.coerceAtMost(if (journalSlots > 0) (journalSlots + 1) / 2 else 0)
        val semanticQuota = semanticHits.size.coerceAtMost(journalSlots - rawQuota)
        val selectedJournal = buildList {
            addAll(rawHits.take(rawQuota))
            addAll(semanticHits.take(semanticQuota))
            val selectedIds = map(AgentMemorySearchHit::id).toSet()
            addAll(
                journalHits.filterNot { it.id in selectedIds }
                    .take(journalSlots - size),
            )
        }
        val hits = (selectedJournal + actionHits.take(reservedActionSlots))
            .distinctBy(AgentMemorySearchHit::id)
        return AgentMemorySearchPage(
            hits = hits,
            coverage = AgentMemorySearchCoverage(
                scannedRecords = (result?.scannedRecords ?: 0) + actionPage.scannedRecords,
                scannedBytes = (result?.scannedBytes ?: 0L) + actionPage.scannedBytes,
                truncated = (result?.truncated ?: false) || actionPage.truncated,
                channels = buildSet {
                    if (journalSource != null) {
                        add("session_evidence")
                        add("experience_event")
                    }
                    if (actionSource != null) add("action_skill_history")
                },
            ),
        )
    }
}
