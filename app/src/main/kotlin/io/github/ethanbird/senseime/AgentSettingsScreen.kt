package io.github.ethanbird.senseime

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import io.github.ethanbird.senseime.brain.api.ProviderAuthMode
import io.github.ethanbird.senseime.brain.runtime.AgentToolSettingsStore
import java.util.concurrent.Executor

/** Configuration overview only: opening or closing it does not start/stop an Agent run. */
internal class AgentSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    private val open: (SettingsSection) -> Unit,
) : AutoCloseable {
    private val tasks = SettingsAsyncLane(
        "Sense-AgentOverview",
        Executor { activity.runOnUiThread(it) },
    )

    fun createView(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val model = views.text(R.string.settings_reading_config, 13f, R.color.sense_secondary)
        val tools = views.text(R.string.settings_agent_tools_summary, 13f, R.color.sense_secondary)
        addView(views.destination("◎", R.string.settings_agent_model, model) {
            open(SettingsSection.PROVIDER)
        })
        addView(views.destination("⌘", R.string.settings_agent_tools, tools) {
            open(SettingsSection.TOOLS)
        }.withTop(views.dp(12)))
        listOf(
            Triple(SettingsSection.SOUL, R.string.settings_agent_memory, R.string.settings_agent_memory_summary),
            Triple(SettingsSection.CHANNELS, R.string.settings_agent_channels, R.string.settings_agent_channels_summary),
            Triple(SettingsSection.SKILLS, R.string.settings_tab_skills, R.string.settings_agent_skills_summary),
        ).forEachIndexed { index, (section, title, summary) ->
            addView(views.destination(listOf("◷", "↗", "✦")[index], title,
                views.text(summary, 13f, R.color.sense_secondary)) { open(section) }
                .withTop(views.dp(12)))
        }
        addView(views.card(R.string.settings_agent_frontend_title,
            views.text(R.string.settings_agent_frontend_body, 13f, R.color.sense_secondary))
            .withTop(views.dp(24)))

        tasks.refresh("model", { RuntimeProviderSettingsRepository(activity).load().getOrThrow() }) { result ->
            model.text = result.fold(onSuccess = { saved ->
                val profile = saved.profile
                if (profile == null) activity.getString(R.string.settings_agent_model_empty)
                else activity.getString(R.string.settings_agent_model_saved, profile.displayName, profile.model) +
                    "\n" + activity.getString(when {
                        profile.authMode == ProviderAuthMode.NONE -> R.string.settings_agent_model_no_auth
                        saved.hasCredential -> R.string.settings_agent_model_credential_saved
                        else -> R.string.settings_agent_model_credential_missing
                    })
            }, onFailure = { activity.getString(R.string.settings_agent_config_failed) })
        }
        tasks.refresh("tools", { AgentToolSettingsStore(activity.applicationContext).load().getOrThrow() }) { result ->
            tools.text = result.fold(onSuccess = { saved ->
                if (saved.masterEnabled) activity.getString(R.string.settings_agent_tools_enabled, saved.enabledToolIds().size)
                else activity.getString(R.string.settings_agent_tools_disabled)
            }, onFailure = { activity.getString(R.string.settings_agent_config_failed) })
        }
    }

    override fun close() = tasks.close()
}
