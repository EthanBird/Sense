package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.api.AgentBrowserAction
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
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
        sessionId: String? = null,
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
            AgentToolId.BROWSER_USE -> decodeBrowserUse(members)
            AgentToolId.TERMINAL_EXEC -> {
                requireKeys(
                    members,
                    required = setOf("command"),
                    optional = setOf("cwd", "timeout_ms"),
                )
                AgentToolArguments.TerminalExec(
                    command = members.requiredText(
                        "command",
                        MAX_TERMINAL_COMMAND_CHARS,
                        trim = false,
                    ),
                    cwd = members.optionalText("cwd", MAX_TERMINAL_CWD_CHARS) ?: ".",
                    timeoutMs = members.optionalInt(
                        "timeout_ms",
                        default = AgentToolArguments.TerminalExec.DEFAULT_TIMEOUT_MS,
                        range = AgentToolArguments.TerminalExec.MIN_TIMEOUT_MS..
                            AgentToolArguments.TerminalExec.MAX_TIMEOUT_MS,
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
            AgentToolId.SKILL_READ -> {
                requireKeys(
                    members,
                    required = setOf("skill_id", "revision"),
                    optional = setOf("offset", "max_chars"),
                )
                AgentToolArguments.SkillRead(
                    skillId = members.requiredText("skill_id", AgentSkillPolicy.MAX_ID_CHARS),
                    revision = members.requiredPositiveLong("revision"),
                    offset = members.optionalInt(
                        "offset",
                        default = 0,
                        range = 0..AgentSkillPolicy.MAX_CONTENT_CHARS,
                    ),
                    maxChars = members.optionalInt(
                        "max_chars",
                        default = AgentToolArguments.SkillRead.DEFAULT_MAX_CHARS,
                        range = AgentToolArguments.SkillRead.MIN_MAX_CHARS..
                            AgentToolArguments.SkillRead.MAX_MAX_CHARS,
                    ),
                )
            }
            AgentToolId.SKILL_MANAGE -> decodeSkillManagement(members)
        }
        return AgentToolCall(
            callId = callId,
            tool = tool,
            arguments = arguments,
            requestId = requestId,
            runGeneration = runGeneration,
            sessionId = sessionId ?: requestId ?: callId,
        )
    }

    private fun decodeBrowserUse(
        members: Map<String, JsonValue>,
    ): AgentToolArguments.BrowserUse {
        val action = AgentBrowserAction.fromWireValue(
            members.requiredText("action", MAX_BROWSER_ACTION_CHARS),
        ) ?: throw ProviderPayloadException("unknown browser_use action")
        val required = when (action) {
            AgentBrowserAction.NAVIGATE -> setOf("action", "url")
            AgentBrowserAction.CLICK -> setOf("action", "ref")
            AgentBrowserAction.TYPE -> setOf("action", "ref", "text")
            AgentBrowserAction.SNAPSHOT,
            AgentBrowserAction.BACK,
            AgentBrowserAction.FORWARD,
            AgentBrowserAction.RELOAD,
            -> setOf("action")
        }
        val optional = when (action) {
            AgentBrowserAction.TYPE -> setOf("submit", "max_chars")
            else -> setOf("max_chars")
        }
        requireKeys(members, required = required, optional = optional)
        val url = members.optionalText("url", MAX_URL_CHARS)?.let(::requireBrowserUrl)
        return AgentToolArguments.BrowserUse(
            action = action,
            url = url,
            ref = members.optionalIntOrNull("ref", 1..MAX_BROWSER_REFS),
            text = members.optionalText("text", MAX_BROWSER_INPUT_CHARS, trim = false),
            submit = members.optionalBoolean("submit", default = false),
            maxChars = members.optionalInt(
                "max_chars",
                default = AgentToolArguments.BrowserUse.DEFAULT_MAX_CHARS,
                range = AgentToolArguments.BrowserUse.MIN_MAX_CHARS..
                    AgentToolArguments.BrowserUse.MAX_MAX_CHARS,
            ),
        )
    }

    private fun requireBrowserUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull()
        if (
            uri == null ||
            !uri.isAbsolute ||
            uri.scheme?.lowercase() !in setOf("http", "https") ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null
        ) {
            throw ProviderPayloadException("browser_use requires an absolute HTTP(S) URL")
        }
        return uri.toASCIIString()
    }

    private fun decodeSkillManagement(
        members: Map<String, JsonValue>,
    ): AgentToolArguments.SkillManage {
        val operation = members.requiredText("operation", MAX_SKILL_OPERATION_CHARS)
        return when (operation) {
            "create" -> {
                requireKeys(
                    members,
                    required = setOf(
                        "operation",
                        "expected_catalog_generation",
                        "skill_id",
                        "name",
                        "description",
                        "content",
                    ),
                    optional = setOf("base_intent", "key_code", "direction"),
                )
                val binding = members.optionalSlot()
                AgentToolArguments.SkillManage.Create(
                    skillId = members.requiredText("skill_id", AgentSkillPolicy.MAX_ID_CHARS),
                    name = members.requiredText("name", AgentSkillPolicy.MAX_NAME_CHARS),
                    description = members.requiredText(
                        "description",
                        AgentSkillPolicy.MAX_DESCRIPTION_CHARS,
                    ),
                    content = members.requiredText(
                        "content",
                        AgentSkillPolicy.MAX_CONTENT_CHARS,
                        trim = false,
                    ),
                    baseIntent = members.optionalEditorIntent() ?: EditorIntent.SMART_EDIT,
                    binding = binding,
                    expectedCatalogGeneration =
                        members.requiredPositiveLong("expected_catalog_generation"),
                )
            }
            "update" -> {
                requireKeys(
                    members,
                    required = setOf(
                        "operation",
                        "expected_catalog_generation",
                        "skill_id",
                    ),
                    optional = setOf("name", "description", "content", "base_intent"),
                )
                val name = members.optionalText("name", AgentSkillPolicy.MAX_NAME_CHARS)
                val description = members.optionalText(
                    "description",
                    AgentSkillPolicy.MAX_DESCRIPTION_CHARS,
                )
                val content = members.optionalText(
                    "content",
                    AgentSkillPolicy.MAX_CONTENT_CHARS,
                    trim = false,
                )
                val baseIntent = members.optionalEditorIntent()
                if (name == null && description == null && content == null && baseIntent == null) {
                    throw ProviderPayloadException("Skill update must change at least one field")
                }
                AgentToolArguments.SkillManage.Update(
                    skillId = members.requiredText("skill_id", AgentSkillPolicy.MAX_ID_CHARS),
                    name = name,
                    description = description,
                    content = content,
                    baseIntent = baseIntent,
                    expectedCatalogGeneration =
                        members.requiredPositiveLong("expected_catalog_generation"),
                )
            }
            "bind" -> {
                requireKeys(
                    members,
                    required = setOf(
                        "operation",
                        "expected_catalog_generation",
                        "skill_id",
                        "key_code",
                        "direction",
                    ),
                    optional = emptySet(),
                )
                AgentToolArguments.SkillManage.Bind(
                    skillId = members.requiredText("skill_id", AgentSkillPolicy.MAX_ID_CHARS),
                    slot = members.requiredSlot(),
                    expectedCatalogGeneration =
                        members.requiredPositiveLong("expected_catalog_generation"),
                )
            }
            "unbind" -> {
                requireKeys(
                    members,
                    required = setOf(
                        "operation",
                        "expected_catalog_generation",
                        "key_code",
                        "direction",
                    ),
                    optional = emptySet(),
                )
                AgentToolArguments.SkillManage.Unbind(
                    slot = members.requiredSlot(),
                    expectedCatalogGeneration =
                        members.requiredPositiveLong("expected_catalog_generation"),
                )
            }
            "unbind_skill" -> {
                requireKeys(
                    members,
                    required = setOf(
                        "operation",
                        "expected_catalog_generation",
                        "skill_id",
                    ),
                    optional = emptySet(),
                )
                AgentToolArguments.SkillManage.UnbindSkill(
                    skillId = members.requiredText("skill_id", AgentSkillPolicy.MAX_ID_CHARS),
                    expectedCatalogGeneration =
                        members.requiredPositiveLong("expected_catalog_generation"),
                )
            }
            else -> throw ProviderPayloadException("unknown Skill management operation")
        }
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

    private fun Map<String, JsonValue>.requiredText(
        name: String,
        maxChars: Int,
        trim: Boolean = true,
    ): String {
        val raw = (get(name) as? JsonValue.StringValue)?.value
            ?: throw ProviderPayloadException("$name must be a string")
        val value = if (trim) raw.trim() else raw
        if (
            value.isBlank() ||
            value.length > maxChars ||
            value.any { Character.isISOControl(it) && it !in setOf('\n', '\r', '\t') }
        ) {
            throw ProviderPayloadException("$name is outside its bounded text contract")
        }
        return value
    }

    private fun Map<String, JsonValue>.optionalText(
        name: String,
        maxChars: Int,
        trim: Boolean = true,
    ): String? {
        if (name !in this) return null
        return requiredText(name, maxChars, trim)
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

    private fun Map<String, JsonValue>.optionalIntOrNull(
        name: String,
        range: IntRange,
    ): Int? {
        if (name !in this) return null
        return optionalInt(name, default = Int.MIN_VALUE, range = range)
    }

    private fun Map<String, JsonValue>.optionalBoolean(
        name: String,
        default: Boolean,
    ): Boolean {
        val raw = get(name) ?: return default
        return (raw as? JsonValue.BooleanValue)?.value
            ?: throw ProviderPayloadException("$name must be a boolean")
    }

    private fun Map<String, JsonValue>.optionalPositiveLong(name: String): Long? {
        val raw = get(name) ?: return null
        val value = (raw as? JsonValue.NumberValue)?.value?.toLongOrNull()
            ?: throw ProviderPayloadException("$name must be an integer")
        if (value <= 0L) {
            throw ProviderPayloadException("$name must be positive")
        }
        return value
    }

    private fun Map<String, JsonValue>.requiredPositiveLong(name: String): Long =
        optionalPositiveLong(name)
            ?: throw ProviderPayloadException("$name is required")

    private fun Map<String, JsonValue>.requiredSlot(): AgentSkillSlot {
        val keyCode = optionalInt(
            "key_code",
            default = Int.MIN_VALUE,
            range = MIN_SKILL_KEY_CODE..MAX_SKILL_KEY_CODE,
        )
        if (keyCode == Int.MIN_VALUE) {
            throw ProviderPayloadException("key_code is required")
        }
        val directionValue = requiredText("direction", MAX_SKILL_DIRECTION_CHARS)
        val direction = runCatching {
            AgentSkillDirection.fromWireValue(directionValue)
        }.getOrElse {
            throw ProviderPayloadException("direction must be up, right, down, or left")
        }
        return runCatching { AgentSkillSlot(keyCode, direction) }.getOrElse {
            throw ProviderPayloadException("key_code is not a bindable keyboard key")
        }
    }

    private fun Map<String, JsonValue>.optionalSlot(): AgentSkillSlot? {
        val hasKey = "key_code" in this
        val hasDirection = "direction" in this
        if (!hasKey && !hasDirection) return null
        if (hasKey != hasDirection) {
            throw ProviderPayloadException("key_code and direction must be supplied together")
        }
        return requiredSlot()
    }

    private fun Map<String, JsonValue>.optionalEditorIntent(): EditorIntent? {
        if ("base_intent" !in this) return null
        val wireValue = requiredText("base_intent", MAX_BASE_INTENT_CHARS)
        return EditorIntent.entries.firstOrNull { it.wireValue == wireValue }
            ?.takeUnless { it == EditorIntent.NO_CHANGE }
            ?: throw ProviderPayloadException("base_intent is not a runnable editor intent")
    }

    const val MAX_TOOL_RESULT_CHARS = 16_384
    // Eleven default-sized pages recover the maximum 65,536-char Skill document. Keep one final
    // bounded turn for another useful tool while still preventing an unbounded provider loop.
    const val MAX_TOOL_TURNS = 12
    const val MAX_QUERY_CHARS = 512
    const val MAX_URL_CHARS = 2_048
    const val MAX_EXPRESSION_CHARS = 512
    const val MAX_TERMINAL_COMMAND_CHARS = 4_096
    const val MAX_TERMINAL_CWD_CHARS = 512
    const val MAX_BROWSER_ACTION_CHARS = 16
    const val MAX_BROWSER_INPUT_CHARS = 4_096
    const val MAX_BROWSER_REFS = 200
    const val MAX_FETCH_CHARS = 12_000
    const val MAX_SEARCH_RESULTS = 10
    const val MAX_MEMORY_RESULTS = 20
    const val DEFAULT_FETCH_CHARS = 8_000
    const val DEFAULT_SEARCH_RESULTS = 5
    const val DEFAULT_MEMORY_RESULTS = 8
    const val MIN_SKILL_KEY_CODE = -1_024
    const val MAX_SKILL_KEY_CODE = 0x10ffff
    const val MAX_SKILL_OPERATION_CHARS = 32
    const val MAX_SKILL_DIRECTION_CHARS = 8
    const val MAX_BASE_INTENT_CHARS = 32
}
