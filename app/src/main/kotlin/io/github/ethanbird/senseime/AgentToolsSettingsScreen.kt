package io.github.ethanbird.senseime

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.ethanbird.senseime.brain.runtime.AgentToolSettings
import io.github.ethanbird.senseime.brain.runtime.AgentToolSettingsStore
import java.util.concurrent.Executor

internal interface AgentToolSettingsRepository {
    fun load(): Result<AgentToolSettings>
    fun save(settings: AgentToolSettings): Result<Unit>
}

internal class RuntimeAgentToolSettingsRepository(
    activity: Activity,
) : AgentToolSettingsRepository {
    private val applicationContext = activity.applicationContext
    private val store by lazy { AgentToolSettingsStore(applicationContext) }

    override fun load(): Result<AgentToolSettings> = store.load()

    override fun save(settings: AgentToolSettings): Result<Unit> = store.save(settings)
}

internal enum class AgentToolsUiPhase {
    LOADING,
    READY,
    SAVING,
    SAVED,
    INVALID,
    SAVE_FAILED,
}

internal data class AgentToolsUiState(
    val settings: AgentToolSettings = AgentToolSettings(),
    val phase: AgentToolsUiPhase = AgentToolsUiPhase.LOADING,
) {
    val controlsEnabled: Boolean
        get() = phase == AgentToolsUiPhase.READY ||
            phase == AgentToolsUiPhase.SAVED ||
            phase == AgentToolsUiPhase.SAVE_FAILED
}

/** Owns tool settings state, its repository seam, and a dedicated serial I/O generation. */
internal class AgentToolsSettingsController(
    private val repository: AgentToolSettingsRepository,
    private val tasks: SettingsTaskRunner,
    private val render: (AgentToolsUiState) -> Unit,
) : AutoCloseable {
    private var generation = 0L
    private var closed = false
    private var lastSaved = AgentToolSettings()
    private var state = AgentToolsUiState()

    fun load() {
        if (closed || state.phase == AgentToolsUiPhase.LOADING && generation > 0L) return
        val requestGeneration = nextGeneration()
        publish(state.copy(phase = AgentToolsUiPhase.LOADING))
        val accepted = tasks.refresh(
            channel = LOAD_CHANNEL,
            operation = { repository.load().getOrThrow() },
        ) { result ->
            if (!accepts(requestGeneration)) return@refresh
            result
                .onSuccess { settings ->
                    lastSaved = settings
                    publish(
                        AgentToolsUiState(
                            settings = settings,
                            phase = AgentToolsUiPhase.READY,
                        ),
                    )
                }
                .onFailure {
                    publish(
                        AgentToolsUiState(
                            settings = AgentToolSettings(),
                            phase = AgentToolsUiPhase.INVALID,
                        ),
                    )
                }
        }
        if (!accepted && accepts(requestGeneration)) {
            publish(state.copy(phase = AgentToolsUiPhase.INVALID))
        }
    }

    fun save(settings: AgentToolSettings) {
        if (closed || state.phase == AgentToolsUiPhase.SAVING) return
        val requestGeneration = nextGeneration()
        publish(AgentToolsUiState(settings, AgentToolsUiPhase.SAVING))
        val accepted = tasks.execute(
            operation = { repository.save(settings).getOrThrow() },
        ) { result ->
            if (!accepts(requestGeneration)) return@execute
            result
                .onSuccess {
                    lastSaved = settings
                    publish(AgentToolsUiState(settings, AgentToolsUiPhase.SAVED))
                }
                .onFailure {
                    publish(
                        AgentToolsUiState(
                            lastSaved,
                            AgentToolsUiPhase.SAVE_FAILED,
                        ),
                    )
                }
        }
        if (!accepted && accepts(requestGeneration)) {
            publish(AgentToolsUiState(lastSaved, AgentToolsUiPhase.SAVE_FAILED))
        }
    }

    override fun close() {
        closed = true
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        tasks.close()
    }

    private fun publish(next: AgentToolsUiState) {
        state = next
        if (!closed) render(next)
    }

    private fun nextGeneration(): Long {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return generation
    }

    private fun accepts(requestGeneration: Long): Boolean =
        !closed && requestGeneration == generation

    private companion object {
        const val LOAD_CHANNEL = "agent-tools-load"
    }
}

internal class AgentToolsViewBinding(
    val root: LinearLayout,
    val master: Switch,
    val webSearch: Switch,
    val webRead: Switch,
    val calculator: Switch,
    val localMemoryRecall: Switch,
    val skillRead: Switch,
    val skillManage: Switch,
    val status: TextView,
)

internal class AgentToolsSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    repository: AgentToolSettingsRepository =
        RuntimeAgentToolSettingsRepository(activity),
    tasks: SettingsTaskRunner = SettingsAsyncLane(
        threadName = "Sense-AgentToolsSettings",
        uiExecutor = Executor(activity::runOnUiThread),
    ),
) : AutoCloseable {
    private var binding: AgentToolsViewBinding? = null
    private var applyingState = false
    private val controller = AgentToolsSettingsController(
        repository = repository,
        tasks = tasks,
        render = ::render,
    )

    fun createView(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(views.text(R.string.agent_tools_body, 13f, R.color.sense_secondary))

        val master = views.switch(R.string.agent_tools_master, checked = true).apply {
            typeface = Typeface.DEFAULT_BOLD
        }
        val webSearch = views.switch(R.string.agent_tool_web_search, checked = true)
        val webRead = views.switch(R.string.agent_tool_web_read, checked = true)
        val calculator = views.switch(R.string.agent_tool_calculator, checked = true)
        val memory = views.switch(R.string.agent_tool_local_memory_recall, checked = true)
        val skillRead = views.switch(R.string.agent_tool_skill_read, checked = true)
        val skillManage = views.switch(R.string.agent_tool_skill_manage, checked = true)
        val status =
            views.text(
                R.string.agent_tools_status_loading,
                12f,
                R.color.sense_secondary,
            ).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }

        root.addView(master.withTop(views.dp(12)))
        root.addView(webSearch.withTop(views.dp(8)))
        root.addView(webRead.withTop(views.dp(8)))
        root.addView(calculator.withTop(views.dp(8)))
        root.addView(memory.withTop(views.dp(8)))
        root.addView(skillRead.withTop(views.dp(8)))
        root.addView(skillManage.withTop(views.dp(8)))
        root.addView(status.withTop(views.dp(10)))

        binding = AgentToolsViewBinding(
            root = root,
            master = master,
            webSearch = webSearch,
            webRead = webRead,
            calculator = calculator,
            localMemoryRecall = memory,
            skillRead = skillRead,
            skillManage = skillManage,
            status = status,
        )
        listOf(master, webSearch, webRead, calculator, memory, skillRead, skillManage)
            .forEach { switch ->
                switch.setOnCheckedChangeListener { _, _ -> onSwitchChanged() }
            }
        setControlsEnabled(false)
        controller.load()
        return root
    }

    override fun close() {
        binding = null
        controller.close()
    }

    private fun onSwitchChanged() {
        if (applyingState) return
        val current = binding ?: return
        applyMasterAvailability(current)
        controller.save(
            AgentToolSettings(
                masterEnabled = current.master.isChecked,
                webSearchEnabled = current.webSearch.isChecked,
                webFetchEnabled = current.webRead.isChecked,
                calculatorEnabled = current.calculator.isChecked,
                memorySearchEnabled = current.localMemoryRecall.isChecked,
                skillReadEnabled = current.skillRead.isChecked,
                skillManageEnabled = current.skillManage.isChecked,
            ),
        )
    }

    private fun render(state: AgentToolsUiState) {
        val current = binding ?: return
        applyingState = true
        try {
            current.master.isChecked = state.settings.masterEnabled
            current.webSearch.isChecked = state.settings.webSearchEnabled
            current.webRead.isChecked = state.settings.webFetchEnabled
            current.calculator.isChecked = state.settings.calculatorEnabled
            current.localMemoryRecall.isChecked = state.settings.memorySearchEnabled
            current.skillRead.isChecked = state.settings.skillReadEnabled
            current.skillManage.isChecked = state.settings.skillManageEnabled
            setControlsEnabled(state.controlsEnabled)
            applyMasterAvailability(current)
        } finally {
            applyingState = false
        }

        when (state.phase) {
            AgentToolsUiPhase.LOADING,
            AgentToolsUiPhase.SAVING,
            -> {
                current.status.setText(R.string.agent_tools_status_loading)
                current.status.setTextColor(activity.getColor(R.color.sense_secondary))
            }

            AgentToolsUiPhase.INVALID -> {
                current.status.setText(R.string.agent_tools_status_invalid)
                current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
            }

            AgentToolsUiPhase.SAVE_FAILED -> {
                current.status.setText(R.string.agent_tools_status_save_failed)
                current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
            }

            AgentToolsUiPhase.READY,
            AgentToolsUiPhase.SAVED,
            -> {
                if (!state.settings.masterEnabled) {
                    current.status.setText(R.string.agent_tools_status_off)
                } else {
                    current.status.text = activity.getString(
                        if (state.phase == AgentToolsUiPhase.SAVED) {
                            R.string.agent_tools_status_saved
                        } else {
                            R.string.agent_tools_status_ready
                        },
                        state.settings.enabledToolIds().size,
                    )
                }
                current.status.setTextColor(
                    activity.getColor(
                        if (state.settings.masterEnabled) {
                            R.color.sense_success
                        } else {
                            R.color.sense_secondary
                        },
                    ),
                )
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val current = binding ?: return
        current.master.isEnabled = enabled
        current.webSearch.isEnabled = enabled && current.master.isChecked
        current.webRead.isEnabled = enabled && current.master.isChecked
        current.calculator.isEnabled = enabled && current.master.isChecked
        current.localMemoryRecall.isEnabled = enabled && current.master.isChecked
        current.skillRead.isEnabled = enabled && current.master.isChecked
        current.skillManage.isEnabled = enabled && current.master.isChecked
    }

    private fun applyMasterAvailability(current: AgentToolsViewBinding) {
        val enabled = current.master.isEnabled && current.master.isChecked
        current.webSearch.isEnabled = enabled
        current.webRead.isEnabled = enabled
        current.calculator.isEnabled = enabled
        current.localMemoryRecall.isEnabled = enabled
        current.skillRead.isEnabled = enabled
        current.skillManage.isEnabled = enabled
    }
}
