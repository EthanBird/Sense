package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolId
import java.net.URI

/**
 * Closed model-JSON to typed-executor boundary.
 *
 * The request factory controls discoverability, while this router independently enforces the
 * frozen user-enabled set. A model cannot invoke a hidden/disabled tool by guessing its name.
 */
internal object AgentToolRouter {
    fun decode(
        callId: String,
        toolName: String,
        argumentsDocument: String,
        enabledTools: Set<AgentToolId>,
        requestId: String? = null,
        runGeneration: Long? = null,
    ): AgentToolCall {
        val tool = AgentToolId.fromWireValue(toolName)
            ?: throw ProviderPayloadException("unknown Agent tool")
        if (tool !in enabledTools) {
            throw ProviderPayloadException("Agent tool is not enabled for this run")
        }
        val members = (ProviderJson.parse(argumentsDocument) as? JsonValue.ObjectValue)?.members
            ?: throw ProviderPayloadException("Agent tool arguments must be an object")
        val arguments = when (tool) {
            AgentToolId.WEB_SEARCH -> {
                requireKeys(members, required = setOf("query"), optional = setOf("max_results"))
                AgentToolArguments.WebSearch(
                    query = members.requiredText("query", MAX_QUERY_CHARS),
                    maxResults = members.optionalInt(
                        "max_results",
                        default = DEFAULT_SEARCH_RESULTS,
                        range = 1..MAX_SEARCH_RESULTS,
                    ),
                )
            }
            AgentToolId.WEB_FETCH -> {
                requireKeys(members, required = setOf("url"), optional = setOf("max_chars"))
                val url = members.requiredText("url", MAX_URL_CHARS)
                val uri = runCatching { URI(url) }.getOrNull()
                if (
                    uri == null ||
                    !uri.isAbsolute ||
                    !uri.scheme.equals("https", ignoreCase = true) ||
                    uri.host.isNullOrBlank() ||
                    uri.rawUserInfo != null
                ) {
                    throw ProviderPayloadException("web_fetch requires an absolute HTTPS URL")
                }
                AgentToolArguments.WebFetch(
                    url = uri.toASCIIString(),
                    maxChars = members.optionalInt(
                        "max_chars",
                        default = DEFAULT_FETCH_CHARS,
                        range = 256..MAX_FETCH_CHARS,
                    ),
                )
            }
            AgentToolId.CALCULATOR -> {
                requireKeys(members, required = setOf("expression"), optional = emptySet())
                AgentToolArguments.Calculator(
                    expression = members.requiredText("expression", MAX_EXPRESSION_CHARS),
                )
            }
            AgentToolId.MEMORY_SEARCH -> {
                requireKeys(members, required = setOf("query"), optional = setOf("max_results"))
                AgentToolArguments.MemorySearch(
                    query = members.requiredText("query", MAX_QUERY_CHARS),
                    maxResults = members.optionalInt(
                        "max_results",
                        default = DEFAULT_MEMORY_RESULTS,
                        range = 1..MAX_MEMORY_RESULTS,
                    ),
                )
            }
        }
        return AgentToolCall(
            callId = callId,
            tool = tool,
            arguments = arguments,
            requestId = requestId,
            runGeneration = runGeneration,
        )
    }

    private fun requireKeys(
        members: Map<String, JsonValue>,
        required: Set<String>,
        optional: Set<String>,
    ) {
        val allowed = required + optional
        if (!members.keys.containsAll(required) || members.keys.any { it !in allowed }) {
            throw ProviderPayloadException("Agent tool arguments do not match the closed schema")
        }
    }

    private fun Map<String, JsonValue>.requiredText(name: String, maxChars: Int): String {
        val value = (get(name) as? JsonValue.StringValue)?.value?.trim()
            ?: throw ProviderPayloadException("$name must be a string")
        if (
            value.isEmpty() ||
            value.length > maxChars ||
            value.any { Character.isISOControl(it) && it !in setOf('\n', '\r', '\t') }
        ) {
            throw ProviderPayloadException("$name is outside its bounded text contract")
        }
        return value
    }

    private fun Map<String, JsonValue>.optionalInt(
        name: String,
        default: Int,
        range: IntRange,
    ): Int {
        val raw = get(name) ?: return default
        val value = (raw as? JsonValue.NumberValue)?.value?.toIntOrNull()
            ?: throw ProviderPayloadException("$name must be an integer")
        if (value !in range) {
            throw ProviderPayloadException("$name is outside its supported range")
        }
        return value
    }

    const val MAX_TOOL_RESULT_CHARS = 16_384
    const val MAX_TOOL_TURNS = 6
    const val MAX_QUERY_CHARS = 512
    const val MAX_URL_CHARS = 2_048
    const val MAX_EXPRESSION_CHARS = 512
    const val MAX_FETCH_CHARS = 12_000
    const val MAX_SEARCH_RESULTS = 10
    const val MAX_MEMORY_RESULTS = 20
    const val DEFAULT_FETCH_CHARS = 8_000
    const val DEFAULT_SEARCH_RESULTS = 5
    const val DEFAULT_MEMORY_RESULTS = 8
}
