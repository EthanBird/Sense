package io.github.ethanbird.senseime

import android.app.Activity
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection

/** The library never reads revision bodies or mutates drafts. Selection is always by stable id. */
internal class SkillsLibraryView(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    createButton: View,
    private val onSelect: (String) -> Unit,
    private val onResumeDraft: (String) -> Unit,
    openActions: () -> Unit,
) : LinearLayout(activity) {
    private var catalog: AgentSkillCatalog? = null
    private var session = SkillDraftSessionState()
    private var filter = 0
    private var limit = PAGE_SIZE
    private var query = ""
    private var actionsEnabled = false
    private val filters = mutableListOf<TextView>()
    private val results = LinearLayout(activity).apply { orientation = VERTICAL }
    private val count = views.text(R.string.skills_loading_body, 12f, R.color.sense_secondary)

    init {
        orientation = VERTICAL
        addView(createButton)
        addView(views.destination("↗", R.string.skills_library_api,
            views.text(R.string.skills_library_api_summary, 12f, R.color.sense_secondary), openActions)
            .withTop(views.dp(12)))
        addView(views.editField(R.string.skills_library_search,
            activity.getString(R.string.skills_library_search)).apply {
            isSaveEnabled = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim()
                    limit = PAGE_SIZE
                    renderResults()
                }
            })
        }.withTop(views.dp(20)))
        addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            listOf(R.string.skills_library_all, R.string.skills_library_mine, R.string.skills_library_bound)
                .forEachIndexed { index, label ->
                    val chip = views.text(label, 13f, R.color.sense_primary, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                        minimumHeight = views.dp(48)
                        isFocusable = true
                        foreground = views.selectableItemBackground()
                        setOnClickListener {
                            filter = index
                            limit = PAGE_SIZE
                            renderResults()
                        }
                    }
                    filters += chip
                    addView(chip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
        }.withTop(views.dp(8)))
        addView(count.withTop(views.dp(14)))
        addView(results)
    }

    fun render(catalog: AgentSkillCatalog, session: SkillDraftSessionState) {
        this.catalog = catalog
        this.session = session
        renderResults()
    }

    fun setActionsEnabled(enabled: Boolean) {
        actionsEnabled = enabled
        for (index in 0 until results.childCount) results.getChildAt(index).isEnabled = enabled
    }

    private fun renderResults() {
        filters.forEachIndexed { index, chip ->
            chip.isSelected = index == filter
            chip.setTextColor(activity.getColor(if (index == filter) R.color.sense_accent else R.color.sense_secondary))
            chip.background = views.rounded(activity.getColor(
                if (index == filter) R.color.sense_surface else R.color.sense_background), views.dp(14).toFloat())
        }
        val saved = catalog ?: return
        val bindings = saved.bindings.groupBy { it.skillId }
        val matches = saved.definitions.filter { skill ->
            (filter != 1 || !skill.builtIn) && (filter != 2 || !bindings[skill.id].isNullOrEmpty()) &&
                (query.isEmpty() || skill.name.contains(query, ignoreCase = true) ||
                    skill.description.contains(query, ignoreCase = true))
        }
        count.text = activity.getString(R.string.skills_library_count, matches.size)
        results.removeAllViews()
        // Retain access to an unsaved creation or a draft whose saved definition was removed.
        session.records.values.filter { it.dirty && (it.creating || saved.definition(it.sourceSkillId.orEmpty()) == null) }
            .take(PAGE_SIZE).forEach { draft ->
                addResult(draft.draft.name.ifBlank { activity.getString(R.string.skills_editor_new) },
                    activity.getString(R.string.skills_library_resume),
                    activity.getString(R.string.skills_library_draft_label)) { onResumeDraft(draft.key) }
            }
        if (matches.isEmpty()) {
            results.addView(views.text(
                if (saved.definitions.isEmpty()) R.string.skills_library_empty else R.string.skills_library_no_match,
                14f, R.color.sense_secondary).withTop(views.dp(24)))
        }
        matches.take(limit).forEach { skill ->
            val badges = buildList {
                add(activity.getString(if (skill.builtIn) R.string.skills_library_builtin else R.string.skills_library_custom))
                if (session.records[skill.id]?.dirty == true) add(activity.getString(R.string.skills_library_draft_label))
                val slots = bindings[skill.id].orEmpty()
                add(if (slots.isEmpty()) activity.getString(R.string.skills_library_unbound) else slots.joinToString(" · ") {
                    SkillKeyOptions.labelOf(it.slot.keyCode) + when (it.slot.direction) {
                        AgentSkillDirection.UP -> " ↑"
                        AgentSkillDirection.RIGHT -> " →"
                        AgentSkillDirection.DOWN -> " ↓"
                        AgentSkillDirection.LEFT -> " ←"
                    }
                })
            }.joinToString(" · ")
            addResult(skill.name, skill.description, badges) { onSelect(skill.id) }
        }
        if (matches.size > limit) {
            results.addView(views.secondaryButton(R.string.skills_library_more) {
                limit += PAGE_SIZE
                renderResults()
            }.withTop(views.dp(12)))
        }
    }

    private fun addResult(name: String, description: String, badges: String, action: () -> Unit) {
        results.addView(LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(views.dp(18), views.dp(17), views.dp(18), views.dp(17))
            background = views.rounded(activity.getColor(R.color.sense_surface), views.dp(18).toFloat())
            foreground = views.selectableItemBackground()
            isFocusable = true
            isEnabled = actionsEnabled
            addView(views.text(name, 17f, R.color.sense_primary, Typeface.BOLD))
            addView(views.text(description, 13f, R.color.sense_secondary).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }.withTop(views.dp(6)))
            addView(views.text(badges, 11f, R.color.sense_accent).withTop(views.dp(12)))
            setOnClickListener { if (actionsEnabled) action() }
        }.withTop(views.dp(10)))
    }

    private companion object { const val PAGE_SIZE = 24 }
}
