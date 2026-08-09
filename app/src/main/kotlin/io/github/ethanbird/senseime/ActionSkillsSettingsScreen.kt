package io.github.ethanbird.senseime

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.ethanbird.senseime.brain.runtime.ActionSkillSettingsStore
import io.github.ethanbird.senseime.brain.runtime.AndroidActionCredentialVault
import io.github.ethanbird.senseime.brain.runtime.DirectActionSkillRuntime
import java.util.concurrent.Executor

internal class ActionSkillsSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    private val store: ActionSkillSettingsStore = ActionSkillSettingsStore(activity.applicationContext),
    private val tasks: SettingsTaskRunner = SettingsAsyncLane(
        threadName = "Sense-ActionSkillSettings",
        uiExecutor = Executor { activity.runOnUiThread(it) },
    ),
) : AutoCloseable {
    private val descriptors = DirectActionSkillRuntime.builtIns(
        credentialVault = AndroidActionCredentialVault(activity.applicationContext),
    ).descriptors()
    private var status: TextView? = null
    private var closed = false

    fun createView(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(views.text(R.string.action_skills_body, 13f, R.color.sense_secondary))
        status = views.text(R.string.action_skills_loading, 12f, R.color.sense_secondary).also {
            addView(it.withTop(views.dp(10)))
        }
        tasks.refresh("action-skills-load", { store.load().getOrThrow() }) { result ->
            if (closed) return@refresh
            result.onSuccess { saved ->
                status?.setText(R.string.action_skills_ready)
                descriptors.forEach { descriptor ->
                    addView(skillCard(descriptor.displayName, descriptor.description, descriptor.id, saved[descriptor.id] ?: true).withTop(views.dp(12)))
                }
            }.onFailure { status?.setText(R.string.action_skills_failed) }
        }
    }

    private fun skillCard(name: String, description: String, id: String, enabled: Boolean): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(views.dp(16), views.dp(14), views.dp(16), views.dp(14))
            addView(views.text(name, 16f, R.color.sense_primary, Typeface.BOLD))
            addView(views.text("0 Token · 直连 API", 12f, R.color.sense_accent, Typeface.BOLD).withTop(views.dp(4)))
            addView(views.text(description, 13f, R.color.sense_secondary).withTop(views.dp(6)))
            addView(views.text("稳定 ID：$id", 11f, R.color.sense_secondary).withTop(views.dp(6)))
            addView(views.switch(R.string.action_skill_enabled, enabled).apply {
                setOnCheckedChangeListener { button, checked ->
                    button.isEnabled = false
                    tasks.execute({ store.setEnabled(id, checked).getOrThrow() }) { result ->
                        if (closed) return@execute
                        button.isEnabled = true
                        if (result.isSuccess) status?.setText(R.string.action_skills_saved)
                        else { button.setOnCheckedChangeListener(null); button.isChecked = !checked; status?.setText(R.string.action_skills_failed) }
                    }
                }
            }.withTop(views.dp(8)))
        }

    override fun close() {
        closed = true
        tasks.close()
        status = null
    }
}
