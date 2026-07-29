package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation

internal enum class SkillHistoryPhase {
    EMPTY,
    LOADING_LIST,
    READY,
    READING_REVISION,
    LIST_FAILED,
    READ_FAILED,
}

internal data class SkillHistoryState(
    val phase: SkillHistoryPhase = SkillHistoryPhase.EMPTY,
    val skillId: String? = null,
    val currentRevision: Long? = null,
    val revisions: List<Long> = emptyList(),
    val selectedRevision: Long? = null,
    val viewedRevision: AgentSkillDefinition? = null,
    val failure: Throwable? = null,
) {
    val listFailureMessage: String?
        get() = failure?.message?.takeIf { phase == SkillHistoryPhase.LIST_FAILED }

    val currentRevisionMissing: Boolean
        get() = phase == SkillHistoryPhase.READY &&
            currentRevision != null &&
            currentRevision !in revisions

    val canView: Boolean
        get() = selectedRevision != null &&
            (phase == SkillHistoryPhase.READY || phase == SkillHistoryPhase.READ_FAILED)

    val canRestore: Boolean
        get() = phase == SkillHistoryPhase.READY &&
            viewedRevision != null &&
            viewedRevision.revision != currentRevision
}

internal enum class SkillHistoryViewAdmission {
    STARTED,
    NO_SELECTION,
    REJECTED,
}

internal sealed interface SkillHistoryRestoreDecision {
    data object ViewFirst : SkillHistoryRestoreDecision
    data object ChooseOlderRevision : SkillHistoryRestoreDecision
    data object SameContent : SkillHistoryRestoreDecision
    data class Ready(
        val mutation: AgentSkillMutation.Update,
        val currentSkillId: String,
    ) : SkillHistoryRestoreDecision
}

/**
 * Owns revision list/read selection and its independent latest-wins generation.
 *
 * It deliberately does not own the shared task lane. Closing the screen revokes this controller's
 * generation while already accepted filesystem work keeps the Skill durability contract.
 */
internal class SkillHistoryController(
    private val repository: SkillHistoryReader,
    private val tasks: SettingsTaskRunner,
    private val render: (SkillHistoryState) -> Unit,
) {
    var state: SkillHistoryState = SkillHistoryState()
        private set
    private var attached = false
    private var generation = 0L

    fun attach() {
        attached = true
        generation = nextGeneration(generation)
    }

    fun detach() {
        attached = false
        generation = nextGeneration(generation)
    }

    fun clear() {
        generation = nextGeneration(generation)
        publish(SkillHistoryState())
    }

    fun load(
        skillId: String,
        currentRevision: Long,
    ): Boolean {
        val requestGeneration = nextRequest()
        publish(
            SkillHistoryState(
                phase = SkillHistoryPhase.LOADING_LIST,
                skillId = skillId,
                currentRevision = currentRevision,
            ),
        )
        val accepted = tasks.refresh(
            channel = LIST_CHANNEL,
            operation = {
                repository.listRevisions(skillId).getOrThrow().sortedDescending()
            },
        ) { result ->
            if (!accepts(requestGeneration)) return@refresh
            result
                .onSuccess { revisions ->
                    publish(
                        SkillHistoryState(
                            phase = SkillHistoryPhase.READY,
                            skillId = skillId,
                            currentRevision = currentRevision,
                            revisions = revisions,
                            selectedRevision = revisions.firstOrNull(),
                        ),
                    )
                }
                .onFailure { error ->
                    publish(
                        SkillHistoryState(
                            phase = SkillHistoryPhase.LIST_FAILED,
                            skillId = skillId,
                            currentRevision = currentRevision,
                            failure = error,
                        ),
                    )
                }
        }
        if (!accepted && accepts(requestGeneration)) {
            publish(
                state.copy(
                    phase = SkillHistoryPhase.LIST_FAILED,
                    failure = IllegalStateException("Skill history lane is closed"),
                ),
            )
        }
        return accepted
    }

    fun select(position: Int) {
        val revision = state.revisions.getOrNull(position)
        generation = nextGeneration(generation)
        publish(
            state.copy(
                phase = if (state.phase == SkillHistoryPhase.EMPTY) {
                    SkillHistoryPhase.EMPTY
                } else {
                    SkillHistoryPhase.READY
                },
                selectedRevision = revision,
                viewedRevision = null,
                failure = null,
            ),
        )
    }

    fun view(current: AgentSkillDefinition): SkillHistoryViewAdmission {
        val revision = state.selectedRevision ?: return SkillHistoryViewAdmission.NO_SELECTION
        val skillId = state.skillId ?: return SkillHistoryViewAdmission.NO_SELECTION
        if (current.id != skillId) return SkillHistoryViewAdmission.REJECTED
        if (revision == current.revision) {
            generation = nextGeneration(generation)
            publish(
                state.copy(
                    phase = SkillHistoryPhase.READY,
                    viewedRevision = current,
                    failure = null,
                ),
            )
            return SkillHistoryViewAdmission.STARTED
        }
        val requestGeneration = nextRequest()
        publish(
            state.copy(
                phase = SkillHistoryPhase.READING_REVISION,
                viewedRevision = null,
                failure = null,
            ),
        )
        val accepted = tasks.refresh(
            channel = REVISION_CHANNEL,
            operation = {
                repository.readRevision(skillId, revision).getOrThrow()
                    ?: throw NoSuchElementException(
                        "Skill revision $skillId@$revision was not found",
                    )
            },
        ) { result ->
            if (!accepts(requestGeneration)) return@refresh
            result
                .onSuccess { definition ->
                    if (
                        state.skillId != skillId ||
                        state.selectedRevision != revision
                    ) {
                        return@onSuccess
                    }
                    if (definition.id != skillId || definition.revision != revision) {
                        publish(
                            state.copy(
                                phase = SkillHistoryPhase.READ_FAILED,
                                viewedRevision = null,
                                failure = IllegalStateException(
                                    "Skill revision identity mismatch: " +
                                        "${definition.id}@${definition.revision}",
                                ),
                            ),
                        )
                        return@onSuccess
                    }
                    publish(
                        state.copy(
                            phase = SkillHistoryPhase.READY,
                            viewedRevision = definition,
                            failure = null,
                        ),
                    )
                }
                .onFailure { error ->
                    publish(
                        state.copy(
                            phase = SkillHistoryPhase.READ_FAILED,
                            viewedRevision = null,
                            failure = error,
                        ),
                    )
                }
        }
        if (!accepted && accepts(requestGeneration)) {
            publish(
                state.copy(
                    phase = SkillHistoryPhase.READ_FAILED,
                    failure = IllegalStateException("Skill history lane is closed"),
                ),
            )
            return SkillHistoryViewAdmission.REJECTED
        }
        return SkillHistoryViewAdmission.STARTED
    }

    fun restoreDecision(
        current: AgentSkillDefinition?,
        catalogGeneration: Long?,
    ): SkillHistoryRestoreDecision {
        val historical = state.viewedRevision
            ?: return SkillHistoryRestoreDecision.ViewFirst
        if (
            current == null ||
            catalogGeneration == null ||
            historical.id != current.id ||
            historical.revision == current.revision
        ) {
            return SkillHistoryRestoreDecision.ChooseOlderRevision
        }
        val mutation = historical.restoreAsNewRevision(current, catalogGeneration)
            ?: return SkillHistoryRestoreDecision.SameContent
        return SkillHistoryRestoreDecision.Ready(mutation, current.id)
    }

    private fun nextRequest(): Long {
        generation = nextGeneration(generation)
        return generation
    }

    private fun accepts(requestGeneration: Long): Boolean =
        attached && generation == requestGeneration

    private fun publish(next: SkillHistoryState) {
        state = next
        if (attached) render(next)
    }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private companion object {
        const val LIST_CHANNEL = "history-list"
        const val REVISION_CHANNEL = "history-revision"
    }
}
