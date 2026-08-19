package io.github.ethanbird.senseime.brain.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class AgentChannelFatalException(message: String) : RuntimeException(message)

internal object AgentChannelFailureClassifier {
    fun isFatalAuthentication(channel: AgentChannelType, failure: Throwable): Boolean {
        if (failure is AgentChannelFatalException) return true
        val message = generateSequence(failure) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" ")
            .lowercase()
        return when (channel) {
            AgentChannelType.TELEGRAM ->
                "http 401" in message || "http 403" in message ||
                    "unauthorized" in message || "forbidden" in message
            AgentChannelType.FEISHU -> listOf(
                "invalid app secret",
                "invalid app_secret",
                "invalid app id",
                "invalid app_id",
                "authentication failed",
                "unauthorized",
                "forbidden",
                "http 401",
                "http 403",
            ).any(message::contains)
        }
    }
}

internal object AgentChannelTerminalStatePolicy {
    fun publishStopped(fatalErrorPublished: Boolean): Boolean = !fatalErrorPublished
}

internal object AgentChannelTerminalConnectionFence {
    fun accepts(hasTerminalError: Boolean, next: AgentChannelConnectionPhase): Boolean =
        !hasTerminalError || next == AgentChannelConnectionPhase.ERROR
}

internal object AgentChannelDeliveryAvailability {
    fun afterConnectionState(
        current: Set<AgentChannelType>,
        state: AgentChannelConnectionState,
    ): Set<AgentChannelType> = when (state.phase) {
        AgentChannelConnectionPhase.ERROR,
        AgentChannelConnectionPhase.STOPPED,
        -> current - state.channel
        else -> current
    }
}

enum class AgentChannelConnectionPhase {
    STARTING,
    CONNECTED,
    RETRYING,
    STOPPED,
    ERROR,
}

data class AgentChannelConnectionState(
    val channel: AgentChannelType,
    val phase: AgentChannelConnectionPhase,
    val detail: String = "",
)

enum class AgentChannelAdmission {
    ADMITTED,
    DUPLICATE,
    RETRY_LATER,
}

fun interface AgentChannelInboundListener {
    /** The platform advances its cursor only for admitted or already-persisted events. */
    fun onInbound(inbound: AgentChannelInbound): AgentChannelAdmission
}

interface AgentChannelAdapter : AutoCloseable {
    val type: AgentChannelType

    fun start(
        inbound: AgentChannelInboundListener,
        stateChanged: (AgentChannelConnectionState) -> Unit,
    )

    fun sendText(source: AgentChannelSource, text: String): CompletableFuture<String>

    fun editText(source: AgentChannelSource, remoteMessageId: String, text: String): CompletableFuture<Unit>

    override fun close()
}

internal inline fun <T> agentChannelFuture(
    block: () -> CompletableFuture<T>,
): CompletableFuture<T> = try {
    block()
} catch (failure: Throwable) {
    failedAgentChannelFuture(failure)
}

/** Android API 29 compatible replacement for CompletableFuture.failedFuture (API 31). */
internal fun <T> failedAgentChannelFuture(failure: Throwable): CompletableFuture<T> =
    CompletableFuture<T>().also { it.completeExceptionally(failure) }

/** Android API 29 compatible timeout wrapper; CompletableFuture.orTimeout requires API 31. */
internal fun <T> CompletableFuture<T>.withAgentChannelTimeout(
    scheduler: ScheduledExecutorService,
    timeout: Long,
    unit: TimeUnit,
): CompletableFuture<T> {
    val source = this
    val result = CompletableFuture<T>()
    val timeoutTask = scheduler.schedule(
        {
            if (result.completeExceptionally(TimeoutException("Agent channel operation timed out"))) {
                source.cancel(true)
            }
        },
        timeout,
        unit,
    )
    source.whenComplete { value, failure ->
        timeoutTask.cancel(false)
        if (failure == null) {
            result.complete(value)
        } else {
            result.completeExceptionally(failure)
        }
    }
    return result
}
