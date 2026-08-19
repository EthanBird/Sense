package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHubProjectionCodecTest {
    @Test
    fun roundTripsEveryImeVisibleProjectionField() {
        val source = AgentHubProjection(
            revision = 73,
            conversationRevision = 7,
            loaded = true,
            messages = listOf(
                AgentHubMessage(AgentHubMessageRole.USER, "hello", 11),
                AgentHubMessage(AgentHubMessageRole.ASSISTANT, "world", 12),
            ),
            tools = listOf(
                AgentHubToolStep(
                    id = "step",
                    toolCallId = "call",
                    toolName = "terminal_exec",
                    title = "Terminal",
                    detail = "running",
                    state = AgentHubToolState.RUNNING,
                ),
            ),
            preview = "live preview",
            status = "working",
            running = true,
            requestId = "request-1",
            generation = 19,
            inputTokens = 20,
            outputTokens = 21,
            conversations = listOf(
                AgentHubConversationSummary("current", "Title", "Preview", 22, 2, true),
            ),
            action = AgentHubActionCard(
                requestId = "action-request",
                skillId = "xauusd.quote",
                title = "Gold",
                primaryValue = "2400",
                secondaryValue = "+1%",
                insertText = "XAUUSD 2400",
                sourceLabel = "provider",
                state = AgentHubActionState.SUCCEEDED,
                detail = "done",
            ),
            actionSkillsEnabled = false,
            messageTotalCount = 2,
            conversationTotalCount = 1,
        )

        val encoded = AgentHubProjectionCodec.encode(source)
        val decoded = AgentHubProjectionCodec.decode(encoded)

        assertEquals(source, decoded)
        assertTrue(encoded.size <= AgentHubProjectionCodec.MAX_WIRE_BYTES)
    }

    @Test
    fun wireProjectionKeepsOnlyNewestBoundedMessagesWithoutChangingDurableSource() {
        val sourceMessages = List(80) { index ->
            AgentHubMessage(
                role = if (index % 2 == 0) AgentHubMessageRole.USER else AgentHubMessageRole.ASSISTANT,
                text = if (index == 79) {
                    "完整回答".repeat(2_048) // 8,192 Chinese chars; Hub assistant maximum.
                } else {
                    "$index:" + "界".repeat(20_000)
                },
                createdAtEpochMs = index.toLong(),
            )
        }
        val source = AgentHubProjection(
            revision = 9,
            loaded = true,
            messages = sourceMessages,
            preview = "流式预览".repeat(2_048),
            status = "状态".repeat(2_000),
            tools = List(40) { index ->
                AgentHubToolStep(
                    id = "id-$index",
                    toolCallId = "call-$index",
                    toolName = "terminal_exec",
                    title = "t".repeat(2_000),
                    detail = "d".repeat(20_000),
                    state = AgentHubToolState.COMPLETED,
                )
            },
            conversations = List(40) { index ->
                AgentHubConversationSummary(
                    id = "c-$index",
                    title = "title".repeat(1_000),
                    preview = "preview".repeat(2_000),
                    updatedAtEpochMs = index.toLong(),
                    messageCount = index,
                    current = index == 0,
                )
            },
            action = AgentHubActionCard(
                requestId = "action-request",
                skillId = "action-skill",
                title = "标题".repeat(2_000),
                primaryValue = "主要值".repeat(10_000),
                secondaryValue = "次要值".repeat(10_000),
                insertText = "插入".repeat(20_000),
                sourceLabel = "来源".repeat(2_000),
                state = AgentHubActionState.SUCCEEDED,
                detail = "详情".repeat(10_000),
            ),
        )

        val encoded = AgentHubProjectionCodec.encode(source)
        val decoded = AgentHubProjectionCodec.decode(encoded)

        assertTrue(encoded.size <= AgentHubProjectionCodec.MAX_WIRE_BYTES)
        assertEquals(AgentHubProjectionCodec.MAX_WIRE_MESSAGES, decoded.messages.size)
        assertEquals(52, decoded.messageWindowStart)
        assertEquals(80, decoded.messageTotalCount)
        assertEquals(52L, decoded.messages.first().createdAtEpochMs)
        assertEquals(79L, decoded.messages.last().createdAtEpochMs)
        assertTrue(decoded.messages.first().text.endsWith("…"))
        assertTrue(decoded.messages.first().wireTruncated)
        assertTrue(decoded.messages.first().text.toByteArray(Charsets.UTF_8).size <= 3 * 1024)
        assertEquals(sourceMessages.last().text, decoded.messages.last().text)
        assertFalse(decoded.messages.last().wireTruncated)
        assertEquals(source.preview, decoded.preview)
        assertTrue(decoded.status.endsWith("…"))
        assertEquals(AgentHubProjectionCodec.MAX_WIRE_TOOLS, decoded.tools.size)
        assertEquals("id-28", decoded.tools.first().id)
        assertEquals(AgentHubProjectionCodec.MAX_WIRE_CONVERSATIONS, decoded.conversations.size)
        assertEquals(0, decoded.conversationWindowStart)
        assertEquals(40, decoded.conversationTotalCount)
        assertTrue(requireNotNull(decoded.action).insertText.endsWith("…"))
        assertEquals(80, source.messages.size)
        assertTrue(source.messages.first().text.length > decoded.messages.first().text.length)
    }

    @Test
    fun decoderRejectsOversizedOrTrailingPayloads() {
        val valid = AgentHubProjectionCodec.encode(AgentHubProjection(revision = 1))
        val trailing = valid + byteArrayOf(1)
        val oversized = ByteArray(AgentHubProjectionCodec.MAX_WIRE_BYTES + 1)

        assertFalse(runCatching { AgentHubProjectionCodec.decode(trailing) }.isSuccess)
        assertFalse(runCatching { AgentHubProjectionCodec.decode(oversized) }.isSuccess)
    }
}
