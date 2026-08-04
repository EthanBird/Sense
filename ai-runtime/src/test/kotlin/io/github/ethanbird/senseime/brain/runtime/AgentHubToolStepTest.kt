package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.AgentProgressKind
import io.github.ethanbird.senseime.ai.protocol.AgentProgressState
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentHubToolStepTest {
    @Test
    fun updatesAStableToolRowAcrossItsLifecycle() {
        val running = event(AgentProgressState.RUNNING, "正在查看项目", "pwd")
        val completed = event(AgentProgressState.COMPLETED, "查看项目完成", "workspace")

        val tools = emptyList<AgentHubToolStep>().merge(running).merge(completed)

        assertEquals(1, tools.size)
        assertEquals(AgentHubToolState.COMPLETED, tools.single().state)
        assertEquals("workspace", tools.single().detail)
    }

    private fun event(state: AgentProgressState, title: String, detail: String) =
        AiEvent.AgentProgress(
            requestId = "request",
            runGeneration = 1L,
            revision = state.ordinal.toLong() + 1,
            stepId = "tool-step",
            kind = AgentProgressKind.TOOL,
            state = state,
            title = title,
            detail = detail,
            toolCallId = "call-1",
            toolName = "terminal_exec",
        )
}
