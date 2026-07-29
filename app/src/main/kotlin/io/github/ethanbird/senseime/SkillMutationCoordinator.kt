package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot

internal enum class SkillReplacementOperation {
    CREATE,
    BIND,
}

internal sealed interface SkillMutationConfirmation {
    data class SlotReplacement(
        val operation: SkillReplacementOperation,
        val generation: Long,
        val slot: AgentSkillSlot,
        val incumbentSkillId: String,
        val incumbentSkillName: String,
        val targetSkillId: String,
    ) : SkillMutationConfirmation

    data class DocumentConflict(
        val skillId: String,
        val sourceRevision: Long,
        val latestRevision: Long,
        val draft: SkillSettingsDraft,
    ) : SkillMutationConfirmation
}

internal data class SkillMutationState(
    val running: Boolean = false,
    val pendingSlotReplacement: SkillMutationConfirmation.SlotReplacement? = null,
    val pendingDocumentConflict: SkillMutationConfirmation.DocumentConflict? = null,
)

internal enum class SkillMutationOperation {
    SAVE,
    BIND,
    UNBIND_SLOT,
    UNBIND_ALL,
    RESTORE,
}

internal enum class SkillMutationBlock {
    NOT_READY,
    SAVE_BEFORE_BINDING,
    CHOOSE_SLOT,
    NO_DOCUMENT_CHANGES,
    SLOT_NOT_BOUND_TO_SELECTED,
    NO_BINDINGS,
    TASK_REJECTED,
}

internal sealed interface SkillMutationOutcome {
    data class ValidationFailed(
        val message: String,
    ) : SkillMutationOutcome

    data class Blocked(
        val reason: SkillMutationBlock,
    ) : SkillMutationOutcome

    data class ConfirmationRequired(
        val confirmation: SkillMutationConfirmation,
    ) : SkillMutationOutcome

    data class Applied(
        val operation: SkillMutationOperation,
        val catalog: AgentSkillCatalog,
        val skillId: String,
        val retainDirtyDraft: Boolean = false,
    ) : SkillMutationOutcome

    data class Failed(
        val error: Throwable,
    ) : SkillMutationOutcome

    data class GenerationConflict(
        val error: Throwable,
    ) : SkillMutationOutcome
}

internal data class SkillMutationUpdate(
    val state: SkillMutationState,
    val outcome: SkillMutationOutcome? = null,
)

/**
 * Serializes every Skill mutation and owns the two exact, identity-bound confirmation protocols.
 *
 * The shared task runner keeps accepted writes FIFO. This controller owns only its callback
 * generation: detaching a View revokes old UI delivery without cancelling an accepted write.
 */
internal class SkillMutationCoordinator(
    private val repository: SkillMutationWriter,
    private val tasks: SettingsTaskRunner,
    private val render: (SkillMutationUpdate) -> Unit,
) {
    var state: SkillMutationState = SkillMutationState()
        private set

    private var attached = false
    private var generation = 0L

    fun attach() {
        attached = true
        generation = nextGeneration(generation)
        render(SkillMutationUpdate(state))
    }

    fun detach() {
        attached = false
        generation = nextGeneration(generation)
    }

    fun clearConfirmations() {
        if (
            state.pendingSlotReplacement == null &&
            state.pendingDocumentConflict == null
        ) {
            return
        }
        state = state.copy(
            pendingSlotReplacement = null,
            pendingDocumentConflict = null,
        )
        publish()
    }

    fun save(
        catalog: AgentSkillCatalog?,
        record: SkillEditorDraftRecord?,
    ) {
        if (state.running) return
        if (catalog == null || record == null) {
            publishBlocked(SkillMutationBlock.NOT_READY)
            return
        }

        val draft = record.draft
        draft.validationError()?.let { message ->
            publishOutcome(SkillMutationOutcome.ValidationFailed(message))
            return
        }

        val existing = record.sourceSkillId?.let(catalog::definition)
        if (!record.creating && record.conflictsWith(existing)) {
            val confirmation = SkillMutationConfirmation.DocumentConflict(
                skillId = requireNotNull(record.sourceSkillId),
                sourceRevision = requireNotNull(record.sourceRevision),
                latestRevision = existing?.revision ?: MISSING_REVISION,
                draft = draft,
            )
            if (state.pendingDocumentConflict != confirmation) {
                state = state.copy(pendingDocumentConflict = confirmation)
                publishOutcome(SkillMutationOutcome.ConfirmationRequired(confirmation))
                return
            }
        }

        if (
            record.creating &&
            requiresSlotReplacement(
                catalog = catalog,
                operation = SkillReplacementOperation.CREATE,
                targetSkillId = draft.id.trim(),
                slot = draft.bindingSlot,
            )
        ) {
            return
        }

        val mutation = if (record.creating) {
            draft.createMutation(catalog.generation)
        } else {
            val current = existing
            if (current == null) {
                publishBlocked(SkillMutationBlock.NOT_READY)
                return
            }
            draft.updateMutation(current, catalog.generation).also { update ->
                if (!update.hasDocumentChanges()) {
                    publishBlocked(SkillMutationBlock.NO_DOCUMENT_CHANGES)
                    return
                }
            }
        }
        submit(
            mutation = mutation,
            operation = SkillMutationOperation.SAVE,
            skillId = draft.id.trim(),
        )
    }

    fun bind(
        catalog: AgentSkillCatalog?,
        skill: AgentSkillDefinition?,
        slot: AgentSkillSlot?,
    ) {
        if (state.running) return
        if (catalog == null) {
            publishBlocked(SkillMutationBlock.NOT_READY)
            return
        }
        if (skill == null) {
            publishBlocked(SkillMutationBlock.SAVE_BEFORE_BINDING)
            return
        }
        if (slot == null) {
            publishBlocked(SkillMutationBlock.CHOOSE_SLOT)
            return
        }
        if (
            requiresSlotReplacement(
                catalog = catalog,
                operation = SkillReplacementOperation.BIND,
                targetSkillId = skill.id,
                slot = slot,
            )
        ) {
            return
        }
        submit(
            mutation = AgentSkillMutation.Bind(
                skillId = skill.id,
                slot = slot,
                expectedGeneration = catalog.generation,
            ),
            operation = SkillMutationOperation.BIND,
            skillId = skill.id,
        )
    }

    fun unbindSlot(
        catalog: AgentSkillCatalog?,
        skill: AgentSkillDefinition?,
        slot: AgentSkillSlot?,
    ) {
        if (state.running) return
        if (catalog == null || skill == null) {
            publishBlocked(SkillMutationBlock.NOT_READY)
            return
        }
        if (slot == null) {
            publishBlocked(SkillMutationBlock.CHOOSE_SLOT)
            return
        }
        if (catalog.binding(slot)?.skillId != skill.id) {
            publishBlocked(SkillMutationBlock.SLOT_NOT_BOUND_TO_SELECTED)
            return
        }
        submit(
            mutation = AgentSkillMutation.Unbind(
                slot = slot,
                expectedGeneration = catalog.generation,
            ),
            operation = SkillMutationOperation.UNBIND_SLOT,
            skillId = skill.id,
        )
    }

    fun unbindAll(
        catalog: AgentSkillCatalog?,
        skill: AgentSkillDefinition?,
    ) {
        if (state.running) return
        if (catalog == null || skill == null) {
            publishBlocked(SkillMutationBlock.NOT_READY)
            return
        }
        if (catalog.bindings.none { it.skillId == skill.id }) {
            publishBlocked(SkillMutationBlock.NO_BINDINGS)
            return
        }
        submit(
            mutation = AgentSkillMutation.UnbindSkill(
                skillId = skill.id,
                expectedGeneration = catalog.generation,
            ),
            operation = SkillMutationOperation.UNBIND_ALL,
            skillId = skill.id,
        )
    }

    fun restore(
        mutation: AgentSkillMutation.Update,
        currentSkillId: String,
        retainDirtyDraft: Boolean,
    ) {
        if (state.running) return
        require(mutation.id == currentSkillId) {
            "Historical revision target does not match the current Skill"
        }
        submit(
            mutation = mutation,
            operation = SkillMutationOperation.RESTORE,
            skillId = currentSkillId,
            retainDirtyDraft = retainDirtyDraft,
        )
    }

    private fun requiresSlotReplacement(
        catalog: AgentSkillCatalog,
        operation: SkillReplacementOperation,
        targetSkillId: String,
        slot: AgentSkillSlot?,
    ): Boolean {
        val occupancy = catalog.occupancy(slot, targetSkillId)
        if (!occupancy.requiresReplacement) return false
        val confirmation = SkillMutationConfirmation.SlotReplacement(
            operation = operation,
            generation = catalog.generation,
            slot = requireNotNull(slot),
            incumbentSkillId = requireNotNull(occupancy.incumbentSkillId),
            incumbentSkillName = occupancy.incumbentSkillName.orEmpty(),
            targetSkillId = targetSkillId,
        )
        if (state.pendingSlotReplacement == confirmation) return false
        state = state.copy(pendingSlotReplacement = confirmation)
        publishOutcome(SkillMutationOutcome.ConfirmationRequired(confirmation))
        return true
    }

    private fun submit(
        mutation: AgentSkillMutation,
        operation: SkillMutationOperation,
        skillId: String,
        retainDirtyDraft: Boolean = false,
    ) {
        val requestGeneration = generation
        state = state.copy(running = true)
        publish()
        val accepted = tasks.execute(
            operation = { repository.apply(mutation).getOrThrow() },
        ) { result ->
            state = state.copy(
                running = false,
                pendingSlotReplacement = null,
                pendingDocumentConflict = null,
            )
            if (!accepts(requestGeneration)) return@execute
            val outcome = result.fold(
                onSuccess = { catalog ->
                    SkillMutationOutcome.Applied(
                        operation = operation,
                        catalog = catalog,
                        skillId = skillId,
                        retainDirtyDraft = retainDirtyDraft,
                    )
                },
                onFailure = { error ->
                    if (
                        error.message.orEmpty().startsWith(
                            SKILL_GENERATION_CONFLICT_PREFIX,
                        )
                    ) {
                        SkillMutationOutcome.GenerationConflict(error)
                    } else {
                        SkillMutationOutcome.Failed(error)
                    }
                },
            )
            render(SkillMutationUpdate(state, outcome))
        }
        if (!accepted) {
            state = state.copy(
                running = false,
                pendingSlotReplacement = null,
                pendingDocumentConflict = null,
            )
            if (accepts(requestGeneration)) {
                render(
                    SkillMutationUpdate(
                        state = state,
                        outcome = SkillMutationOutcome.Blocked(
                            SkillMutationBlock.TASK_REJECTED,
                        ),
                    ),
                )
            }
        }
    }

    private fun publishBlocked(reason: SkillMutationBlock) =
        publishOutcome(SkillMutationOutcome.Blocked(reason))

    private fun publishOutcome(outcome: SkillMutationOutcome) {
        if (attached) render(SkillMutationUpdate(state, outcome))
    }

    private fun publish() {
        if (attached) render(SkillMutationUpdate(state))
    }

    private fun accepts(requestGeneration: Long): Boolean =
        attached && generation == requestGeneration

    private fun AgentSkillMutation.Update.hasDocumentChanges(): Boolean =
        name != null || description != null || content != null || baseIntent != null

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private companion object {
        const val MISSING_REVISION = -1L
        const val SKILL_GENERATION_CONFLICT_PREFIX = "Skill catalog changed:"
    }
}
