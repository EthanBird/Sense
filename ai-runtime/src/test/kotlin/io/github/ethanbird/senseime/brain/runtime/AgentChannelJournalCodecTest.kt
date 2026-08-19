package io.github.ethanbird.senseime.brain.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChannelJournalCodecTest {
    @Test
    fun `journal codec retains inbox active identity cursor and session mapping`() {
        val inbound = AgentChannelInbound(
            source = AgentChannelSource(
                channel = AgentChannelType.TELEGRAM,
                chatId = "-10020",
                peerId = "42",
                messageId = "100",
                threadId = "7",
            ),
            text = "运行终端\n然后总结",
            receivedAtEpochMs = 123_456,
        )
        val state = AgentChannelJournalState(
            telegramOffset = 101,
            pending = listOf(inbound),
            activeEventKey = inbound.source.eventKey,
            recentEventKeys = listOf("TELEGRAM:default:-10020:99"),
            targetBySession = mapOf(inbound.source.sessionKey to "sense"),
            activeRequestId = "request-42",
            activeGeneration = 9,
            activeUserCreatedAtEpochMs = 123_460,
            activeRemoteMessageId = "remote-7",
            activeRunPhase = AgentChannelRunPhase.ACTIVE,
            failedDeliveries = listOf(
                AgentChannelDeliveryFailure(
                    eventKey = "TELEGRAM:default:-10020:98",
                    channel = AgentChannelType.TELEGRAM,
                    reason = "channel disabled",
                    failedAtEpochMs = 123_450,
                ),
            ),
        )
        assertEquals(state, AgentChannelJournalCodec.decode(AgentChannelJournalCodec.encode(state)))
        assertEquals(
            AgentChannelRunIdentity(
                eventKey = inbound.source.eventKey,
                requestId = "request-42",
                generation = 9,
                userCreatedAtEpochMs = 123_460,
            ),
            state.activeRunIdentity,
        )
    }

    @Test
    fun `prepared launch identity survives crash before runtime send`() {
        val inbound = inbound(7)
        val state = AgentChannelJournalState(
            pending = listOf(inbound),
            activeEventKey = inbound.source.eventKey,
            activeRequestId = "prepared-request",
            activeGeneration = 4,
            activeUserCreatedAtEpochMs = 9_000,
            activeRunPhase = AgentChannelRunPhase.PREPARED,
        )
        val restarted = AgentChannelJournalCodec.decode(AgentChannelJournalCodec.encode(state))
        assertEquals(state, restarted)
        assertEquals(AgentChannelRunPhase.PREPARED, restarted.activeRunPhase)
    }

    @Test
    fun `repeated active recovery preserves durable remote draft id`() {
        val inbound = inbound(8)
        val identity = AgentChannelRunIdentity(
            eventKey = inbound.source.eventKey,
            requestId = "request-active",
            generation = 8L,
            userCreatedAtEpochMs = 9L,
        )
        val state = AgentChannelJournalState(
            pending = listOf(inbound),
            activeEventKey = identity.eventKey,
            activeRequestId = identity.requestId,
            activeGeneration = identity.generation,
            activeUserCreatedAtEpochMs = identity.userCreatedAtEpochMs,
            activeRemoteMessageId = "remote-draft",
            activeRunPhase = AgentChannelRunPhase.ACTIVE,
        )
        val firstRecovery = AgentChannelJournalTransition.markActive(state, identity)
        val secondRecovery = AgentChannelJournalTransition.markActive(firstRecovery, identity)
        assertEquals("remote-draft", firstRecovery.activeRemoteMessageId)
        assertEquals("remote-draft", secondRecovery.activeRemoteMessageId)
    }

    @Test
    fun `final chunk progress survives consecutive crash recovery without replay`() {
        val inbound = inbound(9)
        val identity = AgentChannelRunIdentity(
            eventKey = inbound.source.eventKey,
            requestId = "request-chunks",
            generation = 9L,
            userCreatedAtEpochMs = 10L,
        )
        var state = AgentChannelJournalState(
            pending = listOf(inbound),
            activeEventKey = identity.eventKey,
            activeRequestId = identity.requestId,
            activeGeneration = identity.generation,
            activeUserCreatedAtEpochMs = identity.userCreatedAtEpochMs,
            activeRemoteMessageId = "remote-0",
            activeRunPhase = AgentChannelRunPhase.ACTIVE,
        )
        state = AgentChannelJournalTransition.recordFinalChunk(state, 0, "remote-0")
        val firstRestart = AgentChannelJournalCodec.decode(AgentChannelJournalCodec.encode(state))
        assertEquals(
            1,
            AgentChannelFinalChunkProgress.nextIndex(
                firstRestart.activeFinalChunkRemoteMessageIds,
                totalChunks = 3,
            ),
        )
        state = AgentChannelJournalTransition.recordFinalChunk(firstRestart, 1, "remote-1")
        val secondRestart = AgentChannelJournalCodec.decode(AgentChannelJournalCodec.encode(state))
        assertEquals(listOf("remote-0", "remote-1"), secondRestart.activeFinalChunkRemoteMessageIds)
        assertEquals(
            2,
            AgentChannelFinalChunkProgress.nextIndex(
                secondRestart.activeFinalChunkRemoteMessageIds,
                totalChunks = 3,
            ),
        )
        assertEquals(
            secondRestart,
            AgentChannelJournalTransition.recordFinalChunk(secondRestart, 1, "remote-1"),
        )
    }

    @Test
    fun `corrupt journal is classified for quarantine instead of escaping decoder`() {
        val result = AgentChannelJournalRecovery.decode("sense.agent.channel-journal.v3\ninvalid")
        assertTrue(result is AgentChannelJournalRecovery.Result.Corrupt)
    }

    @Test
    fun `legacy v4 journal migrates with empty terminal failure ledger`() {
        val v4 = AgentChannelJournalCodec.encode(AgentChannelJournalState())
            .replaceFirst("sense.agent.channel-journal.v5", "sense.agent.channel-journal.v4")
            .lineSequence()
            .toMutableList()
            .also { lines -> lines.removeAt(9) }
            .joinToString("\n")
        assertTrue(AgentChannelJournalCodec.decode(v4).failedDeliveries.isEmpty())
    }

    @Test
    fun `journal recovery notice is consumed once`() {
        val root = Files.createTempDirectory("sense-channel-recovery").toFile()
        val marker = root.resolve("journal.recovery")
        marker.writeText("journal rebuilt", Charsets.UTF_8)
        assertEquals("journal rebuilt", AgentChannelRecoveryNotice.consume(marker))
        assertEquals(null, AgentChannelRecoveryNotice.consume(marker))
        root.deleteRecursively()
    }

    @Test
    fun `command admitted before process crash remains fifo head after restart`() {
        val status = inbound(1).copy(text = "/status")
        val prompt = inbound(2).copy(text = "继续运行终端")
        val restarted = AgentChannelJournalCodec.decode(
            AgentChannelJournalCodec.encode(
                AgentChannelJournalState(pending = listOf(status, prompt)),
            ),
        )

        val head = AgentChannelDispatchQueue.head(restarted)
        assertEquals(status, head)
        assertEquals(AgentChannelCommand.Status, AgentChannelCommandParser.parse(checkNotNull(head).text))
    }

    @Test
    fun `legacy v1 active record decodes without inventing run identity`() {
        val legacy = """
            sense.agent.channel-journal.v1
            0
            ~
            0
            0
            0
        """.trimIndent() + "\n"
        val decoded = AgentChannelJournalCodec.decode(legacy)
        assertEquals(null, decoded.activeRunIdentity)
        assertEquals(emptyList<AgentChannelInbound>(), decoded.pending)
    }

    @Test
    fun `full durable inbox backpressures new event across codec restart`() {
        val pending = (1..32).map { index -> inbound(index) }
        val restarted = AgentChannelJournalCodec.decode(
            AgentChannelJournalCodec.encode(AgentChannelJournalState(pending = pending)),
        )
        val transition = AgentChannelInboxAdmission.admit(restarted, inbound(33), maxPending = 32)
        assertEquals(AgentChannelInboxAdmission.Transition.Full, transition)
        assertEquals(pending, restarted.pending)
        assertEquals(
            AgentChannelInboxAdmission.Transition.Duplicate,
            AgentChannelInboxAdmission.admit(restarted, inbound(32), maxPending = 32),
        )
    }

    private fun inbound(index: Int) = AgentChannelInbound(
        source = AgentChannelSource(
            channel = AgentChannelType.TELEGRAM,
            chatId = "chat",
            peerId = "peer",
            messageId = index.toString(),
        ),
        text = "message $index",
        receivedAtEpochMs = index.toLong(),
    )
}
