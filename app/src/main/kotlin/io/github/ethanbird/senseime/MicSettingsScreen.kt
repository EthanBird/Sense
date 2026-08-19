package io.github.ethanbird.senseime

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import io.github.ethanbird.senseime.mic.SenseMicCaptureProfile
import io.github.ethanbird.senseime.mic.SenseMicPhase
import io.github.ethanbird.senseime.mic.SenseMicQuality
import io.github.ethanbird.senseime.mic.SenseMicRuntime
import io.github.ethanbird.senseime.mic.SenseMicSettings
import io.github.ethanbird.senseime.mic.SenseMicSettingsStore
import io.github.ethanbird.senseime.mic.SenseMicStatus
import java.util.concurrent.Executor

internal interface MicSettingsRepository {
    fun load(): Result<SenseMicSettings>
    fun save(settings: SenseMicSettings): Result<SenseMicSettings>
}

internal class RuntimeMicSettingsRepository(activity: Activity) : MicSettingsRepository {
    private val store by lazy { SenseMicSettingsStore(activity.applicationContext) }

    override fun load(): Result<SenseMicSettings> = runCatching(store::load)

    override fun save(settings: SenseMicSettings): Result<SenseMicSettings> = runCatching {
        store.save(settings)
        settings
    }
}

internal enum class MicSettingsRuntimeAction {
    NONE,
    START,
    STOP,
    RELOAD,
}

internal enum class MicSettingsUiPhase {
    LOADING,
    READY,
    SAVING,
    ERROR,
}

internal data class MicSettingsUiState(
    val settings: SenseMicSettings = SenseMicSettings(),
    val phase: MicSettingsUiPhase = MicSettingsUiPhase.LOADING,
    val message: String? = null,
)

/** Serializes preference writes and only dispatches the runtime action for the latest UI state. */
internal class MicSettingsController(
    private val repository: MicSettingsRepository,
    private val tasks: SettingsTaskRunner,
    private val render: (MicSettingsUiState) -> Unit,
    private val applyRuntimeAction: (MicSettingsRuntimeAction) -> Unit,
) : AutoCloseable {
    private var generation = 0L
    private var closed = false
    private var state = MicSettingsUiState()

    fun load() {
        if (closed) return
        val request = nextGeneration()
        publish(state.copy(phase = MicSettingsUiPhase.LOADING, message = null))
        val accepted = tasks.refresh(
            channel = LOAD_CHANNEL,
            operation = { repository.load().getOrThrow() },
        ) { result ->
            if (!accepts(request)) return@refresh
            result.fold(
                onSuccess = { publish(MicSettingsUiState(it, MicSettingsUiPhase.READY)) },
                onFailure = {
                    publish(
                        state.copy(
                            phase = MicSettingsUiPhase.ERROR,
                            message = it.message ?: it.javaClass.simpleName,
                        ),
                    )
                },
            )
        }
        if (!accepted && accepts(request)) {
            publish(state.copy(phase = MicSettingsUiPhase.ERROR, message = "设置任务已结束"))
        }
    }

    fun save(settings: SenseMicSettings, action: MicSettingsRuntimeAction) {
        if (closed) return
        val request = nextGeneration()
        publish(MicSettingsUiState(settings, MicSettingsUiPhase.SAVING))
        val accepted = tasks.execute(
            operation = { repository.save(settings).getOrThrow() },
        ) { result ->
            if (!accepts(request)) return@execute
            result.fold(
                onSuccess = {
                    publish(MicSettingsUiState(it, MicSettingsUiPhase.READY))
                    applyRuntimeAction(action)
                },
                onFailure = {
                    publish(
                        MicSettingsUiState(
                            settings = settings,
                            phase = MicSettingsUiPhase.ERROR,
                            message = it.message ?: it.javaClass.simpleName,
                        ),
                    )
                },
            )
        }
        if (!accepted && accepts(request)) {
            publish(
                MicSettingsUiState(
                    settings = settings,
                    phase = MicSettingsUiPhase.ERROR,
                    message = "设置任务已结束",
                ),
            )
        }
    }

    override fun close() {
        closed = true
        nextGeneration()
        tasks.close()
    }

    private fun publish(next: MicSettingsUiState) {
        state = next
        if (!closed) render(next)
    }

    private fun nextGeneration(): Long {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return generation
    }

    private fun accepts(request: Long): Boolean = !closed && generation == request

    private companion object {
        const val LOAD_CHANNEL = "sense-mic-load"
    }
}

/** Settings surface for the demand-driven foreground phone-microphone service. */
internal class MicSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    private val emitEffect: (SettingsEffect) -> Unit,
    repository: MicSettingsRepository = RuntimeMicSettingsRepository(activity),
    tasks: SettingsTaskRunner = SettingsAsyncLane(
        threadName = "Sense-MicSettings",
        uiExecutor = Executor(activity::runOnUiThread),
    ),
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            renderRuntimeStatus(SenseMicRuntime.status())
            if (!closed) mainHandler.postDelayed(this, STATUS_POLL_MILLIS)
        }
    }
    private val controller = MicSettingsController(
        repository = repository,
        tasks = tasks,
        render = ::renderSettings,
        applyRuntimeAction = ::applyRuntimeAction,
    )
    private var binding: Binding? = null
    private var currentSettings = SenseMicSettings()
    private var applyingState = false
    private var permissionRequestInFlight = false
    private var permissionDeniedOnce = false
    private var pendingEnable = false
    private var closed = false

    fun createView(): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(views.text(R.string.sense_mic_intro, 13f, R.color.sense_secondary))

        val master = views.switch(R.string.sense_mic_enable).apply {
            typeface = Typeface.DEFAULT_BOLD
            isEnabled = false
        }
        val runtimeStatus = views.text(
            R.string.sense_mic_status_loading,
            13f,
            R.color.sense_secondary,
            Typeface.BOLD,
        ).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(views.dp(12), views.dp(10), views.dp(12), views.dp(10))
            background = views.rounded(
                activity.getColor(R.color.sense_background),
                views.dp(12).toFloat(),
            )
        }
        val endpoint = views.text("", 12f, R.color.sense_secondary).apply {
            setTextIsSelectable(true)
        }
        val stats = views.text("", 12f, R.color.sense_secondary)
        val permission = views.secondaryButton(R.string.sense_mic_permission_grant) {
            requestPermission()
        }
        val mute = views.secondaryButton(R.string.sense_mic_mute) {
            SenseMicRuntime.toggleMute(activity)
        }.apply { visibility = View.GONE }

        val pairLabel = views.text(
            R.string.sense_mic_pair_code_label,
            12f,
            R.color.sense_secondary,
            Typeface.BOLD,
        )
        val pairCode = views.text("------", 34f, R.color.sense_accent, Typeface.BOLD).apply {
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.18f
            setTextIsSelectable(true)
            contentDescription = activity.getString(R.string.sense_mic_pair_code_loading)
        }
        val rotate = views.secondaryButton(R.string.sense_mic_rotate_code) {
            rotatePairCode()
        }.apply { isEnabled = false }

        val quality = views.accessibleSpinner(R.string.sense_mic_quality_label).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                SenseMicQuality.entries.map { it.label },
            )
            isEnabled = false
        }
        val unprocessed = views.switch(R.string.sense_mic_unprocessed).apply { isEnabled = false }
        val settingsStatus = views.text(
            R.string.sense_mic_status_loading,
            12f,
            R.color.sense_secondary,
        ).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }

        root.addView(master.withTop(views.dp(14)))
        root.addView(runtimeStatus.withTop(views.dp(10)))
        root.addView(endpoint.withTop(views.dp(8)))
        root.addView(stats.withTop(views.dp(4)))
        root.addView(permission.withTop(views.dp(10)))
        root.addView(mute.withTop(views.dp(8)))

        root.addView(pairLabel.withTop(views.dp(22)))
        root.addView(pairCode.withTop(views.dp(4)))
        root.addView(views.text(R.string.sense_mic_pair_code_body, 12f, R.color.sense_secondary))
        root.addView(rotate.withTop(views.dp(10)))
        root.addView(
            views.labeledField(R.string.sense_mic_quality_label, quality)
                .withTop(views.dp(20)),
        )
        root.addView(unprocessed.withTop(views.dp(10)))
        root.addView(
            views.text(R.string.sense_mic_unprocessed_body, 12f, R.color.sense_secondary)
                .withTop(views.dp(2)),
        )
        root.addView(settingsStatus.withTop(views.dp(12)))
        root.addView(
            views.text(R.string.sense_mic_client_hint, 12f, R.color.sense_secondary)
                .withTop(views.dp(20)),
        )

        binding = Binding(
            root = root,
            master = master,
            runtimeStatus = runtimeStatus,
            endpoint = endpoint,
            stats = stats,
            permission = permission,
            mute = mute,
            pairCode = pairCode,
            rotate = rotate,
            quality = quality,
            unprocessed = unprocessed,
            settingsStatus = settingsStatus,
        )
        attachListeners(master, quality, unprocessed)
        controller.load()
        mainHandler.post(statusPoll)
        return root
    }

    fun onResume() {
        updatePermissionControl()
        controller.load()
    }

    fun onPermissionResult(audioGranted: Boolean) {
        permissionRequestInFlight = false
        permissionDeniedOnce = !audioGranted
        updatePermissionControl()
        if (audioGranted && pendingEnable) {
            pendingEnable = false
            persist(currentSettings.copy(enabled = true), MicSettingsRuntimeAction.START)
        } else if (!audioGranted && pendingEnable) {
            pendingEnable = false
            binding?.master?.isChecked = false
            binding?.settingsStatus?.setText(R.string.sense_mic_permission_needed)
            binding?.settingsStatus?.setTextColor(activity.getColor(android.R.color.holo_red_dark))
        }
    }

    override fun close() {
        closed = true
        mainHandler.removeCallbacks(statusPoll)
        binding = null
        controller.close()
    }

    private fun attachListeners(master: Switch, quality: Spinner, unprocessed: Switch) {
        master.setOnCheckedChangeListener { _, checked ->
            if (applyingState) return@setOnCheckedChangeListener
            if (checked && !hasAudioPermission()) {
                pendingEnable = true
                requestPermission()
                return@setOnCheckedChangeListener
            }
            persist(
                currentSettings.copy(enabled = checked),
                if (checked) MicSettingsRuntimeAction.START else MicSettingsRuntimeAction.STOP,
            )
        }
        quality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingState || binding?.quality?.isEnabled != true) return
                val selected = SenseMicQuality.entries[position.coerceIn(0, SenseMicQuality.entries.lastIndex)]
                if (selected == currentSettings.quality) return
                persist(
                    currentSettings.copy(quality = selected),
                    runtimeActionForConfigurationChange(),
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        unprocessed.setOnCheckedChangeListener { _, checked ->
            if (applyingState || binding?.unprocessed?.isEnabled != true) return@setOnCheckedChangeListener
            val profile = if (checked) {
                SenseMicCaptureProfile.UNPROCESSED
            } else {
                SenseMicCaptureProfile.VOICE_COMMUNICATION
            }
            if (profile == currentSettings.captureProfile) return@setOnCheckedChangeListener
            persist(
                currentSettings.copy(captureProfile = profile),
                runtimeActionForConfigurationChange(),
            )
        }
    }

    private fun rotatePairCode() {
        val rotated = currentSettings.copy(pairCode = SenseMicSettings.generatePairCode())
        persist(rotated, runtimeActionForConfigurationChange())
    }

    private fun persist(settings: SenseMicSettings, action: MicSettingsRuntimeAction) {
        currentSettings = settings
        controller.save(settings, action)
    }

    private fun runtimeActionForConfigurationChange(): MicSettingsRuntimeAction =
        if (currentSettings.enabled) MicSettingsRuntimeAction.RELOAD else MicSettingsRuntimeAction.NONE

    private fun renderSettings(state: MicSettingsUiState) {
        val current = binding ?: return
        currentSettings = state.settings
        applyingState = true
        try {
            current.master.isChecked = state.settings.enabled
            current.quality.setSelection(SenseMicQuality.entries.indexOf(state.settings.quality))
            current.unprocessed.isChecked =
                state.settings.captureProfile == SenseMicCaptureProfile.UNPROCESSED
            current.pairCode.text = state.settings.pairCode.chunked(3).joinToString(" ")
            current.pairCode.contentDescription = activity.getString(
                R.string.sense_mic_pair_code_accessibility,
                state.settings.pairCode.toCharArray().joinToString(" "),
            )
            val ready = state.phase != MicSettingsUiPhase.LOADING
            current.master.isEnabled = ready && !permissionRequestInFlight
            current.quality.isEnabled = ready
            current.unprocessed.isEnabled = ready
            current.rotate.isEnabled = ready
        } finally {
            applyingState = false
        }

        when (state.phase) {
            MicSettingsUiPhase.LOADING -> current.settingsStatus.setText(R.string.sense_mic_status_loading)
            MicSettingsUiPhase.SAVING -> current.settingsStatus.setText(R.string.sense_mic_status_saving)
            MicSettingsUiPhase.READY -> current.settingsStatus.setText(R.string.sense_mic_status_saved)
            MicSettingsUiPhase.ERROR -> current.settingsStatus.text = activity.getString(
                R.string.sense_mic_settings_error,
                state.message.orEmpty(),
            )
        }
        current.settingsStatus.setTextColor(
            activity.getColor(
                if (state.phase == MicSettingsUiPhase.ERROR) {
                    android.R.color.holo_red_dark
                } else {
                    R.color.sense_secondary
                },
            ),
        )
        updatePermissionControl()
        if (state.phase == MicSettingsUiPhase.READY && state.settings.enabled) {
            if (hasAudioPermission() && SenseMicRuntime.status().phase == SenseMicPhase.OFF) {
                SenseMicRuntime.start(activity)
            } else if (!hasAudioPermission()) {
                persist(state.settings.copy(enabled = false), MicSettingsRuntimeAction.STOP)
            }
        }
    }

    private fun renderRuntimeStatus(status: SenseMicStatus) {
        val current = binding ?: return
        val labelRes = when (status.phase) {
            SenseMicPhase.OFF -> R.string.sense_mic_runtime_off
            SenseMicPhase.STARTING -> R.string.sense_mic_runtime_starting
            SenseMicPhase.WAITING -> R.string.sense_mic_runtime_waiting
            SenseMicPhase.AUTHENTICATING -> R.string.sense_mic_runtime_authenticating
            SenseMicPhase.STREAMING -> R.string.sense_mic_runtime_streaming
            SenseMicPhase.MUTED -> R.string.sense_mic_runtime_muted
            SenseMicPhase.ERROR -> R.string.sense_mic_runtime_error
        }
        current.runtimeStatus.text = when {
            status.phase == SenseMicPhase.STREAMING ->
                activity.getString(
                    labelRes,
                    status.clientName ?: activity.getString(R.string.sense_mic_default_client),
                )
            status.phase == SenseMicPhase.ERROR && !status.errorMessage.isNullOrBlank() ->
                "${activity.getString(labelRes)} · ${status.errorMessage}"
            else -> activity.getString(labelRes)
        }
        val healthy = status.phase in setOf(
            SenseMicPhase.WAITING,
            SenseMicPhase.AUTHENTICATING,
            SenseMicPhase.STREAMING,
            SenseMicPhase.MUTED,
        )
        current.runtimeStatus.setTextColor(
            activity.getColor(
                when {
                    status.phase == SenseMicPhase.ERROR -> android.R.color.holo_red_dark
                    healthy -> R.color.sense_success
                    else -> R.color.sense_secondary
                },
            ),
        )
        current.endpoint.text = status.endpointSummary.takeIf(String::isNotBlank)
            ?.let { activity.getString(R.string.sense_mic_endpoint, it) }
            .orEmpty()
        current.stats.text = if (status.phase == SenseMicPhase.STREAMING || status.phase == SenseMicPhase.MUTED) {
            activity.getString(
                R.string.sense_mic_stats,
                status.sentPackets,
                status.receivedPackets,
                status.lostPackets,
                status.jitterMillis,
            )
        } else {
            ""
        }
        current.mute.visibility =
            if (status.phase == SenseMicPhase.STREAMING || status.phase == SenseMicPhase.MUTED) {
                View.VISIBLE
            } else {
                View.GONE
            }
        current.mute.setText(
            if (status.phase == SenseMicPhase.MUTED) {
                R.string.sense_mic_unmute
            } else {
                R.string.sense_mic_mute
            },
        )
    }

    private fun applyRuntimeAction(action: MicSettingsRuntimeAction) {
        when (action) {
            MicSettingsRuntimeAction.NONE -> Unit
            MicSettingsRuntimeAction.START -> SenseMicRuntime.start(activity)
            MicSettingsRuntimeAction.STOP -> SenseMicRuntime.stop(activity)
            MicSettingsRuntimeAction.RELOAD -> SenseMicRuntime.reloadSettings(activity)
        }
    }

    private fun requestPermission() {
        if (hasAudioPermission()) {
            pendingEnable = binding?.master?.isChecked == true || pendingEnable
            emitEffect(SettingsEffect.RequestSenseMicPermissions)
            return
        }
        if (
            permissionDeniedOnce &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            emitEffect(SettingsEffect.OpenApplicationDetails)
            return
        }
        permissionRequestInFlight = true
        binding?.master?.isEnabled = false
        emitEffect(SettingsEffect.RequestSenseMicPermissions)
    }

    private fun updatePermissionControl() {
        val current = binding ?: return
        val granted = hasAudioPermission()
        current.permission.visibility = if (granted) View.GONE else View.VISIBLE
        current.permission.setText(
            if (
                permissionDeniedOnce &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            ) {
                R.string.sense_mic_permission_open_settings
            } else {
                R.string.sense_mic_permission_grant
            },
        )
        current.permission.isEnabled = !permissionRequestInFlight
    }

    private fun hasAudioPermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private data class Binding(
        val root: LinearLayout,
        val master: Switch,
        val runtimeStatus: TextView,
        val endpoint: TextView,
        val stats: TextView,
        val permission: Button,
        val mute: Button,
        val pairCode: TextView,
        val rotate: Button,
        val quality: Spinner,
        val unprocessed: Switch,
        val settingsStatus: TextView,
    )

    private companion object {
        const val STATUS_POLL_MILLIS = 500L
    }
}
