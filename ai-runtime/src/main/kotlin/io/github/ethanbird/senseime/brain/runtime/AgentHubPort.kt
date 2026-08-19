package io.github.ethanbird.senseime.brain.runtime

import java.util.UUID

enum class AgentHubCommandOutcomeCode {
    ACCEPTED,
    REJECTED,
    CANCELLED,
}

data class AgentHubCommandOutcome(
    val clientCommandId: String,
    val code: AgentHubCommandOutcomeCode,
    val requestId: String? = null,
    val generation: Long = 0,
)

fun interface AgentHubCommandCallback {
    fun onComplete(outcome: AgentHubCommandOutcome)
}

interface AgentHubCommandHandle : AutoCloseable {
    val clientCommandId: String
}

/**
 * Narrow frontend contract for the single Agent Hub owner.
 *
 * The owner lives in `:brain`. In-process clients (the Hub activity and message-channel service)
 * use [SenseAgentHubRuntime] directly, while the IME process uses
 * [RemoteSenseAgentHubClient]. Keeping storage and tool runtimes behind this boundary prevents an
 * IME process from accidentally creating a second conversation owner.
 */
interface AgentHubPort {
    fun observe(observer: AgentHubObserver): AutoCloseable

    fun currentProjection(): AgentHubProjection

    fun fetchHistoryPage(request: AgentHubHistoryRequest): AgentHubHistoryPage

    fun fetchConversationPage(request: AgentHubConversationPageRequest): AgentHubConversationPage

    fun prepareRun(message: String): AgentHubPreparedRun?

    fun sendPrepared(prepared: AgentHubPreparedRun): Boolean

    fun send(message: String): Boolean = prepareRun(message)?.let(::sendPrepared) == true

    fun stop(): Boolean

    fun clearConversation(): Boolean

    fun openConversation(id: String): Boolean

    fun runGoldQuote(): Boolean

    fun cancelAction(): Boolean

    fun dismissAction(): Boolean

    fun sendAsync(message: String, callback: AgentHubCommandCallback): AgentHubCommandHandle {
        val prepared = prepareRun(message)
        return if (prepared == null) {
            immediate(callback, false)
        } else {
            sendPreparedAsync(prepared, callback)
        }
    }

    fun sendPreparedAsync(
        prepared: AgentHubPreparedRun,
        callback: AgentHubCommandCallback,
    ): AgentHubCommandHandle = immediate(
        callback = callback,
        accepted = sendPrepared(prepared),
        requestId = prepared.requestId,
        generation = prepared.generation,
    )

    fun stopAsync(callback: AgentHubCommandCallback): AgentHubCommandHandle =
        immediate(callback, stop())

    fun clearConversationAsync(callback: AgentHubCommandCallback): AgentHubCommandHandle =
        immediate(callback, clearConversation())

    fun openConversationAsync(
        id: String,
        callback: AgentHubCommandCallback,
    ): AgentHubCommandHandle = immediate(callback, openConversation(id))

    fun runGoldQuoteAsync(callback: AgentHubCommandCallback): AgentHubCommandHandle =
        immediate(callback, runGoldQuote())

    fun cancelActionAsync(callback: AgentHubCommandCallback): AgentHubCommandHandle =
        immediate(callback, cancelAction())

    fun dismissActionAsync(callback: AgentHubCommandCallback): AgentHubCommandHandle =
        immediate(callback, dismissAction())

    private fun immediate(
        callback: AgentHubCommandCallback,
        accepted: Boolean,
        requestId: String? = null,
        generation: Long = 0,
    ): AgentHubCommandHandle {
        val id = UUID.randomUUID().toString()
        callback.onComplete(
            AgentHubCommandOutcome(
                clientCommandId = id,
                code = if (accepted) {
                    AgentHubCommandOutcomeCode.ACCEPTED
                } else {
                    AgentHubCommandOutcomeCode.REJECTED
                },
                requestId = requestId,
                generation = generation,
            ),
        )
        return object : AgentHubCommandHandle {
            override val clientCommandId = id
            override fun close() = Unit
        }
    }
}
