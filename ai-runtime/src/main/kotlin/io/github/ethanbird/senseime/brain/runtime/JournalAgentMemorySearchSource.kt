package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.memory.AgentEventJournal
import io.github.ethanbird.senseime.brain.memory.AgentMemorySearchAccess
import io.github.ethanbird.senseime.brain.memory.AgentMemorySearchBounds
import io.github.ethanbird.senseime.brain.memory.AgentJournalKind
import io.github.ethanbird.senseime.brain.memory.AgentJournalRecord
import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import org.json.JSONObject

/** Adapts the durable complete-event journal to the model-facing memory tool. */
internal class JournalAgentMemorySearchSource(
    private val actionHistory: () -> ActionHistoryStore? = { null },
    private val journal: () -> AgentEventJournal?,
) : AgentMemorySearchSource {
    override fun browsePage(
        arguments: AgentToolArguments.MemorySearch,
        excludeRequestId: String?,
        excludeRunGeneration: Long?,
    ): AgentMemorySearchPage {
        require(arguments.mode == "list")
        val cursor = MemoryBrowseCursor.decode(arguments)
        val journalSource = journal()
        val actionSource = actionHistory()
        var journalPosition = cursor.journal
        var actionPosition = cursor.action
        val wantsJournal = arguments.channel != "action_skill_history"
        val wantsActions = arguments.channel in setOf("all", "action_skill_history")
        if (!wantsJournal || journalSource == null) {
            require(journalPosition < 0) { "Journal browse source is no longer available" }
            journalPosition = MemoryBrowseCursor.END
        }
        if (!wantsActions || actionSource == null) {
            require(actionPosition < 0) { "Action browse source is no longer available" }
            actionPosition = MemoryBrowseCursor.END
        }
        val actionReservation = if (actionPosition != MemoryBrowseCursor.END && journalPosition != MemoryBrowseCursor.END) {
            minOf(2, arguments.maxResults / 2)
        } else if (actionPosition != MemoryBrowseCursor.END) arguments.maxResults else 0
        val journalLimit = arguments.maxResults - actionReservation
        val journalPage = if (journalPosition != MemoryBrowseCursor.END && journalLimit > 0) {
            requireNotNull(journalSource).browse(
                access = AgentMemorySearchAccess.ENABLED,
                bounds = AgentMemorySearchBounds(maxResults = journalLimit, maxScannedBytes = 32L * 1024L * 1024L),
                nextOffset = journalPosition.takeIf { it >= 0 },
                channel = arguments.channel,
                includeTrace = arguments.includeTrace,
                excludeRequestId = excludeRequestId,
                excludeRunGeneration = excludeRunGeneration,
            ).also { journalPosition = it.nextOffset ?: MemoryBrowseCursor.END }
        } else null
        val journalHits = journalPage?.records.orEmpty().map { record ->
            AgentMemorySearchHit(
                id = "journal:${record.sequence}",
                text = browseExcerpt(record, arguments.includeTrace),
                source = "${record.kind.name}:${record.requestId}",
                channel = if (record.kind == AgentJournalKind.EXPERIENCE_EVENT) "experience_event" else "session_evidence",
                evidenceRecordIds = record.attributes["source_record_ids"]?.split(',')
                    ?.filter(String::isNotBlank).orEmpty().ifEmpty { listOf("journal:${record.sequence}") },
            )
        }
        val actionLimit = arguments.maxResults - journalHits.size
        val actionPage = if (actionPosition != MemoryBrowseCursor.END && actionLimit > 0) {
            requireNotNull(actionSource).browse(
                maxResults = actionLimit,
                beforeByte = actionPosition.takeIf { it >= 0 },
                includeTrace = arguments.includeTrace,
                excludeRequestId = excludeRequestId,
            ).also { actionPosition = it.nextBeforeByte ?: MemoryBrowseCursor.END }
        } else null
        val actionHits = actionPage?.hits.orEmpty().map { hit ->
            AgentMemorySearchHit(hit.id, hit.text, hit.source, "action_skill_history", listOf(hit.id))
        }
        val more = journalPosition != MemoryBrowseCursor.END || actionPosition != MemoryBrowseCursor.END
        return AgentMemorySearchPage(
            hits = journalHits + actionHits,
            nextCursor = if (more) MemoryBrowseCursor(journalPosition, actionPosition).encode(arguments) else null,
            hasMore = more,
            coverage = AgentMemorySearchCoverage(
                scannedRecords = (journalPage?.scannedRecords ?: 0) + (actionPage?.scannedRecords ?: 0),
                scannedBytes = (journalPage?.scannedBytes ?: 0L) + (actionPage?.scannedBytes ?: 0L),
                truncated = journalPage?.scanLimitReached == true || actionPage?.scanLimitReached == true,
                skippedRecords = actionPage?.skippedRecords ?: 0,
                channels = buildSet {
                    if (wantsJournal && journalSource != null) {
                        if (arguments.channel != "experience_event") add("session_evidence")
                        if (arguments.channel != "session_evidence") add("experience_event")
                    }
                    if (wantsActions && actionSource != null) add("action_skill_history")
                },
            ),
        )
    }

    private fun browseExcerpt(record: AgentJournalRecord, includeTrace: Boolean): String {
        val raw = record.lexicalText
        // Extract useful text instead of filling a listing with protocol ids before the actual message.
        if (includeTrace || raw.length > 512 * 1024) return raw.memoryExcerpt(1_000)
        val document = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.memoryExcerpt(1_000)
        val text = when (record.kind) {
            AgentJournalKind.REQUEST_INPUT_SNAPSHOT -> document.optJSONObject("snapshot")?.optString("text").orEmpty()
            AgentJournalKind.EXPERIENCE_EVENT -> buildString {
                append(document.optString("summary"))
                document.optJSONObject("facts")?.takeIf { it.length() > 0 }?.let { append("\n").append(it) }
            }
            AgentJournalKind.FINAL -> document.optString("text").ifBlank {
                document.optJSONObject("patch")?.optJSONObject("operation")?.optString("text").orEmpty()
            }
            AgentJournalKind.TOOL_RESULT -> document.optString("result")
            else -> raw
        }
        return text.ifBlank { raw }.memoryExcerpt(1_000)
    }

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

/** Stateless cursor: append-only byte positions remain stable while new runs are recorded. */
private data class MemoryBrowseCursor(val journal: Long = START, val action: Long = START) {
    fun encode(arguments: AgentToolArguments.MemorySearch): String =
        "m1.${arguments.channel}.${if (arguments.includeTrace) 1 else 0}.$journal.$action"

    companion object {
        const val START = -2L
        const val END = -1L

        fun decode(arguments: AgentToolArguments.MemorySearch): MemoryBrowseCursor {
            val token = arguments.cursor ?: return MemoryBrowseCursor()
            val fields = token.split('.')
            require(fields.size == 5 && fields[0] == "m1" && fields[1] == arguments.channel &&
                fields[2] == (if (arguments.includeTrace) "1" else "0")) {
                "Continue with the same memory channel and include_trace, or omit cursor to restart"
            }
            val journal = fields[3].toLongOrNull()
            val action = fields[4].toLongOrNull()
            require(journal != null && journal >= START && action != null && action >= START) { "Invalid memory cursor" }
            return MemoryBrowseCursor(journal, action)
        }
    }
}
