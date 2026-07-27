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
    val calculatorEnabled: Boolean = true,
    val memorySearchEnabled: Boolean = true,
) {
    fun enabledToolIds(): Set<AgentToolId> {
        if (!masterEnabled) return emptySet()
        return buildSet {
            if (webSearchEnabled) add(AgentToolId.WEB_SEARCH)
            if (webFetchEnabled) add(AgentToolId.WEB_FETCH)
            if (calculatorEnabled) add(AgentToolId.CALCULATOR)
            if (memorySearchEnabled) add(AgentToolId.MEMORY_SEARCH)
        }
    }
}

/**
 * A tiny deterministic codec kept independent of Android so corruption and migration behavior can
 * be covered by ordinary JVM tests.
 */
internal object AgentToolSettingsCodec {
    private const val SCHEMA_VERSION = 1
    private val REQUIRED_KEYS = setOf(
        "schema_version",
        "master_enabled",
        "web_search_enabled",
        "web_fetch_enabled",
        "calculator_enabled",
        "memory_search_enabled",
    )

    fun encode(settings: AgentToolSettings): String = buildString {
        appendLine("schema_version=$SCHEMA_VERSION")
        appendLine("master_enabled=${settings.masterEnabled}")
        appendLine("web_search_enabled=${settings.webSearchEnabled}")
        appendLine("web_fetch_enabled=${settings.webFetchEnabled}")
        appendLine("calculator_enabled=${settings.calculatorEnabled}")
        appendLine("memory_search_enabled=${settings.memorySearchEnabled}")
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
            require(key in REQUIRED_KEYS) { "Unknown Agent tool settings field: $key" }
            require(values.put(key, line.substring(separator + 1)) == null) {
                "Duplicate Agent tool settings field: $key"
            }
        }
        require(values.keys == REQUIRED_KEYS) { "Incomplete Agent tool settings" }
        require(values.getValue("schema_version").toIntOrNull() == SCHEMA_VERSION) {
            "Unsupported Agent tool settings schema"
        }
        return AgentToolSettings(
            masterEnabled = values.strictBoolean("master_enabled"),
            webSearchEnabled = values.strictBoolean("web_search_enabled"),
            webFetchEnabled = values.strictBoolean("web_fetch_enabled"),
            calculatorEnabled = values.strictBoolean("calculator_enabled"),
            memorySearchEnabled = values.strictBoolean("memory_search_enabled"),
        )
    }

    private fun Map<String, String>.strictBoolean(key: String): Boolean =
        when (val value = getValue(key)) {
            "true" -> true
            "false" -> false
            else -> error("Agent tool settings field $key is not a boolean: $value")
        }
}
