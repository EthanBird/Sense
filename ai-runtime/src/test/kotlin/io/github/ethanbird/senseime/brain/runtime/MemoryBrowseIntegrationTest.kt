package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.memory.AgentEventJournal
import io.github.ethanbird.senseime.brain.memory.AgentJournalKind
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryBrowseIntegrationTest {
    @Test
    fun executorListsRealSeededMemoryWithoutKeywordsAndExcludesItsOwnRun() {
        val directory = Files.createTempDirectory("sense-memory-list").toFile()
        try {
            AgentEventJournal.open(directory).use { journal ->
                val old = journal.beginRun("old", 1, byteArrayOf(), "text/plain", "我喜欢海军蓝")
                old.appendText(AgentJournalKind.FINAL, "记住偏好")
                old.appendText(AgentJournalKind.EXPERIENCE_EVENT, "偏好：海军蓝")
                journal.beginRun("active", 8, byteArrayOf(), "text/plain", "请列出所有记忆")
                val executor = DefaultAgentToolExecutor(memorySource = JournalAgentMemorySearchSource { journal },
                    documentLoader = { error("listing must not use the network") })
                val seen = mutableSetOf<String>()
                var cursor: String? = null
                var pages = 0
                do {
                    val result = executor.execute(AgentToolCall("memory-list", AgentToolId.MEMORY_SEARCH,
                        AgentToolArguments.MemorySearch(maxResults = 1, cursor = cursor), "active", 8))
                    assertFalse(result.content, result.isError)
                    val data = JSONObject(result.content).getJSONObject("data")
                    val records = data.getJSONArray("results")
                    for (index in 0 until records.length()) {
                        val record = records.getJSONObject(index)
                        assertTrue(seen.add(record.getString("id")))
                        assertFalse(record.getString("source").contains("active"))
                    }
                    cursor = if (data.isNull("next_cursor")) null else data.getString("next_cursor")
                    assertEquals(cursor != null, data.getBoolean("has_more"))
                    assertTrue(++pages <= 4)
                } while (cursor != null)
                assertEquals(setOf("journal:1", "journal:2", "journal:3"), seen)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun actionOnlyHistoryHasRealPaginationAndStableCursors() {
        val directory = Files.createTempDirectory("sense-action-list").toFile()
        try {
            val store = ActionHistoryStore.openForTest(directory)
            repeat(3) { index -> store.appendFailed("r$index", "gold_quote", Exception("记录 $index"), index.toLong()).getOrThrow() }
            val first = store.browse(1)
            assertEquals("r2", first.hits.single().requestId)
            store.appendFailed("new", "gold_quote", Exception("后续追加"), 4).getOrThrow()
            val second = store.browse(2, first.nextBeforeByte)
            assertEquals(listOf("r1", "r0"), second.hits.map { it.requestId })
            assertNull(second.nextBeforeByte)
            val source = JournalAgentMemorySearchSource(actionHistory = { store }, journal = { null })
            val page = source.browsePage(AgentToolArguments.MemorySearch(maxResults = 1,
                channel = "action_skill_history"), null, null)
            assertEquals("action_skill_history", page.hits.single().channel)
            assertTrue(page.hasMore == true)
            assertThrows(IllegalArgumentException::class.java) {
                source.browsePage(AgentToolArguments.MemorySearch(cursor = page.nextCursor), null, null)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun twentyLongEscapedResultsRemainCompleteJsonBelowTheBrainCap() {
        val source = object : AgentMemorySearchSource {
            override fun search(query: String, maxResults: Int, excludeRequestId: String?, excludeRunGeneration: Long?) =
                (1..20).map { AgentMemorySearchHit("journal:$it", "\"\n🧠\\".repeat(3_000),
                    "source".repeat(60), evidenceRecordIds = (1..8).map { id -> "journal:$id" }) }
        }
        val result = DefaultAgentToolExecutor(memorySource = source).execute(AgentToolCall("search",
            AgentToolId.MEMORY_SEARCH, AgentToolArguments.MemorySearch("偏好", 20)))
        assertFalse(result.isError)
        assertTrue(result.content.length < 16_384)
        assertEquals(20, JSONObject(result.content).getJSONObject("data").getJSONArray("results").length())
        assertFalse("a🧠".memoryExcerpt(2).last().isHighSurrogate())
    }
}
