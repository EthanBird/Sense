package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAgentToolExecutorTest {
    @Test
    fun `terminal and browser sources receive stable session identity`() {
        val sessions = mutableListOf<String>()
        val executor = DefaultAgentToolExecutor(
            terminalSource = AgentTerminalToolSource { sessionId, arguments ->
                sessions += sessionId
                "{\"command\":\"${arguments.command}\",\"exit_code\":0}"
            },
            browserSource = AgentBrowserToolSource { sessionId, arguments ->
                sessions += sessionId
                "{\"action\":\"${arguments.action.wireValue}\"}"
            },
        )

        val terminal = executor.execute(
            AgentToolCall(
                callId = "terminal",
                tool = AgentToolId.TERMINAL_EXEC,
                arguments = AgentToolArguments.TerminalExec("pwd"),
                sessionId = "hub-session",
            ),
        )
        val browser = executor.execute(
            AgentToolCall(
                callId = "browser",
                tool = AgentToolId.BROWSER_USE,
                arguments = AgentToolArguments.BrowserUse(
                    io.github.ethanbird.senseime.brain.api.AgentBrowserAction.SNAPSHOT,
                ),
                sessionId = "hub-session",
            ),
        )

        assertEquals(listOf("hub-session", "hub-session"), sessions)
        assertFalse(terminal.isError)
        assertFalse(browser.isError)
        assertTrue(terminal.content.contains("\"exit_code\":0"))
        assertTrue(browser.content.contains("\"action\":\"snapshot\""))
    }

    @Test
    fun calculatorUsesBoundedParserWithoutEval() {
        val result = executor().execute(
            call(AgentToolId.CALCULATOR, AgentToolArguments.Calculator("2 + 3 * (4 ^ 2)")),
        )
        assertFalse(result.isError)
        assertTrue(result.content.contains("\"result\":\"50\""))
    }

    @Test
    fun webSearchExtractsAndDecodesHttpsResults() {
        val html = """
            <a class="result__a"
               href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa">A &amp; B</a>
        """.trimIndent()
        val result = executor { html }.execute(
            call(
                AgentToolId.WEB_SEARCH,
                AgentToolArguments.WebSearch("current answer", 3),
            ),
        )
        assertFalse(result.isError)
        assertTrue(result.content.contains("A & B"))
        assertTrue(result.content.contains("https://example.com/a"))
        assertTrue(result.content.contains("\"provider\":\"duckduckgo\""))
    }

    @Test
    fun webSearchFallsBackToBraveWhenDuckDuckGoChallenges() {
        val brave = """
            <div class="snippet result" data-pos="0" data-type="web">
              <a href="https://developer.android.com/reference/android/inputmethodservice/InputMethodService">
                <div>site</div>
                <div class="title search-snippet-title" title="Input method">
                  InputMethodService &amp; Android
                </div>
              </a>
            </div>
        """.trimIndent()
        val requested = mutableListOf<String>()
        val result = executor { url ->
            requested += url
            if (url.contains("duckduckgo")) {
                """<form id="challenge-form"><div class="anomaly-modal"></div></form>"""
            } else {
                brave
            }
        }.execute(
            call(
                AgentToolId.WEB_SEARCH,
                AgentToolArguments.WebSearch("input method", 3),
            ),
        )
        assertFalse(result.content, result.isError)
        assertTrue(requested.size == 2)
        assertTrue(result.content.contains("\"provider\":\"brave\""))
        assertTrue(result.content.contains("InputMethodService & Android"))
    }

    @Test
    fun webSearchNeverReportsEmptyChallengePagesAsSuccess() {
        val result = executor {
            """<form id="challenge-form"><div class="anomaly-modal"></div></form>"""
        }.execute(
            call(
                AgentToolId.WEB_SEARCH,
                AgentToolArguments.WebSearch("current answer", 3),
            ),
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("web search providers unavailable"))
    }

    @Test
    fun webFetchReturnsPlainBoundedText() {
        val result = executor {
            "<html><title>Page</title><script>secret()</script><body>Hello <b>world</b></body></html>"
        }.execute(
            call(
                AgentToolId.WEB_FETCH,
                AgentToolArguments.WebFetch("https://example.com/page", 256),
            ),
        )
        assertFalse(result.isError)
        assertTrue(result.content.contains("\"title\":\"Page\""))
        assertTrue(result.content.contains("Hello world"))
        assertFalse(result.content.contains("secret()"))
    }

    @Test
    fun memorySearchUsesInjectedRetainedDataSource() {
        var excludedIdentity: Pair<String?, Long?>? = null
        val runtime = DefaultAgentToolExecutor(
            memorySource = AgentMemorySearchSource { query, _, excludedId, excludedGeneration ->
                excludedIdentity = excludedId to excludedGeneration
                listOf(AgentMemorySearchHit("event-1", "用户记得：$query", "event-journal"))
            },
            documentLoader = { error("network must not be used") },
        )
        val result = runtime.execute(
            AgentToolCall(
                callId = "call-1",
                tool = AgentToolId.MEMORY_SEARCH,
                arguments = AgentToolArguments.MemorySearch("上次决定", 5),
                requestId = "current-run",
                runGeneration = 9,
            ),
        )
        assertFalse(result.isError)
        assertTrue(result.content.contains("event-1"))
        assertTrue(result.content.contains("上次决定"))
        assertTrue(excludedIdentity == ("current-run" to 9L))
    }

    @Test
    fun executorReturnsActionableLoaderFailure() {
        val result = executor { error("private failure details") }.execute(
            call(
                AgentToolId.WEB_FETCH,
                AgentToolArguments.WebFetch("https://example.com", 256),
            ),
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("IllegalStateException"))
        assertTrue(result.content.contains("private failure details"))
    }

    @Test
    fun skillReadReturnsExactImmutableRevisionWithPagingMetadata() {
        val runtime = DefaultAgentToolExecutor(
            skillSource = object : AgentSkillToolSource {
                override fun read(skillId: String, revision: Long) = AgentSkillDefinition(
                    id = skillId,
                    revision = revision,
                    name = "简报",
                    description = "生成事实完整的简报",
                    content = "# 简报\n\n保留数字与来源。",
                    baseIntent = EditorIntent.FORMAT,
                )

                override fun apply(mutation: AgentSkillMutation): AgentSkillCatalog =
                    error("mutation must not be used")
            },
            documentLoader = { error("network must not be used") },
        )

        val result = runtime.execute(
            call(
                AgentToolId.SKILL_READ,
                AgentToolArguments.SkillRead("brief", revision = 7),
            ),
        )

        assertFalse(result.content, result.isError)
        assertTrue(result.content.contains("\"revision\":7"))
        assertTrue(result.content.contains("保留数字与来源"))
        assertTrue(result.content.contains("\"base_intent\":\"format\""))
        assertTrue(result.content.contains("\"offset\":0"))
        assertTrue(result.content.contains("\"next_offset\":14"))
        assertTrue(result.content.contains("\"eof\":true"))
    }

    @Test
    fun skillReadCanRecoverMaximumDocumentWithoutExceedingToolResultLimit() {
        val document = "x".repeat(65_536)
        val runtime = DefaultAgentToolExecutor(
            skillSource = object : AgentSkillToolSource {
                override fun read(skillId: String, revision: Long) = AgentSkillDefinition(
                    id = skillId,
                    revision = 9,
                    name = "Long",
                    description = "Long retained document",
                    content = document,
                    baseIntent = EditorIntent.REWRITE,
                )

                override fun apply(mutation: AgentSkillMutation): AgentSkillCatalog =
                    error("mutation must not be used")
            },
            documentLoader = { error("network must not be used") },
        )

        var offset = 0
        var recoveredChars = 0
        var pages = 0
        while (offset < document.length) {
            val result = runtime.execute(
                call(
                    AgentToolId.SKILL_READ,
                    AgentToolArguments.SkillRead(
                        skillId = "long",
                        revision = 9,
                        offset = offset,
                        maxChars = AgentToolArguments.SkillRead.MAX_MAX_CHARS,
                    ),
                ),
            )
            assertFalse(result.content, result.isError)
            assertTrue(result.content.length <= 16_384)
            val next = Regex("\"next_offset\":(\\d+)")
                .find(result.content)?.groupValues?.get(1)?.toInt()
                ?: error("next_offset is missing")
            recoveredChars += next - offset
            offset = next
            pages += 1
        }

        assertEquals(document.length, recoveredChars)
        assertEquals(11, pages)
    }

    @Test
    fun skillReadNeverSplitsUnicodeCodePointAtPageBoundary() {
        val document = "x".repeat(255) + "🌌" + "tail"
        val runtime = DefaultAgentToolExecutor(
            skillSource = object : AgentSkillToolSource {
                override fun read(skillId: String, revision: Long) = AgentSkillDefinition(
                    id = skillId,
                    revision = revision,
                    name = "Unicode",
                    description = "Unicode paging",
                    content = document,
                    baseIntent = EditorIntent.REWRITE,
                )

                override fun apply(mutation: AgentSkillMutation): AgentSkillCatalog =
                    error("mutation must not be used")
            },
        )

        val first = runtime.execute(
            call(
                AgentToolId.SKILL_READ,
                AgentToolArguments.SkillRead("unicode", 1, offset = 0, maxChars = 256),
            ),
        )
        val invalid = runtime.execute(
            call(
                AgentToolId.SKILL_READ,
                AgentToolArguments.SkillRead("unicode", 1, offset = 256, maxChars = 256),
            ),
        )

        assertFalse(first.content, first.isError)
        assertTrue(first.content.contains("\"next_offset\":255"))
        assertTrue(invalid.isError)
        assertTrue(invalid.content.contains("splits a Unicode code point"))
    }

    @Test
    fun skillManageMapsTypedRequestToOneRetainedCatalogMutation() {
        var received: AgentSkillMutation? = null
        val created = AgentSkillDefinition(
            id = "brief",
            revision = 1,
            name = "简报",
            description = "生成事实完整的简报",
            content = "# 简报",
            baseIntent = EditorIntent.FORMAT,
        )
        val runtime = DefaultAgentToolExecutor(
            skillSource = object : AgentSkillToolSource {
                override fun read(skillId: String, revision: Long): AgentSkillDefinition? = null

                override fun apply(mutation: AgentSkillMutation): AgentSkillCatalog {
                    received = mutation
                    return AgentSkillCatalog(
                        generation = 8,
                        definitions = listOf(created),
                        bindings = emptyList(),
                        active = null,
                    )
                }
            },
            documentLoader = { error("network must not be used") },
        )

        val result = runtime.execute(
            call(
                AgentToolId.SKILL_MANAGE,
                AgentToolArguments.SkillManage.Create(
                    skillId = "brief",
                    name = "简报",
                    description = "生成事实完整的简报",
                    content = "# 简报",
                    baseIntent = EditorIntent.FORMAT,
                    binding = null,
                    expectedCatalogGeneration = 7,
                ),
            ),
        )

        assertFalse(result.content, result.isError)
        assertEquals(
            7L,
            (received as AgentSkillMutation.Create).expectedGeneration,
        )
        assertTrue(result.content.contains("\"expected_catalog_generation\":7"))
        assertTrue(result.content.contains("\"catalog_generation\":8"))
        assertTrue(result.content.contains("\"revision\":1"))
        assertEquals(8L, result.skillCatalogSnapshot?.generation)
        assertEquals(
            listOf("brief" to 1L),
            result.skillCatalogSnapshot?.skills?.map { it.id to it.revision },
        )
    }

    private fun executor(loader: (String) -> String = { "" }) =
        DefaultAgentToolExecutor(documentLoader = loader)

    private fun call(tool: AgentToolId, arguments: AgentToolArguments) =
        AgentToolCall("call-1", tool, arguments)
}
