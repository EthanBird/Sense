package io.github.ethanbird.senseime.brain.runtime

enum class AgentChannelType {
    TELEGRAM,
    FEISHU,
}

object TelegramBotTokenValidator {
    private val pattern = Regex("[0-9]{5,16}:[A-Za-z0-9_-]{20,128}")

    fun isValid(token: CharSequence): Boolean = pattern.matches(token)
}

data class AgentChannelSource(
    val channel: AgentChannelType,
    val accountId: String = "default",
    val chatId: String,
    val peerId: String,
    val messageId: String,
    val threadId: String? = null,
) {
    init {
        require(accountId.isNotBlank() && accountId.length <= 64)
        require(chatId.isNotBlank() && chatId.length <= 192)
        require(peerId.isNotBlank() && peerId.length <= 192)
        require(messageId.isNotBlank() && messageId.length <= 192)
        require(threadId == null || threadId.length <= 192)
    }

    val eventKey: String
        get() = listOf(channel.name, accountId, chatId, messageId).joinToString(":")

    val sessionKey: String
        get() = listOfNotNull(channel.name, accountId, chatId, threadId).joinToString(":")
}

data class AgentChannelInbound(
    val source: AgentChannelSource,
    val text: String,
    val receivedAtEpochMs: Long,
) {
    init {
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARS)
        require(receivedAtEpochMs > 0L)
    }

    companion object {
        const val MAX_TEXT_CHARS = 12_000
    }
}

sealed interface AgentChannelCommand {
    data class Prompt(val text: String) : AgentChannelCommand
    data class Pair(val code: String) : AgentChannelCommand
    data object Stop : AgentChannelCommand
    data object NewConversation : AgentChannelCommand
    data object Status : AgentChannelCommand
    data object Help : AgentChannelCommand
    data object ListTargets : AgentChannelCommand
    data class SelectTarget(val targetId: String) : AgentChannelCommand
}

object AgentChannelCommandParser {
    fun parse(raw: String): AgentChannelCommand {
        val text = raw.trim()
        val command = text.substringBefore(' ').substringBefore('@').lowercase()
        val argument = text.substringAfter(' ', "").trim()
        return when (command) {
            "/stop", "停止" -> AgentChannelCommand.Stop
            "/new", "新会话" -> AgentChannelCommand.NewConversation
            "/status", "状态" -> AgentChannelCommand.Status
            "/help", "/start", "帮助" -> AgentChannelCommand.Help
            "/pair", "配对" -> AgentChannelCommand.Pair(argument)
            "/agents", "智能体列表" -> AgentChannelCommand.ListTargets
            "/agent" -> when {
                argument.equals("list", ignoreCase = true) -> AgentChannelCommand.ListTargets
                argument.startsWith("use ", ignoreCase = true) ->
                    AgentChannelCommand.SelectTarget(argument.substringAfter(' ').trim())
                else -> AgentChannelCommand.Help
            }
            else -> AgentChannelCommand.Prompt(text)
        }
    }
}

enum class AgentChannelAccessDecision {
    ACCEPT,
    ACCEPT_PAIRING,
    REJECT,
}

object AgentChannelAccessPolicy {
    fun decide(
        source: AgentChannelSource,
        command: AgentChannelCommand,
        pairingCode: String,
        boundPeerId: String?,
        boundChatId: String?,
    ): AgentChannelAccessDecision {
        val peer = boundPeerId?.takeIf(String::isNotBlank)
        val chat = boundChatId?.takeIf(String::isNotBlank)
        if (peer != null || chat != null) {
            return if (
                (peer == null || peer == source.peerId) &&
                (chat == null || chat == source.chatId)
            ) {
                AgentChannelAccessDecision.ACCEPT
            } else {
                AgentChannelAccessDecision.REJECT
            }
        }
        return if (
            command is AgentChannelCommand.Pair &&
            command.code.length == 6 &&
            command.code == pairingCode
        ) {
            AgentChannelAccessDecision.ACCEPT_PAIRING
        } else {
            AgentChannelAccessDecision.REJECT
        }
    }
}

internal object AgentChannelRuntimeAccessPolicy {
    /** Re-read under the settings file lock before every dispatch. */
    fun isDispatchEnabled(settings: AgentChannelSettings, channel: AgentChannelType): Boolean {
        if (!settings.shouldRun) return false
        return when (channel) {
            AgentChannelType.TELEGRAM -> settings.telegram.enabled
            AgentChannelType.FEISHU -> settings.feishu.enabled
        }
    }
}

internal object AgentChannelUnavailableDeliveryPolicy {
    /**
     * Returns only an event that blocks global progress: the active event first, otherwise FIFO
     * head. Later events remain untouched until they become the head.
     */
    fun blockingEvent(
        state: AgentChannelJournalState,
        availableChannels: Set<AgentChannelType>,
    ): AgentChannelInbound? {
        val active = state.activeEventKey?.let { activeKey ->
            state.pending.firstOrNull { it.source.eventKey == activeKey }
        }
        if (active != null && active.source.channel !in availableChannels) return active
        val head = state.pending.firstOrNull() ?: return null
        return head.takeIf { it.source.channel !in availableChannels }
    }
}

class AgentChannelStreamUpdateGate(
    private val minimumIntervalMs: Long = 900L,
    private val minimumChangedChars: Int = 12,
) {
    private var lastSentAt = Long.MIN_VALUE
    private var lastText = ""

    init {
        require(minimumIntervalMs >= 0L)
        require(minimumChangedChars >= 0)
    }

    fun shouldSend(text: String, nowEpochMs: Long, terminal: Boolean = false): Boolean {
        if (text == lastText) return false
        if (!terminal && lastSentAt != Long.MIN_VALUE) {
            val elapsed = nowEpochMs - lastSentAt
            val changed = kotlin.math.abs(text.length - lastText.length)
            if (elapsed < minimumIntervalMs || changed < minimumChangedChars) return false
        }
        lastText = text
        lastSentAt = nowEpochMs
        return true
    }
}

internal object AgentChannelDispatchQueue {
    /** Durable FIFO: dispatch is always anchored at the oldest uncompleted event. */
    fun head(state: AgentChannelJournalState): AgentChannelInbound? = state.pending.firstOrNull()

    /** `/stop` may interrupt an active run, while every other event keeps FIFO order. */
    fun firstInterrupt(state: AgentChannelJournalState): AgentChannelInbound? =
        state.pending.firstOrNull { pending ->
            pending.source.eventKey != state.activeEventKey &&
                AgentChannelCommandParser.parse(pending.text) == AgentChannelCommand.Stop
        }
}

internal object AgentChannelInterruptPolicy {
    fun canScan(hasChannelDelivery: Boolean, hubRunning: Boolean): Boolean =
        hasChannelDelivery || hubRunning
}

internal object AgentChannelDeliveryOutcome {
    fun succeeded(remoteMessageId: String?, failure: Throwable?): Boolean =
        failure == null && !remoteMessageId.isNullOrBlank()
}

internal object AgentChannelRemoteEditDecision {
    fun requiresEdit(lastAppliedText: String?, nextText: String): Boolean =
        lastAppliedText != nextText
}

internal object AgentChannelTextChunks {
    const val TELEGRAM_CHUNK_UTF16_UNITS = 3_980

    fun split(channel: AgentChannelType, value: String): List<String> {
        // Preserve the model output byte-for-byte (modulo UTF-16 transport representation).
        // Trimming here used to silently remove meaningful leading/trailing whitespace when a
        // terminal answer crossed Telegram's message limit.
        val text = value.takeIf(String::isNotBlank) ?: "Sense Agent"
        if (channel != AgentChannelType.TELEGRAM || text.length <= TELEGRAM_CHUNK_UTF16_UNITS) {
            return listOf(text)
        }
        val chunks = ArrayList<String>((text.length / TELEGRAM_CHUNK_UTF16_UNITS) + 1)
        var start = 0
        while (start < text.length) {
            var end = (start + TELEGRAM_CHUNK_UTF16_UNITS).coerceAtMost(text.length)
            if (
                end in (start + 1) until text.length &&
                Character.isHighSurrogate(text[end - 1]) &&
                Character.isLowSurrogate(text[end])
            ) {
                end--
            }
            check(end > start)
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }
}

internal object AgentChannelUnicode {
    /** Limits UTF-16 transport units without leaving half of a supplementary code point. */
    fun truncate(value: String, maxUnits: Int): String {
        require(maxUnits > 0)
        if (value.length <= maxUnits) return value
        var end = maxUnits
        if (
            Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])
        ) {
            end--
        }
        return value.substring(0, end)
    }
}

internal object AgentChannelFinalChunkProgress {
    fun nextIndex(completedRemoteIds: List<String>, totalChunks: Int): Int {
        require(totalChunks > 0)
        require(completedRemoteIds.size <= totalChunks)
        require(completedRemoteIds.all(String::isNotBlank))
        return completedRemoteIds.size
    }
}

/** Main-thread latest-wins buffer used while a platform edit request is in flight. */
internal class AgentChannelLatestTextBuffer {
    private var pending: String? = null

    fun offer(text: String) {
        require(text.isNotBlank())
        pending = text
    }

    fun take(): String? = pending.also { pending = null }

    fun restoreIfEmpty(text: String) {
        require(text.isNotBlank())
        if (pending == null) pending = text
    }

    fun peek(): String? = pending
}

internal class AgentChannelRetryPolicy(
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
) {
    init {
        require(baseDelayMs > 0L)
        require(maxDelayMs >= baseDelayMs)
    }

    fun delayMs(failedAttempts: Int): Long {
        require(failedAttempts > 0)
        var value = baseDelayMs
        repeat((failedAttempts - 1).coerceAtMost(30)) {
            value = (value * 2L).coerceAtMost(maxDelayMs)
        }
        return value
    }
}

internal object AgentChannelRunMatcher {
    fun isRunning(identity: AgentChannelRunIdentity, projection: AgentHubProjection): Boolean =
        projection.running &&
            projection.requestId == identity.requestId &&
            projection.generation == identity.generation &&
            hasUserMessage(identity, projection)

    fun hasUserMessage(
        identity: AgentChannelRunIdentity,
        projection: AgentHubProjection,
    ): Boolean = projection.messages.any { message ->
        message.role == AgentHubMessageRole.USER &&
            message.createdAtEpochMs == identity.userCreatedAtEpochMs
    }

    fun finalAssistant(
        identity: AgentChannelRunIdentity,
        projection: AgentHubProjection,
    ): String? {
        // The current run appends its USER record last. Choosing the last timestamp match avoids
        // attaching a same-millisecond historical USER segment to the remote delivery.
        val userIndex = projection.messages.indexOfLast { message ->
            message.role == AgentHubMessageRole.USER &&
                message.createdAtEpochMs == identity.userCreatedAtEpochMs
        }
        if (userIndex < 0) return null
        val endExclusive = projection.messages.indexOfFirstAfter(userIndex) { message ->
            message.role == AgentHubMessageRole.USER
        }.takeIf { it >= 0 } ?: projection.messages.size
        return projection.messages.subList(userIndex + 1, endExclusive)
            .lastOrNull { message ->
                message.role == AgentHubMessageRole.ASSISTANT &&
                    message.createdAtEpochMs > identity.userCreatedAtEpochMs &&
                    message.text.isNotBlank()
            }
            ?.text
    }

    private inline fun <T> List<T>.indexOfFirstAfter(
        startIndex: Int,
        predicate: (T) -> Boolean,
    ): Int {
        for (index in startIndex + 1 until size) if (predicate(this[index])) return index
        return -1
    }
}

internal enum class AgentChannelRunRecoveryAction {
    ATTACH_RUNNING,
    ATTACH_TERMINAL,
    RESUME_PREPARED,
    COMPLETE_WITHOUT_REPLAY,
    WAIT_FOR_OTHER_RUN,
}

internal object AgentChannelRunRecovery {
    fun decide(
        identity: AgentChannelRunIdentity,
        phase: AgentChannelRunPhase,
        projection: AgentHubProjection,
    ): AgentChannelRunRecoveryAction = when {
        AgentChannelRunMatcher.isRunning(identity, projection) ->
            AgentChannelRunRecoveryAction.ATTACH_RUNNING
        projection.running -> AgentChannelRunRecoveryAction.WAIT_FOR_OTHER_RUN
        AgentChannelRunMatcher.hasUserMessage(identity, projection) ->
            AgentChannelRunRecoveryAction.ATTACH_TERMINAL
        phase == AgentChannelRunPhase.PREPARED -> AgentChannelRunRecoveryAction.RESUME_PREPARED
        else -> AgentChannelRunRecoveryAction.COMPLETE_WITHOUT_REPLAY
    }
}

interface AgentControlTarget {
    val id: String
    val displayName: String
}

/** Extensible target registry; the service installs the local Sense runtime as the built-in target. */
class AgentControlTargetRegistry(targets: List<AgentControlTarget>) {
    private val byId = targets.associateBy { it.id }.also { indexed ->
        require(indexed.size == targets.size) { "Duplicate Agent target id" }
        require(indexed.keys.all { it.matches(TARGET_ID_PATTERN) })
    }

    fun find(id: String): AgentControlTarget? = byId[id]

    fun list(): List<AgentControlTarget> = byId.values.sortedBy(AgentControlTarget::id)

    companion object {
        private val TARGET_ID_PATTERN = Regex("[a-z][a-z0-9._-]{1,63}")
    }
}
