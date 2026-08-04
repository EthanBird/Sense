package io.github.ethanbird.senseime.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ethanbird.senseime.agent.ui.AgentUiActions
import io.github.ethanbird.senseime.agent.ui.AgentUiState

/** Compact foreground controller rendered inside the IME root while Agent runs in background. */
internal class AgentRunChipView(context: Context) : LinearLayout(context) {
    private val statusView = TextView(context)
    private val stopView = TextView(context)
    private var actions = AgentUiActions()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        elevation = dp(8f).toFloat()
        minimumHeight = dp(40f)
        isClickable = true
        isFocusable = true
        contentDescription = "打开正在运行的 Sense Agent"

        statusView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12f), 0, dp(8f), 0)
        }
        addView(
            statusView,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )

        stopView.apply {
            text = "■"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            isClickable = true
            isFocusable = true
            contentDescription = "停止当前 Agent 任务"
            setOnClickListener { actions.onStop() }
        }
        addView(stopView, LayoutParams(dp(40f), LayoutParams.MATCH_PARENT))
        setOnClickListener { actions.onOpen() }
        applyColors()
    }

    fun bind(state: AgentUiState, actions: AgentUiActions) {
        this.actions = actions
        statusView.text = buildString {
            append("✦ Agent")
            state.status.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
        }
        visibility = if (state.running) View.VISIBLE else View.GONE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyColors()
    }

    private fun applyColors() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val surface = if (dark) Color.rgb(39, 42, 45) else Color.WHITE
        val foreground = if (dark) Color.rgb(239, 241, 242) else Color.rgb(35, 38, 40)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20f).toFloat()
            setColor(surface)
            setStroke(dp(1f), if (dark) Color.rgb(65, 69, 73) else Color.rgb(222, 225, 227))
        }
        statusView.setTextColor(foreground)
        stopView.setTextColor(Color.rgb(202, 64, 61))
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
