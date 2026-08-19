package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHubIpcProtocolTest {
    @Test
    fun roundTripsCommandsWithStableClientCommandIds() {
        val prepared = AgentHubPreparedRun("request", 8, "hello", 9)
        val commands = listOf(
            AgentHubIpcCommand.SendPrepared("c1", prepared),
            AgentHubIpcCommand.Stop("c2"),
            AgentHubIpcCommand.ClearConversation("c3"),
            AgentHubIpcCommand.OpenConversation("c4", "history-id"),
            AgentHubIpcCommand.RunGoldQuote("c5"),
            AgentHubIpcCommand.CancelAction("c6"),
            AgentHubIpcCommand.DismissAction("c7"),
        )

        commands.forEach { command ->
            val bytes = AgentHubIpcProtocol.encodeCommand(command)
            assertEquals(command, AgentHubIpcProtocol.decodeCommand(bytes))
            assertEquals(command.clientCommandId, AgentHubIpcProtocol.peekClientCommandId(bytes))
            assertTrue(bytes.size <= AgentHubIpcProtocol.MAX_COMMAND_BYTES)
        }
    }

    @Test
    fun roundTripsExplicitAcceptedAndRejectedAcks() {
        AgentHubIpcAckCode.entries.forEach { code ->
            val source = AgentHubIpcAck(
                clientCommandId = "command-$code",
                code = code,
                serverRevision = 12,
                requestId = "request",
                generation = 13,
            )
            val decoded = AgentHubIpcProtocol.decodeAck(AgentHubIpcProtocol.encodeAck(source))

            assertEquals(source, decoded)
            assertEquals(code == AgentHubIpcAckCode.ACCEPTED, decoded.accepted)
        }
    }

    @Test
    fun sendAckMustConfirmExactPreparedRequestAndGeneration() {
        val command = AgentHubIpcCommand.SendPrepared(
            "command",
            AgentHubPreparedRun("request", 4, "hello", 5),
        )
        val accepted = AgentHubIpcAck(
            "command",
            AgentHubIpcAckCode.ACCEPTED,
            serverRevision = 6,
            requestId = "request",
            generation = 4,
        )

        assertTrue(accepted.confirms(command))
        assertFalse(accepted.copy(clientCommandId = "other").confirms(command))
        assertFalse(accepted.copy(requestId = "other").confirms(command))
        assertFalse(accepted.copy(generation = 5).confirms(command))
        assertFalse(accepted.copy(code = AgentHubIpcAckCode.REJECTED).confirms(command))
    }

    @Test
    fun projectionFenceDropsOldRevisionAndOldConnectionCallbacks() {
        val fence = AgentHubProjectionFence()
        val first = fence.beginConnection()

        assertTrue(fence.accepts(first, 10))
        assertFalse(fence.accepts(first, 10))
        assertFalse(fence.accepts(first, 9))

        val second = fence.beginConnection()
        assertFalse(fence.accepts(first, 100))
        assertTrue(fence.accepts(second, 1))

        fence.invalidate()
        assertFalse(fence.accepts(second, 2))
    }

    @Test
    fun projectionDeliveryKeepsOneInFlightAndCollapsesPendingToLatest() {
        val delivery = AgentHubProjectionDeliveryWindow<String>()

        assertEquals("r1", delivery.offer(1, "r1"))
        assertEquals(null, delivery.offer(2, "r2"))
        assertEquals(null, delivery.offer(3, "r3"))
        assertEquals(null, delivery.acknowledge(0))
        assertEquals("r3", delivery.acknowledge(1))
        assertEquals(null, delivery.offer(4, "r4"))
        assertEquals("r4", delivery.acknowledge(3))
        assertEquals(null, delivery.acknowledge(4))
    }

    @Test
    fun rejectsOversizedAndTrailingCommandPayloads() {
        val valid = AgentHubIpcProtocol.encodeCommand(AgentHubIpcCommand.Stop("command"))
        assertFalse(
            runCatching { AgentHubIpcProtocol.decodeCommand(valid + byteArrayOf(0)) }.isSuccess,
        )
        assertFalse(
            runCatching {
                AgentHubIpcProtocol.decodeCommand(ByteArray(AgentHubIpcProtocol.MAX_COMMAND_BYTES + 1))
            }.isSuccess,
        )
    }

    @Test
    fun commandReplayWithTheSameIdExecutesOnlyOnceAndReturnsTheOriginalAck() {
        val ledger = AgentHubCommandAckLedger(capacity = 2)
        val command = AgentHubIpcCommand.Stop("stable-command-id")
        var executions = 0

        fun execute() = ledger.executeOnce(command) {
            executions += 1
            AgentHubIpcAck(
                clientCommandId = command.clientCommandId,
                code = AgentHubIpcAckCode.ACCEPTED,
                serverRevision = executions.toLong(),
                requestId = null,
                generation = 4,
            )
        }

        val first = execute()
        val replay = execute()

        assertEquals(1, executions)
        assertEquals(first, replay)
    }

    @Test
    fun deadCallbackRegistrationRaceIsContainedBeforeCommandExecution() {
        var executed = false
        val linked = tryLinkAgentHubCallback {
            throw IllegalStateException("client died before main-thread registration")
        }
        if (linked) executed = true

        assertFalse(linked)
        assertFalse(executed)
        assertTrue(tryLinkAgentHubCallback { })
    }

    @Test
    fun everyWirePayloadHasAStrictSub256KiBBudget() {
        assertTrue(AgentHubIpcProtocol.MAX_COMMAND_BYTES < 256 * 1024)
        assertTrue(AgentHubIpcProtocol.MAX_ACK_BYTES < 256 * 1024)
        assertTrue(AgentHubProjectionCodec.MAX_WIRE_BYTES < 256 * 1024)
        assertTrue(AgentHubHistoryCodec.MAX_PAGE_BYTES < 256 * 1024)
        assertTrue(AgentHubHistoryCodec.MAX_CONVERSATION_PAGE_BYTES < 256 * 1024)
    }

    @Test
    fun preCommandIdleProjectionCannotFalselyAcceptStopBeforeRejectedAck() {
        val command = AgentHubIpcCommand.Stop("stop-command")
        val idleBeforeSubmit = AgentHubCommandProjectionProof(
            submittedServerRevision = 10,
            priorRunning = false,
            priorMessageTotalCount = 0,
            priorActionRequestId = null,
            priorActionState = null,
            openConversationSummary = null,
        )

        assertFalse(
            idleBeforeSubmit.confirms(
                command,
                AgentHubProjection(revision = 11, loaded = true, running = false),
            ),
        )
        val actuallyRunningBeforeSubmit = idleBeforeSubmit.copy(priorRunning = true)
        assertFalse(
            actuallyRunningBeforeSubmit.confirms(
                command,
                AgentHubProjection(revision = 10, loaded = true, running = false),
            ),
        )
        assertTrue(
            actuallyRunningBeforeSubmit.confirms(
                command,
                AgentHubProjection(revision = 11, loaded = true, running = false),
            ),
        )
    }

    @Test
    fun emptyOrActionlessSnapshotsCannotProveClearCancelOrDismiss() {
        val proof = AgentHubCommandProjectionProof(
            submittedServerRevision = 4,
            priorRunning = false,
            priorMessageTotalCount = 0,
            priorActionRequestId = null,
            priorActionState = null,
            openConversationSummary = null,
        )
        val unrelated = AgentHubProjection(revision = 5, loaded = true)

        assertFalse(proof.confirms(AgentHubIpcCommand.ClearConversation("clear"), unrelated))
        assertFalse(proof.confirms(AgentHubIpcCommand.CancelAction("cancel"), unrelated))
        assertFalse(proof.confirms(AgentHubIpcCommand.DismissAction("dismiss"), unrelated))
    }
}
