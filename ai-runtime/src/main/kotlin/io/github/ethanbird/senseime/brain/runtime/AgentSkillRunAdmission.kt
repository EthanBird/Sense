package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog

/**
 * Cross-process consistency gate between the IME-frozen request and the immutable catalog loaded
 * by the private Brain process.
 */
internal object AgentSkillRunAdmission {
    fun requireConsistent(
        request: HarnessRequestV1,
        catalog: AgentSkillCatalog,
    ) {
        request.skillCatalogGeneration?.let { requestedGeneration ->
            require(requestedGeneration == catalog.generation) {
                "Skill discovery catalog generation changed across Binder"
            }
        }
        val active = request.activeSkill
        if (active == null) {
            if (request.skillCatalogGeneration != null) {
                require(catalog.active == null) {
                    "Frozen catalog has an active Skill missing from the request"
                }
            }
            return
        }
        require(active.catalogGeneration == catalog.generation) {
            "Active Skill catalog generation mismatch"
        }
        require(catalog.active?.skillId == active.id) {
            "Requested Skill is not active in the frozen catalog"
        }
        val definition = requireNotNull(catalog.definition(active.id)) {
            "Active Skill is absent from the frozen catalog"
        }
        require(
            definition.revision == active.revision &&
                definition.name == active.name &&
                definition.description == active.description &&
                definition.content == active.content &&
                definition.baseIntent == request.skill,
        ) {
            "Active Skill revision/content identity mismatch"
        }
    }
}
