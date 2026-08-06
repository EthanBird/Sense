package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationSummaryTest {
    @Test
    fun currentAndArchivedConversationsRemainVisibleAsCompleteHistoryEntries() {
        val current = listOf(
            AgentHubMessage(AgentHubMessageRole.USER, "当前任务第一行\n更多内容", 10),
            AgentHubMessage(AgentHubMessageRole.ASSISTANT, "当前回答", 11),
        )
        val archived = AgentHubConversationArchive(
            id = "12345678-1234-1234-1234-123456789abc",
            messages = listOf(
                AgentHubMessage(AgentHubMessageRole.USER, "旧任务", 1),
                AgentHubMessage(AgentHubMessageRole.ASSISTANT, "旧回答", 2),
            ),
        )

        val summaries = conversationSummaries(current, listOf(archived))

        assertEquals(2, summaries.size)
        assertEquals("当前任务第一行", summaries[0].title)
        assertEquals("当前回答", summaries[0].preview)
        assertTrue(summaries[0].current)
        assertEquals("旧任务", summaries[1].title)
        assertFalse(summaries[1].current)
    }
}
