package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import java.io.File

/**
 * Android entry point for the complete-history Skill repository.
 *
 * The settings process, IME process, and private Brain process share the same app-private files.
 * Every API call acquires the same OS file lock before reading or publishing a catalog.
 */
class AgentSkillStore(context: Context) {
    private val repository = AgentSkillRepository(
        File(context.applicationContext.filesDir, DIRECTORY_NAME),
    )

    fun loadCatalog(): Result<AgentSkillCatalog> = repository.loadCatalog()

    fun apply(mutation: AgentSkillMutation): Result<AgentSkillCatalog> =
        repository.apply(mutation)

    fun create(
        id: String,
        name: String,
        description: String,
        content: String,
        baseIntent: EditorIntent = EditorIntent.SMART_EDIT,
        binding: AgentSkillSlot? = null,
        expectedGeneration: Long? = null,
    ): Result<AgentSkillCatalog> = apply(
        AgentSkillMutation.Create(
            id = id,
            name = name,
            description = description,
            content = content,
            baseIntent = baseIntent,
            binding = binding,
            expectedGeneration = expectedGeneration,
        ),
    )

    fun update(
        id: String,
        name: String? = null,
        description: String? = null,
        content: String? = null,
        baseIntent: EditorIntent? = null,
        expectedGeneration: Long? = null,
    ): Result<AgentSkillCatalog> = apply(
        AgentSkillMutation.Update(
            id = id,
            name = name,
            description = description,
            content = content,
            baseIntent = baseIntent,
            expectedGeneration = expectedGeneration,
        ),
    )

    fun bind(
        skillId: String,
        slot: AgentSkillSlot,
        expectedGeneration: Long? = null,
    ): Result<AgentSkillCatalog> =
        apply(AgentSkillMutation.Bind(skillId, slot, expectedGeneration))

    fun unbind(
        slot: AgentSkillSlot,
        expectedGeneration: Long? = null,
    ): Result<AgentSkillCatalog> =
        apply(AgentSkillMutation.Unbind(slot, expectedGeneration))

    fun unbindSkill(
        skillId: String,
        expectedGeneration: Long? = null,
    ): Result<AgentSkillCatalog> =
        apply(AgentSkillMutation.UnbindSkill(skillId, expectedGeneration))

    fun toggleActive(
        slot: AgentSkillSlot,
        expectedGeneration: Long? = null,
    ): Result<AgentSkillCatalog> =
        apply(AgentSkillMutation.ToggleActive(slot, expectedGeneration))

    fun clearActive(expectedGeneration: Long? = null): Result<AgentSkillCatalog> =
        apply(AgentSkillMutation.ClearActive(expectedGeneration))

    /**
     * Applies the keyboard's already-resolved activation intent atomically with concurrent
     * Settings and Agent-tool writes. See [AgentSkillRepository.applySelectionIntent].
     */
    fun applySelectionIntent(
        slot: AgentSkillSlot,
        selectedSkillId: String,
        activate: Boolean,
    ): Result<AgentSkillCatalog> =
        repository.applySelectionIntent(slot, selectedSkillId, activate)

    fun readRevision(skillId: String, revision: Long): Result<AgentSkillDefinition?> =
        repository.readRevision(skillId, revision)

    fun readCatalogGeneration(generation: Long): Result<AgentSkillCatalog?> =
        repository.readCatalogGeneration(generation)

    fun listRevisions(skillId: String): Result<List<Long>> =
        repository.listRevisions(skillId)

    fun listCatalogGenerations(): Result<List<Long>> =
        repository.listCatalogGenerations()

    companion object {
        const val DIRECTORY_NAME = "agent-skills-v1"
        const val CURRENT_FILE_NAME = "CURRENT"
    }
}
