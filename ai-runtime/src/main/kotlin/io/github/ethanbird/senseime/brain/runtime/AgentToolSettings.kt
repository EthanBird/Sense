package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.AgentToolId

/**
 * User-owned allow-list for Agent tools.
 *
 * A disabled tool is omitted from future Agent runs. Changing this configuration never deletes
 * tool output, memory, or any other user data.
 */
data class AgentToolSettings(
    val masterEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val webFetchEnabled: Boolean = true,
    val browserUseEnabled: Boolean = true,
    val terminalExecEnabled: Boolean = true,
    val calculatorEnabled: Boolean = true,
    val memorySearchEnabled: Boolean = true,
    val skillReadEnabled: Boolean = true,
    val skillManageEnabled: Boolean = true,
) {
    fun enabledToolIds(): Set<AgentToolId> {
        if (!masterEnabled) return emptySet()
        return buildSet {
            if (webSearchEnabled) add(AgentToolId.WEB_SEARCH)
            if (webFetchEnabled) add(AgentToolId.WEB_FETCH)
            if (browserUseEnabled) add(AgentToolId.BROWSER_USE)
            if (terminalExecEnabled) add(AgentToolId.TERMINAL_EXEC)
            if (calculatorEnabled) add(AgentToolId.CALCULATOR)
            if (memorySearchEnabled) add(AgentToolId.MEMORY_SEARCH)
            if (skillReadEnabled) add(AgentToolId.SKILL_READ)
            if (skillManageEnabled) add(AgentToolId.SKILL_MANAGE)
        }
    }
}

/**
 * Captures one immutable tool allow-list for a run. The Brain must not append system tools after
 * this boundary: every model-visible tool is controlled by the settings snapshot admitted here.
 */
internal object AgentToolRunAdmission {
    fun freeze(settings: AgentToolSettings): Set<AgentToolId> =
        settings.enabledToolIds().toSet()
}

/**
 * A tiny deterministic codec kept independent of Android so corruption and migration behavior can
 * be covered by ordinary JVM tests.
 */
internal object AgentToolSettingsCodec {
    private const val LEGACY_SCHEMA_VERSION = 1
    private const val SKILLS_SCHEMA_VERSION = 2
    private const val CURRENT_SCHEMA_VERSION = 3
    private val LEGACY_KEYS = setOf(
        "schema_version",
        "master_enabled",
        "web_search_enabled",
        "web_fetch_enabled",
        "calculator_enabled",
        "memory_search_enabled",
    )
    private val SKILLS_KEYS = LEGACY_KEYS + setOf(
        "skill_read_enabled",
        "skill_manage_enabled",
    )
    private val CURRENT_KEYS = SKILLS_KEYS + setOf(
        "browser_use_enabled",
        "terminal_exec_enabled",
    )

    fun encode(settings: AgentToolSettings): String = buildString {
        appendLine("schema_version=$CURRENT_SCHEMA_VERSION")
        appendLine("master_enabled=${settings.masterEnabled}")
        appendLine("web_search_enabled=${settings.webSearchEnabled}")
        appendLine("web_fetch_enabled=${settings.webFetchEnabled}")
        appendLine("browser_use_enabled=${settings.browserUseEnabled}")
        appendLine("terminal_exec_enabled=${settings.terminalExecEnabled}")
        appendLine("calculator_enabled=${settings.calculatorEnabled}")
        appendLine("memory_search_enabled=${settings.memorySearchEnabled}")
        appendLine("skill_read_enabled=${settings.skillReadEnabled}")
        appendLine("skill_manage_enabled=${settings.skillManageEnabled}")
    }

    fun decode(document: String): AgentToolSettings {
        val values = LinkedHashMap<String, String>()
        document.lineSequence().forEachIndexed { index, sourceLine ->
            val line = sourceLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            val separator = line.indexOf('=')
            require(separator > 0 && separator < line.lastIndex) {
                "Malformed Agent tool settings at line ${index + 1}"
            }
            val key = line.substring(0, separator)
            require(values.put(key, line.substring(separator + 1)) == null) {
                "Duplicate Agent tool settings field: $key"
            }
        }
        val schemaVersion = values["schema_version"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Missing Agent tool settings schema")
        val expectedKeys = when (schemaVersion) {
            LEGACY_SCHEMA_VERSION -> LEGACY_KEYS
            SKILLS_SCHEMA_VERSION -> SKILLS_KEYS
            CURRENT_SCHEMA_VERSION -> CURRENT_KEYS
            else -> throw IllegalArgumentException("Unsupported Agent tool settings schema")
        }
        val unknownKeys = values.keys - expectedKeys
        require(unknownKeys.isEmpty()) {
            "Unknown Agent tool settings field: ${unknownKeys.first()}"
        }
        require(values.keys == expectedKeys) { "Incomplete Agent tool settings" }
        return AgentToolSettings(
            masterEnabled = values.strictBoolean("master_enabled"),
            webSearchEnabled = values.strictBoolean("web_search_enabled"),
            webFetchEnabled = values.strictBoolean("web_fetch_enabled"),
            browserUseEnabled = if (schemaVersion >= CURRENT_SCHEMA_VERSION) {
                values.strictBoolean("browser_use_enabled")
            } else {
                true
            },
            terminalExecEnabled = if (schemaVersion >= CURRENT_SCHEMA_VERSION) {
                values.strictBoolean("terminal_exec_enabled")
            } else {
                true
            },
            calculatorEnabled = values.strictBoolean("calculator_enabled"),
            memorySearchEnabled = values.strictBoolean("memory_search_enabled"),
            skillReadEnabled = if (schemaVersion >= SKILLS_SCHEMA_VERSION) {
                values.strictBoolean("skill_read_enabled")
            } else {
                true
            },
            skillManageEnabled = if (schemaVersion >= SKILLS_SCHEMA_VERSION) {
                values.strictBoolean("skill_manage_enabled")
            } else {
                true
            },
        )
    }

    private fun Map<String, String>.strictBoolean(key: String): Boolean =
        when (val value = getValue(key)) {
            "true" -> true
            "false" -> false
            else -> error("Agent tool settings field $key is not a boolean: $value")
        }
}
