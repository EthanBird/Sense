package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHubHistoryProtocolTest {
    @Test
    fun pagesAndReassemblesAllRetainedMessagesWithoutProjectionTruncation() {
        val source = List(80) { index ->
            AgentHubMessage(
                role = if (index % 2 == 0) {
                    AgentHubMessageRole.USER
                } else {
                    AgentHubMessageRole.ASSISTANT
                },
                text = if (index == 5) {
                    "\u5b8c\u6574\u56de\u7b54".repeat(2_048)
                } else {
                    "message-$index-" + "x".repeat(index)
                },
                createdAtEpochMs = index.toLong(),
            )
        }
        val recovered = arrayOfNulls<AgentHubMessage>(source.size)
        var cursor: Int? = source.size
        var requestIndex = 0

        while (cursor != null) {
            val request = AgentHubHistoryRequest(
                requestId = "request-${requestIndex++}",
                conversationId = SenseAgentHubRuntime.CURRENT_CONVERSATION_ID,
                expectedConversationRevision = 17,
                beforeIndexExclusive = cursor,
            )
            val requestBytes = AgentHubHistoryCodec.encodeRequest(request)
            assertTrue(requestBytes.size < 256 * 1024)
            assertEquals(request, AgentHubHistoryCodec.decodeRequest(requestBytes))

            val page = AgentHubHistoryPager.page(request, 17, source)
            val pageBytes = AgentHubHistoryCodec.encodePage(page)
            assertTrue(pageBytes.size < 256 * 1024)
            val decoded = AgentHubHistoryCodec.decodePage(pageBytes)
            assertEquals(AgentHubHistoryStatus.OK, decoded.status)
            decoded.messages.forEachIndexed { offset, message ->
                assertFalse(message.wireTruncated)
                recovered[decoded.startIndex + offset] = message
            }
            cursor = decoded.nextBeforeIndexExclusive
        }

        assertEquals(source, recovered.toList())
        assertNull(cursor)
    }

    @Test
    fun exactRevisionFenceRejectsStaleHistoryWithoutReturningMixedMessages() {
        val request = AgentHubHistoryRequest("request", "archive", 8, 1)
        val page = AgentHubHistoryPager.page(
            request = request,
            conversationRevision = 9,
            messages = listOf(AgentHubMessage(AgentHubMessageRole.USER, "old", 1)),
        )

        assertEquals(AgentHubHistoryStatus.STALE_REVISION, page.status)
        assertTrue(page.messages.isEmpty())
        assertNull(page.nextBeforeIndexExclusive)
    }

    @Test
    fun conversationCatalogPagesAllFortyArchivesPlusCurrent() {
        val source = List(41) { index ->
            AgentHubConversationSummary(
                id = if (index == 0) SenseAgentHubRuntime.CURRENT_CONVERSATION_ID else "archive-$index",
                title = "Title $index",
                preview = "Preview $index",
                updatedAtEpochMs = 1_000L - index,
                messageCount = index + 1,
                current = index == 0,
            )
        }
        val recovered = mutableListOf<AgentHubConversationSummary>()
        var start: Int? = 0

        while (start != null) {
            val request = AgentHubConversationPageRequest("catalog-$start", 23, start)
            val requestBytes = AgentHubHistoryCodec.encodeConversationRequest(request)
            assertTrue(requestBytes.size < 256 * 1024)
            assertEquals(request, AgentHubHistoryCodec.decodeConversationRequest(requestBytes))

            val page = AgentHubConversationPager.page(request, 23, source)
            val pageBytes = AgentHubHistoryCodec.encodeConversationPage(page)
            assertTrue(pageBytes.size < 256 * 1024)
            val decoded = AgentHubHistoryCodec.decodeConversationPage(pageBytes)
            assertEquals(recovered.size, decoded.startIndex)
            recovered += decoded.conversations
            start = decoded.nextStartIndex
        }

        assertEquals(source, recovered)
        assertEquals(40, recovered.count { !it.current })
    }

    @Test
    fun conversationCatalogUsesTheSameRevisionFence() {
        val page = AgentHubConversationPager.page(
            request = AgentHubConversationPageRequest("request", 3, 0),
            conversationRevision = 4,
            conversations = emptyList(),
        )

        assertEquals(AgentHubHistoryStatus.STALE_REVISION, page.status)
        assertTrue(page.conversations.isEmpty())
    }

    @Test
    fun codecsRejectTrailingOrOverBudgetBinderPayloads() {
        val request = AgentHubHistoryCodec.encodeRequest(
            AgentHubHistoryRequest("request", "current", 1, 0),
        )
        assertFalse(
            runCatching {
                AgentHubHistoryCodec.decodeRequest(request + byteArrayOf(0))
            }.isSuccess,
        )
        assertFalse(
            runCatching {
                AgentHubHistoryCodec.decodePage(ByteArray(AgentHubHistoryCodec.MAX_PAGE_BYTES + 1))
            }.isSuccess,
        )
        assertTrue(AgentHubProjectionCodec.MAX_WIRE_BYTES < 256 * 1024)
        assertTrue(AgentHubHistoryCodec.MAX_PAGE_BYTES < 256 * 1024)
        assertTrue(AgentHubHistoryCodec.MAX_CONVERSATION_PAGE_BYTES < 256 * 1024)
    }
}
