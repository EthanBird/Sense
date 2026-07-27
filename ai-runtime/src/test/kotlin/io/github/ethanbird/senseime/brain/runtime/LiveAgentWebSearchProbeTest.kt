package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opt-in, no-key production-network probe. Skipped in ordinary CI. */
class LiveAgentWebSearchProbeTest {
    @Test
    fun duckDuckGoHttpsSearchReturnsBoundedResults() {
        assumeTrue(System.getenv(ENABLE_ENV) == "1")
        val result = DefaultAgentToolExecutor().execute(
            AgentToolCall(
                callId = "live-web-search",
                tool = AgentToolId.WEB_SEARCH,
                arguments = AgentToolArguments.WebSearch(
                    query = "Android InputMethodService official documentation",
                    maxResults = 3,
                ),
            ),
        )
        assertFalse(result.content, result.isError)
        assertTrue(result.content.length <= 16_384)
        assertTrue(result.content.contains("\"results\":["))
        assertFalse(result.content.contains("\"results\":[]"))
    }

    private companion object {
        const val ENABLE_ENV = "SENSE_RUN_LIVE_WEB_TOOL_TEST"
    }
}
