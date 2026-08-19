package io.github.ethanbird.senseime.brain.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal sealed interface AgentHubIpcCommand {
    val clientCommandId: String

    data class SendPrepared(
        override val clientCommandId: String,
        val prepared: AgentHubPreparedRun,
    ) : AgentHubIpcCommand

    data class Stop(override val clientCommandId: String) : AgentHubIpcCommand

    data class ClearConversation(override val clientCommandId: String) : AgentHubIpcCommand

    data class OpenConversation(
        override val clientCommandId: String,
        val conversationId: String,
    ) : AgentHubIpcCommand

    data class RunGoldQuote(override val clientCommandId: String) : AgentHubIpcCommand

    data class CancelAction(override val clientCommandId: String) : AgentHubIpcCommand

    data class DismissAction(override val clientCommandId: String) : AgentHubIpcCommand
}

internal enum class AgentHubIpcAckCode {
    ACCEPTED,
    REJECTED,
    PROTOCOL_ERROR,
    INTERNAL_ERROR,
}

internal data class AgentHubIpcAck(
    val clientCommandId: String,
    val code: AgentHubIpcAckCode,
    val serverRevision: Long,
    val requestId: String?,
    val generation: Long,
) {
    val accepted: Boolean
        get() = code == AgentHubIpcAckCode.ACCEPTED
}

/** Connection-independent replay ledger; a command id is executed at most once per Hub process. */
internal class AgentHubCommandAckLedger(private val capacity: Int = 128) {
    private val entries = linkedMapOf<String, AgentHubIpcAck>()

    init {
        require(capacity > 0)
    }

    fun executeOnce(
        command: AgentHubIpcCommand,
        execute: () -> AgentHubIpcAck,
    ): AgentHubIpcAck {
        entries[command.clientCommandId]?.let { return it }
        return execute().also { ack ->
            require(ack.clientCommandId == command.clientCommandId)
            entries[command.clientCommandId] = ack
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }
}

/** Testable dead-client seam used before a one-way request is allowed to mutate Hub state. */
internal inline fun tryLinkAgentHubCallback(link: () -> Unit): Boolean = try {
    link()
    true
} catch (_: Exception) {
    false
}

internal fun AgentHubIpcAck.confirms(command: AgentHubIpcCommand): Boolean =
    clientCommandId == command.clientCommandId &&
        accepted &&
        when (command) {
            is AgentHubIpcCommand.SendPrepared ->
                requestId == command.prepared.requestId &&
                    generation == command.prepared.generation
            else -> true
        }

/**
 * Command-specific projection proof used only when an ACK was lost.
 *
 * State commands require both a post-submit server revision and a captured prior-to-next
 * transition. A queued pre-command projection therefore cannot turn a later REJECTED ACK into a
 * false success. Prepared sends use their durable request/generation/timestamp identity instead.
 */
internal data class AgentHubCommandProjectionProof(
    val submittedServerRevision: Long,
    val priorRunning: Boolean,
    val priorMessageTotalCount: Int,
    val priorActionRequestId: String?,
    val priorActionState: AgentHubActionState?,
    val openConversationSummary: AgentHubConversationSummary?,
) {
    fun confirms(command: AgentHubIpcCommand, next: AgentHubProjection): Boolean {
        if (command is AgentHubIpcCommand.SendPrepared) {
            return next.generation >= command.prepared.generation &&
                (
                    next.requestId == command.prepared.requestId ||
                        next.messages.any { message ->
                            val text = message.text.removeSuffix("…")
                            message.role == AgentHubMessageRole.USER &&
                                message.createdAtEpochMs ==
                                command.prepared.userCreatedAtEpochMs &&
                                text == command.prepared.userMessage.take(text.length)
                        }
                    )
        }
        if (next.revision <= submittedServerRevision) return false
        return when (command) {
            is AgentHubIpcCommand.Stop -> priorRunning && !next.running
            is AgentHubIpcCommand.ClearConversation ->
                priorMessageTotalCount > 0 && !next.running && next.messageTotalCount == 0
            is AgentHubIpcCommand.CancelAction ->
                priorActionRequestId != null &&
                    priorActionState == AgentHubActionState.RUNNING &&
                    next.action?.requestId == priorActionRequestId &&
                    next.action.state == AgentHubActionState.CANCELLED
            is AgentHubIpcCommand.DismissAction ->
                priorActionRequestId != null && next.action == null
            is AgentHubIpcCommand.OpenConversation ->
                openConversationSummary?.let { summary ->
                    next.messageTotalCount == summary.messageCount &&
                        next.messages.lastOrNull()?.text
                            ?.lineSequence()
                            ?.firstOrNull()
                            ?.take(80) == summary.preview &&
                        next.messages.any {
                            it.createdAtEpochMs == summary.updatedAtEpochMs
                        }
                } == true
            is AgentHubIpcCommand.RunGoldQuote ->
                next.action?.requestId != priorActionRequestId &&
                    next.action?.skillId == XauUsdActionSkill.SKILL_ID
            is AgentHubIpcCommand.SendPrepared -> error("Handled above")
        }
    }
}

/** Drops delayed callbacks from an old connection or an old server revision. */
internal class AgentHubProjectionFence {
    var connectionGeneration: Long = 0L
        private set
    private var lastServerRevision = Long.MIN_VALUE

    fun beginConnection(): Long {
        connectionGeneration = nextGeneration(connectionGeneration)
        lastServerRevision = Long.MIN_VALUE
        return connectionGeneration
    }

    fun invalidate() {
        connectionGeneration = nextGeneration(connectionGeneration)
        lastServerRevision = Long.MIN_VALUE
    }

    fun accepts(callbackGeneration: Long, serverRevision: Long): Boolean {
        if (callbackGeneration != connectionGeneration || serverRevision <= lastServerRevision) {
            return false
        }
        lastServerRevision = serverRevision
        return true
    }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L
}

/** At most one Binder projection is in flight; newer offers collapse to the latest revision. */
internal class AgentHubProjectionDeliveryWindow<T> {
    private var inFlightRevision: Long? = null
    private var pending: Pair<Long, T>? = null

    fun offer(revision: Long, value: T): T? {
        val inFlight = inFlightRevision
        if (inFlight == null) {
            inFlightRevision = revision
            return value
        }
        if (revision <= inFlight) return null
        if (pending == null || revision > requireNotNull(pending).first) {
            pending = revision to value
        }
        return null
    }

    fun acknowledge(revision: Long): T? {
        if (inFlightRevision != revision) return null
        inFlightRevision = null
        val next = pending
        pending = null
        if (next != null) inFlightRevision = next.first
        return next?.second
    }

    fun reset() {
        inFlightRevision = null
        pending = null
    }
}

/** Pure, bounded wire protocol used by the private Binder bridge. */
internal object AgentHubIpcProtocol {
    const val DESCRIPTOR = "io.github.ethanbird.senseime.AgentHubBridge.v1"
    const val PROTOCOL_VERSION = 1
    const val TRANSACTION_REGISTER = 1
    const val TRANSACTION_UNREGISTER = 2
    const val TRANSACTION_COMMAND = 3
    const val TRANSACTION_PROJECTION = 4
    const val TRANSACTION_PROJECTION_ACK = 5
    const val TRANSACTION_COMMAND_ACK = 6
    const val TRANSACTION_HISTORY_FETCH = 7
    const val TRANSACTION_HISTORY_PAGE = 8
    const val TRANSACTION_CONVERSATION_FETCH = 9
    const val TRANSACTION_CONVERSATION_PAGE = 10

    const val MAX_COMMAND_BYTES = 64 * 1024
    const val MAX_ACK_BYTES = 4 * 1024

    private const val COMMAND_MAGIC = 0x5348434D // SHCM
    private const val ACK_MAGIC = 0x5348414B // SHAK
    private const val MAX_COMMAND_ID_BYTES = 96
    private const val MAX_CONVERSATION_ID_BYTES = 256
    private const val MAX_MESSAGE_BYTES = 12_000 * 4

    fun encodeCommand(command: AgentHubIpcCommand): ByteArray = encode(MAX_COMMAND_BYTES) { output ->
        output.writeInt(COMMAND_MAGIC)
        output.writeInt(PROTOCOL_VERSION)
        val type = when (command) {
            is AgentHubIpcCommand.SendPrepared -> 1
            is AgentHubIpcCommand.Stop -> 2
            is AgentHubIpcCommand.ClearConversation -> 3
            is AgentHubIpcCommand.OpenConversation -> 4
            is AgentHubIpcCommand.RunGoldQuote -> 5
            is AgentHubIpcCommand.CancelAction -> 6
            is AgentHubIpcCommand.DismissAction -> 7
        }
        output.writeInt(type)
        output.writeUtf8(command.clientCommandId, MAX_COMMAND_ID_BYTES)
        when (command) {
            is AgentHubIpcCommand.SendPrepared -> {
                output.writeUtf8(command.prepared.requestId, MAX_COMMAND_ID_BYTES)
                output.writeLong(command.prepared.generation)
                output.writeUtf8(command.prepared.userMessage, MAX_MESSAGE_BYTES)
                output.writeLong(command.prepared.userCreatedAtEpochMs)
            }
            is AgentHubIpcCommand.OpenConversation ->
                output.writeUtf8(command.conversationId, MAX_CONVERSATION_ID_BYTES)
            else -> Unit
        }
    }

    fun decodeCommand(bytes: ByteArray): AgentHubIpcCommand = decode(bytes, MAX_COMMAND_BYTES) { input ->
        require(input.readInt() == COMMAND_MAGIC) { "Invalid command magic" }
        require(input.readInt() == PROTOCOL_VERSION) { "Unsupported command version" }
        val type = input.readInt()
        val commandId = input.readUtf8(MAX_COMMAND_ID_BYTES).also {
            require(it.isNotBlank()) { "Blank client command id" }
        }
        when (type) {
            1 -> AgentHubIpcCommand.SendPrepared(
                commandId,
                AgentHubPreparedRun(
                    requestId = input.readUtf8(MAX_COMMAND_ID_BYTES),
                    generation = input.readLong(),
                    userMessage = input.readUtf8(MAX_MESSAGE_BYTES),
                    userCreatedAtEpochMs = input.readLong(),
                ),
            )
            2 -> AgentHubIpcCommand.Stop(commandId)
            3 -> AgentHubIpcCommand.ClearConversation(commandId)
            4 -> AgentHubIpcCommand.OpenConversation(
                commandId,
                input.readUtf8(MAX_CONVERSATION_ID_BYTES),
            )
            5 -> AgentHubIpcCommand.RunGoldQuote(commandId)
            6 -> AgentHubIpcCommand.CancelAction(commandId)
            7 -> AgentHubIpcCommand.DismissAction(commandId)
            else -> throw IllegalArgumentException("Unknown command type")
        }
    }

    fun peekClientCommandId(bytes: ByteArray): String? = runCatching {
        require(bytes.size <= MAX_COMMAND_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == COMMAND_MAGIC)
        require(input.readInt() == PROTOCOL_VERSION)
        input.readInt()
        input.readUtf8(MAX_COMMAND_ID_BYTES).takeIf(String::isNotBlank)
    }.getOrNull()

    fun encodeAck(ack: AgentHubIpcAck): ByteArray = encode(MAX_ACK_BYTES) { output ->
        output.writeInt(ACK_MAGIC)
        output.writeInt(PROTOCOL_VERSION)
        output.writeUtf8(ack.clientCommandId, MAX_COMMAND_ID_BYTES)
        output.writeInt(ack.code.ordinal)
        output.writeLong(ack.serverRevision)
        output.writeNullableUtf8(ack.requestId, MAX_COMMAND_ID_BYTES)
        output.writeLong(ack.generation)
    }

    fun decodeAck(bytes: ByteArray): AgentHubIpcAck = decode(bytes, MAX_ACK_BYTES) { input ->
        require(input.readInt() == ACK_MAGIC) { "Invalid ack magic" }
        require(input.readInt() == PROTOCOL_VERSION) { "Unsupported ack version" }
        AgentHubIpcAck(
            clientCommandId = input.readUtf8(MAX_COMMAND_ID_BYTES),
            code = input.readEnum(),
            serverRevision = input.readLong(),
            requestId = input.readNullableUtf8(MAX_COMMAND_ID_BYTES),
            generation = input.readLong(),
        )
    }

    private inline fun encode(maxBytes: Int, block: (DataOutputStream) -> Unit): ByteArray {
        val sink = ByteArrayOutputStream()
        DataOutputStream(sink).use(block)
        return sink.toByteArray().also {
            require(it.size <= maxBytes) { "IPC payload exceeds $maxBytes bytes" }
        }
    }

    private inline fun <T> decode(
        bytes: ByteArray,
        maxBytes: Int,
        block: (DataInputStream) -> T,
    ): T {
        require(bytes.size <= maxBytes) { "IPC payload exceeds $maxBytes bytes" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val value = block(input)
        require(input.available() == 0) { "Trailing IPC payload bytes" }
        return value
    }

    internal fun DataOutputStream.writeUtf8(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxBytes) { "String exceeds $maxBytes UTF-8 bytes" }
        writeInt(bytes.size)
        write(bytes)
    }

    internal fun DataOutputStream.writeNullableUtf8(value: String?, maxBytes: Int) {
        writeBoolean(value != null)
        if (value != null) writeUtf8(value, maxBytes)
    }

    internal fun DataInputStream.readUtf8(maxBytes: Int): String {
        val size = readInt()
        require(size in 0..maxBytes && size <= available()) { "Invalid UTF-8 field size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    internal fun DataInputStream.readNullableUtf8(maxBytes: Int): String? =
        if (readBoolean()) readUtf8(maxBytes) else null

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val ordinal = readInt()
        return enumValues<T>().getOrNull(ordinal)
            ?: throw IllegalArgumentException("Invalid enum ordinal")
    }
}
