package io.github.ethanbird.senseime.ai.protocol

/**
 * Immutable Skill revision selected by the user before one Agent run starts.
 *
 * The full document deliberately crosses the private Brain Binder boundary with the frozen editor
 * snapshot. This prevents a settings change in another process from silently changing the
 * instructions of an already-admitted run.
 */
data class ActiveSkillInstructionV1(
    val protocol: String = SenseAiProtocol.ACTIVE_SKILL_INSTRUCTION_V1,
    val id: String,
    val revision: Long,
    val catalogGeneration: Long,
    val name: String,
    val description: String,
    val content: String,
)
