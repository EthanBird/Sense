package io.github.ethanbird.senseime

import android.app.Activity
import android.view.View
import android.widget.TextView
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot

/**
 * Owns the complete Skills settings vertical slice: editor state, persistence, mutation ordering,
 * slot replacement, revision history, and the detachable Android View tree.
 */
internal class SkillsSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    bundledState: ByteArray?,
) : AutoCloseable {
    private val viewFactory = SkillsSettingsViewFactory(activity, views)
    private val skillDraftController = SkillDraftLifecycleController(
        activity = activity,
        bundledState = bundledState,
        onRecoveryStatusChanged = { error, notice ->
            skillBinding?.let { binding ->
                when {
                    error != null -> {
                        binding.status.text = activity.getString(
                            R.string.skills_draft_recovery_degraded,
                            error,
                        )
                        binding.status.setTextColor(
                            activity.getColor(android.R.color.holo_red_dark),
                        )
                    }
                    notice != null -> {
                        binding.status.setText(R.string.skills_draft_unreadable_preserved)
                        binding.status.setTextColor(activity.getColor(R.color.sense_accent))
                    }
                }
            }
        },
    )
    private val skillRepository: SkillSettingsRepository =
        RuntimeSkillSettingsRepository(activity.applicationContext)
    private val agentSkillIoSession: SkillSettingsIoSession
        get() = skillDraftController.ioSession
    private val historyController by lazy {
        SkillHistoryController(
            repository = skillRepository,
            tasks = agentSkillIoSession,
            render = ::renderHistoryState,
        )
    }
    private val mutationController by lazy {
        SkillMutationCoordinator(
            repository = skillRepository,
            tasks = agentSkillIoSession,
            render = ::renderMutationUpdate,
        )
    }
    private var agentSkillCatalog: AgentSkillCatalog? = null
    private var agentSkillDraftSession: SkillDraftSessionState
        get() = skillDraftController.session
        set(value) {
            skillDraftController.session = value
        }
    private var agentSkillViewGeneration = 0L
    private var agentSkillCatalogLoadInFlight = false
    private var agentSkillCatalogLoadViewGeneration = -1L
    private var creatingAgentSkill = false
    private var applyingAgentSkillUi = false
    private var agentSkillEditorAttached = false
    private var agentSkillEditorHydrated = false
    private val draftCapture = SkillDraftCaptureCoordinator()
    private val agentSkillDraftRecoveryError: String?
        get() = skillDraftController.recoveryError
    private val agentSkillDraftRecoveryNotice: String?
        get() = skillDraftController.recoveryNotice?.let {
            activity.getString(R.string.skills_draft_unreadable_preserved)
        }
    private var agentSkillDraftRecoveryWriteAuthorized: Boolean
        get() = true
        set(value) {
            if (value) skillDraftController.authorizeWrites()
        }
    private var skillBinding: SkillSettingsViewBinding? = null
    private val agentSkillStatus: TextView
        get() = requireSkillBinding().status

    private fun requireSkillBinding(): SkillSettingsViewBinding =
        requireNotNull(skillBinding) { "Skills view is detached" }


    val isAttached: Boolean
        get() = skillBinding != null

    fun createView(): View {
        check(!isAttached) { "Skills view is already attached" }
        agentSkillViewGeneration = nextGeneration(agentSkillViewGeneration)
        val binding = viewFactory.create(
            actions = SkillsSettingsViewActions(
                onSkillSelected = ::selectAgentSkill,
                onCreate = ::startCreatingAgentSkill,
                onTemplateSelected = ::applySkillTemplate,
                onDiscard = ::discardCurrentAgentSkillDraft,
                onSave = ::saveAgentSkill,
                onBind = ::bindSelectedAgentSkill,
                onUnbindSlot = ::unbindSelectedAgentSkillSlot,
                onUnbindAll = ::unbindSelectedAgentSkill,
                onViewRevision = ::viewSelectedAgentSkillRevision,
                onRestoreRevision = ::restoreSelectedAgentSkillRevision,
                onDraftEdited = ::onAgentSkillDraftEdited,
                onSlotSelectionChanged = ::onAgentSkillSlotSelectionChanged,
                onRevisionSelectionChanged = { position ->
                    historyController.select(position)
                },
            ),
            isApplyingState = { applyingAgentSkillUi },
        )
        skillBinding = binding
        skillDraftController.attach(binding.root, ::captureAgentSkillDraftFromViews)
        historyController.attach()
        mutationController.attach()
        agentSkillEditorAttached = true
        agentSkillEditorHydrated = false
        setAgentSkillEditorEnabled(false)
        loadAgentSkillsPreservingDraft()
        return binding.root
    }

    fun onResume() {
        if (isAttached) loadAgentSkillsPreservingDraft()
    }

    fun onStop() {
        // Opening Home, Provider, Tools, Voice, or About must never join the Skills fsync lane.
        // The bounded durability barrier is reserved for a currently attached editor.
        if (!isAttached) return
        captureAgentSkillDraftFromViews()
        if (skillDraftController.hasPendingDurabilityWork()) {
            skillDraftController.flushForLifecycle()
        }
    }

    fun snapshotForSavedState(): ByteArray? {
        captureAgentSkillDraftFromViews()
        return skillDraftController.snapshotForSavedState()
    }

    fun detach(persistDraft: Boolean = true) {
        if (!isAttached) return
        captureAgentSkillDraftFromViews()
        if (persistDraft) skillDraftController.persist()
        skillDraftController.detach()
        historyController.detach()
        mutationController.detach()
        skillBinding = null
        agentSkillEditorAttached = false
        agentSkillEditorHydrated = false
        agentSkillViewGeneration = nextGeneration(agentSkillViewGeneration)
    }

    override fun close() {
        detach(persistDraft = false)
        skillDraftController.close()
    }

    private fun selectAgentSkill(position: Int) {
        val definition = agentSkillCatalog?.definitions?.getOrNull(position) ?: return
        captureAgentSkillDraftFromViews()
        mutationController.clearConfirmations()
        agentSkillDraftSession = agentSkillDraftSession.selectExisting(
            definition,
            agentSkillCatalog?.bindings
                ?.firstOrNull { it.skillId == definition.id }
                ?.slot,
        )
        renderAgentSkillDraft(requireNotNull(agentSkillDraftSession.current()))
        persistAgentSkillDraftSession()
    }

    private fun loadAgentSkillsPreservingDraft(
        afterLoadMessage: Int? = null,
    ) {
        if (skillBinding == null) return
        val viewGeneration = agentSkillViewGeneration
        if (
            agentSkillCatalogLoadInFlight &&
            agentSkillCatalogLoadViewGeneration == viewGeneration
        ) {
            return
        }
        captureAgentSkillDraftFromViews()
        agentSkillCatalogLoadInFlight = true
        agentSkillCatalogLoadViewGeneration = viewGeneration
        agentSkillStatus.setText(R.string.skills_loading_body)
        agentSkillStatus.setTextColor(activity.getColor(R.color.sense_secondary))
        setAgentSkillEditorEnabled(false)
        val accepted = agentSkillIoSession.refresh(
            channel = SKILL_IO_CATALOG_CHANNEL,
            operation = { skillRepository.loadCatalog().getOrThrow() },
        ) { result ->
            if (agentSkillCatalogLoadViewGeneration == viewGeneration) {
                agentSkillCatalogLoadInFlight = false
            }
            if (!isCurrentAgentSkillView(viewGeneration)) return@refresh
            result
                .onSuccess { catalog ->
                    applyAgentSkillCatalog(catalog)
                    val conflict = currentAgentSkillConflict()
                    when {
                        historyDegradedMessage() != null -> {
                            agentSkillStatus.text = activity.getString(
                                R.string.skills_history_degraded,
                                historyDegradedMessage().orEmpty(),
                            )
                            agentSkillStatus.setTextColor(
                                activity.getColor(android.R.color.holo_red_dark),
                            )
                        }
                        agentSkillDraftRecoveryError != null -> {
                            agentSkillStatus.text = activity.getString(
                                R.string.skills_draft_recovery_degraded,
                                agentSkillDraftRecoveryError.orEmpty(),
                            )
                            agentSkillStatus.setTextColor(
                                activity.getColor(android.R.color.holo_red_dark),
                            )
                        }
                        agentSkillDraftRecoveryNotice != null -> {
                            agentSkillStatus.text = agentSkillDraftRecoveryNotice
                            agentSkillStatus.setTextColor(activity.getColor(R.color.sense_accent))
                        }
                        afterLoadMessage != null -> {
                            agentSkillStatus.setText(afterLoadMessage)
                            agentSkillStatus.setTextColor(activity.getColor(R.color.sense_accent))
                        }
                        conflict != null -> showAgentSkillRevisionConflict(conflict)
                        else -> {
                            agentSkillStatus.text = activity.getString(
                                R.string.skills_ready,
                                catalog.definitions.size,
                                catalog.bindings.size,
                            )
                            agentSkillStatus.setTextColor(activity.getColor(R.color.sense_success))
                        }
                    }
                    setAgentSkillEditorEnabled(!mutationController.state.running)
                }
                .onFailure { error ->
                    agentSkillStatus.text = activity.getString(
                        R.string.skills_load_degraded,
                        error.message.orEmpty(),
                    )
                    agentSkillStatus.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                    setAgentSkillEditorEnabled(false)
                }
        }
        if (!accepted) {
            if (agentSkillCatalogLoadViewGeneration == viewGeneration) {
                agentSkillCatalogLoadInFlight = false
            }
            if (isCurrentAgentSkillView(viewGeneration)) {
                agentSkillStatus.text = activity.getString(
                    R.string.skills_load_degraded,
                    activity.getString(R.string.skills_not_ready),
                )
                agentSkillStatus.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                setAgentSkillEditorEnabled(false)
            }
        }
    }

    private fun applyAgentSkillCatalog(catalog: AgentSkillCatalog) {
        agentSkillCatalog = catalog
        agentSkillDraftSession = agentSkillDraftSession.reconcile(catalog)
        if (agentSkillDraftSession.current() == null) {
            agentSkillDraftSession = if (catalog.definitions.isEmpty()) {
                agentSkillDraftSession.beginCreate(emptyAgentSkillDraft())
            } else {
                val first = catalog.definitions.first()
                agentSkillDraftSession.selectExisting(
                    first,
                    catalog.bindings.firstOrNull { it.skillId == first.id }?.slot,
                )
            }
        }
        applyingAgentSkillUi = true
        try {
            val record = requireNotNull(agentSkillDraftSession.current())
            viewFactory.renderCatalogSelector(
                binding = requireSkillBinding(),
                catalog = catalog,
                selectedSkillId = record.sourceSkillId,
            )
            renderAgentSkillDraft(record)
        } finally {
            applyingAgentSkillUi = false
        }
        persistAgentSkillDraftSession()
    }

    private fun startCreatingAgentSkill() {
        captureAgentSkillDraftFromViews()
        agentSkillDraftRecoveryWriteAuthorized = true
        mutationController.clearConfirmations()
        agentSkillDraftSession = agentSkillDraftSession.beginCreate(emptyAgentSkillDraft())
        renderAgentSkillDraft(requireNotNull(agentSkillDraftSession.current()))
        agentSkillStatus.setText(R.string.skills_creating)
        agentSkillStatus.setTextColor(activity.getColor(R.color.sense_accent))
        persistAgentSkillDraftSession()
    }

    private fun applySkillTemplate(template: SkillCreationTemplate) {
        agentSkillDraftRecoveryWriteAuthorized = true
        mutationController.clearConfirmations()
        if (agentSkillDraftSession.current()?.creating != true) {
            captureAgentSkillDraftFromViews()
            agentSkillDraftSession = agentSkillDraftSession.beginCreate(emptyAgentSkillDraft())
        }
        val current = requireNotNull(agentSkillDraftSession.current())
        val draft = template.instantiate(
            occupiedIds = agentSkillCatalog?.definitions.orEmpty().mapTo(linkedSetOf()) { it.id },
            bindingSlot = current.bindingSelection.slot,
        )
        agentSkillDraftSession = agentSkillDraftSession.capture(
            draft = draft,
            bindingSelectionExplicit = current.bindingSelection.explicitlySelected,
        )
        renderAgentSkillDraft(requireNotNull(agentSkillDraftSession.current()))
        agentSkillStatus.text = activity.getString(
            R.string.skills_template_applied,
            template.label,
        )
        agentSkillStatus.setTextColor(activity.getColor(R.color.sense_success))
        persistAgentSkillDraftSession()
    }

    private fun renderAgentSkillDraft(record: SkillEditorDraftRecord) {
        creatingAgentSkill = record.creating
        applyingAgentSkillUi = true
        try {
            setAgentSkillEditorEnabled(!mutationController.state.running)
            viewFactory.applyInputCapacity(
                binding = requireSkillBinding(),
                draft = record.draft,
                onRejected = ::showAgentSkillError,
            )
            viewFactory.renderDraftFields(requireSkillBinding(), record)
            if (record.creating) {
                viewFactory.renderBindingSummary(
                    binding = requireSkillBinding(),
                    catalog = agentSkillCatalog,
                    skillId = null,
                )
                clearAgentSkillHistory()
            } else {
                viewFactory.renderBindingSummary(
                    binding = requireSkillBinding(),
                    catalog = agentSkillCatalog,
                    skillId = requireNotNull(record.sourceSkillId),
                )
                loadAgentSkillRevisionList(record.sourceSkillId)
            }
            updateAgentSkillSlotOccupancy()
            renderMutationControls(mutationController.state)
        } finally {
            applyingAgentSkillUi = false
        }
        draftCapture.reset()
        agentSkillEditorHydrated = true
    }

    private fun saveAgentSkill() {
        agentSkillDraftRecoveryWriteAuthorized = true
        captureAgentSkillDraftFromViews()
        val record = agentSkillDraftSession.current()
        if (
            record?.creating == true &&
            !SkillBindingSlotPolicy.isSelectable(record.draft.bindingSlot)
        ) {
            return showReservedAgentSkillSlot(record.draft.bindingSlot)
        }
        mutationController.save(
            catalog = agentSkillCatalog,
            record = record,
        )
    }

    private fun bindSelectedAgentSkill() {
        agentSkillDraftRecoveryWriteAuthorized = true
        captureAgentSkillDraftFromViews()
        val slot = viewFactory.selectedSlot(requireSkillBinding())
        if (!SkillBindingSlotPolicy.isSelectable(slot)) {
            return showReservedAgentSkillSlot(slot)
        }
        mutationController.bind(
            catalog = agentSkillCatalog,
            skill = selectedAgentSkill(),
            slot = slot,
        )
    }

    private fun unbindSelectedAgentSkillSlot() {
        agentSkillDraftRecoveryWriteAuthorized = true
        captureAgentSkillDraftFromViews()
        mutationController.unbindSlot(
            catalog = agentSkillCatalog,
            skill = selectedAgentSkill(),
            slot = viewFactory.selectedSlot(requireSkillBinding()),
        )
    }

    private fun unbindSelectedAgentSkill() {
        agentSkillDraftRecoveryWriteAuthorized = true
        captureAgentSkillDraftFromViews()
        mutationController.unbindAll(
            catalog = agentSkillCatalog,
            skill = selectedAgentSkill(),
        )
    }

    private fun selectedAgentSkill(): AgentSkillDefinition? {
        val sourceId = agentSkillDraftSession.current()?.sourceSkillId ?: return null
        return agentSkillCatalog?.definition(sourceId)
    }

    private fun emptyAgentSkillDraft(): SkillSettingsDraft =
        SkillCreationTemplates.BLANK.instantiate(
            occupiedIds = agentSkillCatalog?.definitions.orEmpty().mapTo(linkedSetOf()) { it.id },
        )

    private fun showReservedAgentSkillSlot(slot: AgentSkillSlot?) {
        val command = SkillBindingSlotPolicy.reservedCommand(slot) ?: return
        showAgentSkillError(
            activity.getString(
                R.string.skills_reserved_slot_error,
                SkillKeyOptions.labelOf(requireNotNull(slot).keyCode),
                viewFactory.directionLabel(slot.direction),
                command,
            ),
        )
    }

    private fun captureAgentSkillDraftFromViews(
        bindingSelectionExplicit: Boolean = false,
    ) {
        if (skillBinding == null) return
        if (
            !SkillDraftCapturePolicy.shouldCapture(
                editorAttached = agentSkillEditorAttached,
                editorHydrated = agentSkillEditorHydrated,
                applyingStateToUi = applyingAgentSkillUi,
                hasCurrentDraft = agentSkillDraftSession.current() != null,
            )
        ) {
            return
        }
        if (!draftCapture.claimCapture(force = bindingSelectionExplicit)) return
        val captured = agentSkillDraftSession.capture(
            draft = viewFactory.readDraft(requireSkillBinding()),
            bindingSelectionExplicit = bindingSelectionExplicit,
        )
        agentSkillDraftSession = captured
        mutationController.clearConfirmations()
        updateAgentSkillSlotOccupancy()
    }

    private fun onAgentSkillDraftEdited() {
        if (applyingAgentSkillUi) return
        agentSkillDraftRecoveryWriteAuthorized = true
        draftCapture.markDirty()
        scheduleAgentSkillDraftPersistence()
    }

    private fun onAgentSkillSlotSelectionChanged() {
        if (applyingAgentSkillUi) return
        agentSkillDraftRecoveryWriteAuthorized = true
        captureAgentSkillDraftFromViews(bindingSelectionExplicit = true)
        mutationController.clearConfirmations()
        updateAgentSkillSlotOccupancy()
        scheduleAgentSkillDraftPersistence()
    }

    private fun discardCurrentAgentSkillDraft() {
        val catalog = agentSkillCatalog ?: return showAgentSkillError(
            activity.getString(R.string.skills_not_ready),
        )
        agentSkillDraftRecoveryWriteAuthorized = true
        captureAgentSkillDraftFromViews()
        agentSkillDraftSession = agentSkillDraftSession.discardCurrent(catalog)
        if (agentSkillDraftSession.current() == null) {
            agentSkillDraftSession = agentSkillDraftSession.beginCreate(emptyAgentSkillDraft())
        }
        mutationController.clearConfirmations()
        applyAgentSkillCatalog(catalog)
        agentSkillStatus.setText(R.string.skills_draft_discarded)
        agentSkillStatus.setTextColor(activity.getColor(R.color.sense_secondary))
    }

    private fun currentAgentSkillConflict(): SkillEditorDraftRecord? {
        val record = agentSkillDraftSession.current() ?: return null
        val latest = record.sourceSkillId?.let { agentSkillCatalog?.definition(it) }
        return record.takeIf { it.conflictsWith(latest) }
    }

    private fun showAgentSkillRevisionConflict(record: SkillEditorDraftRecord) {
        val latestRevision = record.sourceSkillId
            ?.let { agentSkillCatalog?.definition(it)?.revision }
        agentSkillStatus.text = activity.getString(
            R.string.skills_revision_conflict,
            record.sourceRevision ?: 0L,
            latestRevision?.toString() ?: activity.getString(R.string.skills_revision_missing),
        )
        agentSkillStatus.setTextColor(activity.getColor(R.color.sense_accent))
    }

    private fun updateAgentSkillSlotOccupancy() {
        val binding = skillBinding ?: return
        val slot = viewFactory.selectedSlot(binding)
        val targetId = agentSkillDraftSession.current()?.let { record ->
            record.sourceSkillId
                ?: binding.id.text.toString().trim().takeIf { it.isNotEmpty() }
        }
        val occupancy = agentSkillCatalog?.occupancy(slot, targetId)
            ?: SkillSlotOccupancy(SkillSlotOccupancyKind.EMPTY, slot)
        viewFactory.renderSlotOccupancy(binding, occupancy)
    }

    private fun renderMutationControls(state: SkillMutationState) {
        val binding = skillBinding ?: return
        viewFactory.renderMutationControls(binding, state)
    }

    private fun renderMutationUpdate(update: SkillMutationUpdate) {
        if (skillBinding == null) return
        renderMutationControls(update.state)
        if (update.state.running) {
            setAgentSkillEditorEnabled(false)
        } else if (!agentSkillCatalogLoadInFlight) {
            setAgentSkillEditorEnabled(agentSkillCatalog != null)
        }

        when (val outcome = update.outcome) {
            null -> Unit
            is SkillMutationOutcome.ValidationFailed ->
                showAgentSkillError(outcome.message)
            is SkillMutationOutcome.Blocked ->
                renderMutationBlock(outcome.reason)
            is SkillMutationOutcome.ConfirmationRequired ->
                renderMutationConfirmation(outcome.confirmation)
            is SkillMutationOutcome.Applied ->
                renderAppliedMutation(outcome)
            is SkillMutationOutcome.GenerationConflict ->
                loadAgentSkillsPreservingDraft(
                    R.string.skills_generation_conflict_refreshed,
                )
            is SkillMutationOutcome.Failed -> {
                agentSkillStatus.text = activity.getString(
                    R.string.skills_operation_degraded,
                    outcome.error.message.orEmpty(),
                )
                agentSkillStatus.setTextColor(
                    activity.getColor(android.R.color.holo_red_dark),
                )
            }
        }
    }

    private fun renderMutationBlock(reason: SkillMutationBlock) {
        when (reason) {
            SkillMutationBlock.NO_DOCUMENT_CHANGES -> {
                agentSkillStatus.setText(R.string.skills_no_document_changes)
                agentSkillStatus.setTextColor(activity.getColor(R.color.sense_secondary))
            }
            SkillMutationBlock.NO_BINDINGS -> {
                agentSkillStatus.setText(R.string.skills_bindings_none)
                agentSkillStatus.setTextColor(activity.getColor(R.color.sense_secondary))
            }
            SkillMutationBlock.SAVE_BEFORE_BINDING ->
                showAgentSkillError(activity.getString(R.string.skills_save_before_binding))
            SkillMutationBlock.CHOOSE_SLOT ->
                showAgentSkillError(activity.getString(R.string.skills_choose_key))
            SkillMutationBlock.SLOT_NOT_BOUND_TO_SELECTED ->
                showAgentSkillError(
                    activity.getString(R.string.skills_slot_not_bound_to_selected),
                )
            SkillMutationBlock.NOT_READY,
            SkillMutationBlock.TASK_REJECTED,
            -> showAgentSkillError(activity.getString(R.string.skills_not_ready))
        }
    }

    private fun renderMutationConfirmation(confirmation: SkillMutationConfirmation) {
        when (confirmation) {
            is SkillMutationConfirmation.DocumentConflict -> {
                agentSkillStatus.text = activity.getString(
                    R.string.skills_revision_conflict,
                    confirmation.sourceRevision,
                    confirmation.latestRevision
                        .takeIf { it > 0L }
                        ?.toString()
                        ?: activity.getString(R.string.skills_revision_missing),
                )
                agentSkillStatus.setTextColor(activity.getColor(R.color.sense_accent))
            }
            is SkillMutationConfirmation.SlotReplacement -> {
                agentSkillStatus.text = activity.getString(
                    R.string.skills_replace_confirmation,
                    SkillKeyOptions.labelOf(confirmation.slot.keyCode),
                    viewFactory.directionLabel(confirmation.slot.direction),
                    confirmation.incumbentSkillName,
                )
                agentSkillStatus.setTextColor(activity.getColor(R.color.sense_accent))
            }
        }
    }

    private fun renderAppliedMutation(outcome: SkillMutationOutcome.Applied) {
        if (
            outcome.operation == SkillMutationOperation.SAVE ||
            (
                outcome.operation == SkillMutationOperation.RESTORE &&
                    !outcome.retainDirtyDraft
                )
        ) {
            agentSkillDraftSession = agentSkillDraftSession.acceptSaved(
                outcome.catalog,
                outcome.skillId,
            )
        }
        applyAgentSkillCatalog(outcome.catalog)
        agentSkillStatus.setText(
            when (outcome.operation) {
                SkillMutationOperation.SAVE -> R.string.skills_saved
                SkillMutationOperation.BIND -> R.string.skills_bound
                SkillMutationOperation.UNBIND_SLOT -> R.string.skills_unbound
                SkillMutationOperation.UNBIND_ALL -> R.string.skills_unbound_all
                SkillMutationOperation.RESTORE ->
                    if (outcome.retainDirtyDraft) {
                        R.string.skills_history_restored_draft_retained
                    } else {
                        R.string.skills_history_restored
                    }
            },
        )
        agentSkillStatus.setTextColor(activity.getColor(R.color.sense_success))
    }

    private fun loadAgentSkillRevisionList(skillId: String) {
        val currentRevision = agentSkillCatalog?.definition(skillId)?.revision ?: return
        historyController.load(skillId, currentRevision)
    }

    private fun clearAgentSkillHistory() {
        historyController.clear()
    }

    private fun viewSelectedAgentSkillRevision() {
        val skill = selectedAgentSkill() ?: return showAgentSkillError(
            activity.getString(R.string.skills_save_before_history),
        )
        when (historyController.view(skill)) {
            SkillHistoryViewAdmission.NO_SELECTION ->
                showAgentSkillError(activity.getString(R.string.skills_history_not_ready))
            SkillHistoryViewAdmission.REJECTED ->
                showAgentSkillError(activity.getString(R.string.skills_not_ready))
            SkillHistoryViewAdmission.STARTED -> Unit
        }
    }

    private fun restoreSelectedAgentSkillRevision() {
        agentSkillDraftRecoveryWriteAuthorized = true
        val catalog = agentSkillCatalog
        val current = selectedAgentSkill()
        when (val decision = historyController.restoreDecision(current, catalog?.generation)) {
            SkillHistoryRestoreDecision.ViewFirst ->
                showAgentSkillError(activity.getString(R.string.skills_history_view_first))
            SkillHistoryRestoreDecision.ChooseOlderRevision ->
                showAgentSkillError(activity.getString(R.string.skills_history_choose_old))
            SkillHistoryRestoreDecision.SameContent -> {
                agentSkillStatus.setText(R.string.skills_history_same_content)
                agentSkillStatus.setTextColor(activity.getColor(R.color.sense_secondary))
            }
            is SkillHistoryRestoreDecision.Ready -> {
                captureAgentSkillDraftFromViews()
                val hadUnsavedDraft =
                    agentSkillDraftSession.current()?.documentDirty == true
                mutationController.restore(
                    mutation = decision.mutation,
                    currentSkillId = decision.currentSkillId,
                    retainDirtyDraft = hadUnsavedDraft,
                )
            }
        }
    }

    private fun renderHistoryState(state: SkillHistoryState) {
        val binding = skillBinding ?: return
        val wasApplying = applyingAgentSkillUi
        applyingAgentSkillUi = true
        try {
            viewFactory.renderHistory(
                binding = binding,
                state = state,
                mutationRunning = mutationController.state.running,
            )
        } finally {
            applyingAgentSkillUi = wasApplying
        }
    }

    private fun historyDegradedMessage(): String? =
        viewFactory.historyDegradedMessage(historyController.state)

    private fun scheduleAgentSkillDraftPersistence() =
        skillDraftController.schedulePersistence()

    private fun persistAgentSkillDraftSession() =
        skillDraftController.persist()

    private fun isCurrentAgentSkillView(viewGeneration: Long): Boolean =
        agentSkillEditorAttached &&
            agentSkillViewGeneration == viewGeneration

    private fun setAgentSkillEditorEnabled(enabled: Boolean) {
        viewFactory.setEditorEnabled(
            binding = requireSkillBinding(),
            enabled = enabled,
            creating = creatingAgentSkill,
            history = historyController.state,
        )
    }

    private fun showAgentSkillError(error: Throwable) =
        showAgentSkillError(error.message.orEmpty())

    private fun showAgentSkillError(message: String) {
        agentSkillStatus.text = activity.getString(R.string.skills_save_failed, message)
        agentSkillStatus.setTextColor(activity.getColor(android.R.color.holo_red_dark))
    }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private companion object {
        private const val SKILL_IO_CATALOG_CHANNEL = "catalog"
    }
}
