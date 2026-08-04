package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.AgentProgressKind
import io.github.ethanbird.senseime.ai.protocol.AgentProgressState
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainRunReplayTest {
    @Test
    fun compactsStreamingStateForANewImeSubscriber() {
        val replay = BrainRunReplay("request", 7)
        replay.accept(AiEvent.Started("request", 7, 10))
        replay.accept(AiEvent.Status("request", 7, HarnessPhase.THINKING, "thinking"))
        replay.accept(AiEvent.PreviewDelta("request", 7, "hello "))
        replay.accept(AiEvent.PreviewDelta("request", 7, "world"))
        replay.accept(
            AiEvent.AgentProgress(
                requestId = "request",
                runGeneration = 7,
                revision = 1,
                stepId = "tool-1",
                kind = AgentProgressKind.TOOL,
                state = AgentProgressState.RUNNING,
                title = "运行终端",
                toolCallId = "call-1",
                toolName = "terminal_exec",
            ),
        )

        val snapshot = replay.snapshot()

        assertTrue(snapshot.first() is AiEvent.Started)
        assertTrue(snapshot.any { it is AiEvent.Status && it.label == "thinking" })
        assertTrue(snapshot.any { it is AiEvent.AgentProgress && it.toolCallId == "call-1" })
        assertEquals(
            "hello world",
            snapshot.filterIsInstance<AiEvent.PreviewDelta>().single().text,
        )
    }

    @Test
    fun replacesOneStableToolRowInsteadOfGrowingWithoutBound() {
        val replay = BrainRunReplay("request", 1)
        repeat(40) { revision ->
            replay.accept(
                AiEvent.AgentProgress(
                    requestId = "request",
                    runGeneration = 1,
                    revision = revision.toLong(),
                    stepId = "tool-$revision",
                    kind = AgentProgressKind.TOOL,
                    state = AgentProgressState.RUNNING,
                    title = "tool",
                    toolCallId = "same-call",
                ),
            )
        }

        val tools = replay.snapshot().filterIsInstance<AiEvent.AgentProgress>()
        assertEquals(1, tools.size)
        assertEquals(39, tools.single().revision)
    }
}
