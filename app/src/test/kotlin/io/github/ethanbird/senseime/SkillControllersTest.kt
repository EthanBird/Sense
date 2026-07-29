package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.brain.api.AgentSkillBinding
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalogReducer
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillControllersTest {
    @Test
    fun historySortsRevisionsAndSelectsNewest() {
        val repository = FakeSkillSettingsRepository(catalog(definition()))
        repository.revisions["alpha"] = Result.success(listOf(1L, 3L, 2L, 2L))
        val tasks = DeferredSkillTaskRunner()
        val states = mutableListOf<SkillHistoryState>()
        val controller = SkillHistoryController(repository, tasks, states::add)

        controller.attach()
        assertTrue(controller.load("alpha", currentRevision = 3L))
        assertEquals(SkillHistoryPhase.LOADING_LIST, states.last().phase)

        tasks.runNext()

        assertEquals(listOf(3L, 2L, 2L, 1L), controller.state.revisions)
        assertEquals(3L, controller.state.selectedRevision)
        assertEquals(SkillHistoryPhase.READY, controller.state.phase)
    }

    @Test
    fun historySameRevisionSelectionIsIdempotentDuringReadyAndRead() {
        val current = definition(revision = 2L, content = "current")
        val historical = current.copy(revision = 1L, content = "historical")
        val repository = FakeSkillSettingsRepository(catalog(current))
        repository.revisions["alpha"] = Result.success(listOf(2L, 1L))
        repository.documents["alpha" to 1L] = Result.success(historical)
        val tasks = DeferredSkillTaskRunner()
        val states = mutableListOf<SkillHistoryState>()
        val controller = SkillHistoryController(repository, tasks, states::add)
        controller.attach()
        controller.load("alpha", currentRevision = 2L)
        tasks.runNext()

        val readyPublicationCount = states.size
        controller.select(0)
        assertEquals(readyPublicationCount, states.size)
        assertEquals(SkillHistoryPhase.READY, controller.state.phase)

        controller.select(1)
        assertEquals(SkillHistoryViewAdmission.STARTED, controller.view(current))
        assertEquals(SkillHistoryPhase.READING_REVISION, controller.state.phase)
        val readingPublicationCount = states.size

        controller.select(1)

        assertEquals(readingPublicationCount, states.size)
        assertEquals(SkillHistoryPhase.READING_REVISION, controller.state.phase)
        tasks.runNext()
        assertEquals(SkillHistoryPhase.READY, controller.state.phase)
        assertEquals(historical, controller.state.viewedRevision)
    }

    @Test
    fun historyDropsSupersededSkillAndRevisionReads() {
        val alpha = definition(id = "alpha", revision = 3L)
        val beta = definition(id = "beta", revision = 4L)
        val repository = FakeSkillSettingsRepository(catalog(alpha, beta))
        repository.revisions["alpha"] = Result.success(listOf(3L, 2L))
        repository.revisions["beta"] = Result.success(listOf(4L, 1L))
        repository.documents["beta" to 1L] = Result.success(
            beta.copy(revision = 1L, content = "old beta"),
        )
        val tasks = DeferredSkillTaskRunner()
        val controller = SkillHistoryController(repository, tasks) {}
        controller.attach()

        controller.load("alpha", currentRevision = 3L)
        controller.load("beta", currentRevision = 4L)
        tasks.runNext()
        assertEquals("beta", controller.state.skillId)
        assertEquals(SkillHistoryPhase.LOADING_LIST, controller.state.phase)
        tasks.runNext()
        assertEquals(listOf(4L, 1L), controller.state.revisions)

        controller.select(1)
        assertEquals(SkillHistoryViewAdmission.STARTED, controller.view(beta))
        controller.select(0)
        tasks.runNext()

        assertEquals(4L, controller.state.selectedRevision)
        assertNull(controller.state.viewedRevision)
    }

    @Test
    fun historyReportsMissingCurrentRevisionAndRevokesDetachedCallback() {
        val current = definition(revision = 9L)
        val repository = FakeSkillSettingsRepository(catalog(current))
        repository.revisions["alpha"] = Result.success(listOf(8L, 7L))
        val tasks = DeferredSkillTaskRunner()
        val states = mutableListOf<SkillHistoryState>()
        val controller = SkillHistoryController(repository, tasks, states::add)
        controller.attach()

        controller.load("alpha", currentRevision = 9L)
        tasks.runNext()
        assertTrue(controller.state.currentRevisionMissing)
        assertTrue(controller.state.canView)

        controller.load("alpha", currentRevision = 9L)
        val publishedBeforeDetach = states.size
        controller.detach()
        controller.attach()
        tasks.runNext()

        assertEquals(publishedBeforeDetach, states.size)
        assertEquals(SkillHistoryPhase.LOADING_LIST, controller.state.phase)
    }

    @Test
    fun historyReadFailureAndClosedLaneBecomeExplicitStates() {
        val current = definition(revision = 2L)
        val repository = FakeSkillSettingsRepository(catalog(current))
        repository.revisions["alpha"] = Result.success(listOf(2L, 1L))
        repository.documents["alpha" to 1L] = Result.success(null)
        val tasks = DeferredSkillTaskRunner()
        val controller = SkillHistoryController(repository, tasks) {}
        controller.attach()
        controller.load("alpha", currentRevision = 2L)
        tasks.runNext()
        controller.select(1)

        assertEquals(SkillHistoryViewAdmission.STARTED, controller.view(current))
        tasks.runNext()
        assertEquals(SkillHistoryPhase.READ_FAILED, controller.state.phase)
        assertTrue(controller.state.failure is NoSuchElementException)

        tasks.accepting = false
        controller.load("alpha", currentRevision = 2L)
        assertEquals(SkillHistoryPhase.LIST_FAILED, controller.state.phase)
        assertTrue(controller.state.failure is IllegalStateException)
    }

    @Test
    fun historyReadFailureKeepsSelectionAvailableForRetryOrAnotherRevision() {
        val current = definition(revision = 2L, content = "current")
        val historical = current.copy(revision = 1L, content = "historical")
        val repository = FakeSkillSettingsRepository(catalog(current))
        repository.revisions["alpha"] = Result.success(listOf(2L, 1L))
        repository.documents["alpha" to 1L] =
            Result.failure(IllegalStateException("transient read failure"))
        val tasks = DeferredSkillTaskRunner()
        val controller = SkillHistoryController(repository, tasks) {}
        controller.attach()
        controller.load("alpha", currentRevision = 2L)
        tasks.runNext()
        controller.select(1)

        assertEquals(SkillHistoryViewAdmission.STARTED, controller.view(current))
        tasks.runNext()

        assertEquals(SkillHistoryPhase.READ_FAILED, controller.state.phase)
        assertEquals(listOf(2L, 1L), controller.state.revisions)
        assertEquals(1L, controller.state.selectedRevision)
        assertTrue(controller.state.canView)

        controller.select(0)
        assertEquals(SkillHistoryPhase.READY, controller.state.phase)
        assertEquals(2L, controller.state.selectedRevision)
        controller.select(1)
        repository.documents["alpha" to 1L] = Result.success(historical)

        assertEquals(SkillHistoryViewAdmission.STARTED, controller.view(current))
        assertEquals(SkillHistoryPhase.READING_REVISION, controller.state.phase)
        assertEquals(listOf(2L, 1L), controller.state.revisions)
        assertEquals(1L, controller.state.selectedRevision)
        tasks.runNext()

        assertEquals(SkillHistoryPhase.READY, controller.state.phase)
        assertEquals(historical, controller.state.viewedRevision)
    }

    @Test
    fun historyRestoreDecisionBuildsAppendOnlyUpdate() {
        val current = definition(revision = 3L, content = "current")
        val historical = current.copy(revision = 1L, content = "historical")
        val repository = FakeSkillSettingsRepository(catalog(current))
        repository.revisions["alpha"] = Result.success(listOf(3L, 1L))
        repository.documents["alpha" to 1L] = Result.success(historical)
        val tasks = DeferredSkillTaskRunner()
        val controller = SkillHistoryController(repository, tasks) {}
        controller.attach()
        controller.load("alpha", currentRevision = 3L)
        tasks.runNext()
        controller.select(1)
        controller.view(current)
        tasks.runNext()

        val decision = controller.restoreDecision(current, catalogGeneration = 11L)
        assertTrue(decision is SkillHistoryRestoreDecision.Ready)
        val ready = decision as SkillHistoryRestoreDecision.Ready
        assertEquals("alpha", ready.currentSkillId)
        assertEquals("historical", ready.mutation.content)
        assertEquals(11L, ready.mutation.expectedGeneration)
    }

    @Test
    fun createIntoOccupiedSlotRequiresExactSecondConfirmation() {
        val slot = slot()
        val incumbent = definition(id = "incumbent")
        val repository = FakeSkillSettingsRepository(
            catalog(
                incumbent,
                generation = 4L,
                bindings = listOf(AgentSkillBinding(slot, incumbent.id)),
            ),
        )
        val tasks = DeferredSkillTaskRunner()
        val updates = mutableListOf<SkillMutationUpdate>()
        val controller = SkillMutationCoordinator(repository, tasks, updates::add)
        controller.attach()
        val record = creatingRecord(id = "created", bindingSlot = slot)

        controller.save(repository.currentCatalog, record)
        assertEquals(0, repository.appliedMutations.size)
        assertEquals(0, tasks.pendingCount)
        assertTrue(updates.last().outcome is SkillMutationOutcome.ConfirmationRequired)

        controller.save(repository.currentCatalog, record)
        assertTrue(controller.state.running)
        assertEquals(1, tasks.pendingCount)
        tasks.runNext()

        assertEquals(1, repository.appliedMutations.size)
        assertEquals("created", repository.currentCatalog.definition("created")?.id)
        val applied = updates.last().outcome as SkillMutationOutcome.Applied
        assertEquals(SkillMutationOperation.SAVE, applied.operation)
        assertFalse(controller.state.running)
    }

    @Test
    fun changedReplacementIdentityRequiresFreshConfirmation() {
        val slot = slot()
        val incumbent = definition(id = "incumbent")
        val repository = FakeSkillSettingsRepository(catalog(incumbent))
        val tasks = DeferredSkillTaskRunner()
        val controller = SkillMutationCoordinator(repository, tasks) {}
        controller.attach()
        val record = creatingRecord(id = "created", bindingSlot = slot)
        val first = catalog(
            incumbent,
            generation = 3L,
            bindings = listOf(AgentSkillBinding(slot, incumbent.id)),
        )
        val second = catalog(
            incumbent,
            generation = 4L,
            bindings = listOf(AgentSkillBinding(slot, incumbent.id)),
        )

        controller.save(first, record)
        val firstConfirmation = controller.state.pendingSlotReplacement
        controller.save(second, record)

        assertEquals(0, tasks.pendingCount)
        assertEquals(4L, controller.state.pendingSlotReplacement?.generation)
        assertTrue(firstConfirmation != controller.state.pendingSlotReplacement)
    }

    @Test
    fun bindAndUnbindMutationsStaySerializedAndReportTheirOperation() {
        val slot = slot()
        val selected = definition(id = "selected")
        val incumbent = definition(id = "incumbent")
        val repository = FakeSkillSettingsRepository(
            catalog(
                selected,
                incumbent,
                generation = 6L,
                bindings = listOf(AgentSkillBinding(slot, incumbent.id)),
            ),
        )
        val tasks = DeferredSkillTaskRunner()
        val outcomes = mutableListOf<SkillMutationOutcome?>()
        val controller = SkillMutationCoordinator(repository, tasks) {
            outcomes += it.outcome
        }
        controller.attach()

        controller.bind(repository.currentCatalog, selected, slot)
        controller.bind(repository.currentCatalog, selected, slot)
        tasks.runNext()
        assertEquals(
            SkillMutationOperation.BIND,
            (outcomes.last() as SkillMutationOutcome.Applied).operation,
        )
        assertEquals(selected.id, repository.currentCatalog.binding(slot)?.skillId)

        controller.unbindSlot(repository.currentCatalog, selected, slot)
        tasks.runNext()
        assertEquals(
            SkillMutationOperation.UNBIND_SLOT,
            (outcomes.last() as SkillMutationOutcome.Applied).operation,
        )
        assertNull(repository.currentCatalog.binding(slot))
    }

    @Test
    fun documentConflictRequiresSameDraftTwice() {
        val latest = definition(revision = 2L, content = "server")
        val repository = FakeSkillSettingsRepository(catalog(latest, generation = 7L))
        val tasks = DeferredSkillTaskRunner()
        val controller = SkillMutationCoordinator(repository, tasks) {}
        controller.attach()
        val first = existingRecord(
            sourceRevision = 1L,
            latest = latest,
            content = "local one",
        )
        val edited = first.copy(draft = first.draft.copy(content = "local two"))

        controller.save(repository.currentCatalog, first)
        controller.save(repository.currentCatalog, edited)
        assertEquals(0, tasks.pendingCount)
        assertEquals("local two", controller.state.pendingDocumentConflict?.draft?.content)

        controller.save(repository.currentCatalog, edited)
        assertEquals(1, tasks.pendingCount)
        tasks.runNext()
        assertEquals("local two", repository.currentCatalog.definition("alpha")?.content)
    }

    @Test
    fun validationAndCleanUpdateNeverReachRepository() {
        val current = definition()
        val repository = FakeSkillSettingsRepository(catalog(current))
        val tasks = DeferredSkillTaskRunner()
        val outcomes = mutableListOf<SkillMutationOutcome?>()
        val controller = SkillMutationCoordinator(repository, tasks) {
            outcomes += it.outcome
        }
        controller.attach()

        controller.save(repository.currentCatalog, creatingRecord(id = "INVALID ID"))
        assertTrue(outcomes.last() is SkillMutationOutcome.ValidationFailed)
        controller.save(repository.currentCatalog, existingRecord(current, current.content))

        val blocked = outcomes.last() as SkillMutationOutcome.Blocked
        assertEquals(SkillMutationBlock.NO_DOCUMENT_CHANGES, blocked.reason)
        assertEquals(0, tasks.pendingCount)
        assertTrue(repository.appliedMutations.isEmpty())
    }

    @Test
    fun bindingAndUnbindingPreconditionsAreOwnedByCoordinator() {
        val occupied = slot()
        val other = AgentSkillSlot('b'.code, AgentSkillDirection.LEFT)
        val selected = definition(id = "selected")
        val incumbent = definition(id = "incumbent")
        val repository = FakeSkillSettingsRepository(
            catalog(
                selected,
                incumbent,
                bindings = listOf(AgentSkillBinding(occupied, incumbent.id)),
            ),
        )
        val tasks = DeferredSkillTaskRunner()
        val outcomes = mutableListOf<SkillMutationOutcome?>()
        val controller = SkillMutationCoordinator(repository, tasks) {
            outcomes += it.outcome
        }
        controller.attach()

        controller.bind(repository.currentCatalog, selected, occupied)
        assertTrue(outcomes.last() is SkillMutationOutcome.ConfirmationRequired)
        controller.unbindSlot(repository.currentCatalog, selected, occupied)
        assertEquals(
            SkillMutationBlock.SLOT_NOT_BOUND_TO_SELECTED,
            (outcomes.last() as SkillMutationOutcome.Blocked).reason,
        )
        controller.unbindSlot(repository.currentCatalog, selected, other)
        assertEquals(
            SkillMutationBlock.SLOT_NOT_BOUND_TO_SELECTED,
            (outcomes.last() as SkillMutationOutcome.Blocked).reason,
        )
        controller.unbindAll(repository.currentCatalog, selected)
        assertEquals(
            SkillMutationBlock.NO_BINDINGS,
            (outcomes.last() as SkillMutationOutcome.Blocked).reason,
        )
        assertEquals(0, tasks.pendingCount)
    }

    @Test
    fun mutationRejectsDuplicateAndRevokesDetachedCompletion() {
        val current = definition(content = "before")
        val repository = FakeSkillSettingsRepository(catalog(current))
        val tasks = DeferredSkillTaskRunner()
        val updates = mutableListOf<SkillMutationUpdate>()
        val controller = SkillMutationCoordinator(repository, tasks, updates::add)
        controller.attach()
        val edited = existingRecord(current, content = "after")

        controller.save(repository.currentCatalog, edited)
        controller.save(repository.currentCatalog, edited)
        assertEquals(1, tasks.pendingCount)
        controller.detach()
        controller.attach()
        val publishedBeforeCompletion = updates.size
        tasks.runNext()

        assertEquals(1, repository.appliedMutations.size)
        assertEquals(publishedBeforeCompletion, updates.size)
        assertFalse(controller.state.running)
    }

    @Test
    fun mutationSeparatesLaneRejectionGenerationConflictAndOrdinaryFailure() {
        val current = definition(content = "before")
        val repository = FakeSkillSettingsRepository(catalog(current))
        val tasks = DeferredSkillTaskRunner()
        val outcomes = mutableListOf<SkillMutationOutcome?>()
        val controller = SkillMutationCoordinator(repository, tasks) {
            outcomes += it.outcome
        }
        controller.attach()
        val edited = existingRecord(current, content = "after")

        tasks.accepting = false
        controller.save(repository.currentCatalog, edited)
        assertEquals(
            SkillMutationBlock.TASK_REJECTED,
            (outcomes.last() as SkillMutationOutcome.Blocked).reason,
        )
        assertFalse(controller.state.running)

        tasks.accepting = true
        repository.applyFailure = IllegalStateException("Skill catalog changed: stale")
        controller.save(repository.currentCatalog, edited)
        tasks.runNext()
        assertTrue(outcomes.last() is SkillMutationOutcome.GenerationConflict)

        repository.applyFailure = IllegalStateException("disk full")
        controller.save(repository.currentCatalog, edited)
        tasks.runNext()
        assertTrue(outcomes.last() is SkillMutationOutcome.Failed)
    }

    @Test
    fun restoreCarriesDirtyDraftRetentionContext() {
        val current = definition(revision = 2L, content = "current")
        val repository = FakeSkillSettingsRepository(catalog(current, generation = 5L))
        val tasks = DeferredSkillTaskRunner()
        val outcomes = mutableListOf<SkillMutationOutcome?>()
        val controller = SkillMutationCoordinator(repository, tasks) {
            outcomes += it.outcome
        }
        controller.attach()

        controller.restore(
            mutation = AgentSkillMutation.Update(
                id = current.id,
                content = "historical",
                expectedGeneration = 5L,
            ),
            currentSkillId = current.id,
            retainDirtyDraft = true,
        )
        tasks.runNext()

        val applied = outcomes.last() as SkillMutationOutcome.Applied
        assertEquals(SkillMutationOperation.RESTORE, applied.operation)
        assertTrue(applied.retainDirtyDraft)
        assertEquals("alpha", applied.skillId)
    }

    private class DeferredSkillTaskRunner : SettingsTaskRunner {
        private val pending = ArrayDeque<() -> Unit>()
        var accepting = true
        val pendingCount: Int
            get() = pending.size

        override fun <T> refresh(
            channel: String,
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean = enqueue(operation, deliver)

        override fun <T> execute(
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean = enqueue(operation, deliver)

        override fun close() {
            accepting = false
        }

        fun runNext() {
            pending.removeFirst().invoke()
        }

        private fun <T> enqueue(
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean {
            if (!accepting) return false
            pending += { deliver(runCatching(operation)) }
            return true
        }
    }

    private class FakeSkillSettingsRepository(
        initialCatalog: AgentSkillCatalog,
    ) : SkillSettingsRepository {
        var currentCatalog = initialCatalog
        var applyFailure: Throwable? = null
        val appliedMutations = mutableListOf<AgentSkillMutation>()
        val revisions = mutableMapOf<String, Result<List<Long>>>()
        val documents =
            mutableMapOf<Pair<String, Long>, Result<AgentSkillDefinition?>>()

        override fun loadCatalog(): Result<AgentSkillCatalog> =
            Result.success(currentCatalog)

        override fun apply(mutation: AgentSkillMutation): Result<AgentSkillCatalog> {
            appliedMutations += mutation
            applyFailure?.let { return Result.failure(it) }
            currentCatalog = AgentSkillCatalogReducer.apply(currentCatalog, mutation).catalog
            return Result.success(currentCatalog)
        }

        override fun listRevisions(skillId: String): Result<List<Long>> =
            revisions[skillId] ?: Result.success(emptyList())

        override fun readRevision(
            skillId: String,
            revision: Long,
        ): Result<AgentSkillDefinition?> =
            documents[skillId to revision] ?: Result.success(null)
    }

    private fun definition(
        id: String = "alpha",
        revision: Long = 1L,
        content: String = "content",
    ): AgentSkillDefinition =
        AgentSkillDefinition(
            id = id,
            revision = revision,
            name = "$id name",
            description = "$id description",
            content = content,
            baseIntent = EditorIntent.REWRITE,
        )

    private fun catalog(
        vararg definitions: AgentSkillDefinition,
        generation: Long = 1L,
        bindings: List<AgentSkillBinding> = emptyList(),
    ): AgentSkillCatalog =
        AgentSkillCatalog(
            generation = generation,
            definitions = definitions.toList(),
            bindings = bindings,
            active = null,
        )

    private fun slot(): AgentSkillSlot =
        AgentSkillSlot('a'.code, AgentSkillDirection.UP)

    private fun creatingRecord(
        id: String,
        bindingSlot: AgentSkillSlot? = null,
    ): SkillEditorDraftRecord {
        val empty = SkillSettingsDraft(
            id = "",
            name = "",
            description = "",
            content = "",
            baseIntent = EditorIntent.REWRITE,
            bindingSlot = null,
        )
        val draft = SkillSettingsDraft(
            id = id,
            name = "New skill",
            description = "New description",
            content = "New instructions",
            baseIntent = EditorIntent.REWRITE,
            bindingSlot = bindingSlot,
        )
        return SkillEditorDraftRecord(
            key = SkillDraftSessionState.NEW_DRAFT_KEY,
            sourceSkillId = null,
            sourceRevision = null,
            base = empty,
            draft = draft,
        )
    }

    private fun existingRecord(
        latest: AgentSkillDefinition,
        content: String,
    ): SkillEditorDraftRecord =
        existingRecord(
            sourceRevision = latest.revision,
            latest = latest,
            content = content,
        )

    private fun existingRecord(
        sourceRevision: Long,
        latest: AgentSkillDefinition,
        content: String,
    ): SkillEditorDraftRecord {
        val base = SkillSettingsDraft(
            id = latest.id,
            name = latest.name,
            description = latest.description,
            content = latest.content,
            baseIntent = latest.baseIntent,
            bindingSlot = null,
        )
        return SkillEditorDraftRecord(
            key = latest.id,
            sourceSkillId = latest.id,
            sourceRevision = sourceRevision,
            base = base.copy(content = if (sourceRevision == latest.revision) latest.content else "base"),
            draft = base.copy(content = content),
        )
    }
}
