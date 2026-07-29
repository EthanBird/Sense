package io.github.ethanbird.senseime

import android.app.Activity
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot

internal data class SkillsSettingsViewActions(
    val onSkillSelected: (Int) -> Unit,
    val onCreate: () -> Unit,
    val onDiscard: () -> Unit,
    val onSave: () -> Unit,
    val onBind: () -> Unit,
    val onUnbindSlot: () -> Unit,
    val onUnbindAll: () -> Unit,
    val onViewRevision: () -> Unit,
    val onRestoreRevision: () -> Unit,
    val onDraftEdited: () -> Unit,
    val onSlotSelectionChanged: () -> Unit,
    val onRevisionSelectionChanged: (Int) -> Unit,
)

/**
 * Concrete construction boundary for the complete Skills editor View tree.
 *
 * It owns labels, accessibility names, adapters, touch targets, listeners, and atomic input
 * rejection. The screen receives one immutable binding and only orchestrates state.
 */
internal class SkillsSettingsViewFactory(
    private val activity: Activity,
    private val views: SettingsViewFactory,
) {
    val intents: List<EditorIntent> =
        EditorIntent.entries.filterNot { it == EditorIntent.NO_CHANGE }

    fun create(
        actions: SkillsSettingsViewActions,
        isApplyingState: () -> Boolean,
    ): SkillSettingsViewBinding {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(views.text(R.string.skills_body, 13f, R.color.sense_secondary))

        val selector = views.accessibleSpinner(R.string.skills_select)
        root.addView(
            views.labeledField(R.string.skills_select, selector).withTop(views.dp(14)),
        )
        val createButton =
            views.secondaryButton(R.string.skills_create_new, actions.onCreate)
        root.addView(createButton.withTop(views.dp(8)))
        val discardButton =
            views.secondaryButton(R.string.skills_discard_draft, actions.onDiscard)
        root.addView(discardButton.withTop(views.dp(8)))

        val id = views.editField(R.string.skills_id, "lowercase_id").apply {
            isSaveEnabled = false
        }
        val name = views.editField(R.string.skills_name, "例如：周报").apply {
            isSaveEnabled = false
        }
        val description = views.editField(
            R.string.skills_description,
            "默认 Agent 可看到的简短能力描述",
        ).apply {
            isSaveEnabled = false
        }
        val content = views.multiLineEditField(
            R.string.skills_content,
            "# Skill\n写下完整指令、约束与工作流程",
        ).apply {
            isSaveEnabled = false
        }
        val intent = views.accessibleSpinner(R.string.skills_base_intent).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                intents.map(::intentLabel),
            )
        }
        val key = views.accessibleSpinner(R.string.skills_key).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                SkillKeyOptions.all.map(SkillKeyOption::label),
            )
        }
        val direction = views.accessibleSpinner(R.string.skills_direction).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                AgentSkillDirection.entries.map(::directionLabel),
            )
        }

        root.addView(views.labeledField(R.string.skills_id, id).withTop(views.dp(14)))
        root.addView(views.labeledField(R.string.skills_name, name).withTop(views.dp(10)))
        root.addView(
            views.labeledField(R.string.skills_description, description).withTop(views.dp(10)),
        )
        root.addView(views.labeledField(R.string.skills_content, content).withTop(views.dp(10)))
        root.addView(
            views.labeledField(R.string.skills_base_intent, intent).withTop(views.dp(10)),
        )
        root.addView(
            views.text(R.string.skills_binding_hint, 12f, R.color.sense_secondary)
                .withTop(views.dp(14)),
        )
        root.addView(views.labeledField(R.string.skills_key, key).withTop(views.dp(8)))
        root.addView(
            views.labeledField(R.string.skills_direction, direction).withTop(views.dp(10)),
        )
        val slotOccupancy =
            views.text(R.string.skills_slot_unbound, 12f, R.color.sense_secondary).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        root.addView(slotOccupancy.withTop(views.dp(10)))

        val saveButton = views.primaryButton(R.string.skills_save, actions.onSave)
        root.addView(saveButton.withTop(views.dp(14)))
        val bindButton = views.secondaryButton(R.string.skills_bind, actions.onBind)
        root.addView(bindButton.withTop(views.dp(8)))
        val unbindSlotButton =
            views.secondaryButton(R.string.skills_unbind_slot, actions.onUnbindSlot)
        root.addView(unbindSlotButton.withTop(views.dp(8)))
        val unbindAllButton =
            views.secondaryButton(R.string.skills_unbind_all, actions.onUnbindAll)
        root.addView(unbindAllButton.withTop(views.dp(8)))

        val revisionSelector = views.accessibleSpinner(R.string.skills_history_select)
        root.addView(
            views.labeledField(R.string.skills_history_select, revisionSelector)
                .withTop(views.dp(18)),
        )
        val viewRevisionButton =
            views.secondaryButton(R.string.skills_history_view, actions.onViewRevision)
        root.addView(viewRevisionButton.withTop(views.dp(8)))
        val restoreRevisionButton =
            views.secondaryButton(R.string.skills_history_restore, actions.onRestoreRevision)
        root.addView(restoreRevisionButton.withTop(views.dp(8)))
        val historyPreview =
            views.text(
                R.string.skills_history_preview_empty,
                12f,
                R.color.sense_secondary,
            ).apply {
                setTextIsSelectable(true)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        root.addView(historyPreview.withTop(views.dp(10)))

        val bindingSummary =
            views.text(R.string.skills_bindings_none, 12f, R.color.sense_secondary)
        root.addView(bindingSummary.withTop(views.dp(12)))
        val status =
            views.text(R.string.skills_loading_body, 12f, R.color.sense_secondary).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        root.addView(status.withTop(views.dp(10)))

        val binding = SkillSettingsViewBinding(
            root = root,
            selector = selector,
            id = id,
            name = name,
            description = description,
            content = content,
            intent = intent,
            key = key,
            direction = direction,
            revisionSelector = revisionSelector,
            historyPreview = historyPreview,
            restoreRevisionButton = restoreRevisionButton,
            viewRevisionButton = viewRevisionButton,
            createButton = createButton,
            discardButton = discardButton,
            saveButton = saveButton,
            bindButton = bindButton,
            unbindSlotButton = unbindSlotButton,
            unbindAllButton = unbindAllButton,
            slotOccupancy = slotOccupancy,
            bindingSummary = bindingSummary,
            status = status,
        )
        attachListeners(binding, actions, isApplyingState)
        return binding
    }

    fun applyInputCapacity(
        binding: SkillSettingsViewBinding,
        draft: SkillSettingsDraft,
        onRejected: (String) -> Unit,
    ) {
        fun EditText.rejectBeyond(
            policyLimit: Int,
            retainedLength: Int,
            fieldLabel: String,
        ) {
            val maximumAcceptedLength = SkillDraftInputCapacity.maximumAcceptedLength(
                policyLimit = policyLimit,
                retainedLength = retainedLength,
            )
            filters = arrayOf(
                InputFilter { source, start, end, destination, destinationStart, destinationEnd ->
                    val accepted = SkillDraftInputCapacity.acceptsWholeEdit(
                        maximumAcceptedLength = maximumAcceptedLength,
                        currentLength = destination.length,
                        replacedLength = destinationEnd - destinationStart,
                        incomingLength = end - start,
                    )
                    if (accepted) {
                        null
                    } else {
                        binding.status.post {
                            onRejected("$fieldLabel 最多 $policyLimit 个字符；本次输入未写入")
                        }
                        destination.subSequence(destinationStart, destinationEnd)
                    }
                },
            )
        }
        binding.id.rejectBeyond(AgentSkillPolicy.MAX_ID_CHARS, draft.id.length, "Skill ID")
        binding.name.rejectBeyond(AgentSkillPolicy.MAX_NAME_CHARS, draft.name.length, "名称")
        binding.description.rejectBeyond(
            AgentSkillPolicy.MAX_DESCRIPTION_CHARS,
            draft.description.length,
            "简短描述",
        )
        binding.content.rejectBeyond(
            AgentSkillPolicy.MAX_CONTENT_CHARS,
            draft.content.length,
            "Skill 文档",
        )
    }

    fun readDraft(binding: SkillSettingsViewBinding): SkillSettingsDraft =
        SkillSettingsDraft(
            id = binding.id.text.toString(),
            name = binding.name.text.toString(),
            description = binding.description.text.toString(),
            content = binding.content.text.toString(),
            baseIntent = intentAt(binding.intent.selectedItemPosition),
            bindingSlot = selectedSlot(binding),
        )

    fun selectedSlot(binding: SkillSettingsViewBinding): AgentSkillSlot? {
        val keyCode = SkillKeyOptions.all
            .getOrNull(binding.key.selectedItemPosition)
            ?.keyCode
            ?: return null
        val direction = AgentSkillDirection.entries[
            binding.direction.selectedItemPosition.coerceIn(
                0,
                AgentSkillDirection.entries.lastIndex,
            )
        ]
        return AgentSkillSlot(keyCode, direction)
    }

    fun renderCatalogSelector(
        binding: SkillSettingsViewBinding,
        catalog: AgentSkillCatalog,
        selectedSkillId: String?,
    ) {
        binding.selector.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            catalog.definitions.map { definition ->
                activity.getString(
                    R.string.skills_selector_item,
                    definition.name,
                    definition.revision,
                )
            },
        )
        selectedSkillId?.let { selectedId ->
            val index = catalog.definitions.indexOfFirst { it.id == selectedId }
            if (index >= 0) binding.selector.setSelection(index)
        }
    }

    fun renderDraftFields(
        binding: SkillSettingsViewBinding,
        record: SkillEditorDraftRecord,
    ) {
        binding.id.setText(record.draft.id)
        binding.id.isEnabled = record.creating
        binding.name.setText(record.draft.name)
        binding.description.setText(record.draft.description)
        binding.content.setText(record.draft.content)
        binding.intent.setSelection(intentIndex(record.draft.baseIntent))
        binding.key.setSelection(
            SkillKeyOptions.indexOf(record.bindingSelection.slot?.keyCode),
        )
        binding.direction.setSelection(
            record.bindingSelection.slot?.direction?.ordinal ?: 0,
        )
    }

    fun renderSlotOccupancy(
        binding: SkillSettingsViewBinding,
        occupancy: SkillSlotOccupancy,
    ) {
        val slot = occupancy.slot
        binding.slotOccupancy.text = when {
            slot == null -> activity.getString(R.string.skills_slot_not_selected)
            occupancy.kind == SkillSlotOccupancyKind.EMPTY -> activity.getString(
                R.string.skills_slot_empty,
                SkillKeyOptions.labelOf(slot.keyCode),
                directionLabel(slot.direction),
            )
            occupancy.kind == SkillSlotOccupancyKind.CURRENT_SKILL -> activity.getString(
                R.string.skills_slot_current,
                SkillKeyOptions.labelOf(slot.keyCode),
                directionLabel(slot.direction),
                occupancy.incumbentSkillName.orEmpty(),
            )
            else -> activity.getString(
                R.string.skills_slot_occupied,
                SkillKeyOptions.labelOf(slot.keyCode),
                directionLabel(slot.direction),
                occupancy.incumbentSkillName.orEmpty(),
            )
        }
        binding.slotOccupancy.setTextColor(
            activity.getColor(
                if (occupancy.requiresReplacement) {
                    R.color.sense_accent
                } else {
                    R.color.sense_secondary
                },
            ),
        )
    }

    fun renderBindingSummary(
        binding: SkillSettingsViewBinding,
        catalog: AgentSkillCatalog?,
        skillId: String?,
    ) {
        if (skillId == null) {
            binding.bindingSummary.setText(R.string.skills_bindings_new)
            return
        }
        val bindings = catalog?.bindings.orEmpty().filter { it.skillId == skillId }
        binding.bindingSummary.text = if (bindings.isEmpty()) {
            activity.getString(R.string.skills_bindings_none)
        } else {
            bindings.joinToString(
                prefix = activity.getString(R.string.skills_bindings_prefix),
                separator = "\n",
            ) {
                "${SkillKeyOptions.labelOf(it.slot.keyCode)} \u00b7 " +
                    directionLabel(it.slot.direction)
            }
        }
    }

    fun renderMutationControls(
        binding: SkillSettingsViewBinding,
        state: SkillMutationState,
    ) {
        binding.saveButton.setText(
            if (
                state.pendingDocumentConflict != null ||
                state.pendingSlotReplacement?.operation == SkillReplacementOperation.CREATE
            ) {
                R.string.skills_save_confirm
            } else {
                R.string.skills_save
            },
        )
        binding.bindButton.setText(
            if (state.pendingSlotReplacement?.operation == SkillReplacementOperation.BIND) {
                R.string.skills_bind_confirm
            } else {
                R.string.skills_bind
            },
        )
    }

    fun renderHistory(
        binding: SkillSettingsViewBinding,
        state: SkillHistoryState,
        mutationRunning: Boolean,
    ) {
        when (state.phase) {
            SkillHistoryPhase.EMPTY -> {
                binding.revisionSelector.adapter = historyAdapter(emptyList())
                binding.historyPreview.setText(R.string.skills_history_preview_new)
                binding.historyPreview.setTextColor(activity.getColor(R.color.sense_secondary))
            }
            SkillHistoryPhase.LOADING_LIST -> {
                binding.historyPreview.setText(R.string.skills_history_preview_empty)
                binding.historyPreview.setTextColor(activity.getColor(R.color.sense_secondary))
            }
            SkillHistoryPhase.READY,
            SkillHistoryPhase.READING_REVISION,
            SkillHistoryPhase.READ_FAILED,
            -> {
                binding.revisionSelector.adapter = historyAdapter(
                    state.revisions.map { revision ->
                        activity.getString(
                            if (revision == state.currentRevision) {
                                R.string.skills_history_item_current
                            } else {
                                R.string.skills_history_item
                            },
                            revision,
                        )
                    },
                )
                val selectedIndex = state.revisions.indexOf(state.selectedRevision)
                if (selectedIndex >= 0) binding.revisionSelector.setSelection(selectedIndex)
                val viewed = state.viewedRevision
                if (state.phase == SkillHistoryPhase.READ_FAILED) {
                    binding.historyPreview.text = activity.getString(
                        R.string.skills_history_degraded,
                        state.failure?.message.orEmpty(),
                    )
                    binding.historyPreview.setTextColor(
                        activity.getColor(android.R.color.holo_red_dark),
                    )
                } else if (viewed == null) {
                    binding.historyPreview.setText(R.string.skills_history_preview_empty)
                    binding.historyPreview.setTextColor(activity.getColor(R.color.sense_secondary))
                } else {
                    binding.historyPreview.text = activity.getString(
                        R.string.skills_history_preview,
                        viewed.revision,
                        viewed.name,
                        viewed.description,
                        intentLabel(viewed.baseIntent),
                        viewed.content,
                    )
                    binding.historyPreview.setTextColor(activity.getColor(R.color.sense_primary))
                }
                if (state.currentRevisionMissing) {
                    binding.status.text = activity.getString(
                        R.string.skills_history_degraded,
                        historyDegradedMessage(state).orEmpty(),
                    )
                    binding.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                }
            }
            SkillHistoryPhase.LIST_FAILED -> {
                binding.revisionSelector.adapter = historyAdapter(emptyList())
                binding.status.text = activity.getString(
                    R.string.skills_history_degraded,
                    state.failure?.message.orEmpty(),
                )
                binding.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                binding.historyPreview.text = binding.status.text
                binding.historyPreview.setTextColor(
                    activity.getColor(android.R.color.holo_red_dark),
                )
            }
        }
        binding.viewRevisionButton.isEnabled = state.canView && !mutationRunning
        binding.restoreRevisionButton.isEnabled = state.canRestore && !mutationRunning
    }

    fun historyDegradedMessage(state: SkillHistoryState): String? = when {
        state.currentRevisionMissing -> activity.getString(
            R.string.skills_history_current_missing,
            state.currentRevision ?: 0L,
        )
        else -> state.listFailureMessage
    }

    fun setEditorEnabled(
        binding: SkillSettingsViewBinding,
        enabled: Boolean,
        creating: Boolean,
        history: SkillHistoryState,
    ) {
        binding.editorControls.forEach { it.isEnabled = enabled }
        binding.id.isEnabled = enabled && creating
        binding.saveButton.isEnabled = enabled
        binding.bindButton.isEnabled = enabled
        binding.viewRevisionButton.isEnabled = enabled && history.canView
        binding.restoreRevisionButton.isEnabled = enabled && history.canRestore
    }

    private fun historyAdapter(labels: List<String>): ArrayAdapter<String> =
        ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )

    fun intentAt(position: Int): EditorIntent =
        intents[position.coerceIn(0, intents.lastIndex)]

    fun intentIndex(intent: EditorIntent): Int =
        intents.indexOf(intent).coerceAtLeast(0)

    fun directionLabel(direction: AgentSkillDirection): String = when (direction) {
        AgentSkillDirection.UP -> "上滑"
        AgentSkillDirection.RIGHT -> "右滑"
        AgentSkillDirection.DOWN -> "下滑"
        AgentSkillDirection.LEFT -> "左滑"
    }

    private fun attachListeners(
        binding: SkillSettingsViewBinding,
        actions: SkillsSettingsViewActions,
        isApplyingState: () -> Boolean,
    ) {
        binding.selector.onItemSelectedListener =
            selectionListener(isApplyingState) { position ->
                actions.onSkillSelected(position)
            }
        val documentWatcher = object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (!isApplyingState()) actions.onDraftEdited()
            }
        }
        binding.editableFields.forEach { it.addTextChangedListener(documentWatcher) }
        binding.intent.onItemSelectedListener =
            selectionListener(isApplyingState) { actions.onDraftEdited() }
        binding.key.onItemSelectedListener =
            selectionListener(isApplyingState) { actions.onSlotSelectionChanged() }
        binding.direction.onItemSelectedListener =
            selectionListener(isApplyingState) { actions.onSlotSelectionChanged() }
        binding.revisionSelector.onItemSelectedListener =
            selectionListener(isApplyingState, actions.onRevisionSelectionChanged)
    }

    private fun selectionListener(
        isApplyingState: () -> Boolean,
        action: (Int) -> Unit,
    ): AdapterView.OnItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!isApplyingState()) action(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    fun intentLabel(intent: EditorIntent): String = when (intent) {
        EditorIntent.SMART_EDIT -> "智能编辑"
        EditorIntent.ANSWER -> "回答"
        EditorIntent.REWRITE -> "改写"
        EditorIntent.CONTINUE -> "续写"
        EditorIntent.TRANSLATE -> "翻译"
        EditorIntent.FORMAT -> "整理格式"
        EditorIntent.NO_CHANGE -> "不修改"
    }
}
