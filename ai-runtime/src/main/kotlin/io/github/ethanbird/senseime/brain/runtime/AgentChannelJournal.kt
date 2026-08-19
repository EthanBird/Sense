package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class AgentChannelJournalState(
    val telegramOffset: Long = 0L,
    val pending: List<AgentChannelInbound> = emptyList(),
    val activeEventKey: String? = null,
    val recentEventKeys: List<String> = emptyList(),
    val targetBySession: Map<String, String> = emptyMap(),
    val activeRequestId: String? = null,
    val activeGeneration: Long = 0L,
    val activeUserCreatedAtEpochMs: Long = 0L,
    val activeRemoteMessageId: String? = null,
    val activeRunPhase: AgentChannelRunPhase? = null,
    val activeFinalChunkRemoteMessageIds: List<String> = emptyList(),
    val failedDeliveries: List<AgentChannelDeliveryFailure> = emptyList(),
)

internal data class AgentChannelDeliveryFailure(
    val eventKey: String,
    val channel: AgentChannelType,
    val reason: String,
    val failedAtEpochMs: Long,
) {
    init {
        require(eventKey.isNotBlank() && eventKey.length <= 768)
        require(reason.isNotBlank() && reason.length <= 240)
        require(failedAtEpochMs > 0L)
    }
}

internal enum class AgentChannelRunPhase {
    PREPARED,
    ACTIVE,
}

internal data class AgentChannelRunIdentity(
    val eventKey: String,
    val requestId: String,
    val generation: Long,
    val userCreatedAtEpochMs: Long,
) {
    init {
        require(eventKey.isNotBlank())
        require(requestId.isNotBlank())
        require(generation > 0L)
        require(userCreatedAtEpochMs > 0L)
    }
}

internal val AgentChannelJournalState.activeRunIdentity: AgentChannelRunIdentity?
    get() {
        val eventKey = activeEventKey ?: return null
        val requestId = activeRequestId ?: return null
        if (activeGeneration <= 0L || activeUserCreatedAtEpochMs <= 0L) return null
        return AgentChannelRunIdentity(
            eventKey = eventKey,
            requestId = requestId,
            generation = activeGeneration,
            userCreatedAtEpochMs = activeUserCreatedAtEpochMs,
        )
    }

/**
 * Crash-safe inbox and delivery identity ledger.
 *
 * Platform callbacks are acknowledged only after [admit] persists the normalized event. A process
 * restart replays `pending` and reattaches `activeEventKey` to the durable Brain run. Completed IDs
 * remain in a bounded ring so Telegram update or Feishu event retries stay idempotent.
 */
internal class AgentChannelJournal(context: Context) {
    private val root = File(context.applicationContext.filesDir, "agent/channels")
    private val file = AtomicFile(File(root, "journal.v1"))
    private val lockFile = File(root, "journal.lock")
    private val recoveryMarker = File(root, "journal.recovery")

    fun snapshot(): AgentChannelJournalState = withLock { read() }

    fun consumeRecoveryNotice(): String? = withLock {
        AgentChannelRecoveryNotice.consume(recoveryMarker)
    }

    fun admit(inbound: AgentChannelInbound): AgentChannelAdmission = withLock {
        val state = read()
        when (val transition = AgentChannelInboxAdmission.admit(state, inbound, MAX_PENDING)) {
            is AgentChannelInboxAdmission.Transition.Accepted -> {
                write(transition.state)
                AgentChannelAdmission.ADMITTED
            }
            AgentChannelInboxAdmission.Transition.Duplicate -> AgentChannelAdmission.DUPLICATE
            AgentChannelInboxAdmission.Transition.Full -> AgentChannelAdmission.RETRY_LATER
        }
    }

    fun markActive(identity: AgentChannelRunIdentity) = withLock {
        val state = read()
        require(state.pending.any { it.source.eventKey == identity.eventKey })
        require(state.activeEventKey == null || state.activeRunIdentity == identity)
        write(AgentChannelJournalTransition.markActive(state, identity))
    }

    fun markPrepared(identity: AgentChannelRunIdentity) = withLock {
        val state = read()
        require(state.activeEventKey == null)
        require(state.pending.firstOrNull()?.source?.eventKey == identity.eventKey)
        write(
            state.copy(
                activeEventKey = identity.eventKey,
                activeRequestId = identity.requestId,
                activeGeneration = identity.generation,
                activeUserCreatedAtEpochMs = identity.userCreatedAtEpochMs,
                activeRemoteMessageId = null,
                activeRunPhase = AgentChannelRunPhase.PREPARED,
                activeFinalChunkRemoteMessageIds = emptyList(),
            ),
        )
    }

    fun recordActiveRemoteMessage(eventKey: String, remoteMessageId: String) = withLock {
        require(remoteMessageId.isNotBlank() && remoteMessageId.length <= 192)
        val state = read()
        if (state.activeEventKey == eventKey && state.activeRemoteMessageId != remoteMessageId) {
            write(state.copy(activeRemoteMessageId = remoteMessageId))
        }
    }

    fun recordFinalChunk(
        eventKey: String,
        chunkIndex: Int,
        remoteMessageId: String,
    ) = withLock {
        require(chunkIndex >= 0)
        require(remoteMessageId.isNotBlank() && remoteMessageId.length <= 192)
        val state = read()
        if (state.activeEventKey != eventKey) return@withLock
        val next = AgentChannelJournalTransition.recordFinalChunk(
            state,
            chunkIndex,
            remoteMessageId,
        )
        if (next != state) write(next)
    }

    fun markDone(eventKey: String) = withLock {
        val state = read()
        write(AgentChannelJournalTransition.markDone(state, eventKey))
    }

    fun markFailed(
        eventKey: String,
        reason: String,
        failedAtEpochMs: Long = System.currentTimeMillis(),
    ) = withLock {
        val state = read()
        if (state.pending.none { it.source.eventKey == eventKey }) return@withLock
        write(
            AgentChannelJournalTransition.markFailed(
                state = state,
                eventKey = eventKey,
                reason = reason,
                failedAtEpochMs = failedAtEpochMs,
            ),
        )
    }

    fun advanceTelegramOffset(nextOffset: Long) = withLock {
        val state = read()
        if (nextOffset > state.telegramOffset) write(state.copy(telegramOffset = nextOffset))
    }

    fun selectTarget(sessionKey: String, targetId: String) = withLock {
        require(sessionKey.isNotBlank() && sessionKey.length <= 768)
        require(targetId.matches(Regex("[a-z][a-z0-9._-]{1,63}")))
        val state = read()
        val next = LinkedHashMap(state.targetBySession)
        next.remove(sessionKey)
        next[sessionKey] = targetId
        while (next.size > MAX_SESSION_BINDINGS) next.remove(next.keys.first())
        write(state.copy(targetBySession = next))
    }

    private fun read(): AgentChannelJournalState {
        val document = try {
            file.openRead().bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
        } catch (_: FileNotFoundException) {
            return AgentChannelJournalState()
        } catch (failure: Throwable) {
            quarantine(failure)
            return AgentChannelJournalState()
        }
        return when (val recovered = AgentChannelJournalRecovery.decode(document)) {
            is AgentChannelJournalRecovery.Result.Loaded -> recovered.state
            is AgentChannelJournalRecovery.Result.Corrupt -> {
                quarantine(recovered.failure)
                AgentChannelJournalState()
            }
        }
    }

    private fun quarantine(failure: Throwable) {
        val base = file.baseFile
        val quarantine = File(root, "journal.corrupt-${System.currentTimeMillis()}.txt")
        runCatching {
            if (base.exists()) base.copyTo(quarantine, overwrite = false)
        }
        file.delete()
        runCatching {
            recoveryMarker.writeText(
                "Agent channel journal quarantined and rebuilt: ${failure::class.java.simpleName}",
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun write(state: AgentChannelJournalState) {
        val stream = file.startWrite()
        try {
            stream.write(AgentChannelJournalCodec.encode(state).toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun <T> withLock(block: () -> T): T = synchronized(STORE_MUTEX) {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            error("Agent channel journal directory could not be created")
        }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private companion object {
        const val MAX_PENDING = 32
        const val MAX_RECENT = 512
        const val MAX_SESSION_BINDINGS = 64
        val STORE_MUTEX = Any()
    }
}

internal object AgentChannelInboxAdmission {
    sealed interface Transition {
        data class Accepted(val state: AgentChannelJournalState) : Transition
        data object Duplicate : Transition
        data object Full : Transition
    }

    fun admit(
        state: AgentChannelJournalState,
        inbound: AgentChannelInbound,
        maxPending: Int,
    ): Transition {
        require(maxPending > 0)
        val key = inbound.source.eventKey
        if (key in state.recentEventKeys || state.pending.any { it.source.eventKey == key }) {
            return Transition.Duplicate
        }
        if (state.pending.size >= maxPending) return Transition.Full
        return Transition.Accepted(state.copy(pending = state.pending + inbound))
    }
}

internal object AgentChannelJournalTransition {
    fun markActive(
        state: AgentChannelJournalState,
        identity: AgentChannelRunIdentity,
    ): AgentChannelJournalState {
        val preserveRemoteMessage = state.activeRunIdentity == identity
        return state.copy(
            activeEventKey = identity.eventKey,
            activeRequestId = identity.requestId,
            activeGeneration = identity.generation,
            activeUserCreatedAtEpochMs = identity.userCreatedAtEpochMs,
            activeRemoteMessageId = state.activeRemoteMessageId.takeIf {
                preserveRemoteMessage
            },
            activeFinalChunkRemoteMessageIds = state.activeFinalChunkRemoteMessageIds.takeIf {
                preserveRemoteMessage
            }.orEmpty(),
            activeRunPhase = AgentChannelRunPhase.ACTIVE,
        )
    }

    fun recordFinalChunk(
        state: AgentChannelJournalState,
        chunkIndex: Int,
        remoteMessageId: String,
    ): AgentChannelJournalState {
        require(chunkIndex >= 0)
        require(remoteMessageId.isNotBlank() && remoteMessageId.length <= 192)
        val completed = state.activeFinalChunkRemoteMessageIds
        return when {
            chunkIndex < completed.size -> {
                require(completed[chunkIndex] == remoteMessageId)
                state
            }
            chunkIndex == completed.size -> state.copy(
                activeRemoteMessageId = state.activeRemoteMessageId
                    ?: remoteMessageId.takeIf { chunkIndex == 0 },
                activeFinalChunkRemoteMessageIds = completed + remoteMessageId,
            )
            else -> error("Final chunks must be recorded in order")
        }
    }

    fun markDone(
        state: AgentChannelJournalState,
        eventKey: String,
    ): AgentChannelJournalState = state.copy(
        pending = state.pending.filterNot { it.source.eventKey == eventKey },
        activeEventKey = state.activeEventKey.takeUnless { it == eventKey },
        activeRequestId = state.activeRequestId.takeUnless { state.activeEventKey == eventKey },
        activeGeneration = state.activeGeneration.takeUnless {
            state.activeEventKey == eventKey
        } ?: 0L,
        activeUserCreatedAtEpochMs = state.activeUserCreatedAtEpochMs.takeUnless {
            state.activeEventKey == eventKey
        } ?: 0L,
        activeRemoteMessageId = state.activeRemoteMessageId.takeUnless {
            state.activeEventKey == eventKey
        },
        activeRunPhase = state.activeRunPhase.takeUnless {
            state.activeEventKey == eventKey
        },
        activeFinalChunkRemoteMessageIds = state.activeFinalChunkRemoteMessageIds
            .takeUnless { state.activeEventKey == eventKey }
            ?: emptyList(),
        recentEventKeys = (state.recentEventKeys.filterNot { it == eventKey } + eventKey)
            .takeLast(MAX_RECENT_TRANSITION),
    )

    fun markFailed(
        state: AgentChannelJournalState,
        eventKey: String,
        reason: String,
        failedAtEpochMs: Long,
    ): AgentChannelJournalState {
        val inbound = state.pending.firstOrNull { it.source.eventKey == eventKey } ?: return state
        val failure = AgentChannelDeliveryFailure(
            eventKey = eventKey,
            channel = inbound.source.channel,
            reason = reason.take(240),
            failedAtEpochMs = failedAtEpochMs,
        )
        val completed = markDone(state, eventKey)
        return completed.copy(
            failedDeliveries = (
                completed.failedDeliveries.filterNot { it.eventKey == eventKey } + failure
            ).takeLast(MAX_FAILURES),
        )
    }

    private const val MAX_RECENT_TRANSITION = 512
    private const val MAX_FAILURES = 64
}

internal object AgentChannelJournalRecovery {
    sealed interface Result {
        data class Loaded(val state: AgentChannelJournalState) : Result
        data class Corrupt(val failure: Throwable) : Result
    }

    fun decode(document: String): Result = try {
        Result.Loaded(AgentChannelJournalCodec.decode(document))
    } catch (failure: Throwable) {
        Result.Corrupt(failure)
    }
}

internal object AgentChannelRecoveryNotice {
    fun consume(marker: File): String? {
        if (!marker.exists()) return null
        return try {
            marker.readText(StandardCharsets.UTF_8).take(240)
        } finally {
            marker.delete()
        }
    }
}

internal object AgentChannelJournalCodec {
    private const val HEADER_V1 = "sense.agent.channel-journal.v1"
    private const val HEADER_V2 = "sense.agent.channel-journal.v2"
    private const val HEADER_V3 = "sense.agent.channel-journal.v3"
    private const val HEADER_V4 = "sense.agent.channel-journal.v4"
    private const val HEADER_V5 = "sense.agent.channel-journal.v5"

    fun encode(state: AgentChannelJournalState): String = buildString {
        appendLine(HEADER_V5)
        appendLine(state.telegramOffset)
        appendLine(state.activeEventKey.orEmpty().wire())
        appendLine(state.activeRequestId.orEmpty().wire())
        appendLine(state.activeGeneration)
        appendLine(state.activeUserCreatedAtEpochMs)
        appendLine(state.activeRemoteMessageId.orEmpty().wire())
        appendLine(state.activeRunPhase?.name.orEmpty().wire())
        appendLine(state.activeFinalChunkRemoteMessageIds.size)
        state.activeFinalChunkRemoteMessageIds.forEach { appendLine(it.wire()) }
        appendLine(state.failedDeliveries.size)
        state.failedDeliveries.forEach { failure ->
            appendLine(
                listOf(
                    failure.eventKey.wire(),
                    failure.channel.name,
                    failure.failedAtEpochMs.toString(),
                    failure.reason.wire(),
                ).joinToString("|"),
            )
        }
        appendLine(state.pending.size)
        state.pending.forEach { inbound ->
            val source = inbound.source
            appendLine(
                listOf(
                    source.channel.name,
                    source.accountId.wire(),
                    source.chatId.wire(),
                    source.peerId.wire(),
                    source.messageId.wire(),
                    source.threadId.orEmpty().wire(),
                    inbound.receivedAtEpochMs.toString(),
                    inbound.text.wire(),
                ).joinToString("|"),
            )
        }
        appendLine(state.recentEventKeys.size)
        state.recentEventKeys.forEach { appendLine(it.wire()) }
        appendLine(state.targetBySession.size)
        state.targetBySession.forEach { (session, target) ->
            appendLine("${session.wire()}|${target.wire()}")
        }
    }

    fun decode(document: String): AgentChannelJournalState {
        val lines = document.lineSequence().toList()
        require(
            lines.size >= 6 &&
                lines[0] in setOf(HEADER_V1, HEADER_V2, HEADER_V3, HEADER_V4, HEADER_V5),
        )
        val version2 = lines[0] in setOf(HEADER_V2, HEADER_V3, HEADER_V4, HEADER_V5)
        val version3 = lines[0] in setOf(HEADER_V3, HEADER_V4, HEADER_V5)
        val version4 = lines[0] in setOf(HEADER_V4, HEADER_V5)
        val version5 = lines[0] == HEADER_V5
        var index = 1
        val telegramOffset = lines[index++].toLong().also { require(it >= 0) }
        val active = lines[index++].unwire().ifBlank { null }
        val activeRequestId = if (version2) lines[index++].unwire().ifBlank { null } else null
        val activeGeneration = if (version2) lines[index++].toLong().also { require(it >= 0L) } else 0L
        val activeUserCreatedAt = if (version2) {
            lines[index++].toLong().also { require(it >= 0L) }
        } else {
            0L
        }
        val activeRemoteMessageId = if (version2) lines[index++].unwire().ifBlank { null } else null
        val activeRunPhase = if (version3) {
            lines[index++].unwire().ifBlank { null }?.let(AgentChannelRunPhase::valueOf)
        } else if (activeRequestId != null) {
            AgentChannelRunPhase.ACTIVE
        } else {
            null
        }
        val finalChunkRemoteMessageIds = if (version4) {
            val count = lines[index++].toInt().also { require(it in 0..64) }
            buildList(count) { repeat(count) { add(lines[index++].unwire()) } }
                .also { ids -> require(ids.all { it.isNotBlank() && it.length <= 192 }) }
        } else {
            emptyList()
        }
        val failedDeliveries = if (version5) {
            val count = lines[index++].toInt().also { require(it in 0..64) }
            buildList(count) {
                repeat(count) {
                    val parts = lines[index++].split('|')
                    require(parts.size == 4)
                    add(
                        AgentChannelDeliveryFailure(
                            eventKey = parts[0].unwire(),
                            channel = AgentChannelType.valueOf(parts[1]),
                            failedAtEpochMs = parts[2].toLong(),
                            reason = parts[3].unwire(),
                        ),
                    )
                }
            }
        } else {
            emptyList()
        }
        val pendingCount = lines[index++].toInt().also { require(it in 0..32) }
        val pending = buildList(pendingCount) {
            repeat(pendingCount) {
                val parts = lines[index++].split('|')
                require(parts.size == 8)
                add(
                    AgentChannelInbound(
                        source = AgentChannelSource(
                            channel = AgentChannelType.valueOf(parts[0]),
                            accountId = parts[1].unwire(),
                            chatId = parts[2].unwire(),
                            peerId = parts[3].unwire(),
                            messageId = parts[4].unwire(),
                            threadId = parts[5].unwire().ifBlank { null },
                        ),
                        receivedAtEpochMs = parts[6].toLong(),
                        text = parts[7].unwire(),
                    ),
                )
            }
        }
        val recentCount = lines[index++].toInt().also { require(it in 0..512) }
        val recent = buildList(recentCount) { repeat(recentCount) { add(lines[index++].unwire()) } }
        val sessionCount = lines[index++].toInt().also { require(it in 0..64) }
        val sessions = buildMap(sessionCount) {
            repeat(sessionCount) {
                val parts = lines[index++].split('|')
                require(parts.size == 2)
                put(parts[0].unwire(), parts[1].unwire())
            }
        }
        require(active == null || pending.any { it.source.eventKey == active })
        if (activeRequestId != null) {
            require(active != null && activeGeneration > 0L && activeUserCreatedAt > 0L)
            require(activeRunPhase != null)
        }
        require(finalChunkRemoteMessageIds.isEmpty() || active != null)
        require(
            finalChunkRemoteMessageIds.isEmpty() ||
                activeRemoteMessageId == finalChunkRemoteMessageIds.first(),
        )
        return AgentChannelJournalState(
            telegramOffset = telegramOffset,
            pending = pending,
            activeEventKey = active,
            recentEventKeys = recent,
            targetBySession = sessions,
            activeRequestId = activeRequestId,
            activeGeneration = activeGeneration,
            activeUserCreatedAtEpochMs = activeUserCreatedAt,
            activeRemoteMessageId = activeRemoteMessageId,
            activeRunPhase = activeRunPhase,
            activeFinalChunkRemoteMessageIds = finalChunkRemoteMessageIds,
            failedDeliveries = failedDeliveries,
        )
    }

    private fun String.wire(): String = if (isEmpty()) {
        "~"
    } else {
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(StandardCharsets.UTF_8))
    }

    private fun String.unwire(): String = if (this == "~") {
        ""
    } else {
        Base64.getUrlDecoder().decode(this).toString(StandardCharsets.UTF_8)
    }
}
