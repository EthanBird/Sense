package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.memory.AgentEventJournal
import io.github.ethanbird.senseime.brain.memory.AgentJournalKind
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalAgentMemorySearchSourceTest {
    @Test
    fun `memory skill returns seeded complete-history data through production adapter`() {
        val directory = Files.createTempDirectory("sense-memory-skill-seed").toFile()
        try {
            AgentEventJournal.open(directory).use { journal ->
                val seeded = journal.beginRun(
                    requestId = "seed-profile",
                    runGeneration = 7,
                    payload = "记住：我最喜欢海军蓝，回答风格保持简洁直接。".toByteArray(),
                    contentType = "text/plain",
                    lexicalText = "记住：我最喜欢海军蓝，回答风格保持简洁直接。",
                )
                seeded.appendText(
                    AgentJournalKind.FINAL,
                    "已记录用户颜色偏好：海军蓝；表达偏好：简洁直接。",
                )
                val executor = DefaultAgentToolExecutor(
                    memorySource = JournalAgentMemorySearchSource { journal },
                )

                val result = executor.execute(
                    AgentToolCall(
                        callId = "memory-call",
                        tool = AgentToolId.MEMORY_SEARCH,
                        arguments = AgentToolArguments.MemorySearch(
                            query = "用户喜欢什么颜色",
                            maxResults = 5,
                        ),
                        requestId = "active-run",
                        runGeneration = 8,
                    ),
                )

                assertFalse(result.content, result.isError)
                assertTrue(result.content, result.content.contains("\"result_count\":"))
                assertTrue(result.content, result.content.contains("海军蓝"))
                assertTrue(result.content, result.content.contains("seed-profile"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
