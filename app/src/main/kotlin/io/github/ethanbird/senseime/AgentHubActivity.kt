package io.github.ethanbird.senseime

import android.Manifest
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.ethanbird.senseime.brain.api.AgentBrowserAction
import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.runtime.AgentBrowserRuntime
import io.github.ethanbird.senseime.brain.runtime.AgentHubMessage
import io.github.ethanbird.senseime.brain.runtime.AgentHubMessageRole
import io.github.ethanbird.senseime.brain.runtime.AgentHubObserver
import io.github.ethanbird.senseime.brain.runtime.AgentHubProjection
import io.github.ethanbird.senseime.brain.runtime.AgentRuntimeComponents
import io.github.ethanbird.senseime.brain.runtime.AgentTerminalRuntime
import io.github.ethanbird.senseime.brain.runtime.SenseAgentHubRuntime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Full Agent workspace: conversation, session terminal and the exact Agent-controlled WebView. */
class AgentHubActivity : ComponentActivity() {
    private lateinit var runtime: SenseAgentHubRuntime
    private lateinit var terminal: AgentTerminalRuntime
    private lateinit var browser: AgentBrowserRuntime
    private lateinit var status: TextView
    private lateinit var messageScroll: ScrollView
    private lateinit var messageList: LinearLayout
    private lateinit var preview: TextView
    private lateinit var composer: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var agentPanel: View
    private lateinit var terminalPanel: View
    private lateinit var browserPanel: View
    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText
    private lateinit var browserAddress: EditText
    private lateinit var browserContainer: FrameLayout
    private var browserView: WebView? = null
    private var subscription: AutoCloseable? = null
    private var renderedMessages: List<AgentHubMessage> = emptyList()
    private var activeTab = HubTab.AGENT
    private val work: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-agent-hub-ui-work").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.sense_background)
        window.navigationBarColor = getColor(R.color.sense_background)
        runtime = SenseAgentHubRuntime.get(applicationContext)
        terminal = AgentRuntimeComponents.terminal(applicationContext)
        browser = AgentRuntimeComponents.browser(applicationContext)
        setContentView(buildContent())
        subscription = runtime.observe(AgentHubObserver(::render))
        requestNotificationPermissionIfNeeded()
    }

    override fun onDestroy() {
        subscription?.close()
        subscription = null
        if (::browserContainer.isInitialized) {
            browser.detach(SenseAgentHubRuntime.SESSION_FIELD_IDENTITY, browserContainer)
        }
        work.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(getColor(R.color.sense_background))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = getString(R.string.agent_hub_title)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.sense_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(actionButton(getString(R.string.close)) { finish() })
        root.addView(titleRow)

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.sense_secondary))
            setPadding(0, dp(5), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        HubTab.entries.forEach { tab ->
            tabs.addView(
                actionButton(tab.label) { showTab(tab) },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = if (tab == HubTab.BROWSER) 0 else dp(6)
                },
            )
        }
        root.addView(tabs)

        val content = FrameLayout(this)
        agentPanel = buildAgentPanel()
        terminalPanel = buildTerminalPanel().apply { visibility = View.GONE }
        browserPanel = buildBrowserPanel().apply { visibility = View.GONE }
        content.addView(agentPanel, FrameLayout.LayoutParams(-1, -1))
        content.addView(terminalPanel, FrameLayout.LayoutParams(-1, -1))
        content.addView(browserPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        return root
    }

    private fun buildAgentPanel(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        preview = messageBubble(AgentHubMessageRole.ASSISTANT, "").apply {
            visibility = View.GONE
        }
        messageList.addView(preview)
        messageScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(messageList)
        }
        root.addView(messageScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        composer = EditText(this).apply {
            hint = getString(R.string.agent_hub_composer_hint)
            minLines = 2
            maxLines = 5
            setTextColor(getColor(R.color.sense_primary))
            setHintTextColor(getColor(R.color.sense_secondary))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        root.addView(card(composer), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(actionButton(getString(R.string.agent_hub_new_chat)) {
            runtime.clearConversation()
        })
        stop = actionButton(getString(R.string.agent_hub_stop)) { runtime.stop() }
        actions.addView(stop, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(6) })
        send = actionButton(getString(R.string.agent_hub_send)) {
            if (runtime.send(composer.text.toString())) composer.text.clear()
        }
        actions.addView(send, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(6) })
        root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return root
    }

    private fun buildTerminalPanel(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        terminalOutput = TextView(this).apply {
            text = getString(R.string.agent_terminal_welcome)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.sense_primary))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.sense_surface))
            addView(terminalOutput)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        terminalInput = EditText(this).apply {
            hint = getString(R.string.agent_terminal_hint)
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    runTerminalCommand()
                    true
                } else {
                    false
                }
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(terminalInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(actionButton(getString(R.string.agent_terminal_run)) {
            runTerminalCommand()
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(6) })
        row.addView(actionButton(getString(R.string.agent_terminal_clear)) {
            terminalOutput.text = ""
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(6) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return root
    }

    private fun buildBrowserPanel(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(actionButton("‹") { runBrowserAction(AgentBrowserAction.BACK) })
        controls.addView(actionButton("›") { runBrowserAction(AgentBrowserAction.FORWARD) })
        controls.addView(actionButton("↻") { runBrowserAction(AgentBrowserAction.RELOAD) })
        browserAddress = EditText(this).apply {
            hint = "https://"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigateBrowser()
                    true
                } else {
                    false
                }
            }
        }
        controls.addView(browserAddress, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(6)
        })
        controls.addView(actionButton(getString(R.string.agent_browser_go)) {
            navigateBrowser()
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(6) })
        root.addView(controls)
        browserContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        root.addView(browserContainer, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(8)
        })
        return root
    }

    private fun showTab(tab: HubTab) {
        activeTab = tab
        agentPanel.visibility = if (tab == HubTab.AGENT) View.VISIBLE else View.GONE
        terminalPanel.visibility = if (tab == HubTab.TERMINAL) View.VISIBLE else View.GONE
        browserPanel.visibility = if (tab == HubTab.BROWSER) View.VISIBLE else View.GONE
        if (tab == HubTab.BROWSER) {
            browserView = browser.attach(
                SenseAgentHubRuntime.SESSION_FIELD_IDENTITY,
                browserContainer,
            )
            browserAddress.setText(browserView?.url.orEmpty())
        }
    }

    private fun render(projection: AgentHubProjection) {
        status.text = buildString {
            append(projection.status)
            if (projection.inputTokens > 0 || projection.outputTokens > 0) {
                append(" · ").append(projection.inputTokens)
                append('/').append(projection.outputTokens).append(" Token")
            }
        }
        send.isEnabled = projection.loaded && !projection.running
        stop.isEnabled = projection.running
        composer.isEnabled = projection.loaded && !projection.running
        if (renderedMessages != projection.messages) {
            renderedMessages = projection.messages
            messageList.removeAllViews()
            projection.messages.forEach { message ->
                messageList.addView(messageBubble(message.role, message.text))
            }
            messageList.addView(preview)
        }
        preview.text = projection.preview
        preview.visibility = if (projection.preview.isBlank()) View.GONE else View.VISIBLE
        messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun runTerminalCommand() {
        val command = terminalInput.text.toString().trim()
        if (command.isEmpty()) return
        terminalInput.text.clear()
        terminalOutput.append("\n$ $command\n")
        work.execute {
            val result = runCatching {
                terminal.execute(
                    SenseAgentHubRuntime.SESSION_FIELD_IDENTITY,
                    AgentToolArguments.TerminalExec(command),
                )
            }
            runOnUiThread {
                result.onSuccess { value ->
                    if (value.stdout.isNotEmpty()) terminalOutput.append(value.stdout)
                    if (value.stderr.isNotEmpty()) terminalOutput.append(value.stderr)
                    terminalOutput.append(
                        if (value.timedOut) "\n[timeout]\n"
                        else "\n[exit ${value.exitCode}]\n",
                    )
                }.onFailure { failure ->
                    terminalOutput.append("[${failure.message ?: failure.javaClass.simpleName}]\n")
                }
            }
        }
    }

    private fun navigateBrowser() {
        var url = browserAddress.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.contains("://")) url = "https://$url"
        browserAddress.setText(url)
        runBrowserAction(AgentBrowserAction.NAVIGATE, url)
    }

    private fun runBrowserAction(action: AgentBrowserAction, url: String? = null) {
        status.text = getString(R.string.agent_browser_loading)
        work.execute {
            val result = runCatching {
                browser.executeForTool(
                    SenseAgentHubRuntime.SESSION_FIELD_IDENTITY,
                    AgentToolArguments.BrowserUse(action = action, url = url),
                )
            }
            runOnUiThread {
                status.text = result.fold(
                    onSuccess = { getString(R.string.agent_browser_ready) },
                    onFailure = { it.message ?: getString(R.string.agent_browser_error) },
                )
                browserAddress.setText(browser.currentUrl(SenseAgentHubRuntime.SESSION_FIELD_IDENTITY))
            }
        }
    }

    private fun messageBubble(role: AgentHubMessageRole, value: String): TextView =
        TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(getColor(R.color.sense_primary))
            setTextIsSelectable(role == AgentHubMessageRole.ASSISTANT)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(
                    getColor(
                        if (role == AgentHubMessageRole.USER) {
                            R.color.sense_accent_soft
                        } else {
                            R.color.sense_surface
                        },
                    ),
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = if (role == AgentHubMessageRole.USER) Gravity.END else Gravity.START
                topMargin = dp(7)
                marginStart = if (role == AgentHubMessageRole.USER) dp(40) else 0
                marginEnd = if (role == AgentHubMessageRole.USER) 0 else dp(40)
            }
        }

    private fun card(child: View): View = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(getColor(R.color.sense_surface))
            setStroke(dp(1), getColor(R.color.sense_border))
        }
        addView(child, FrameLayout.LayoutParams(-1, -2))
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minimumWidth = 0
        minWidth = 0
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private enum class HubTab(val label: String) {
        AGENT("Agent"),
        TERMINAL("终端"),
        BROWSER("浏览器"),
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 0xA17
    }
}
