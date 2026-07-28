package io.github.ethanbird.senseime.service.ai

import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.EditorIntent

data class SelectedAgentSkill(
    val baseIntent: EditorIntent,
    val instruction: ActiveSkillInstructionV1,
) {
    init {
        require(baseIntent != EditorIntent.NO_CHANGE)
    }
}

/**
 * Complete immutable Skills projection captured without disk I/O on the Space-hold path.
 */
data class AgentSkillRunSnapshot(
    val catalogGeneration: Long,
    val activeSkill: SelectedAgentSkill?,
) {
    init {
        require(catalogGeneration > 0L)
        require(
            activeSkill == null ||
                activeSkill.instruction.catalogGeneration == catalogGeneration,
        ) {
            "Selected Skill must belong to the frozen catalog generation"
        }
    }
}
