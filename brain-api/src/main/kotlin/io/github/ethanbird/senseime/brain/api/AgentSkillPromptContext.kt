package io.github.ethanbird.senseime.brain.api

/**
 * Compact discovery record exposed to the default Agent.
 *
 * Full Skill text is intentionally absent; the Agent calls `skill_read` only when a non-active
 * Skill is relevant. This keeps the ordinary input-method prompt bounded as the catalog grows.
 */
data class AgentSkillSummary(
    val id: String,
    val revision: Long,
    val name: String,
    val description: String,
) {
    init {
        AgentSkillPolicy.requireValidId(id)
        require(revision > 0L)
        AgentSkillPolicy.requireValidName(name)
        AgentSkillPolicy.requireValidDescription(description)
    }
}

fun AgentSkillDefinition.toSummary(): AgentSkillSummary = AgentSkillSummary(
    id = id,
    revision = revision,
    name = name,
    description = description,
)

/**
 * Compact, content-free catalog projection returned out of band by `skill_manage`.
 *
 * Repository generations may skip numbers when corrupt/orphaned snapshots are preserved. The
 * exact successful generation and every discoverable id/revision must therefore travel together;
 * advancing only a scalar generation would mislabel stale summaries as current.
 */
class AgentSkillCatalogSnapshot(
    val generation: Long,
    skills: List<AgentSkillSummary>,
) {
    val skills: List<AgentSkillSummary> = skills.toList()

    init {
        require(generation > 0L)
        require(this.skills.size <= AgentSkillPolicy.MAX_SKILLS)
        require(this.skills.map(AgentSkillSummary::id).toSet().size == this.skills.size) {
            "Duplicate Skill ids in catalog snapshot"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is AgentSkillCatalogSnapshot &&
                    generation == other.generation &&
                    skills == other.skills
                )

    override fun hashCode(): Int = 31 * generation.hashCode() + skills.hashCode()

    override fun toString(): String =
        "AgentSkillCatalogSnapshot(generation=$generation, skills=$skills)"
}
