package io.github.ethanbird.senseime.brain.runtime

import com.lark.oapi.channel.ChannelEventHandler
import com.lark.oapi.channel.LarkChannel
import com.lark.oapi.channel.LarkChannelFactory
import com.lark.oapi.channel.config.LarkChannelOptions
import com.lark.oapi.channel.model.NormalizedMessage
import com.lark.oapi.channel.model.SendInput
import com.lark.oapi.channel.model.SendOptions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Official Feishu/Lark Channel SDK WebSocket transport; no public callback endpoint is needed. */
internal class FeishuAgentChannelAdapter(
    private val appId: String,
    private val appSecret: String,
    private val domain: FeishuDomain,
) : AgentChannelAdapter {
    override val type = AgentChannelType.FEISHU
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val transportClosed = AtomicBoolean(false)
    private val reconnect = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sense-agent-feishu-reconnect").apply { isDaemon = true }
    }
    private val retryPolicy = AgentChannelRetryPolicy()
    private val admissionLane = FeishuAdmissionLane()
    private val lifecycleLock = Any()
    @Volatile
    private var channel: LarkChannel? = null

    init {
        require(appId.isNotBlank())
        require(appSecret.isNotBlank())
    }

    override fun start(
        inbound: AgentChannelInboundListener,
        stateChanged: (AgentChannelConnectionState) -> Unit,
    ) {
        if (closed.get() || !running.compareAndSet(false, true)) return
        stateChanged(AgentChannelConnectionState(type, AgentChannelConnectionPhase.STARTING))
        reconnect.execute { connectAttempt(inbound, stateChanged, failedAttempts = 0) }
    }

    private fun connectAttempt(
        inbound: AgentChannelInboundListener,
        stateChanged: (AgentChannelConnectionState) -> Unit,
        failedAttempts: Int,
    ) {
        if (!running.get() || closed.get()) return
        val options = LarkChannelOptions.newBuilder(appId, appSecret)
            .transport("websocket")
            .domain(domain.openApiHost)
            .source("sense-android")
            .safety(feishuIndependentMessageSafety())
            .build()
        val created = LarkChannelFactory.createLarkChannel(options)
        created.on(
            "message",
            ChannelEventHandler<NormalizedMessage> { message ->
                decode(message)?.let { decoded ->
                    val admitted = admissionLane.admit(decoded, inbound) {
                        running.get() && !closed.get()
                    }
                    if (!admitted) awaitTransportClosed()
                }
            },
        )
        created.on(
            "reconnecting",
            ChannelEventHandler<Any?> {
                if (running.get() && channel === created) {
                    stateChanged(
                        AgentChannelConnectionState(
                            type,
                            AgentChannelConnectionPhase.RETRYING,
                            "飞书 WebSocket 正在重连",
                        ),
                    )
                }
            },
        )
        created.on(
            "reconnected",
            ChannelEventHandler<Any?> {
                if (running.get() && channel === created) {
                    stateChanged(
                        AgentChannelConnectionState(type, AgentChannelConnectionPhase.CONNECTED),
                    )
                }
            },
        )
        synchronized(lifecycleLock) {
            if (!running.get() || closed.get()) {
                runCatching { created.disconnect() }
                return
            }
            channel = created
        }
        created.connect()
            .withAgentChannelTimeout(reconnect, 30, TimeUnit.SECONDS)
            .whenComplete { _, failure ->
                if (closed.get()) {
                    runCatching { created.disconnect() }
                    return@whenComplete
                }
                runCatching { reconnect.execute {
                    if (!running.get() || closed.get() || channel !== created) {
                        runCatching { created.disconnect() }
                        return@execute
                    }
                    if (failure == null) {
                        stateChanged(
                            AgentChannelConnectionState(type, AgentChannelConnectionPhase.CONNECTED),
                        )
                        return@execute
                    }
                    channel = null
                    runCatching { created.disconnect() }
                    if (AgentChannelFailureClassifier.isFatalAuthentication(type, failure)) {
                        running.set(false)
                        stateChanged(
                            AgentChannelConnectionState(
                                type,
                                AgentChannelConnectionPhase.ERROR,
                                failure.message?.take(180).orEmpty(),
                            ),
                        )
                        return@execute
                    }
                    val nextAttempt = failedAttempts + 1
                    stateChanged(
                        AgentChannelConnectionState(
                            type,
                            AgentChannelConnectionPhase.RETRYING,
                            failure.message?.take(180).orEmpty(),
                        ),
                    )
                    reconnect.schedule(
                        { connectAttempt(inbound, stateChanged, nextAttempt) },
                        retryPolicy.delayMs(nextAttempt),
                        TimeUnit.MILLISECONDS,
                    )
                } }
            }
    }

    private fun awaitTransportClosed() {
        while (closed.get() && !transportClosed.get()) {
            try {
                Thread.sleep(10L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun decode(message: NormalizedMessage): AgentChannelInbound? {
        val text = message.content?.trim().orEmpty()
        if (text.isEmpty()) return null
        val peerId = message.senderId?.takeIf(String::isNotBlank) ?: return null
        val messageId = message.messageId?.takeIf(String::isNotBlank) ?: return null
        val chatId = message.chatId?.takeIf(String::isNotBlank) ?: return null
        return AgentChannelInbound(
            source = AgentChannelSource(
                channel = type,
                chatId = chatId,
                peerId = peerId,
                messageId = messageId,
                threadId = message.threadId?.takeIf(String::isNotBlank),
            ),
            text = AgentChannelUnicode.truncate(text, AgentChannelInbound.MAX_TEXT_CHARS),
            receivedAtEpochMs = message.createTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
    }

    override fun sendText(source: AgentChannelSource, text: String): CompletableFuture<String> {
        val current = channel ?: return failedAgentChannelFuture(
            IllegalStateException("Feishu channel is not connected"),
        )
        val options = SendOptions.newBuilder()
            .replyTo(source.messageId)
            .build()
        return agentChannelFuture {
            current.send(source.chatId, SendInput.text(feishuText(text)), options)
                .thenApply { it.messageId }
        }
    }

    override fun editText(
        source: AgentChannelSource,
        remoteMessageId: String,
        text: String,
    ): CompletableFuture<Unit> {
        val current = channel ?: return failedAgentChannelFuture(
            IllegalStateException("Feishu channel is not connected"),
        )
        return agentChannelFuture {
            current.editMessage(remoteMessageId, feishuText(text)).thenApply { Unit }
        }
    }

    private fun feishuText(text: String): String =
        AgentChannelUnicode.truncate(
            text.takeIf(String::isNotBlank) ?: "Sense Agent",
            18_000,
        )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        val current = synchronized(lifecycleLock) {
            channel.also { channel = null }
        }
        admissionLane.close()
        reconnect.shutdownNow()
        val disconnect = runCatching { current?.disconnect() }.getOrNull()
        if (disconnect == null) {
            transportClosed.set(true)
        } else {
            disconnect.whenComplete { _, _ -> transportClosed.set(true) }
        }
    }
}

internal fun feishuIndependentMessageSafety(): LarkChannelOptions.SafetyConfig =
    LarkChannelOptions.SafetyConfig().apply {
        // SDK default batching merges several message IDs/content payloads. The durable Sense
        // journal requires one normalized platform event per eventKey.
        isChatQueueEnabled = false
    }

/**
 * The Feishu SDK may invoke message handlers concurrently. This bounded lane serializes durable
 * admission in callback-arrival order, and keeps the head event in place until AtomicFile accepts
 * it. A full lane blocks producers instead of dropping or acknowledging a platform event.
 */
internal class FeishuAdmissionLane(
    capacity: Int = 32,
) : AutoCloseable {
    private data class Task(
        val inbound: AgentChannelInbound,
        val listener: AgentChannelInboundListener,
        val keepRunning: () -> Boolean,
        val completion: CompletableFuture<Boolean> = CompletableFuture(),
    )

    private val closed = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<Task>(capacity, true)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-agent-feishu-admission").apply { isDaemon = true }
    }

    init {
        require(capacity > 0)
        worker.execute(::runLoop)
    }

    fun admit(
        inbound: AgentChannelInbound,
        listener: AgentChannelInboundListener,
        keepRunning: () -> Boolean,
    ): Boolean {
        val task = Task(inbound, listener, keepRunning)
        try {
            while (!closed.get() && keepRunning()) {
                if (queue.offer(task, 50L, TimeUnit.MILLISECONDS)) {
                    return task.completion.get()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: java.util.concurrent.ExecutionException) {
            // The worker completes with a Boolean; this only covers executor teardown races.
        }
        return false
    }

    private fun runLoop() {
        var current: Task? = null
        try {
            while (!closed.get()) {
                current = queue.poll(100L, TimeUnit.MILLISECONDS) ?: continue
                current.completion.complete(admitHead(current))
                current = null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            current?.completion?.complete(false)
            drainClosed()
        }
    }

    private fun admitHead(task: Task): Boolean {
        var delayMs = 25L
        while (!closed.get() && task.keepRunning()) {
            when (
                runCatching { task.listener.onInbound(task.inbound) }
                    .getOrDefault(AgentChannelAdmission.RETRY_LATER)
            ) {
                AgentChannelAdmission.ADMITTED,
                AgentChannelAdmission.DUPLICATE,
                -> return true
                AgentChannelAdmission.RETRY_LATER -> try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            delayMs = (delayMs * 2L).coerceAtMost(250L)
        }
        return false
    }

    private fun drainClosed() {
        while (true) (queue.poll() ?: return).completion.complete(false)
    }

    internal fun queuedCountForTest(): Int = queue.size

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.shutdownNow()
        drainClosed()
    }
}
