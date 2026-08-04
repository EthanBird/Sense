package io.github.ethanbird.senseime.ai.protocol

/**
 * Deterministic public state machine for one Sense Agent run.
 *
 * Gesture ownership intentionally does not appear here. Holding, upward locking and pressing Stop
 * are control-plane concerns; they must never reset execution progress or visible output.
 */
class AgentSessionStateMachine(
    private val requestId: String,
    private val runGeneration: Long,
    private val maxVisibleSteps: Int = DEFAULT_MAX_VISIBLE_STEPS,
) {
    private val steps = linkedMapOf<String, AgentSessionStep>()
    private val activeTools = linkedMapOf<String, String>()
    private var mutableState = AgentExecutionState.CREATED
    private var lastProgressRevision = 0L
    private var revision = 0L

    init {
        require(requestId.isNotBlank())
        require(runGeneration > 0L)
        require(maxVisibleSteps > 0)
    }

    val snapshot: AgentSessionSnapshot
        get() = AgentSessionSnapshot(
            revision = revision,
            state = mutableState,
            steps = steps.values.toList().takeLast(maxVisibleSteps),
            activeToolCallIds = activeTools.keys.toSet(),
        )

    fun accept(event: AiEvent): AgentSessionTransition {
        if (event.requestId != requestId || event.runGeneration != runGeneration) {
            return AgentSessionTransition.Dropped(snapshot, AgentSessionDropReason.IDENTITY_MISMATCH)
        }
        if (mutableState.isTerminal) {
            return AgentSessionTransition.Dropped(snapshot, AgentSessionDropReason.TERMINATED)
        }

        val accepted = when (event) {
            is AiEvent.Started -> moveTo(AgentExecutionState.CAPTURING)
            is AiEvent.Status -> moveTo(event.phase.executionState)
            is AiEvent.AgentProgress -> acceptProgress(event)
            is AiEvent.DescriptionDelta -> moveTo(AgentExecutionState.DRAFTING)
            is AiEvent.PreviewReset -> moveTo(AgentExecutionState.RECOVERING)
            is AiEvent.PreviewDelta,
            is AiEvent.PreviewReplace,
            -> moveTo(AgentExecutionState.DRAFTING)
            is AiEvent.Usage -> true
            is AiEvent.FinalPatch -> moveTo(AgentExecutionState.VALIDATING)
            is AiEvent.FinalAnswer -> {
                steps["terminal"] = AgentSessionStep(
                    id = "terminal",
                    kind = AgentProgressKind.ASSISTANT_UPDATE,
                    state = AgentProgressState.COMPLETED,
                    title = "回答已完成",
                )
                trimSteps()
                mutableState = AgentExecutionState.COMPLETED
                true
            }
            is AiEvent.Cancelled -> {
                steps["terminal"] = AgentSessionStep(
                    id = "terminal",
                    kind = AgentProgressKind.APPLICATION,
                    state = AgentProgressState.FAILED,
                    title = "任务已停止",
                )
                moveTo(AgentExecutionState.CANCELLED)
            }
            is AiEvent.Failed -> {
                steps["terminal"] = AgentSessionStep(
                    id = "terminal",
                    kind = AgentProgressKind.APPLICATION,
                    state = AgentProgressState.FAILED,
                    title = "Agent 未能完成本次任务",
                )
                trimSteps()
                moveTo(AgentExecutionState.FAILED)
            }
        }
        if (!accepted) {
            return AgentSessionTransition.Dropped(snapshot, AgentSessionDropReason.INVALID_TRANSITION)
        }
        revision += 1L
        return AgentSessionTransition.Accepted(snapshot)
    }

    fun markApplying(title: String = "正在安全写入输入框"): AgentSessionTransition =
        localStep(
            state = AgentExecutionState.APPLYING,
            step = AgentSessionStep(
                id = "local-apply",
                kind = AgentProgressKind.APPLICATION,
                state = AgentProgressState.RUNNING,
                title = title,
            ),
        )

    fun markApplied(title: String = "已写入输入框"): AgentSessionTransition =
        localStep(
            state = AgentExecutionState.COMPLETED,
            step = AgentSessionStep(
                id = "local-apply",
                kind = AgentProgressKind.APPLICATION,
                state = AgentProgressState.COMPLETED,
                title = title,
            ),
        )

    fun markApplyFailed(title: String): AgentSessionTransition =
        localStep(
            state = AgentExecutionState.FAILED,
            step = AgentSessionStep(
                id = "local-apply",
                kind = AgentProgressKind.APPLICATION,
                state = AgentProgressState.FAILED,
                title = title,
            ),
        )

    private fun acceptProgress(event: AiEvent.AgentProgress): Boolean {
        if (event.revision <= lastProgressRevision) return false
        if (event.stepId.isBlank() || event.title.isBlank()) return false
        val nextExecutionState = if (event.kind == AgentProgressKind.HEARTBEAT) {
            mutableState
        } else {
            event.kind.executionState
        }
        if (
            nextExecutionState != mutableState &&
            !mutableState.canTransitionTo(nextExecutionState)
        ) {
            return false
        }
        if (event.kind == AgentProgressKind.TOOL) {
            val callId = event.toolCallId ?: return false
            val toolName = event.toolName ?: return false
            when (event.state) {
                AgentProgressState.RUNNING -> {
                    val existing = activeTools[callId]
                    if (existing != null && existing != toolName) return false
                }
                AgentProgressState.COMPLETED,
                AgentProgressState.FAILED,
                -> if (activeTools[callId] != toolName) return false
            }
        } else if (event.toolCallId != null || event.toolName != null) {
            return false
        }

        if (event.kind == AgentProgressKind.TOOL) {
            val callId = checkNotNull(event.toolCallId)
            when (event.state) {
                AgentProgressState.RUNNING -> activeTools[callId] = checkNotNull(event.toolName)
                AgentProgressState.COMPLETED,
                AgentProgressState.FAILED,
                -> activeTools.remove(callId)
            }
        }
        lastProgressRevision = event.revision
        steps[event.stepId] = AgentSessionStep(
            id = event.stepId,
            kind = event.kind,
            state = event.state,
            title = event.title,
            detail = event.detail,
            toolCallId = event.toolCallId,
            toolName = event.toolName,
        )
        trimSteps()
        return moveTo(nextExecutionState)
    }

    private fun localStep(
        state: AgentExecutionState,
        step: AgentSessionStep,
    ): AgentSessionTransition {
        if (mutableState.isTerminal) {
            return AgentSessionTransition.Dropped(snapshot, AgentSessionDropReason.TERMINATED)
        }
        if (state != mutableState && !mutableState.canTransitionTo(state)) {
            return AgentSessionTransition.Dropped(
                snapshot,
                AgentSessionDropReason.INVALID_TRANSITION,
            )
        }
        steps[step.id] = step
        trimSteps()
        mutableState = state
        revision += 1L
        return AgentSessionTransition.Accepted(snapshot)
    }

    private fun trimSteps() {
        while (steps.size > maxVisibleSteps) {
            val removable = steps.entries.firstOrNull {
                it.value.toolCallId == null || it.value.toolCallId !in activeTools
            } ?: break
            steps.remove(removable.key)
        }
    }

    private fun moveTo(next: AgentExecutionState): Boolean {
        if (mutableState.isTerminal) return false
        if (next == mutableState) return true
        if (!mutableState.canTransitionTo(next)) return false
        mutableState = next
        return true
    }

    companion object {
        const val DEFAULT_MAX_VISIBLE_STEPS = 5
    }
}

enum class AgentExecutionState {
    CREATED,
    CAPTURING,
    CONNECTING,
    UNDERSTANDING,
    THINKING,
    TOOL_RUNNING,
    DRAFTING,
    VALIDATING,
    APPLYING,
    RECOVERING,
    COMPLETED,
    CANCELLED,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED
}

data class AgentSessionStep(
    val id: String,
    val kind: AgentProgressKind,
    val state: AgentProgressState,
    val title: String,
    val detail: String = "",
    val toolCallId: String? = null,
    val toolName: String? = null,
)

data class AgentSessionSnapshot(
    val revision: Long,
    val state: AgentExecutionState,
    val steps: List<AgentSessionStep>,
    val activeToolCallIds: Set<String>,
)

sealed interface AgentSessionTransition {
    val snapshot: AgentSessionSnapshot

    data class Accepted(
        override val snapshot: AgentSessionSnapshot,
    ) : AgentSessionTransition

    data class Dropped(
        override val snapshot: AgentSessionSnapshot,
        val reason: AgentSessionDropReason,
    ) : AgentSessionTransition
}

enum class AgentSessionDropReason {
    IDENTITY_MISMATCH,
    INVALID_TRANSITION,
    TERMINATED,
}

private val HarnessPhase.executionState: AgentExecutionState
    get() = when (this) {
        HarnessPhase.CONNECTING -> AgentExecutionState.CONNECTING
        HarnessPhase.UNDERSTANDING -> AgentExecutionState.UNDERSTANDING
        HarnessPhase.THINKING -> AgentExecutionState.THINKING
        HarnessPhase.TOOL_RUNNING -> AgentExecutionState.TOOL_RUNNING
        HarnessPhase.GENERATING -> AgentExecutionState.DRAFTING
        HarnessPhase.VALIDATING -> AgentExecutionState.VALIDATING
        HarnessPhase.APPLYING -> AgentExecutionState.APPLYING
    }

private val AgentProgressKind.executionState: AgentExecutionState
    get() = when (this) {
        AgentProgressKind.OBSERVATION -> AgentExecutionState.CAPTURING
        AgentProgressKind.CONNECTION -> AgentExecutionState.CONNECTING
        AgentProgressKind.THINKING -> AgentExecutionState.THINKING
        AgentProgressKind.DRAFTING -> AgentExecutionState.DRAFTING
        AgentProgressKind.HEARTBEAT -> error("heartbeat preserves the current execution state")
        AgentProgressKind.ASSISTANT_UPDATE -> AgentExecutionState.UNDERSTANDING
        AgentProgressKind.TOOL -> AgentExecutionState.TOOL_RUNNING
        AgentProgressKind.VALIDATION -> AgentExecutionState.VALIDATING
        AgentProgressKind.APPLICATION -> AgentExecutionState.APPLYING
        AgentProgressKind.RECOVERY -> AgentExecutionState.RECOVERING
    }

private fun AgentExecutionState.canTransitionTo(next: AgentExecutionState): Boolean {
    if (next == AgentExecutionState.CANCELLED || next == AgentExecutionState.FAILED) return true
    return when (this) {
        AgentExecutionState.CREATED ->
            next == AgentExecutionState.CAPTURING
        AgentExecutionState.CAPTURING ->
            next == AgentExecutionState.CONNECTING ||
                next == AgentExecutionState.UNDERSTANDING
        AgentExecutionState.CONNECTING ->
            next == AgentExecutionState.UNDERSTANDING ||
                next == AgentExecutionState.THINKING ||
                next == AgentExecutionState.RECOVERING
        AgentExecutionState.UNDERSTANDING ->
            next == AgentExecutionState.CONNECTING ||
                next == AgentExecutionState.THINKING ||
                next == AgentExecutionState.TOOL_RUNNING ||
                next == AgentExecutionState.DRAFTING ||
                next == AgentExecutionState.RECOVERING
        AgentExecutionState.THINKING ->
            next == AgentExecutionState.TOOL_RUNNING ||
                next == AgentExecutionState.DRAFTING ||
                next == AgentExecutionState.VALIDATING ||
                next == AgentExecutionState.RECOVERING
        AgentExecutionState.TOOL_RUNNING ->
            next == AgentExecutionState.CONNECTING ||
                next == AgentExecutionState.UNDERSTANDING ||
                next == AgentExecutionState.DRAFTING ||
                next == AgentExecutionState.VALIDATING ||
                next == AgentExecutionState.RECOVERING
        AgentExecutionState.DRAFTING ->
            next == AgentExecutionState.TOOL_RUNNING ||
                next == AgentExecutionState.VALIDATING ||
                next == AgentExecutionState.RECOVERING
        AgentExecutionState.VALIDATING ->
            next == AgentExecutionState.APPLYING ||
                next == AgentExecutionState.RECOVERING
        AgentExecutionState.RECOVERING ->
            next == AgentExecutionState.CONNECTING ||
                next == AgentExecutionState.UNDERSTANDING ||
                next == AgentExecutionState.THINKING ||
                next == AgentExecutionState.TOOL_RUNNING ||
                next == AgentExecutionState.DRAFTING ||
                next == AgentExecutionState.VALIDATING
        AgentExecutionState.APPLYING ->
            next == AgentExecutionState.COMPLETED
        AgentExecutionState.COMPLETED,
        AgentExecutionState.CANCELLED,
        AgentExecutionState.FAILED,
        -> false
    }
}
