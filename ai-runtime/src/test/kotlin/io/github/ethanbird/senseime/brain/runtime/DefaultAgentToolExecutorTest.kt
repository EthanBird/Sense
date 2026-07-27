package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAgentToolExecutorTest {
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

    private fun executor(loader: (String) -> String = { "" }) =
        DefaultAgentToolExecutor(documentLoader = loader)

    private fun call(tool: AgentToolId, arguments: AgentToolArguments) =
        AgentToolCall("call-1", tool, arguments)
}
