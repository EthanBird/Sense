package io.github.ethanbird.senseime.brain.api

/**
 * Stable IDs shared by settings, the Brain request factory and the execution router.
 *
 * Wire values are intentionally lowercase and must remain stable once released because saved
 * settings use them.
 */
enum class AgentToolId(val wireValue: String) {
    WEB_SEARCH("web_search"),
    WEB_FETCH("web_fetch"),
    CALCULATOR("calculator"),
    MEMORY_SEARCH("memory_search"),
    ;

    companion object {
        fun fromWireValue(value: String): AgentToolId? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Typed, locally validated arguments. Raw model JSON never reaches an executor. */
sealed interface AgentToolArguments {
    data class WebSearch(
        val query: String,
        val maxResults: Int,
    ) : AgentToolArguments

    data class WebFetch(
        val url: String,
        val maxChars: Int,
    ) : AgentToolArguments

    data class Calculator(
        val expression: String,
    ) : AgentToolArguments

    data class MemorySearch(
        val query: String,
        val maxResults: Int,
    ) : AgentToolArguments
}

data class AgentToolCall(
    val callId: String,
    val tool: AgentToolId,
    val arguments: AgentToolArguments,
    /** Run identity used to prevent memory_search from recalling its own in-flight trace. */
    val requestId: String? = null,
    val runGeneration: Long? = null,
) {
    init {
        require(callId.isNotBlank())
        require(callId.length <= MAX_CALL_ID_CHARS)
        require(callId.none { Character.isISOControl(it) })
        require((requestId == null) == (runGeneration == null))
        requestId?.let { require(it.isNotBlank()) }
        runGeneration?.let { require(it >= 0L) }
    }

    private companion object {
        const val MAX_CALL_ID_CHARS = 256
    }
}

/**
 * One bounded tool result replayed to the provider as a tool message.
 *
 * [content] should be compact JSON whenever practical. Brain applies the authoritative character
 * cap even when an executor is buggy, so implementations do not control Provider prompt growth.
 */
data class AgentToolExecutionResult(
    val content: String,
    val isError: Boolean = false,
)

/**
 * Blocking execution boundary called outside the Brain state lock.
 *
 * Android implementations may perform bounded network or local-memory work here. Cancellation of
 * the Agent run invalidates the result synchronously; executors must still configure their own
 * finite I/O timeouts so abandoned work cannot live indefinitely.
 */
fun interface AgentToolExecutor {
    fun execute(call: AgentToolCall): AgentToolExecutionResult

    companion object {
        val UNAVAILABLE = AgentToolExecutor {
            AgentToolExecutionResult(
                content = "{\"ok\":false,\"error\":\"tool runtime unavailable\"}",
                isError = true,
            )
        }
    }
}
