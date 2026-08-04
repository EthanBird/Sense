package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import io.github.ethanbird.senseime.ai.protocol.AiEvent
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
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.Base64
import java.util.concurrent.Executors

enum class AgentHubMessageRole {
    USER,
    ASSISTANT,
}

data class AgentHubMessage(
    val role: AgentHubMessageRole,
    val text: String,
    val createdAtEpochMs: Long,
)

data class AgentHubProjection(
    val revision: Long = 0,
    val loaded: Boolean = false,
    val messages: List<AgentHubMessage> = emptyList(),
    val preview: String = "",
    val status: String = "正在读取会话…",
    val running: Boolean = false,
    val requestId: String? = null,
    val generation: Long = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

fun interface AgentHubObserver {
    fun onProjection(projection: AgentHubProjection)
}

/**
 * Durable, process-local owner for the Agent Hub conversation.
 *
 * Activities attach and detach observers; this owner retains the Brain client and current run so
 * browser takeover, Activity recreation and IME window changes do not imply cancellation.
 */
class SenseAgentHubRuntime private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-agent-hub-store").apply { isDaemon = true }
    }
    private val store = AgentConversationStore(applicationContext)
    private val observers = linkedSetOf<AgentHubObserver>()
    private val brainClient = SenseAiBrainClient(applicationContext, ::onBrainEvent)
    private var projection = AgentHubProjection()

    init {
        io.execute {
            val loaded = store.load().getOrDefault(emptyList())
            mainHandler.post {
                projection = projection.copy(
                    revision = projection.revision + 1,
                    loaded = true,
                    messages = loaded.takeLast(MAX_RETAINED_MESSAGES),
                    status = "Agent 已就绪",
                )
                publish()
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
        projection = projection.copy(
            revision = projection.revision + 1,
            messages = messages,
            preview = "",
            status = "正在启动 Agent…",
            running = true,
            requestId = requestId,
            generation = generation,
            inputTokens = 0,
            outputTokens = 0,
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
        brainClient.cancel(
            requestId,
            projection.generation,
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

    fun clearConversation(): Boolean {
        checkMainThread()
        if (!projection.loaded || projection.running) return false
        projection = projection.copy(
            revision = projection.revision + 1,
            messages = emptyList(),
            preview = "",
            status = "已开始新会话",
            inputTokens = 0,
            outputTokens = 0,
        )
        persist(emptyList())
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
        projection = when (event) {
            is AiEvent.Started -> projection.next(status = "Agent 正在理解任务…")
            is AiEvent.Status -> projection.next(status = event.label.toAgentStatus())
            is AiEvent.AgentProgress -> projection.next(status = event.title)
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

    private fun publish() {
        observers.toList().forEach { observer -> observer.onProjection(projection) }
    }

    private fun AgentHubProjection.next(
        messages: List<AgentHubMessage> = this.messages,
        preview: String = this.preview,
        status: String = this.status,
        running: Boolean = this.running,
        requestId: String? = this.requestId,
        inputTokens: Int = this.inputTokens,
        outputTokens: Int = this.outputTokens,
    ): AgentHubProjection = copy(
        revision = revision + 1,
        messages = messages,
        preview = preview,
        status = status,
        running = running,
        requestId = requestId,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
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
        private const val MAX_ASSISTANT_CHARS = 8_192
        private const val MAX_TRANSCRIPT_CHARS = 48_000
        private const val MAX_RETAINED_MESSAGES = 80

        private fun nextGeneration(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}

internal class AgentConversationStore(context: Context) {
    private val root = File(context.filesDir, "agent/sessions/default")
    private val file = AtomicFile(File(root, "conversation.v1"))

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
