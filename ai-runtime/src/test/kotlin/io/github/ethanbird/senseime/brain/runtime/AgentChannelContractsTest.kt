package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletionException

class AgentChannelContractsTest {
    @Test
    fun `slash commands bypass ordinary prompt routing`() {
        assertEquals(AgentChannelCommand.Stop, AgentChannelCommandParser.parse("/stop"))
        assertEquals(AgentChannelCommand.NewConversation, AgentChannelCommandParser.parse("/new"))
        assertEquals(AgentChannelCommand.Status, AgentChannelCommandParser.parse("/status"))
        assertEquals(
            AgentChannelCommand.SelectTarget("sense"),
            AgentChannelCommandParser.parse("/agent use sense"),
        )
        assertEquals(
            AgentChannelCommand.Prompt("总结今天的终端输出"),
            AgentChannelCommandParser.parse("总结今天的终端输出"),
        )
    }

    @Test
    fun `stop bypasses queued prompt while an external frontend owns hub run`() {
        val prompt = AgentChannelInbound(
            source = source(peer = "u-1", chat = "c-1").copy(messageId = "prompt"),
            text = "排队请求",
            receivedAtEpochMs = 1L,
        )
        val stop = AgentChannelInbound(
            source = source(peer = "u-1", chat = "c-1").copy(messageId = "stop"),
            text = "/stop",
            receivedAtEpochMs = 2L,
        )
        val state = AgentChannelJournalState(pending = listOf(prompt, stop))
        assertTrue(
            AgentChannelInterruptPolicy.canScan(
                hasChannelDelivery = false,
                hubRunning = true,
            ),
        )
        assertEquals(stop, AgentChannelDispatchQueue.firstInterrupt(state))
    }

    @Test
    fun `unbound channel accepts only exact pairing command then binds exact source`() {
        val source = source(peer = "u-1", chat = "c-1")
        assertEquals(
            AgentChannelAccessDecision.ACCEPT_PAIRING,
            AgentChannelAccessPolicy.decide(
                source,
                AgentChannelCommand.Pair("204816"),
                "204816",
                null,
                null,
            ),
        )
        assertEquals(
            AgentChannelAccessDecision.REJECT,
            AgentChannelAccessPolicy.decide(
                source,
                AgentChannelCommand.Pair("204817"),
                "204816",
                null,
                null,
            ),
        )
        assertEquals(
            AgentChannelAccessDecision.ACCEPT,
            AgentChannelAccessPolicy.decide(
                source,
                AgentChannelCommand.Prompt("hello"),
                "204816",
                "u-1",
                "c-1",
            ),
        )
        assertEquals(
            AgentChannelAccessDecision.REJECT,
            AgentChannelAccessPolicy.decide(
                source(peer = "u-2", chat = "c-1"),
                AgentChannelCommand.Prompt("hello"),
                "204816",
                "u-1",
                "c-1",
            ),
        )
    }

    @Test
    fun `latest persisted pause or endpoint disable closes dispatch race`() {
        val enabled = AgentChannelSettings(
            telegram = TelegramAgentChannelSettings(
                enabled = true,
                pairingCode = "123456",
                boundPeerId = "peer",
                boundChatId = "chat",
            ),
        )
        assertTrue(
            AgentChannelRuntimeAccessPolicy.isDispatchEnabled(enabled, AgentChannelType.TELEGRAM),
        )
        assertFalse(
            AgentChannelRuntimeAccessPolicy.isDispatchEnabled(
                enabled.copy(paused = true),
                AgentChannelType.TELEGRAM,
            ),
        )
        assertFalse(
            AgentChannelRuntimeAccessPolicy.isDispatchEnabled(
                enabled.copy(telegram = enabled.telegram.copy(enabled = false)),
                AgentChannelType.TELEGRAM,
            ),
        )
    }

    @Test
    fun `disabled active channel is terminated before next available channel head`() {
        val telegram = AgentChannelInbound(
            source = source(peer = "tg-peer", chat = "tg-chat").copy(messageId = "active"),
            text = "long task",
            receivedAtEpochMs = 1L,
        )
        val feishu = AgentChannelInbound(
            source = AgentChannelSource(
                channel = AgentChannelType.FEISHU,
                chatId = "fs-chat",
                peerId = "fs-peer",
                messageId = "next",
            ),
            text = "next task",
            receivedAtEpochMs = 2L,
        )
        val activeState = AgentChannelJournalState(
            pending = listOf(telegram, feishu),
            activeEventKey = telegram.source.eventKey,
            activeRequestId = "request-tg",
            activeGeneration = 2L,
            activeUserCreatedAtEpochMs = 3L,
            activeRunPhase = AgentChannelRunPhase.ACTIVE,
        )
        val blocked = AgentChannelUnavailableDeliveryPolicy.blockingEvent(
            activeState,
            setOf(AgentChannelType.FEISHU),
        )
        assertEquals(telegram, blocked)
        val released = AgentChannelJournalTransition.markFailed(
            state = activeState,
            eventKey = telegram.source.eventKey,
            reason = "Telegram disabled on reload",
            failedAtEpochMs = 4L,
        )
        assertEquals(feishu, AgentChannelDispatchQueue.head(released))
        assertEquals(null, released.activeEventKey)
        assertEquals(telegram.source.eventKey, released.failedDeliveries.single().eventKey)
        assertEquals(
            null,
            AgentChannelUnavailableDeliveryPolicy.blockingEvent(
                released,
                setOf(AgentChannelType.FEISHU),
            ),
        )
    }

    @Test
    fun `control event without adapter is terminally parked instead of retried forever`() {
        val status = AgentChannelInbound(
            source = source(peer = "peer", chat = "chat").copy(messageId = "status"),
            text = "/status",
            receivedAtEpochMs = 5L,
        )
        val state = AgentChannelJournalState(pending = listOf(status))
        assertEquals(
            status,
            AgentChannelUnavailableDeliveryPolicy.blockingEvent(
                state,
                setOf(AgentChannelType.FEISHU),
            ),
        )
        val parked = AgentChannelJournalTransition.markFailed(
            state,
            status.source.eventKey,
            "Telegram adapter unavailable",
            6L,
        )
        assertTrue(parked.pending.isEmpty())
        assertEquals(AgentChannelType.TELEGRAM, parked.failedDeliveries.single().channel)
    }

    @Test
    fun `stream updates are throttled but terminal state flushes immediately`() {
        val gate = AgentChannelStreamUpdateGate(minimumIntervalMs = 900, minimumChangedChars = 4)
        assertTrue(gate.shouldSend("正在处理", 1_000))
        assertFalse(gate.shouldSend("正在处理。", 2_000))
        assertFalse(gate.shouldSend("正在处理更多内容", 1_200))
        assertTrue(gate.shouldSend("正在处理更多内容", 2_100))
        assertTrue(gate.shouldSend("完成", 2_101, terminal = true))
    }

    @Test
    fun `event and session keys preserve channel routing dimensions`() {
        val source = AgentChannelSource(
            channel = AgentChannelType.FEISHU,
            accountId = "primary",
            chatId = "oc_chat",
            peerId = "ou_user",
            messageId = "om_message",
            threadId = "omt_thread",
        )
        assertEquals("FEISHU:primary:oc_chat:om_message", source.eventKey)
        assertEquals("FEISHU:primary:oc_chat:omt_thread", source.sessionKey)
    }

    @Test
    fun `outbound failure never qualifies a durable event for completion`() {
        assertFalse(AgentChannelDeliveryOutcome.succeeded(null, IllegalStateException("offline")))
        assertFalse(AgentChannelDeliveryOutcome.succeeded("", null))
        assertTrue(AgentChannelDeliveryOutcome.succeeded("remote-message-1", null))
        val policy = AgentChannelRetryPolicy(baseDelayMs = 100, maxDelayMs = 800)
        assertEquals(100, policy.delayMs(1))
        assertEquals(200, policy.delayMs(2))
        assertEquals(800, policy.delayMs(8))
        assertEquals(800, policy.delayMs(40))
    }

    @Test
    fun `run matcher rejects unrelated frontend run and old assistant`() {
        val identity = AgentChannelRunIdentity(
            eventKey = "TELEGRAM:default:c:m",
            requestId = "request-channel",
            generation = 7,
            userCreatedAtEpochMs = 2_000,
        )
        val projection = AgentHubProjection(
            loaded = true,
            running = true,
            requestId = "request-frontend",
            generation = 8,
            messages = listOf(
                AgentHubMessage(AgentHubMessageRole.ASSISTANT, "旧回答", 1_000),
                AgentHubMessage(AgentHubMessageRole.USER, "信道请求", 2_000),
                AgentHubMessage(AgentHubMessageRole.ASSISTANT, "本次回答", 3_000),
                AgentHubMessage(AgentHubMessageRole.USER, "其他前端请求", 4_000),
                AgentHubMessage(AgentHubMessageRole.ASSISTANT, "其他前端回答", 5_000),
            ),
        )
        assertFalse(AgentChannelRunMatcher.isRunning(identity, projection))
        assertEquals("本次回答", AgentChannelRunMatcher.finalAssistant(identity, projection))
        assertEquals(
            "其他前端回答",
            AgentChannelRunMatcher.finalAssistant(
                identity.copy(userCreatedAtEpochMs = 4_000),
                projection,
            ),
        )
        assertEquals(
            null,
            AgentChannelRunMatcher.finalAssistant(
                identity,
                projection.copy(
                    messages = projection.messages.filterNot { it.createdAtEpochMs == 3_000L },
                ),
            ),
        )
    }

    @Test
    fun `run matcher uses latest user segment when timestamps collide`() {
        val identity = AgentChannelRunIdentity(
            eventKey = "TELEGRAM:default:c:m",
            requestId = "request-channel",
            generation = 7,
            userCreatedAtEpochMs = 2_000,
        )
        val projection = AgentHubProjection(
            loaded = true,
            messages = listOf(
                AgentHubMessage(AgentHubMessageRole.USER, "同毫秒历史请求", 2_000),
                AgentHubMessage(AgentHubMessageRole.ASSISTANT, "历史回答", 2_001),
                AgentHubMessage(AgentHubMessageRole.USER, "本次请求", 2_000),
                AgentHubMessage(AgentHubMessageRole.ASSISTANT, "本次回答", 2_002),
            ),
        )
        assertEquals("本次回答", AgentChannelRunMatcher.finalAssistant(identity, projection))
    }

    @Test
    fun `two phase recovery resumes prepared run but never replays active run`() {
        val identity = AgentChannelRunIdentity(
            eventKey = "TELEGRAM:default:c:m",
            requestId = "request-channel",
            generation = 7,
            userCreatedAtEpochMs = 2_000,
        )
        val idle = AgentHubProjection(loaded = true, generation = 6)
        assertEquals(
            AgentChannelRunRecoveryAction.RESUME_PREPARED,
            AgentChannelRunRecovery.decide(identity, AgentChannelRunPhase.PREPARED, idle),
        )
        assertEquals(
            AgentChannelRunRecoveryAction.COMPLETE_WITHOUT_REPLAY,
            AgentChannelRunRecovery.decide(identity, AgentChannelRunPhase.ACTIVE, idle),
        )
        val running = idle.copy(
            running = true,
            requestId = identity.requestId,
            generation = identity.generation,
            messages = listOf(
                AgentHubMessage(AgentHubMessageRole.USER, "request", identity.userCreatedAtEpochMs),
            ),
        )
        assertEquals(
            AgentChannelRunRecoveryAction.ATTACH_RUNNING,
            AgentChannelRunRecovery.decide(identity, AgentChannelRunPhase.PREPARED, running),
        )
    }

    @Test
    fun `telegram token validation rejects malformed value before adapter construction`() {
        assertTrue(TelegramBotTokenValidator.isValid("123456:abcdefghijklmnopqrstuvwxyzABCD"))
        assertFalse(TelegramBotTokenValidator.isValid("not-a-token"))
        assertFalse(TelegramBotTokenValidator.isValid("123456:short"))
    }

    @Test
    fun `authentication failure and aggregate connection status are explicit`() {
        assertTrue(
            AgentChannelFailureClassifier.isFatalAuthentication(
                AgentChannelType.TELEGRAM,
                AgentChannelFatalException("Telegram authentication rejected (HTTP 401)"),
            ),
        )
        assertFalse(AgentChannelTerminalStatePolicy.publishStopped(fatalErrorPublished = true))
        assertTrue(AgentChannelTerminalStatePolicy.publishStopped(fatalErrorPublished = false))
        assertFalse(
            AgentChannelTerminalConnectionFence.accepts(
                hasTerminalError = true,
                next = AgentChannelConnectionPhase.STOPPED,
            ),
        )
        assertTrue(
            AgentChannelTerminalConnectionFence.accepts(
                hasTerminalError = true,
                next = AgentChannelConnectionPhase.ERROR,
            ),
        )
        assertTrue(
            AgentChannelFailureClassifier.isFatalAuthentication(
                AgentChannelType.FEISHU,
                IllegalStateException("invalid app secret"),
            ),
        )
        assertTrue(
            AgentChannelFailureClassifier.isFatalAuthentication(
                AgentChannelType.TELEGRAM,
                CompletionException(IOException("Telegram sendMessage HTTP 401")),
            ),
        )
        assertEquals(
            SenseAgentChannelPhase.STARTING,
            AgentChannelStatusAggregation.resolve(
                listOf(
                    AgentChannelConnectionState(
                        AgentChannelType.TELEGRAM,
                        AgentChannelConnectionPhase.RETRYING,
                        "network retry",
                    ),
                ),
                expectedCount = 1,
            ).phase,
        )
        assertEquals(
            SenseAgentChannelPhase.ERROR,
            AgentChannelStatusAggregation.resolve(
                listOf(
                    AgentChannelConnectionState(
                        AgentChannelType.TELEGRAM,
                        AgentChannelConnectionPhase.ERROR,
                        "HTTP 401",
                    ),
                ),
                expectedCount = 1,
            ).phase,
        )
        assertEquals(
            SenseAgentChannelPhase.DEGRADED,
            AgentChannelStatusAggregation.resolve(
                listOf(
                    AgentChannelConnectionState(
                        AgentChannelType.TELEGRAM,
                        AgentChannelConnectionPhase.CONNECTED,
                    ),
                    AgentChannelConnectionState(
                        AgentChannelType.FEISHU,
                        AgentChannelConnectionPhase.RETRYING,
                        "reconnecting",
                    ),
                ),
                expectedCount = 2,
            ).phase,
        )
    }

    @Test
    fun `fatal authentication removes channel from delivery set and releases other fifo head`() {
        val telegram = AgentChannelInbound(
            source = source(peer = "tg-peer", chat = "tg-chat").copy(messageId = "auth-active"),
            text = "running task",
            receivedAtEpochMs = 10L,
        )
        val feishu = AgentChannelInbound(
            source = AgentChannelSource(
                channel = AgentChannelType.FEISHU,
                chatId = "fs-chat",
                peerId = "fs-peer",
                messageId = "after-auth-error",
            ),
            text = "continue here",
            receivedAtEpochMs = 11L,
        )
        val state = AgentChannelJournalState(
            pending = listOf(telegram, feishu),
            activeEventKey = telegram.source.eventKey,
            activeRequestId = "request-auth",
            activeGeneration = 3L,
            activeUserCreatedAtEpochMs = 12L,
            activeRunPhase = AgentChannelRunPhase.ACTIVE,
        )
        val available = AgentChannelDeliveryAvailability.afterConnectionState(
            setOf(AgentChannelType.TELEGRAM, AgentChannelType.FEISHU),
            AgentChannelConnectionState(
                AgentChannelType.TELEGRAM,
                AgentChannelConnectionPhase.ERROR,
                "HTTP 401",
            ),
        )
        assertEquals(setOf(AgentChannelType.FEISHU), available)
        val blocked = AgentChannelUnavailableDeliveryPolicy.blockingEvent(state, available)
        assertEquals(telegram, blocked)
        val released = AgentChannelJournalTransition.markFailed(
            state,
            telegram.source.eventKey,
            "Telegram authentication failed",
            13L,
        )
        assertEquals(feishu, AgentChannelDispatchQueue.head(released))
        assertEquals(
            null,
            AgentChannelUnavailableDeliveryPolicy.blockingEvent(released, available),
        )
    }

    @Test
    fun `failed streaming edit retains newest pending text`() {
        val buffer = AgentChannelLatestTextBuffer()
        buffer.offer("A")
        assertEquals("A", buffer.take())
        buffer.offer("B")
        buffer.restoreIfEmpty("A")
        assertEquals("B", buffer.peek())
        buffer.offer("C")
        assertEquals("C", buffer.take())
        buffer.restoreIfEmpty("C")
        assertEquals("C", buffer.peek())
    }

    @Test
    fun `terminal delivery skips unchanged platform edit`() {
        assertFalse(AgentChannelRemoteEditDecision.requiresEdit("最终回答", "最终回答"))
        assertTrue(AgentChannelRemoteEditDecision.requiresEdit("处理中", "最终回答"))
        assertTrue(AgentChannelRemoteEditDecision.requiresEdit(null, "最终回答"))
    }

    @Test
    fun `telegram final answer chunks preserve every unicode code point in order`() {
        val answer = "  " + "甲".repeat(3_979) + "😀" + "乙".repeat(4_500) + "\n"
        val chunks = AgentChannelTextChunks.split(AgentChannelType.TELEGRAM, answer)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.length <= AgentChannelTextChunks.TELEGRAM_CHUNK_UTF16_UNITS })
        chunks.forEach { chunk ->
            assertTrue(chunk.isNotEmpty())
            assertTrue(!Character.isHighSurrogate(chunk.last()))
            assertTrue(!Character.isLowSurrogate(chunk.first()))
        }
        assertEquals(answer, chunks.joinToString(separator = ""))
        assertEquals(
            listOf(answer),
            AgentChannelTextChunks.split(AgentChannelType.FEISHU, answer),
        )
    }

    @Test
    fun `transport truncation never splits supplementary code point`() {
        val value = "甲".repeat(17_999) + "😀" + "tail"
        val truncated = AgentChannelUnicode.truncate(value, 18_000)
        assertEquals(17_999, truncated.length)
        assertFalse(Character.isHighSurrogate(truncated.last()))
        assertEquals(value, AgentChannelUnicode.truncate(value, value.length))
    }

    private fun source(peer: String, chat: String) = AgentChannelSource(
        channel = AgentChannelType.TELEGRAM,
        chatId = chat,
        peerId = peer,
        messageId = "m-1",
    )
}
