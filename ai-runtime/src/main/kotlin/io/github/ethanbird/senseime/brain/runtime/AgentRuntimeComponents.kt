package io.github.ethanbird.senseime.brain.runtime

import android.content.Context

/** Process-local runtime graph shared by the private Brain service and Agent Hub Activity. */
object AgentRuntimeComponents {
    @Volatile
    private var terminalRuntime: AgentTerminalRuntime? = null

    fun terminal(context: Context): AgentTerminalRuntime =
        terminalRuntime ?: synchronized(this) {
            terminalRuntime ?: AgentTerminalRuntime(context).also { terminalRuntime = it }
        }

    fun browser(context: Context): AgentBrowserRuntime = AgentBrowserRuntime.get(context)
}
