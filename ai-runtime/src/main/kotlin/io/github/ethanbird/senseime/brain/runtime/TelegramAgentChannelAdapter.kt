package io.github.ethanbird.senseime.brain.runtime

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import org.json.JSONObject

internal class TelegramAgentChannelAdapter(
    token: String,
    initialOffset: Long,
    private val advanceOffset: (Long) -> Unit,
) : AgentChannelAdapter {
    override val type = AgentChannelType.TELEGRAM
    private val baseUrl = "https://api.telegram.org/bot$token"
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val poller = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-agent-telegram-poll").apply { isDaemon = true }
    }
    private val outbound = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "sense-agent-telegram-send").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val activePoll = AtomicReference<Call?>()
    @Volatile
    private var offset = initialOffset.coerceAtLeast(0L)

    init {
        require(TelegramBotTokenValidator.isValid(token)) {
            "Telegram bot token format is invalid"
        }
    }

    override fun start(
        inbound: AgentChannelInboundListener,
        stateChanged: (AgentChannelConnectionState) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        stateChanged(AgentChannelConnectionState(type, AgentChannelConnectionPhase.STARTING))
        poller.execute { pollLoop(inbound, stateChanged) }
    }

    private fun pollLoop(
        inbound: AgentChannelInboundListener,
        stateChanged: (AgentChannelConnectionState) -> Unit,
    ) {
        var delayMs = 1_000L
        var connected = false
        var fatalErrorPublished = false
        while (running.get()) {
            try {
                val url = "$baseUrl/getUpdates".toHttpUrl().newBuilder()
                    .addQueryParameter("offset", offset.toString())
                    .addQueryParameter("timeout", "25")
                    .addQueryParameter("limit", "50")
                    .addQueryParameter("allowed_updates", "[\"message\",\"edited_message\"]")
                    .build()
                val request = Request.Builder().url(url).get().build()
                val call = http.newCall(request)
                activePoll.set(call)
                if (!running.get()) call.cancel()
                val body = try {
                    call.execute().use { response ->
                        if (response.code == 401 || response.code == 403) {
                            throw AgentChannelFatalException(
                                "Telegram authentication rejected (HTTP ${response.code})",
                            )
                        }
                        if (!response.isSuccessful) throw IOException("Telegram HTTP ${response.code}")
                        response.body.string()
                    }
                } finally {
                    activePoll.compareAndSet(call, null)
                }
                val root = JSONObject(body)
                if (!root.optBoolean("ok")) throw IOException("Telegram API rejected getUpdates")
                if (!connected) {
                    connected = true
                    stateChanged(AgentChannelConnectionState(type, AgentChannelConnectionPhase.CONNECTED))
                }
                delayMs = 1_000L
                val updates = root.getJSONArray("result")
                var backpressured = false
                for (index in 0 until updates.length()) {
                    val update = updates.getJSONObject(index)
                    val updateId = update.getLong("update_id")
                    val message = update.optJSONObject("message")
                        ?: update.optJSONObject("edited_message")
                    val decoded = message?.let { decodeInbound(updateId, it) }
                    if (decoded != null && inbound.onInbound(decoded) == AgentChannelAdmission.RETRY_LATER) {
                        backpressured = true
                        break
                    }
                    val next = updateId + 1L
                    if (next > offset) {
                        offset = TelegramOffsetCommit.advance(offset, next, advanceOffset)
                    }
                }
                if (backpressured) sleepInterruptibly(500L)
            } catch (failure: Throwable) {
                if (!running.get()) break
                if (AgentChannelFailureClassifier.isFatalAuthentication(type, failure)) {
                    running.set(false)
                    fatalErrorPublished = true
                    stateChanged(
                        AgentChannelConnectionState(
                            type,
                            AgentChannelConnectionPhase.ERROR,
                            publicError(failure),
                        ),
                    )
                    break
                }
                connected = false
                stateChanged(
                    AgentChannelConnectionState(
                        type,
                        AgentChannelConnectionPhase.RETRYING,
                        publicError(failure),
                    ),
                )
                sleepInterruptibly(delayMs)
                delayMs = (delayMs * 2L).coerceAtMost(30_000L)
            }
        }
        if (AgentChannelTerminalStatePolicy.publishStopped(fatalErrorPublished)) {
            stateChanged(AgentChannelConnectionState(type, AgentChannelConnectionPhase.STOPPED))
        }
    }

    private fun decodeInbound(updateId: Long, message: JSONObject): AgentChannelInbound? {
        val text = message.optString("text").ifBlank { message.optString("caption") }.trim()
        if (text.isEmpty()) return null
        val chat = message.getJSONObject("chat")
        val peer = message.optJSONObject("from")?.optLong("id")?.takeIf { it != 0L }
            ?: message.optJSONObject("sender_chat")?.optLong("id")?.takeIf { it != 0L }
            ?: chat.getLong("id")
        return AgentChannelInbound(
            source = AgentChannelSource(
                channel = type,
                chatId = chat.getLong("id").toString(),
                peerId = peer.toString(),
                messageId = "${message.getLong("message_id")}@$updateId",
                threadId = message.optLong("message_thread_id")
                    .takeIf { it != 0L }
                    ?.toString(),
            ),
            text = AgentChannelUnicode.truncate(text, AgentChannelInbound.MAX_TEXT_CHARS),
            receivedAtEpochMs = message.optLong("date").takeIf { it > 0L }?.times(1_000L)
                ?: System.currentTimeMillis(),
        )
    }

    override fun sendText(source: AgentChannelSource, text: String): CompletableFuture<String> {
        if (!running.get() || closed.get()) {
            return failedAgentChannelFuture(
                IllegalStateException("Telegram channel is not running"),
            )
        }
        return agentChannelFuture {
            CompletableFuture.supplyAsync(
            {
                val form = FormBody.Builder()
                    .add("chat_id", source.chatId)
                    .add("text", telegramTextForDelivery(text))
                    .apply { source.threadId?.let { add("message_thread_id", it) } }
                    .build()
                val json = post("sendMessage", form)
                json.getJSONObject("result").getLong("message_id").toString()
            },
            outbound,
        )
        }
    }

    override fun editText(
        source: AgentChannelSource,
        remoteMessageId: String,
        text: String,
    ): CompletableFuture<Unit> {
        if (!running.get() || closed.get()) {
            return failedAgentChannelFuture(
                IllegalStateException("Telegram channel is not running"),
            )
        }
        return agentChannelFuture {
            CompletableFuture.supplyAsync(
                {
                    val form = FormBody.Builder()
                        .add("chat_id", source.chatId)
                        .add("message_id", remoteMessageId)
                        .add("text", telegramTextForDelivery(text))
                        .build()
                    post("editMessageText", form, acceptUnmodifiedEdit = true)
                    Unit
                },
                outbound,
            )
        }
    }

    private fun post(
        method: String,
        form: FormBody,
        acceptUnmodifiedEdit: Boolean = false,
    ): JSONObject {
        val request = Request.Builder().url("$baseUrl/$method").post(form).build()
        return http.newCall(request).execute().use { response ->
            val document = response.body.string()
            val json = runCatching { JSONObject(document) }.getOrNull()
            if (!response.isSuccessful || json?.optBoolean("ok") != true) {
                if (
                    acceptUnmodifiedEdit &&
                    TelegramEditResponse.isIdempotentSuccess(
                        httpCode = response.code,
                        description = json?.optString("description"),
                    )
                ) {
                    return@use checkNotNull(json)
                }
                throw IOException("Telegram $method HTTP ${response.code}")
            }
            json
        }
    }

    private fun sleepInterruptibly(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun publicError(failure: Throwable): String = failure.message
        ?.replace(Regex("bot[0-9]{5,16}:[A-Za-z0-9_-]+"), "bot***")
        ?.take(180)
        .orEmpty()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        activePoll.getAndSet(null)?.cancel()
        poller.shutdownNow()
        outbound.shutdownNow()
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        (http.cache as? Closeable)?.close()
    }
}

internal object TelegramOffsetCommit {
    fun advance(current: Long, next: Long, persist: (Long) -> Unit): Long {
        if (next <= current) return current
        persist(next)
        return next
    }
}

internal object TelegramEditResponse {
    private const val NOT_MODIFIED_PREFIX = "Bad Request: message is not modified"

    fun isIdempotentSuccess(httpCode: Int, description: String?): Boolean =
        httpCode == 400 && description?.startsWith(NOT_MODIFIED_PREFIX) == true
}

internal fun telegramTextForDelivery(value: String): String {
    val text = value.takeIf(String::isNotBlank) ?: "Sense Agent"
    if (text.length <= 4_000) return text
    var end = 3_980.coerceAtMost(text.length)
    if (
        end in 1 until text.length &&
        Character.isHighSurrogate(text[end - 1]) &&
        Character.isLowSurrogate(text[end])
    ) {
        end--
    }
    return text.substring(0, end) + "\n…"
}
