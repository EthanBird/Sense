package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolExecutionResult
import io.github.ethanbird.senseime.brain.api.AgentToolExecutor
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalogSnapshot
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.toSummary
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.pow

data class AgentMemorySearchHit(
    val id: String,
    val text: String,
    val source: String = "",
    val channel: String = "session_evidence",
    val evidenceRecordIds: List<String> = emptyList(),
)

data class AgentMemorySearchCoverage(
    val scannedRecords: Int = 0,
    val scannedBytes: Long = 0,
    val truncated: Boolean = false,
    val channels: Set<String> = emptySet(),
)

data class AgentMemorySearchPage(
    val hits: List<AgentMemorySearchHit>,
    val coverage: AgentMemorySearchCoverage = AgentMemorySearchCoverage(),
)

fun interface AgentMemorySearchSource {
    fun search(
        query: String,
        maxResults: Int,
        excludeRequestId: String?,
        excludeRunGeneration: Long?,
    ): List<AgentMemorySearchHit>

    fun searchPage(
        query: String,
        maxResults: Int,
        excludeRequestId: String?,
        excludeRunGeneration: Long?,
    ): AgentMemorySearchPage = AgentMemorySearchPage(
        hits = search(query, maxResults, excludeRequestId, excludeRunGeneration),
    )

    companion object {
        val EMPTY = AgentMemorySearchSource { _, _, _, _ -> emptyList() }
    }
}

interface AgentSkillToolSource {
    fun read(skillId: String, revision: Long): AgentSkillDefinition?

    fun apply(mutation: AgentSkillMutation): AgentSkillCatalog

    companion object {
        val UNAVAILABLE = object : AgentSkillToolSource {
            override fun read(skillId: String, revision: Long): AgentSkillDefinition? =
                throw IllegalStateException("Skill runtime unavailable")

            override fun apply(mutation: AgentSkillMutation): AgentSkillCatalog =
                throw IllegalStateException("Skill runtime unavailable")
        }
    }
}

fun interface AgentTerminalToolSource {
    /** Returns one compact JSON object that becomes the tool result's `data` field. */
    fun execute(sessionId: String, arguments: AgentToolArguments.TerminalExec): String

    companion object {
        val UNAVAILABLE = AgentTerminalToolSource { _, _ ->
            throw IllegalStateException("Terminal runtime unavailable")
        }
    }
}

fun interface AgentBrowserToolSource {
    /** Returns one compact JSON object that becomes the tool result's `data` field. */
    fun execute(sessionId: String, arguments: AgentToolArguments.BrowserUse): String

    companion object {
        val UNAVAILABLE = AgentBrowserToolSource { _, _ ->
            throw IllegalStateException("Browser runtime unavailable")
        }
    }
}

/**
 * Dependency-free Android tool runtime.
 *
 * All network reads are HTTPS-only, timeout-bounded and byte-bounded. The Brain applies a second
 * result cap before replaying content to the model.
 */
class DefaultAgentToolExecutor(
    private val memorySource: AgentMemorySearchSource = AgentMemorySearchSource.EMPTY,
    private val skillSource: AgentSkillToolSource = AgentSkillToolSource.UNAVAILABLE,
    private val terminalSource: AgentTerminalToolSource = AgentTerminalToolSource.UNAVAILABLE,
    private val browserSource: AgentBrowserToolSource = AgentBrowserToolSource.UNAVAILABLE,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
    private val documentLoader: ((String) -> String)? = null,
) : AgentToolExecutor {
    override fun execute(call: AgentToolCall): AgentToolExecutionResult = try {
        when (val arguments = call.arguments) {
            is AgentToolArguments.WebSearch -> webSearch(arguments)
            is AgentToolArguments.WebFetch -> webFetch(arguments)
            is AgentToolArguments.BrowserUse -> success(
                browserSource.execute(call.sessionId, arguments),
            )
            is AgentToolArguments.TerminalExec -> success(
                terminalSource.execute(call.sessionId, arguments),
            )
            is AgentToolArguments.Calculator -> calculate(arguments)
            is AgentToolArguments.MemorySearch -> memorySearch(
                arguments,
                call.requestId,
                call.runGeneration,
            )
            is AgentToolArguments.SkillRead -> skillRead(arguments)
            is AgentToolArguments.SkillManage -> skillManage(arguments)
        }
    } catch (failure: Exception) {
        failureResult(
            buildString {
                append(failure::class.java.simpleName)
                failure.message?.takeIf(String::isNotBlank)?.let {
                    append(": ").append(it)
                }
            },
        )
    }

    private fun skillRead(
        arguments: AgentToolArguments.SkillRead,
    ): AgentToolExecutionResult {
        val skill = skillSource.read(arguments.skillId, arguments.revision)
            ?: return failureResult("Unknown Skill: ${arguments.skillId}")
        if (skill.revision != arguments.revision) {
            return failureResult(
                "Skill revision mismatch: requested ${arguments.revision}, got ${skill.revision}",
            )
        }
        if (arguments.offset > skill.content.length) {
            return failureResult(
                "Skill offset ${arguments.offset} exceeds document length ${skill.content.length}",
            )
        }
        if (
            arguments.offset in 1 until skill.content.length &&
            Character.isLowSurrogate(skill.content[arguments.offset]) &&
            Character.isHighSurrogate(skill.content[arguments.offset - 1])
        ) {
            return failureResult("Skill offset splits a Unicode code point")
        }
        var nextOffset = (arguments.offset + arguments.maxChars)
            .coerceAtMost(skill.content.length)
        if (
            nextOffset in 1 until skill.content.length &&
            Character.isLowSurrogate(skill.content[nextOffset]) &&
            Character.isHighSurrogate(skill.content[nextOffset - 1])
        ) {
            nextOffset -= 1
        }
        val content = skill.content.substring(arguments.offset, nextOffset)
        return success(
            buildString {
                append("{\"id\":")
                appendJson(skill.id)
                append(",\"revision\":").append(skill.revision)
                append(",\"name\":")
                appendJson(skill.name)
                append(",\"description\":")
                appendJson(skill.description)
                append(",\"base_intent\":")
                appendJson(skill.baseIntent.wireValue)
                append(",\"offset\":").append(arguments.offset)
                append(",\"next_offset\":").append(nextOffset)
                append(",\"eof\":").append(nextOffset == skill.content.length)
                append(",\"content\":")
                appendJson(content)
                append('}')
            },
        )
    }

    private fun skillManage(
        arguments: AgentToolArguments.SkillManage,
    ): AgentToolExecutionResult {
        val mutation = when (arguments) {
            is AgentToolArguments.SkillManage.Create -> AgentSkillMutation.Create(
                id = arguments.skillId,
                name = arguments.name,
                description = arguments.description,
                content = arguments.content,
                baseIntent = arguments.baseIntent,
                binding = arguments.binding,
                expectedGeneration = arguments.expectedCatalogGeneration,
            )
            is AgentToolArguments.SkillManage.Update -> AgentSkillMutation.Update(
                id = arguments.skillId,
                name = arguments.name,
                description = arguments.description,
                content = arguments.content,
                baseIntent = arguments.baseIntent,
                expectedGeneration = arguments.expectedCatalogGeneration,
            )
            is AgentToolArguments.SkillManage.Bind -> AgentSkillMutation.Bind(
                skillId = arguments.skillId,
                slot = arguments.slot,
                expectedGeneration = arguments.expectedCatalogGeneration,
            )
            is AgentToolArguments.SkillManage.Unbind -> AgentSkillMutation.Unbind(
                slot = arguments.slot,
                expectedGeneration = arguments.expectedCatalogGeneration,
            )
            is AgentToolArguments.SkillManage.UnbindSkill ->
                AgentSkillMutation.UnbindSkill(
                    skillId = arguments.skillId,
                    expectedGeneration = arguments.expectedCatalogGeneration,
                )
        }
        val catalog = skillSource.apply(mutation)
        val affectedId = when (arguments) {
            is AgentToolArguments.SkillManage.Create -> arguments.skillId
            is AgentToolArguments.SkillManage.Update -> arguments.skillId
            is AgentToolArguments.SkillManage.Bind -> arguments.skillId
            is AgentToolArguments.SkillManage.Unbind -> null
            is AgentToolArguments.SkillManage.UnbindSkill -> arguments.skillId
        }
        return success(
            buildString {
                append("{\"expected_catalog_generation\":")
                    .append(arguments.expectedCatalogGeneration)
                append(",\"catalog_generation\":").append(catalog.generation)
                affectedId?.let { skillId ->
                    append(",\"skill_id\":")
                    appendJson(skillId)
                    catalog.definition(skillId)?.let { definition ->
                        append(",\"revision\":").append(definition.revision)
                    }
                }
                append(",\"binding_count\":").append(catalog.bindings.size)
                append(",\"active_skill_id\":")
                catalog.active?.skillId?.let { appendJson(it) } ?: append("null")
                append('}')
            },
            skillCatalogSnapshot = AgentSkillCatalogSnapshot(
                generation = catalog.generation,
                skills = catalog.definitions.map { it.toSummary() },
            ),
        )
    }

    private fun webSearch(arguments: AgentToolArguments.WebSearch): AgentToolExecutionResult {
        val query = URLEncoder.encode(arguments.query, StandardCharsets.UTF_8.name())
        val failures = ArrayList<String>(2)
        val duckDuckGoResults = runCatching {
            WebTextExtractor.duckDuckGoSearchResults(
                loadDocument("https://html.duckduckgo.com/html/?q=$query"),
                arguments.maxResults,
            )
        }.onFailure {
            failures += "duckduckgo: ${it::class.java.simpleName}"
        }.getOrDefault(emptyList())
        val (provider, results) = if (duckDuckGoResults.isNotEmpty()) {
            "duckduckgo" to duckDuckGoResults
        } else {
            if (failures.isEmpty()) failures += "duckduckgo: no usable results"
            val braveResults = runCatching {
                WebTextExtractor.braveSearchResults(
                    loadDocument("https://search.brave.com/search?q=$query&source=web"),
                    arguments.maxResults,
                )
            }.onFailure {
                failures += "brave: ${it::class.java.simpleName}"
            }.getOrDefault(emptyList())
            if (braveResults.isEmpty()) {
                if (failures.size == 1) failures += "brave: no usable results"
                throw IllegalStateException(
                    "web search providers unavailable (${failures.joinToString("; ")})",
                )
            }
            "brave" to braveResults
        }
        return success(
            buildString {
                append("{\"query\":")
                appendJson(arguments.query)
                append(",\"provider\":")
                appendJson(provider)
                append(",\"results\":[")
                results.forEachIndexed { index, result ->
                    if (index > 0) append(',')
                    append("{\"title\":")
                    appendJson(result.title)
                    append(",\"url\":")
                    appendJson(result.url)
                    append('}')
                }
                append("]}")
            },
        )
    }

    private fun webFetch(arguments: AgentToolArguments.WebFetch): AgentToolExecutionResult {
        val document = loadDocument(arguments.url)
        val title = WebTextExtractor.title(document)
        val text = WebTextExtractor.pageText(document).take(arguments.maxChars)
        return success(
            buildString {
                append("{\"url\":")
                appendJson(arguments.url)
                append(",\"title\":")
                appendJson(title)
                append(",\"text\":")
                appendJson(text)
                append(",\"truncated\":")
                append(text.length >= arguments.maxChars)
                append('}')
            },
        )
    }

    private fun calculate(
        arguments: AgentToolArguments.Calculator,
    ): AgentToolExecutionResult {
        val value = BoundedCalculator.evaluate(arguments.expression)
        return success(
            "{\"expression\":${json(arguments.expression)},\"result\":${json(value)}}",
        )
    }

    private fun memorySearch(
        arguments: AgentToolArguments.MemorySearch,
        excludeRequestId: String?,
        excludeRunGeneration: Long?,
    ): AgentToolExecutionResult {
        val page = memorySource.searchPage(
            arguments.query,
            arguments.maxResults,
            excludeRequestId,
            excludeRunGeneration,
        )
        val hits = page.hits.take(arguments.maxResults)
        return success(
            buildString {
                append("{\"query\":")
                appendJson(arguments.query)
                append(",\"result_count\":").append(hits.size)
                append(",\"coverage\":{")
                append("\"scanned_records\":").append(page.coverage.scannedRecords)
                append(",\"scanned_bytes\":").append(page.coverage.scannedBytes)
                append(",\"truncated\":").append(page.coverage.truncated)
                append(",\"channels\":[")
                page.coverage.channels.sorted().forEachIndexed { index, channel ->
                    if (index > 0) append(',')
                    appendJson(channel)
                }
                append("]}")
                append(",\"results\":[")
                hits.forEachIndexed { index, hit ->
                    if (index > 0) append(',')
                    append("{\"id\":")
                    appendJson(hit.id.take(256))
                    append(",\"text\":")
                    appendJson(hit.text.take(MAX_MEMORY_HIT_CHARS))
                    append(",\"source\":")
                    appendJson(hit.source.take(256))
                    append(",\"channel\":")
                    appendJson(hit.channel.take(64))
                    append(",\"evidence_record_ids\":[")
                    hit.evidenceRecordIds.take(MAX_MEMORY_EVIDENCE_IDS).forEachIndexed {
                            evidenceIndex,
                            evidenceId,
                        ->
                        if (evidenceIndex > 0) append(',')
                        appendJson(evidenceId.take(256))
                    }
                    append(']')
                    append('}')
                }
                append("]}")
            },
        )
    }

    private fun httpsGet(initialUrl: String): String {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val uri = URI(current)
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
            val connection = URL(uri.toASCIIString()).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Accept", "text/html,text/plain,application/json")
                connection.setRequestProperty("User-Agent", MOBILE_USER_AGENT)
                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirect < MAX_REDIRECTS)
                    current = uri.resolve(
                        requireNotNull(connection.getHeaderField("Location")),
                    ).toASCIIString()
                    return@repeat
                }
                require(status in 200..299)
                val bytes = connection.inputStream.use(::readBounded)
                return bytes.toString(StandardCharsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("redirect limit exceeded")
    }

    private fun loadDocument(url: String): String =
        documentLoader?.invoke(url) ?: httpsGet(url)

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size().toLong() + read <= MAX_DOWNLOAD_BYTES)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun success(
        content: String,
        skillCatalogSnapshot: AgentSkillCatalogSnapshot? = null,
    ) = AgentToolExecutionResult(
        content = "{\"ok\":true,\"data\":$content}",
        skillCatalogSnapshot = skillCatalogSnapshot,
    )

    private fun failureResult(message: String) = AgentToolExecutionResult(
        content = "{\"ok\":false,\"error\":${json(message)}}",
        isError = true,
    )

    private fun StringBuilder.appendJson(value: String) {
        append(json(value))
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 524_288
        const val MAX_REDIRECTS = 3
        const val MAX_MEMORY_HIT_CHARS = 2_000
        const val MAX_MEMORY_EVIDENCE_IDS = 8
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36 Sense-IME/0.4.12"
    }
}

internal object WebTextExtractor {
    data class SearchResult(val title: String, val url: String)

    private val duckDuckGoResultLink = Regex(
        """<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val braveResult = Regex(
        """<div[^>]*class=["'][^"']*snippet[^"']*["'][^>]*data-type=["']web["'][^>]*>.*?""" +
            """<a[^>]*href=["'](https://[^"']+)["'][^>]*>.*?""" +
            """<div[^>]*class=["'][^"']*search-snippet-title[^"']*["'][^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val titleTag = Regex(
        """<title[^>]*>(.*?)</title>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val discarded = Regex(
        """<(script|style|noscript|svg)[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tags = Regex("""<[^>]+>""")
    private val spaces = Regex("""\s+""")
    private val entities = mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&nbsp;" to " ",
    )

    fun duckDuckGoSearchResults(document: String, limit: Int): List<SearchResult> =
        duckDuckGoResultLink.findAll(document).mapNotNull { match ->
            val title = plain(match.groupValues[2])
            val url = resultUrl(decodeEntities(match.groupValues[1]))
            if (title.isBlank() || !url.startsWith("https://")) null else SearchResult(title, url)
        }.distinctBy(SearchResult::url).take(limit).toList()

    fun braveSearchResults(document: String, limit: Int): List<SearchResult> =
        braveResult.findAll(document).mapNotNull { match ->
            val title = plain(match.groupValues[2])
            val url = decodeEntities(match.groupValues[1])
            if (title.isBlank() || !url.startsWith("https://")) null else SearchResult(title, url)
        }.distinctBy(SearchResult::url).take(limit).toList()

    fun title(document: String): String =
        titleTag.find(document)?.groupValues?.get(1)?.let(::plain).orEmpty().take(512)

    fun pageText(document: String): String = plain(discarded.replace(document, " "))

    private fun plain(value: String): String =
        spaces.replace(decodeEntities(tags.replace(value, " ")), " ").trim()

    private fun decodeEntities(value: String): String {
        var result = value
        entities.forEach { (encoded, decoded) ->
            result = result.replace(encoded, decoded, ignoreCase = true)
        }
        return Regex("""&#(x[0-9a-fA-F]+|\d+);""").replace(result) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith('x', ignoreCase = true)) {
                raw.drop(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            code?.takeIf(Character::isValidCodePoint)?.let(Character::toChars)?.concatToString()
                ?: match.value
        }
    }

    private fun resultUrl(value: String): String {
        val absolute = if (value.startsWith("//")) "https:$value" else value
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return ""
        if (uri.host?.endsWith("duckduckgo.com", ignoreCase = true) == true) {
            val uddg = uri.rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("uddg=") }
                ?.substringAfter('=')
            if (uddg != null) {
                return URLDecoder.decode(uddg, StandardCharsets.UTF_8.name())
            }
        }
        return absolute
    }
}

internal object BoundedCalculator {
    fun evaluate(expression: String): String {
        require(expression.length <= 512)
        val value = Parser(expression).parse()
        require(value.isFinite())
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.12g", value)
        }
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): Double {
            val value = expression()
            whitespace()
            require(index == source.length)
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                whitespace()
                value = when {
                    consume('+') -> value + term()
                    consume('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = power()
            while (true) {
                whitespace()
                value = when {
                    consume('*') -> value * power()
                    consume('/') -> value / power()
                    consume('%') -> value % power()
                    else -> return value
                }
            }
        }

        private fun power(): Double {
            var value = unary()
            whitespace()
            if (consume('^')) value = value.pow(power())
            return value
        }

        private fun unary(): Double {
            whitespace()
            return when {
                consume('+') -> unary()
                consume('-') -> -unary()
                else -> primary()
            }
        }

        private fun primary(): Double {
            whitespace()
            if (consume('(')) {
                val value = expression()
                whitespace()
                require(consume(')'))
                return value
            }
            val start = index
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) {
                index += 1
            }
            require(start != index)
            return source.substring(start, index).toDouble()
        }

        private fun whitespace() {
            while (index < source.length && source[index].isWhitespace()) index += 1
        }

        private fun consume(char: Char): Boolean {
            if (index >= source.length || source[index] != char) return false
            index += 1
            return true
        }
    }
}
