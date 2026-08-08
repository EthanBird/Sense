package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.AgentProgressKind
import io.github.ethanbird.senseime.ai.protocol.AgentProgressState
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.EditorTextDigest
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.HarnessResultMode
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.api.ActionSkillInvocation
import io.github.ethanbird.senseime.brain.api.ActionSkillResult
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Future

enum class AgentHubMessageRole {
    USER,
    ASSISTANT,
}

data class AgentHubMessage(
    val role: AgentHubMessageRole,
    val text: String,
    val createdAtEpochMs: Long,
)

enum class AgentHubToolState {
    RUNNING,
    COMPLETED,
    FAILED,
}

data class AgentHubToolStep(
    val id: String,
    val toolCallId: String?,
    val toolName: String?,
    val title: String,
    val detail: String,
    val state: AgentHubToolState,
)

enum class AgentHubActionState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class AgentHubActionCard(
    val requestId: String,
    val skillId: String,
    val title: String,
    val primaryValue: String = "",
    val secondaryValue: String = "",
    val insertText: String = "",
    val sourceLabel: String = "",
    val state: AgentHubActionState,
    val detail: String = "",
)

data class AgentHubConversationSummary(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAtEpochMs: Long,
    val messageCount: Int,
    val current: Boolean,
)

internal data class AgentHubConversationArchive(
    val id: String,
    val messages: List<AgentHubMessage>,
)

data class AgentHubProjection(
    val revision: Long = 0,
    val loaded: Boolean = false,
    val messages: List<AgentHubMessage> = emptyList(),
    val tools: List<AgentHubToolStep> = emptyList(),
    val preview: String = "",
    val status: String = "正在读取会话…",
    val running: Boolean = false,
    val requestId: String? = null,
    val generation: Long = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val conversations: List<AgentHubConversationSummary> = emptyList(),
    val action: AgentHubActionCard? = null,
)

fun interface AgentHubObserver {
    fun onProjection(projection: AgentHubProjection)
}

/**
 * Transitional process-local owner for the Agent conversation.
 *
 * IME and Activity frontends attach observers while the Brain service owns each bounded model/tool
 * run. The next protocol migration moves the durable conversation projection behind the service;
 * keeping this observer boundary now lets that migration preserve the new IME UI contract.
 */
class SenseAgentHubRuntime private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-agent-hub-store").apply { isDaemon = true }
    }
    private val actionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-action-skills").apply { isDaemon = true }
    }
    private val store = AgentConversationStore(applicationContext)
    private val durableRunStore = AgentDurableRunStore(applicationContext)
    private val actionHistory = ActionHistoryStore(applicationContext)
    private val observers = linkedSetOf<AgentHubObserver>()
    private val brainClient = SenseAiBrainClient(applicationContext, ::onBrainEvent)
    private val actionRuntime = DirectActionSkillRuntime.builtIns(
        credentialVault = AndroidActionCredentialVault(applicationContext),
    )
    private var projection = AgentHubProjection()
    private var archives = emptyList<AgentHubConversationArchive>()
    private var actionFuture: Future<*>? = null

    init {
        io.execute {
            val loaded = store.load().getOrDefault(emptyList())
            val loadedArchives = store.loadArchives().getOrDefault(emptyList())
            val durable = durableRunStore.load().getOrNull()
            val recovered = loaded.toMutableList()
            durable?.let { record ->
                if (recovered.none {
                        it.role == AgentHubMessageRole.USER &&
                            it.createdAtEpochMs == record.userCreatedAtEpochMs
                    }
                ) {
                    recovered += AgentHubMessage(
                        role = AgentHubMessageRole.USER,
                        text = record.userMessage,
                        createdAtEpochMs = record.userCreatedAtEpochMs,
                    )
                }
                if (
                    record.outcome == AgentDurableRunOutcome.ANSWER &&
                    recovered.none {
                        it.role == AgentHubMessageRole.ASSISTANT && it.text == record.payload
                    }
                ) {
                    recovered += AgentHubMessage(
                        role = AgentHubMessageRole.ASSISTANT,
                        text = record.payload,
                        createdAtEpochMs = record.userCreatedAtEpochMs + 1,
                    )
                }
            }
            val recoveredMessages = recovered.takeLast(MAX_RETAINED_MESSAGES)
            if (recoveredMessages != loaded) runCatching { store.save(recoveredMessages) }
            if (durable != null && durable.outcome != AgentDurableRunOutcome.RUNNING) {
                runCatching { durableRunStore.clearIf(durable.requestId, durable.generation) }
            }
            mainHandler.post {
                archives = loadedArchives
                val recovering = durable?.outcome == AgentDurableRunOutcome.RUNNING
                projection = projection.copy(
                    revision = projection.revision + 1,
                    loaded = true,
                    messages = recoveredMessages,
                    status = when (durable?.outcome) {
                        AgentDurableRunOutcome.RUNNING -> "正在恢复后台 Agent…"
                        AgentDurableRunOutcome.ANSWER -> "回答完成"
                        AgentDurableRunOutcome.CANCELLED -> "任务已停止"
                        AgentDurableRunOutcome.FAILED -> "上次任务已结束"
                        AgentDurableRunOutcome.PATCH -> "编辑提案已完成"
                        null -> "Agent 已就绪"
                    },
                    running = recovering,
                    requestId = if (recovering) durable?.requestId else null,
                    generation = maxOf(projection.generation, durable?.generation ?: 0L),
                    conversations = conversationSummaries(recoveredMessages, archives),
                )
                publish()
                if (recovering) {
                    brainClient.attach(
                        requestId = checkNotNull(durable).requestId,
                        generation = durable.generation,
                    )
                }
            }
        }
    }

    fun observe(observer: AgentHubObserver): AutoCloseable {
        checkMainThread()
        observers += observer
        observer.onProjection(projection)
        return AutoCloseable {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                observers -= observer
            } else {
                mainHandler.post { observers -= observer }
            }
        }
    }

    fun currentProjection(): AgentHubProjection {
        checkMainThread()
        return projection
    }

    fun send(message: String): Boolean {
        checkMainThread()
        val text = message.trim()
        if (!projection.loaded || projection.running || text.isEmpty()) return false
        if (text.length > MAX_USER_MESSAGE_CHARS) return false
        val userMessage = AgentHubMessage(
            role = AgentHubMessageRole.USER,
            text = text,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val messages = (projection.messages + userMessage).takeLast(MAX_RETAINED_MESSAGES)
        val requestId = UUID.randomUUID().toString()
        val generation = nextGeneration(projection.generation)
        val transcript = transcript(messages)
        val snapshot = EditorSnapshotV1(
            requestId = requestId,
            snapshotId = "$requestId.snapshot",
            editorGeneration = generation,
            fieldIdentity = SESSION_FIELD_IDENTITY,
            capability = SnapshotCapability.FULL_DOCUMENT,
            text = transcript,
            selection = TextSelectionV1(transcript.length, transcript.length),
            target = PatchTarget.WHOLE_FIELD,
            baseSha256 = EditorTextDigest.sha256Utf8(transcript),
            capturedAtMonotonicMs = SystemClock.elapsedRealtime(),
            truncated = messages.size < projection.messages.size + 1,
            maxOutputChars = MAX_ASSISTANT_CHARS,
        )
        val request = HarnessRequestV1(
            requestId = requestId,
            runGeneration = generation,
            skill = EditorIntent.ANSWER,
            snapshot = snapshot,
            resultMode = HarnessResultMode.ASSISTANT_MESSAGE,
            maxOutputChars = MAX_ASSISTANT_CHARS,
        )
        runCatching {
            durableRunStore.save(
                AgentDurableRunRecord(
                    requestId = requestId,
                    generation = generation,
                    userMessage = userMessage.text,
                    userCreatedAtEpochMs = userMessage.createdAtEpochMs,
                ),
            )
        }.getOrElse { return false }
        projection = projection.copy(
            revision = projection.revision + 1,
            messages = messages,
            tools = emptyList(),
            preview = "",
            status = "正在启动 Agent…",
            running = true,
            requestId = requestId,
            generation = generation,
            inputTokens = 0,
            outputTokens = 0,
            conversations = conversationSummaries(messages, archives),
        )
        persist(messages)
        publish()
        runCatching {
            applicationContext.startForegroundService(
                Intent(applicationContext, SenseAiBrainService::class.java)
                    .setAction(SenseAiBrainService.ACTION_PROMOTE_DURABLE_RUN),
            )
        }
        brainClient.start(request)
        return true
    }

    fun stop(): Boolean {
        checkMainThread()
        val requestId = projection.requestId ?: return false
        if (!projection.running) return false
        val generation = projection.generation
        runCatching {
            durableRunStore.complete(
                AiEvent.Cancelled(
                    requestId,
                    generation,
                    HarnessCancelReason.CALLER_REQUESTED,
                ),
            )
        }
        runCatching {
            applicationContext.startService(
                Intent(applicationContext, SenseAiBrainService::class.java)
                    .setAction(SenseAiBrainService.ACTION_STOP_DURABLE_RUN),
            )
        }
        brainClient.cancel(
            requestId,
            generation,
            HarnessCancelReason.CALLER_REQUESTED,
        )
        projection = projection.copy(
            revision = projection.revision + 1,
            running = false,
            requestId = null,
            preview = "",
            status = "任务已停止",
        )
        publish()
        return true
    }

    /** Executes the built-in quote connector directly. No model request is created. */
    fun runGoldQuote(): Boolean {
        checkMainThread()
        if (!projection.loaded || projection.action?.state == AgentHubActionState.RUNNING) return false
        val requestId = UUID.randomUUID().toString()
        projection = projection.copy(
            revision = projection.revision + 1,
            action = AgentHubActionCard(
                requestId = requestId,
                skillId = XauUsdActionSkill.SKILL_ID,
                title = "XAUUSD · 现货黄金",
                state = AgentHubActionState.RUNNING,
                detail = "正在直连行情 API…",
            ),
        )
        publish()
        actionFuture = actionExecutor.submit {
            val invocation = ActionSkillInvocation(requestId, XauUsdActionSkill.SKILL_ID)
            val result = actionHistory.appendStarted(invocation, System.currentTimeMillis())
                .mapCatching { actionRuntime.execute(invocation).getOrThrow() }
                .mapCatching { value ->
                    actionHistory.appendSucceeded(value).getOrThrow()
                    value
                }
                .onFailure { failure ->
                    actionHistory.appendFailed(
                        requestId = requestId,
                        skillId = XauUsdActionSkill.SKILL_ID,
                        failure = failure,
                        occurredAtEpochMs = System.currentTimeMillis(),
                    )
                }
            mainHandler.post { completeAction(requestId, result) }
        }
        return true
    }

    fun cancelAction(): Boolean {
        checkMainThread()
        val current = projection.action?.takeIf { it.state == AgentHubActionState.RUNNING }
            ?: return false
        actionFuture?.cancel(true)
        actionFuture = null
        projection = projection.copy(
            revision = projection.revision + 1,
            action = current.copy(state = AgentHubActionState.CANCELLED, detail = "已停止"),
        )
        publish()
        return true
    }

    fun dismissAction(): Boolean {
        checkMainThread()
        if (projection.action?.state == AgentHubActionState.RUNNING || projection.action == null) {
            return false
        }
        projection = projection.copy(revision = projection.revision + 1, action = null)
        publish()
        return true
    }

    private fun completeAction(requestId: String, result: Result<ActionSkillResult>) {
        checkMainThread()
        val current = projection.action?.takeIf {
            it.requestId == requestId && it.state == AgentHubActionState.RUNNING
        } ?: return
        actionFuture = null
        projection = projection.copy(
            revision = projection.revision + 1,
            action = result.fold(
                onSuccess = { value ->
                    current.copy(
                        title = value.title,
                        primaryValue = value.primaryValue,
                        secondaryValue = value.secondaryValue,
                        insertText = value.insertText,
                        sourceLabel = value.sourceLabel,
                        state = AgentHubActionState.SUCCEEDED,
                        detail = "直连完成 · 0 模型 Token",
                    )
                },
                onFailure = { failure ->
                    current.copy(
                        state = AgentHubActionState.FAILED,
                        detail = failure.message?.take(180).orEmpty().ifBlank { "行情请求失败" },
                    )
                },
            ),
        )
        publish()
    }

    fun clearConversation(): Boolean {
        checkMainThread()
        if (!projection.loaded || projection.running) return false
        archiveCurrentConversation(projection.messages)
        projection = projection.copy(
            revision = projection.revision + 1,
            messages = emptyList(),
            tools = emptyList(),
            preview = "",
            status = "已开始新会话",
            inputTokens = 0,
            outputTokens = 0,
            conversations = conversationSummaries(emptyList(), archives),
        )
        persist(emptyList())
        durableRunStore.clear()
        publish()
        return true
    }

    fun openConversation(id: String): Boolean {
        checkMainThread()
        if (!projection.loaded || projection.running) return false
        if (id == CURRENT_CONVERSATION_ID) return true
        val selected = archives.firstOrNull { it.id == id } ?: return false
        archives = archives.filterNot { it.id == id }
        archiveCurrentConversation(projection.messages)
        val restored = selected.messages.takeLast(MAX_RETAINED_MESSAGES)
        projection = projection.copy(
            revision = projection.revision + 1,
            messages = restored,
            tools = emptyList(),
            preview = "",
            status = "已打开历史会话",
            inputTokens = 0,
            outputTokens = 0,
            conversations = conversationSummaries(restored, archives),
        )
        persist(restored)
        // The single IO lane first archives the displaced current session and publishes the
        // selected session as current. Deleting its old archive last makes every crash point
        // duplicate-safe rather than loss-prone.
        io.execute { store.deleteArchive(id) }
        publish()
        return true
    }

    private fun onBrainEvent(event: AiEvent) {
        checkMainThread()
        if (
            event.requestId != projection.requestId ||
            event.runGeneration != projection.generation
        ) {
            return
        }
        if (event is AiEvent.Failed && projection.status == "正在恢复后台 Agent…") {
            runCatching { durableRunStore.complete(event) }
        }
        projection = when (event) {
            is AiEvent.Started -> projection.next(status = "Agent 正在理解任务…")
            is AiEvent.Status -> projection.next(status = event.label.toAgentStatus())
            is AiEvent.AgentProgress -> projection.next(
                status = event.title,
                tools = if (event.kind == AgentProgressKind.TOOL) {
                    projection.tools.merge(event)
                } else {
                    projection.tools
                },
            )
            is AiEvent.DescriptionDelta -> projection
            is AiEvent.PreviewReset -> projection.next(preview = "")
            is AiEvent.PreviewDelta -> projection.next(
                preview = (projection.preview + event.text).take(MAX_ASSISTANT_CHARS),
                status = "正在生成回答…",
            )
            is AiEvent.PreviewReplace -> projection.next(
                preview = event.text.take(MAX_ASSISTANT_CHARS),
                status = "正在生成回答…",
            )
            is AiEvent.Usage -> projection.next(
                inputTokens = event.inputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                outputTokens = event.outputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
            is AiEvent.FinalAnswer -> {
                val messages = (
                    projection.messages + AgentHubMessage(
                        AgentHubMessageRole.ASSISTANT,
                        event.text,
                        System.currentTimeMillis(),
                    )
                ).takeLast(MAX_RETAINED_MESSAGES)
                persist(messages)
                projection.next(
                    messages = messages,
                    preview = "",
                    status = "回答完成",
                    running = false,
                    requestId = null,
                    conversations = conversationSummaries(messages, archives),
                )
            }
            is AiEvent.FinalPatch -> projection.next(
                preview = "",
                status = "Agent 返回了编辑提案",
                running = false,
                requestId = null,
            )
            is AiEvent.Cancelled -> projection.next(
                preview = "",
                status = "任务已停止",
                running = false,
                requestId = null,
            )
            is AiEvent.Failed -> projection.next(
                preview = "",
                status = event.code.toHubMessage(),
                running = false,
                requestId = null,
            )
        }
        publish()
    }

    private fun transcript(messages: List<AgentHubMessage>): String {
        val header = "Sense Agent Hub conversation. Answer the latest USER message and use " +
            "terminal_exec or browser_use when they materially help.\n\n"
        val selected = ArrayDeque<String>()
        var used = header.length
        messages.asReversed().forEach { message ->
            val line = "${message.role.name}: ${message.text}\n"
            if (used + line.length <= MAX_TRANSCRIPT_CHARS) {
                selected.addFirst(line)
                used += line.length
            }
        }
        return header + selected.joinToString("")
    }

    private fun persist(messages: List<AgentHubMessage>) {
        val snapshot = messages.toList()
        io.execute { store.save(snapshot) }
    }

    private fun archiveCurrentConversation(messages: List<AgentHubMessage>) {
        if (messages.isEmpty()) return
        val archive = AgentHubConversationArchive(
            id = UUID.randomUUID().toString(),
            messages = messages.takeLast(MAX_RETAINED_MESSAGES),
        )
        val retained = (listOf(archive) + archives).take(MAX_ARCHIVED_CONVERSATIONS)
        val dropped = archives.filterNot { existing -> retained.any { it.id == existing.id } }
        archives = retained
        io.execute {
            store.saveArchive(archive)
            dropped.forEach { store.deleteArchive(it.id) }
        }
    }

    private fun publish() {
        observers.toList().forEach { observer -> observer.onProjection(projection) }
    }

    private fun AgentHubProjection.next(
        messages: List<AgentHubMessage> = this.messages,
        tools: List<AgentHubToolStep> = this.tools,
        preview: String = this.preview,
        status: String = this.status,
        running: Boolean = this.running,
        requestId: String? = this.requestId,
        inputTokens: Int = this.inputTokens,
        outputTokens: Int = this.outputTokens,
        conversations: List<AgentHubConversationSummary> = this.conversations,
    ): AgentHubProjection = copy(
        revision = revision + 1,
        messages = messages,
        tools = tools,
        preview = preview,
        status = status,
        running = running,
        requestId = requestId,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        conversations = conversations,
    )

    private fun String.toAgentStatus(): String = when (this) {
        "connecting" -> "正在连接模型…"
        "understanding" -> "正在理解任务…"
        "thinking" -> "Agent 正在思考…"
        "tool_running" -> "Agent 正在运行工具…"
        "generating" -> "正在生成回答…"
        "validating_answer" -> "正在整理回答…"
        else -> replace('_', ' ')
    }

    private fun HarnessErrorCode.toHubMessage(): String =
        when (this) {
            HarnessErrorCode.PROVIDER_NOT_CONFIGURED ->
                "请先在 Sense 设置中配置 AI Provider"
            HarnessErrorCode.PROVIDER_AUTHENTICATION ->
                "Provider 认证失败，请检查 API Key"
            HarnessErrorCode.PROVIDER_RATE_LIMIT ->
                "Provider 请求过多，请稍后重试"
            HarnessErrorCode.FIRST_EVENT_TIMEOUT,
            HarnessErrorCode.STREAM_IDLE_TIMEOUT,
            HarnessErrorCode.TOTAL_TIMEOUT,
            -> "本次任务超时，可再次发送"
            else -> "本次任务未完成：$name"
        }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    companion object {
        @Volatile
        private var instance: SenseAgentHubRuntime? = null

        fun get(context: Context): SenseAgentHubRuntime = instance ?: synchronized(this) {
            instance ?: SenseAgentHubRuntime(context).also { instance = it }
        }

        const val SESSION_FIELD_IDENTITY = "sense.agent-hub.default"
        const val MAX_USER_MESSAGE_CHARS = 12_000
        const val CURRENT_CONVERSATION_ID = "current"
        private const val MAX_ASSISTANT_CHARS = 8_192
        private const val MAX_TRANSCRIPT_CHARS = 48_000
        private const val MAX_RETAINED_MESSAGES = 80
        private const val MAX_ARCHIVED_CONVERSATIONS = 40

        private fun nextGeneration(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}

internal fun List<AgentHubToolStep>.merge(event: AiEvent.AgentProgress): List<AgentHubToolStep> {
    require(event.kind == AgentProgressKind.TOOL)
    val next = AgentHubToolStep(
        id = event.stepId,
        toolCallId = event.toolCallId,
        toolName = event.toolName,
        title = event.title,
        detail = event.detail,
        state = when (event.state) {
            AgentProgressState.RUNNING -> AgentHubToolState.RUNNING
            AgentProgressState.COMPLETED -> AgentHubToolState.COMPLETED
            AgentProgressState.FAILED -> AgentHubToolState.FAILED
        },
    )
    val index = indexOfFirst { existing ->
        existing.id == event.stepId ||
            (event.toolCallId != null && existing.toolCallId == event.toolCallId)
    }
    if (index < 0) return (this + next).takeLast(24)
    return toMutableList().also { it[index] = next }
}

internal class AgentConversationStore(context: Context) {
    private val root = File(context.filesDir, "agent/sessions/default")
    private val file = AtomicFile(File(root, "conversation.v1"))
    private val archiveRoot = File(context.filesDir, "agent/sessions/archive")

    fun load(): Result<List<AgentHubMessage>> = runCatching {
        if (!file.baseFile.exists()) return@runCatching emptyList()
        AgentConversationCodec.decode(
            file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() },
        )
    }

    fun save(messages: List<AgentHubMessage>) {
        if (!root.exists() && !root.mkdirs()) error("Agent session directory could not be created")
        val stream = file.startWrite()
        try {
            stream.write(
                AgentConversationCodec.encode(messages).toByteArray(StandardCharsets.UTF_8),
            )
            stream.flush()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    fun loadArchives(): Result<List<AgentHubConversationArchive>> = runCatching {
        if (!archiveRoot.exists()) return@runCatching emptyList()
        archiveRoot.listFiles { candidate -> candidate.extension == ARCHIVE_EXTENSION }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .mapNotNull { archiveFile ->
                runCatching {
                    val atomic = AtomicFile(archiveFile)
                    AgentHubConversationArchive(
                        id = archiveFile.nameWithoutExtension,
                        messages = atomic.openRead().bufferedReader(StandardCharsets.UTF_8).use {
                            AgentConversationCodec.decode(it.readText())
                        },
                    )
                }.getOrNull()
            }
            .take(MAX_LOADED_ARCHIVES)
    }

    fun saveArchive(archive: AgentHubConversationArchive) {
        require(archive.id.matches(ARCHIVE_ID))
        if (archive.messages.isEmpty()) return
        if (!archiveRoot.exists() && !archiveRoot.mkdirs()) {
            error("Agent archive directory could not be created")
        }
        val atomic = AtomicFile(File(archiveRoot, "${archive.id}.$ARCHIVE_EXTENSION"))
        val stream = atomic.startWrite()
        try {
            stream.write(
                AgentConversationCodec.encode(archive.messages).toByteArray(StandardCharsets.UTF_8),
            )
            stream.flush()
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    fun deleteArchive(id: String) {
        if (!id.matches(ARCHIVE_ID)) return
        AtomicFile(File(archiveRoot, "$id.$ARCHIVE_EXTENSION")).delete()
    }

    private companion object {
        const val ARCHIVE_EXTENSION = "session"
        const val MAX_LOADED_ARCHIVES = 40
        val ARCHIVE_ID = Regex("[0-9a-fA-F-]{36}")
    }
}

internal fun conversationSummaries(
    current: List<AgentHubMessage>,
    archives: List<AgentHubConversationArchive>,
): List<AgentHubConversationSummary> = buildList {
    if (current.isNotEmpty()) {
        add(current.toSummary(SenseAgentHubRuntime.CURRENT_CONVERSATION_ID, current = true))
    }
    archives.forEach { archive ->
        if (archive.messages.isNotEmpty()) add(archive.messages.toSummary(archive.id, current = false))
    }
}

private fun List<AgentHubMessage>.toSummary(
    id: String,
    current: Boolean,
): AgentHubConversationSummary {
    val firstUser = firstOrNull { it.role == AgentHubMessageRole.USER }?.text.orEmpty()
    return AgentHubConversationSummary(
        id = id,
        title = firstUser.lineSequence().firstOrNull().orEmpty().take(32).ifBlank { "Sense 会话" },
        preview = lastOrNull()?.text.orEmpty().lineSequence().firstOrNull().orEmpty().take(80),
        updatedAtEpochMs = maxOfOrNull(AgentHubMessage::createdAtEpochMs) ?: 0L,
        messageCount = size,
        current = current,
    )
}

internal object AgentConversationCodec {
    fun encode(messages: List<AgentHubMessage>): String = buildString {
        appendLine("sense.agent.conversation.v1")
        messages.forEach { message ->
            append(message.role.name)
            append('|').append(message.createdAtEpochMs)
            append('|').append(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    message.text.toByteArray(StandardCharsets.UTF_8),
                ),
            )
            append('\n')
        }
    }

    fun decode(document: String): List<AgentHubMessage> {
        val lines = document.lineSequence().toList()
        require(lines.firstOrNull() == "sense.agent.conversation.v1")
        return lines.drop(1).filter(String::isNotBlank).map { line ->
            val parts = line.split('|', limit = 3)
            require(parts.size == 3)
            val role = AgentHubMessageRole.valueOf(parts[0])
            val timestamp = parts[1].toLong()
            val text = Base64.getUrlDecoder().decode(parts[2])
                .toString(StandardCharsets.UTF_8)
            require(text.isNotBlank() && text.length <= 12_000)
            AgentHubMessage(role, text, timestamp)
        }.takeLast(80)
    }
}
