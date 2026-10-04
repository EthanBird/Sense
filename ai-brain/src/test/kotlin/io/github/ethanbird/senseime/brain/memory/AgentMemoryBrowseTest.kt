package io.github.ethanbird.senseime.brain.memory

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AgentMemoryBrowseTest {
    @Test
    fun listingSurvivesRestartAndConcurrentAppendsWithoutRepeatingRecords() {
        val directory = Files.createTempDirectory("sense-browse").toFile()
        try {
            AgentEventJournal.open(directory).use { journal ->
                val run = journal.beginRun("old", 1, byteArrayOf(), "text/plain", "用户喜欢海军蓝")
                run.appendText(AgentJournalKind.FINAL, "已记住颜色偏好")
            }
            AgentEventJournal.open(directory).use { journal ->
                val first = journal.browse(AgentMemorySearchAccess.ENABLED, AgentMemorySearchBounds(maxResults = 1))
                assertEquals(listOf(2L), first.records.map { it.sequence })
                assertNotNull(first.nextOffset)
                journal.beginRun("new", 2, byteArrayOf(), "text/plain", "new append")
                val second = journal.browse(AgentMemorySearchAccess.ENABLED,
                    AgentMemorySearchBounds(maxResults = 1), nextOffset = first.nextOffset)
                assertEquals(listOf(1L), second.records.map { it.sequence })
                assertNull(second.nextOffset)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun emptyBoundedPageHasContinuationAndTraceIsOptIn() {
        val directory = Files.createTempDirectory("sense-browse-filter").toFile()
        try {
            AgentEventJournal.open(directory).use { journal ->
                val run = journal.beginRun("old", 1, byteArrayOf(), "text/plain", "原始用户记录")
                run.appendText(AgentJournalKind.EXPERIENCE_EVENT, "用户偏好简洁回答")
                run.appendText(AgentJournalKind.PROVIDER_OUTPUT, "low-level trace")
                val bounds = AgentMemorySearchBounds(maxResults = 1, maxScannedRecords = 1)
                val first = journal.browse(AgentMemorySearchAccess.ENABLED, bounds, channel = "experience_event")
                assertTrue(first.records.isEmpty())
                assertTrue(first.scanLimitReached)
                assertNotNull(first.nextOffset)
                val second = journal.browse(AgentMemorySearchAccess.ENABLED, bounds,
                    nextOffset = first.nextOffset, channel = "experience_event")
                assertEquals(AgentJournalKind.EXPERIENCE_EVENT, second.records.single().kind)
                val trace = journal.browse(AgentMemorySearchAccess.ENABLED, bounds, includeTrace = true)
                assertEquals(AgentJournalKind.PROVIDER_OUTPUT, trace.records.single().kind)
                val excluded = journal.browse(AgentMemorySearchAccess.ENABLED,
                    excludeRequestId = "old", excludeRunGeneration = 1)
                assertTrue(excluded.records.isEmpty())
                assertNull(excluded.nextOffset)
                val disabled = journal.browse(AgentMemorySearchAccess.DISABLED)
                assertEquals(0, disabled.scannedRecords)
                assertEquals(0L, disabled.scannedBytes)
            }
        } finally { directory.deleteRecursively() }
    }
}
