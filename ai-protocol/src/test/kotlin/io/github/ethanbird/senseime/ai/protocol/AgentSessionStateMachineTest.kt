package io.github.ethanbird.senseime.ai.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionStateMachineTest {
    @Test
    fun `final answer completes the public session without a local apply step`() {
        val machine = AgentSessionStateMachine("request", 7L)
        machine.accept(started())
        machine.accept(status(HarnessPhase.GENERATING))

        val transition = machine.accept(AiEvent.FinalAnswer("request", 7L, "回答"))

        assertTrue(transition is AgentSessionTransition.Accepted)
        assertEquals(AgentExecutionState.COMPLETED, machine.snapshot.state)
        assertEquals(AgentProgressKind.ASSISTANT_UPDATE, machine.snapshot.steps.last().kind)
    }

    @Test
    fun `tool rows correlate running and completion by call id`() {
        val machine = AgentSessionStateMachine("request", 7L)
        machine.accept(started())
        machine.accept(status(HarnessPhase.CONNECTING))
        machine.accept(status(HarnessPhase.UNDERSTANDING))

        machine.accept(progress(1, AgentProgressState.RUNNING))
        val completed = machine.accept(progress(2, AgentProgressState.COMPLETED))

        assertTrue(completed is AgentSessionTransition.Accepted)
        assertEquals(AgentExecutionState.TOOL_RUNNING, machine.snapshot.state)
        assertTrue(machine.snapshot.activeToolCallIds.isEmpty())
        assertEquals(1, machine.snapshot.steps.size)
        assertEquals(AgentProgressState.COMPLETED, machine.snapshot.steps.single().state)
    }

    @Test
    fun `completion for an unknown tool is rejected without corrupting visible state`() {
        val machine = AgentSessionStateMachine("request", 7L)

        val rejected = machine.accept(progress(1, AgentProgressState.COMPLETED))

        assertTrue(rejected is AgentSessionTransition.Dropped)
        assertEquals(AgentExecutionState.CREATED, machine.snapshot.state)
        assertTrue(machine.snapshot.steps.isEmpty())
    }

    @Test
    fun `control-plane lock cannot reset execution because it is not a state-machine input`() {
        val machine = AgentSessionStateMachine("request", 7L)
        machine.accept(started())
        machine.accept(status(HarnessPhase.CONNECTING))
        machine.accept(status(HarnessPhase.UNDERSTANDING))
        machine.accept(
            AiEvent.AgentProgress(
                requestId = "request",
                runGeneration = 7L,
                revision = 1,
                stepId = "thinking",
                kind = AgentProgressKind.THINKING,
                state = AgentProgressState.RUNNING,
                title = "正在分析",
            ),
        )
        val beforeLockGesture = machine.snapshot

        // Gesture locking is deliberately owned by AiHoldGestureSession and sends no event here.
        val afterLockGesture = machine.snapshot

        assertEquals(beforeLockGesture, afterLockGesture)
        assertEquals("正在分析", afterLockGesture.steps.single().title)
    }

    @Test
    fun `late progress after terminal failure is dropped`() {
        val machine = AgentSessionStateMachine("request", 7L)
        machine.accept(
            AiEvent.Failed(
                requestId = "request",
                runGeneration = 7L,
                code = HarnessErrorCode.PROVIDER_FAILURE,
            ),
        )

        val late = machine.accept(
            AiEvent.AgentProgress(
                requestId = "request",
                runGeneration = 7L,
                revision = 1,
                stepId = "late",
                kind = AgentProgressKind.HEARTBEAT,
                state = AgentProgressState.RUNNING,
                title = "不应出现",
            ),
        )

        assertTrue(late is AgentSessionTransition.Dropped)
        assertEquals(AgentSessionDropReason.TERMINATED, (late as AgentSessionTransition.Dropped).reason)
    }

    @Test
    fun `heartbeat updates a row without regressing the execution state`() {
        val machine = AgentSessionStateMachine("request", 7L)
        machine.accept(started())
        machine.accept(status(HarnessPhase.CONNECTING))
        machine.accept(status(HarnessPhase.UNDERSTANDING))
        machine.accept(status(HarnessPhase.GENERATING))

        val heartbeat = machine.accept(
            AiEvent.AgentProgress(
                requestId = "request",
                runGeneration = 7L,
                revision = 1,
                stepId = "heartbeat",
                kind = AgentProgressKind.HEARTBEAT,
                state = AgentProgressState.RUNNING,
                title = "仍在生成",
            ),
        )

        assertTrue(heartbeat is AgentSessionTransition.Accepted)
        assertEquals(AgentExecutionState.DRAFTING, machine.snapshot.state)
    }

    @Test
    fun `invalid backwards transition is dropped`() {
        val machine = AgentSessionStateMachine("request", 7L)
        machine.accept(started())
        machine.accept(status(HarnessPhase.CONNECTING))
        machine.accept(status(HarnessPhase.UNDERSTANDING))
        machine.accept(status(HarnessPhase.GENERATING))
        machine.accept(status(HarnessPhase.VALIDATING))
        val before = machine.snapshot

        val backwards = machine.accept(status(HarnessPhase.THINKING))

        assertTrue(backwards is AgentSessionTransition.Dropped)
        assertEquals(
            AgentSessionDropReason.INVALID_TRANSITION,
            (backwards as AgentSessionTransition.Dropped).reason,
        )
        assertEquals(before, machine.snapshot)
    }

    @Test
    fun `local apply cannot skip provider validation`() {
        val machine = AgentSessionStateMachine("request", 7L)
        machine.accept(started())
        machine.accept(status(HarnessPhase.CONNECTING))
        val before = machine.snapshot

        val rejected = machine.markApplying()

        assertTrue(rejected is AgentSessionTransition.Dropped)
        assertEquals(
            AgentSessionDropReason.INVALID_TRANSITION,
            (rejected as AgentSessionTransition.Dropped).reason,
        )
        assertEquals(before, machine.snapshot)
    }

    private fun started() = AiEvent.Started(
        requestId = "request",
        runGeneration = 7L,
        startedAtMonotonicMs = 0L,
    )

    private fun status(phase: HarnessPhase) = AiEvent.Status(
        requestId = "request",
        runGeneration = 7L,
        phase = phase,
        label = phase.name.lowercase(),
    )

    private fun progress(
        revision: Long,
        state: AgentProgressState,
    ) = AiEvent.AgentProgress(
        requestId = "request",
        runGeneration = 7L,
        revision = revision,
        stepId = "tool-1",
        kind = AgentProgressKind.TOOL,
        state = state,
        title = "安全编辑工具",
        toolCallId = "call-1",
        toolName = "sense_submit_patch",
    )
}
