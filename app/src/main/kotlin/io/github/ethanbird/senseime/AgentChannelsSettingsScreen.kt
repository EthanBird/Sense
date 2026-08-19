package io.github.ethanbird.senseime

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import io.github.ethanbird.senseime.brain.runtime.AgentChannelConnectionPhase
import io.github.ethanbird.senseime.brain.runtime.AgentChannelConfigReloadPolicy
import io.github.ethanbird.senseime.brain.runtime.AgentChannelSettings
import io.github.ethanbird.senseime.brain.runtime.AgentChannelSettingsSnapshot
import io.github.ethanbird.senseime.brain.runtime.AgentChannelSettingsStore
import io.github.ethanbird.senseime.brain.runtime.AgentChannelType
import io.github.ethanbird.senseime.brain.runtime.FeishuDomain
import io.github.ethanbird.senseime.brain.runtime.SenseAgentChannelObserver
import io.github.ethanbird.senseime.brain.runtime.SenseAgentChannelPhase
import io.github.ethanbird.senseime.brain.runtime.SenseAgentChannelRuntime
import io.github.ethanbird.senseime.brain.runtime.SenseAgentChannelStatus
import java.util.concurrent.Executors

internal class AgentChannelsSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
) : AutoCloseable {
    private val store = AgentChannelSettingsStore(activity.applicationContext)
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Sense-AgentChannelSettings").apply { isDaemon = true }
    }
    private var binding: Binding? = null
    private var snapshot: AgentChannelSettingsSnapshot? = null
    private var statusSubscription: AutoCloseable? = null
    private var closed = false
    private val settingsReload = AgentChannelSettingsReloadCoordinator()

    fun createView(): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(views.text(R.string.agent_channels_body, 13f, R.color.sense_secondary))

        val telegramEnabled = views.switch(R.string.agent_channel_telegram_enable).apply {
            typeface = Typeface.DEFAULT_BOLD
        }
        val telegramToken = edit(R.string.agent_channel_telegram_token, secret = true)
        val telegramPairing = views.text("", 14f, R.color.sense_primary, Typeface.BOLD)
        val telegramBinding = views.text("", 12f, R.color.sense_secondary)
        val telegramReset = views.secondaryButton(R.string.agent_channel_reset_pairing) {
            resetBinding(AgentChannelType.TELEGRAM)
        }
        root.addView(
            views.card(
                R.string.agent_channel_telegram_title,
                vertical(telegramEnabled, telegramToken, telegramPairing, telegramBinding, telegramReset),
            ).withTop(views.dp(16)),
        )

        val feishuEnabled = views.switch(R.string.agent_channel_feishu_enable).apply {
            typeface = Typeface.DEFAULT_BOLD
        }
        val feishuAppId = edit(R.string.agent_channel_feishu_app_id)
        val feishuSecret = edit(R.string.agent_channel_feishu_app_secret, secret = true)
        val feishuDomain = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    activity.getString(R.string.agent_channel_feishu_domain_cn),
                    activity.getString(R.string.agent_channel_feishu_domain_global),
                ),
            )
        }
        val feishuHint = views.text(R.string.agent_channel_feishu_hint, 12f, R.color.sense_secondary)
        val feishuPairing = views.text("", 14f, R.color.sense_primary, Typeface.BOLD)
        val feishuBinding = views.text("", 12f, R.color.sense_secondary)
        val feishuReset = views.secondaryButton(R.string.agent_channel_reset_pairing) {
            resetBinding(AgentChannelType.FEISHU)
        }
        root.addView(
            views.card(
                R.string.agent_channel_feishu_title,
                vertical(
                    feishuEnabled,
                    feishuAppId,
                    feishuSecret,
                    feishuDomain,
                    feishuHint,
                    feishuPairing,
                    feishuBinding,
                    feishuReset,
                ),
            ).withTop(views.dp(12)),
        )

        val save = views.primaryButton(R.string.agent_channels_save, ::save)
        val pauseResume = views.secondaryButton(R.string.agent_channels_pause, ::togglePaused)
        val runtimeStatus = views.text(
            R.string.agent_channels_loading,
            12f,
            R.color.sense_secondary,
        ).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(save.withTop(views.dp(16)))
        root.addView(pauseResume.withTop(views.dp(10)))
        root.addView(runtimeStatus.withTop(views.dp(10)))
        binding = Binding(
            telegramEnabled,
            telegramToken,
            telegramPairing,
            telegramBinding,
            telegramReset,
            feishuEnabled,
            feishuAppId,
            feishuSecret,
            feishuDomain,
            feishuPairing,
            feishuBinding,
            feishuReset,
            save,
            pauseResume,
            runtimeStatus,
        )
        setControlsEnabled(false)
        statusSubscription = SenseAgentChannelRuntime.observe(
            activity,
            SenseAgentChannelObserver { status ->
                activity.runOnUiThread {
                    renderRuntime(status)
                    reloadSettingsIfRevisionChanged(status)
                }
            },
        )
        load()
        return root
    }

    private fun vertical(vararg children: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        children.forEachIndexed { index, child ->
            addView(if (index == 0) child else child.withTop(views.dp(8)))
        }
    }

    private fun load() {
        if (closed || !settingsReload.request()) return
        io.execute {
            val result = store.load()
            activity.runOnUiThread {
                val again = settingsReload.complete()
                if (!closed) {
                    result.onSuccess(::renderSettings).onFailure(::renderFailure)
                    if (again) load()
                }
            }
        }
    }

    private fun save() {
        val current = binding ?: return
        val previous = snapshot ?: return
        setControlsEnabled(false)
        current.runtimeStatus.setText(R.string.agent_channels_saving)
        val next = previous.settings.copy(
            paused = false,
            telegram = previous.settings.telegram.copy(enabled = current.telegramEnabled.isChecked),
            feishu = previous.settings.feishu.copy(
                enabled = current.feishuEnabled.isChecked,
                appId = current.feishuAppId.text.toString(),
                domain = if (current.feishuDomain.selectedItemPosition == 1) {
                    FeishuDomain.LARK
                } else {
                    FeishuDomain.FEISHU
                },
            ),
        )
        val telegramSecret = current.telegramToken.text.toString().trim()
            .takeIf(String::isNotEmpty)?.toCharArray()
        val feishuSecret = current.feishuSecret.text.toString().trim()
            .takeIf(String::isNotEmpty)?.toCharArray()
        io.execute {
            val result = store.save(next, telegramSecret, feishuSecret)
                .mapCatching { store.load().getOrThrow() }
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                result.onSuccess { loaded ->
                    current.telegramToken.text.clear()
                    current.feishuSecret.text.clear()
                    renderSettings(loaded)
                    if (loaded.settings.anyEnabled) {
                        SenseAgentChannelRuntime.reload(activity)
                    } else {
                        SenseAgentChannelRuntime.stop(activity)
                    }
                }.onFailure(::renderFailure)
            }
        }
    }

    private fun togglePaused() {
        val previous = snapshot ?: return
        val pause = !previous.settings.paused
        setControlsEnabled(false)
        io.execute {
            val result = store.setPaused(pause).mapCatching { store.load().getOrThrow() }
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                result.onSuccess { loaded ->
                    renderSettings(loaded)
                    if (loaded.settings.shouldRun) {
                        SenseAgentChannelRuntime.start(activity)
                    } else {
                        SenseAgentChannelRuntime.pause(activity)
                    }
                }.onFailure(::renderFailure)
            }
        }
    }

    private fun resetBinding(type: AgentChannelType) {
        setControlsEnabled(false)
        io.execute {
            val result = store.resetBinding(type).mapCatching { store.load().getOrThrow() }
            activity.runOnUiThread {
                if (!closed) {
                    result.onSuccess { loaded ->
                        renderSettings(loaded)
                        when {
                            loaded.settings.shouldRun -> SenseAgentChannelRuntime.reload(activity)
                            !loaded.settings.anyEnabled -> SenseAgentChannelRuntime.stop(activity)
                            // Pairing reset must not turn a user-paused service back on just to
                            // observe the new generation. It will be read on explicit resume.
                            else -> Unit
                        }
                    }.onFailure(::renderFailure)
                }
            }
        }
    }

    private fun renderSettings(next: AgentChannelSettingsSnapshot) {
        snapshot = next
        val current = binding ?: return
        current.telegramEnabled.isChecked = next.settings.telegram.enabled
        current.telegramToken.hint = activity.getString(
            if (next.telegramCredentialStored) R.string.agent_channel_secret_saved
            else R.string.agent_channel_telegram_token,
        )
        current.feishuEnabled.isChecked = next.settings.feishu.enabled
        if (current.feishuAppId.text.toString() != next.settings.feishu.appId) {
            current.feishuAppId.setText(next.settings.feishu.appId)
        }
        current.feishuSecret.hint = activity.getString(
            if (next.feishuCredentialStored) R.string.agent_channel_secret_saved
            else R.string.agent_channel_feishu_app_secret,
        )
        current.feishuDomain.setSelection(if (next.settings.feishu.domain == FeishuDomain.LARK) 1 else 0)
        current.pauseResume.visibility = if (next.settings.anyEnabled) View.VISIBLE else View.GONE
        current.pauseResume.setText(
            if (next.settings.paused) R.string.agent_channels_resume
            else R.string.agent_channels_pause,
        )
        renderPairing(
            next.settings.telegram.pairingCode,
            next.settings.telegram.boundPeerId,
            next.settings.telegram.boundChatId,
            current.telegramPairing,
            current.telegramBinding,
        )
        renderPairing(
            next.settings.feishu.pairingCode,
            next.settings.feishu.boundPeerId,
            next.settings.feishu.boundChatId,
            current.feishuPairing,
            current.feishuBinding,
        )
        setControlsEnabled(true)
        renderRuntime(SenseAgentChannelRuntime.status())
        if (next.settings.shouldRun) SenseAgentChannelRuntime.requestStatus(activity)
    }

    private fun renderPairing(
        code: String,
        peer: String,
        chat: String,
        pairing: TextView,
        bound: TextView,
    ) {
        pairing.text = activity.getString(R.string.agent_channel_pairing_code, code)
        bound.text = if (peer.isBlank() && chat.isBlank()) {
            activity.getString(R.string.agent_channel_pairing_waiting, code)
        } else {
            activity.getString(R.string.agent_channel_pairing_bound, peer, chat)
        }
    }

    private fun renderRuntime(status: SenseAgentChannelStatus) {
        val current = binding ?: return
        val paused = snapshot?.settings?.paused == true
        current.runtimeStatus.text = if (paused) {
            activity.getString(R.string.agent_channels_runtime_paused)
        } else when (status.phase) {
            SenseAgentChannelPhase.OFF -> activity.getString(R.string.agent_channels_runtime_off)
            SenseAgentChannelPhase.STARTING -> activity.getString(R.string.agent_channels_runtime_starting)
            SenseAgentChannelPhase.DEGRADED -> activity.getString(
                R.string.agent_channels_runtime_degraded,
                status.connections.values.count { it.phase == AgentChannelConnectionPhase.CONNECTED },
                status.connections.size,
                status.detail,
            )
            SenseAgentChannelPhase.ERROR -> activity.getString(R.string.agent_channels_error, status.detail)
            SenseAgentChannelPhase.RUNNING -> activity.getString(
                R.string.agent_channels_runtime_running,
                status.connections.values.count { it.phase == AgentChannelConnectionPhase.CONNECTED },
                status.connections.size,
                status.queuedMessages,
            )
        }
        current.runtimeStatus.setTextColor(
            activity.getColor(
                if (paused) {
                    R.color.sense_secondary
                } else when (status.phase) {
                    SenseAgentChannelPhase.RUNNING -> R.color.sense_success
                    SenseAgentChannelPhase.DEGRADED -> android.R.color.holo_orange_dark
                    SenseAgentChannelPhase.ERROR -> android.R.color.holo_red_dark
                    else -> R.color.sense_secondary
                },
            ),
        )
    }

    private fun reloadSettingsIfRevisionChanged(status: SenseAgentChannelStatus) {
        if (status.configRevision <= 0L) return
        val localRevision = snapshot?.settings?.revision
        if (
            localRevision == null ||
            AgentChannelConfigReloadPolicy.shouldReload(localRevision, status.configRevision)
        ) {
            load()
        }
    }

    private fun renderFailure(failure: Throwable) {
        setControlsEnabled(true)
        binding?.runtimeStatus?.text = activity.getString(
            R.string.agent_channels_error,
            failure.message.orEmpty(),
        )
    }

    private fun edit(hintRes: Int, secret: Boolean = false): EditText =
        if (secret) {
            views.secretField(hintRes, activity.getString(hintRes))
        } else {
            views.editField(hintRes, activity.getString(hintRes))
        }

    private fun setControlsEnabled(enabled: Boolean) {
        val current = binding ?: return
        listOf<View>(
            current.telegramEnabled,
            current.telegramToken,
            current.telegramReset,
            current.feishuEnabled,
            current.feishuAppId,
            current.feishuSecret,
            current.feishuDomain,
            current.feishuReset,
            current.save,
            current.pauseResume,
        ).forEach { it.isEnabled = enabled }
    }

    override fun close() {
        closed = true
        statusSubscription?.close()
        statusSubscription = null
        io.shutdownNow()
        binding = null
    }

    private data class Binding(
        val telegramEnabled: Switch,
        val telegramToken: EditText,
        val telegramPairing: TextView,
        val telegramBinding: TextView,
        val telegramReset: Button,
        val feishuEnabled: Switch,
        val feishuAppId: EditText,
        val feishuSecret: EditText,
        val feishuDomain: Spinner,
        val feishuPairing: TextView,
        val feishuBinding: TextView,
        val feishuReset: Button,
        val save: Button,
        val pauseResume: Button,
        val runtimeStatus: TextView,
    )
}

internal class AgentChannelSettingsReloadCoordinator {
    private var loading = false
    private var pending = false

    fun request(): Boolean {
        if (loading) {
            pending = true
            return false
        }
        loading = true
        return true
    }

    /** Returns true when one revision signal arrived during the completed read. */
    fun complete(): Boolean {
        check(loading)
        loading = false
        return pending.also { pending = false }
    }
}
