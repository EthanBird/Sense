package io.github.ethanbird.senseime

import android.content.Context
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.runtime.AgentSkillStore

internal fun interface SkillCatalogReader {
    fun loadCatalog(): Result<AgentSkillCatalog>
}

internal fun interface SkillMutationWriter {
    fun apply(mutation: AgentSkillMutation): Result<AgentSkillCatalog>
}

internal interface SkillHistoryReader {
    fun listRevisions(skillId: String): Result<List<Long>>
    fun readRevision(skillId: String, revision: Long): Result<AgentSkillDefinition?>
}

internal interface SkillSettingsRepository :
    SkillCatalogReader,
    SkillMutationWriter,
    SkillHistoryReader

/**
 * Lazy Android adapter: even AgentSkillStore's filesDir lookup happens on the owning task lane.
 */
internal class RuntimeSkillSettingsRepository(
    context: Context,
) : SkillSettingsRepository {
    private val applicationContext = context.applicationContext
    private val store by lazy { AgentSkillStore(applicationContext) }

    override fun loadCatalog(): Result<AgentSkillCatalog> =
        store.loadCatalog()

    override fun apply(mutation: AgentSkillMutation): Result<AgentSkillCatalog> =
        store.apply(mutation)

    override fun listRevisions(skillId: String): Result<List<Long>> =
        store.listRevisions(skillId)

    override fun readRevision(
        skillId: String,
        revision: Long,
    ): Result<AgentSkillDefinition?> =
        store.readRevision(skillId, revision)
}
