package io.github.ethanbird.senseime.service

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.ethanbird.senseime.agent.ui.AgentComposeHostView
import io.github.ethanbird.senseime.agent.ui.AgentUiActions
import io.github.ethanbird.senseime.agent.ui.AgentUiState
import io.github.ethanbird.senseime.ui.KeyboardSizeProfile
import io.github.ethanbird.senseime.ui.SenseKeyboardSurface

internal enum class ImeFrontMode {
    KEYBOARD,
    AGENT_READING,
    AGENT_COMPOSING,
}

/**
 * One IME-window root for both the zero-Compose keyboard path and the full Agent surface.
 * The Compose host is allocated on first Agent entry and remains detached from ordinary startup.
 */
internal class SenseImeRootLayout(context: Context) : FrameLayout(context) {
    val keyboardSurface = SenseKeyboardSurface(context)
    private val runChip = AgentRunChipView(context)
    private var agentHost: AgentComposeHostView? = null
    private var lastAgentState = AgentUiState()
    private var lastAgentActions = AgentUiActions()
    private var imeWindowVisible = false
    var mode: ImeFrontMode = ImeFrontMode.KEYBOARD
        private set

    init {
        addView(
            keyboardSurface,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            runChip,
            LayoutParams(dp(236f), dp(40f), Gravity.TOP or Gravity.START).also { params ->
                params.setMargins(dp(8f), dp(7f), 0, 0)
            },
        )
        runChip.visibility = View.GONE
    }

    fun setKeyboardSizeProfile(profile: KeyboardSizeProfile) {
        keyboardSurface.setKeyboardSizeProfile(profile)
        applyModeLayout()
    }

    fun showKeyboard() {
        mode = ImeFrontMode.KEYBOARD
        applyModeLayout()
    }

    fun showAgent(
        state: AgentUiState,
        actions: AgentUiActions,
        composing: Boolean,
    ) {
        lastAgentState = state.copy(composing = composing)
        lastAgentActions = actions
        mode = if (composing) ImeFrontMode.AGENT_COMPOSING else ImeFrontMode.AGENT_READING
        ensureAgentHost().render(lastAgentState, actions)
        applyModeLayout()
    }

    fun renderAgent(state: AgentUiState, actions: AgentUiActions) {
        lastAgentState = state.copy(composing = mode == ImeFrontMode.AGENT_COMPOSING)
        lastAgentActions = actions
        agentHost?.render(lastAgentState, actions)
        updateRunChip()
    }

    fun setImeWindowVisible(visible: Boolean) {
        imeWindowVisible = visible
        keyboardSurface.setImeWindowVisible(visible)
        agentHost?.setRenderingActive(visible && mode != ImeFrontMode.KEYBOARD)
    }

    fun preferredKeyboardHeightPx(): Int = keyboardSurface.preferredHeightPx()

    fun release() {
        agentHost?.release()
        agentHost = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyModeLayout()
    }

    private fun ensureAgentHost(): AgentComposeHostView {
        agentHost?.let { return it }
        return AgentComposeHostView(context).also { host ->
            host.visibility = View.GONE
            agentHost = host
            addView(host, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun applyModeLayout() {
        val keyboardHeight = preferredKeyboardHeightPx()
        val targetHeight = when (mode) {
            ImeFrontMode.KEYBOARD -> keyboardHeight
            ImeFrontMode.AGENT_READING,
            ImeFrontMode.AGENT_COMPOSING,
            -> ImeWindowHeightPolicy.agentHeightPx(
                availableHeightPx = availableWindowHeightPx(),
                keyboardHeightPx = keyboardHeight,
                density = resources.displayMetrics.density,
                landscape = resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE,
                composing = mode == ImeFrontMode.AGENT_COMPOSING,
            )
        }
        layoutParams = (layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            targetHeight,
        )).also { params -> params.height = targetHeight }

        when (mode) {
            ImeFrontMode.KEYBOARD -> {
                agentHost?.setRenderingActive(false)
                agentHost?.visibility = View.GONE
                keyboardSurface.visibility = View.VISIBLE
                keyboardSurface.layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    keyboardHeight,
                    Gravity.BOTTOM,
                )
            }
            ImeFrontMode.AGENT_READING -> {
                runChip.visibility = View.GONE
                keyboardSurface.visibility = View.GONE
                agentHost?.apply {
                    visibility = View.VISIBLE
                    setRenderingActive(imeWindowVisible)
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT,
                    )
                }
            }
            ImeFrontMode.AGENT_COMPOSING -> {
                runChip.visibility = View.GONE
                keyboardSurface.visibility = View.VISIBLE
                keyboardSurface.layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    keyboardHeight,
                    Gravity.BOTTOM,
                )
                agentHost?.apply {
                    visibility = View.VISIBLE
                    setRenderingActive(imeWindowVisible)
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT,
                    ).also { params -> params.bottomMargin = keyboardHeight }
                }
            }
        }
        updateRunChip()
        requestLayout()
    }

    private fun availableWindowHeightPx(): Int {
        // A Service context is not a visual Window context on every Android release. Querying
        // WindowManager.currentWindowMetrics from an IME service can therefore throw exactly
        // when the Agent page is opened. Configuration metrics are stable for this View tree and
        // are sufficient for the bounded 80% IME-page policy.
        val density = resources.displayMetrics.density
        val configured = (resources.configuration.screenHeightDp * density).toInt()
        return configured.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels.coerceAtLeast(1)
    }

    private fun updateRunChip() {
        if (mode == ImeFrontMode.KEYBOARD) {
            runChip.bind(lastAgentState, lastAgentActions)
            if (runChip.visibility == View.VISIBLE) runChip.bringToFront()
        } else {
            runChip.visibility = View.GONE
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
