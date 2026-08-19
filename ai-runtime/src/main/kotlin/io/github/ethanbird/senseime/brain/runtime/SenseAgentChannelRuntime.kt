package io.github.ethanbird.senseime.brain.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.CopyOnWriteArraySet

enum class SenseAgentChannelPhase {
    OFF,
    STARTING,
    RUNNING,
    DEGRADED,
    ERROR,
}

internal object AgentChannelStatusAggregation {
    data class Result(val phase: SenseAgentChannelPhase, val detail: String)

    fun resolve(
        connections: Collection<AgentChannelConnectionState>,
        expectedCount: Int,
    ): Result {
        if (expectedCount == 0) return Result(SenseAgentChannelPhase.OFF, "")
        val connected = connections.count { it.phase == AgentChannelConnectionPhase.CONNECTED }
        val error = connections.firstOrNull { it.phase == AgentChannelConnectionPhase.ERROR }
        val retrying = connections.firstOrNull {
            it.phase == AgentChannelConnectionPhase.RETRYING ||
                it.phase == AgentChannelConnectionPhase.STARTING
        }
        return when {
            error != null && connected == 0 -> Result(SenseAgentChannelPhase.ERROR, error.detail)
            error != null -> Result(SenseAgentChannelPhase.DEGRADED, error.detail)
            connected == expectedCount -> Result(SenseAgentChannelPhase.RUNNING, "")
            connected > 0 -> Result(SenseAgentChannelPhase.DEGRADED, retrying?.detail.orEmpty())
            else -> Result(SenseAgentChannelPhase.STARTING, retrying?.detail.orEmpty())
        }
    }
}

object AgentChannelConfigReloadPolicy {
    fun shouldReload(localRevision: Long, remoteRevision: Long): Boolean =
        remoteRevision > 0L && remoteRevision != localRevision
}

data class SenseAgentChannelStatus(
    val phase: SenseAgentChannelPhase = SenseAgentChannelPhase.OFF,
    val connections: Map<AgentChannelType, AgentChannelConnectionState> = emptyMap(),
    val queuedMessages: Int = 0,
    val activeSessionKey: String? = null,
    val detail: String = "",
    val configRevision: Long = 0L,
)

fun interface SenseAgentChannelObserver {
    fun onStatus(status: SenseAgentChannelStatus)
}

/** Cross-process service control/status facade used by the settings UI and `:brain` service. */
object SenseAgentChannelRuntime {
    @Volatile
    private var current = SenseAgentChannelStatus()
    private val observers = CopyOnWriteArraySet<SenseAgentChannelObserver>()

    fun status(): SenseAgentChannelStatus = current

    fun observe(context: Context, observer: SenseAgentChannelObserver): AutoCloseable {
        val applicationContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_STATUS) return
                val decoded = intent.decodeStatus() ?: return
                current = decoded
                observer.onStatus(decoded)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.registerReceiver(
                receiver,
                IntentFilter(ACTION_STATUS),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(receiver, IntentFilter(ACTION_STATUS))
        }
        observers += observer
        observer.onStatus(current)
        return AutoCloseable {
            observers -= observer
            runCatching { applicationContext.unregisterReceiver(receiver) }
        }
    }

    fun start(context: Context) {
        context.applicationContext.startForegroundService(
            Intent(context.applicationContext, SenseAgentChannelService::class.java)
                .setAction(SenseAgentChannelService.ACTION_START),
        )
    }

    fun reload(context: Context) {
        context.applicationContext.startForegroundService(
            Intent(context.applicationContext, SenseAgentChannelService::class.java)
                .setAction(SenseAgentChannelService.ACTION_RELOAD),
        )
    }

    fun requestStatus(context: Context) {
        // Dynamic receiver exists only while the service is alive; this query never creates the
        // service or adapters, so a user pause remains paused when settings opens.
        context.applicationContext.sendBroadcast(
            Intent(SenseAgentChannelService.ACTION_STATUS_QUERY)
                .setPackage(context.packageName),
        )
    }

    fun pause(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, SenseAgentChannelService::class.java)
                .setAction(SenseAgentChannelService.ACTION_PAUSE),
        )
    }

    fun stop(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, SenseAgentChannelService::class.java)
                .setAction(SenseAgentChannelService.ACTION_STOP),
        )
    }

    internal fun publish(context: Context, status: SenseAgentChannelStatus) {
        current = status
        observers.forEach { observer -> runCatching { observer.onStatus(status) } }
        context.applicationContext.sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_PHASE, status.phase.name)
                .putExtra(EXTRA_QUEUED, status.queuedMessages)
                .putExtra(EXTRA_ACTIVE_SESSION, status.activeSessionKey)
                .putExtra(EXTRA_DETAIL, status.detail)
                .putExtra(EXTRA_CONFIG_REVISION, status.configRevision)
                .apply {
                    AgentChannelType.entries.forEach { type ->
                        status.connections[type]?.let { connection ->
                            putExtra(connectionPhaseExtra(type), connection.phase.name)
                            putExtra(connectionDetailExtra(type), connection.detail)
                        }
                    }
                },
        )
    }

    private fun Intent.decodeStatus(): SenseAgentChannelStatus? {
        val phase = runCatching {
            SenseAgentChannelPhase.valueOf(getStringExtra(EXTRA_PHASE).orEmpty())
        }.getOrNull() ?: return null
        val connections = buildMap {
            AgentChannelType.entries.forEach { type ->
                val connectionPhase = getStringExtra(connectionPhaseExtra(type))
                    ?.let { encoded ->
                        runCatching { AgentChannelConnectionPhase.valueOf(encoded) }.getOrNull()
                    }
                if (connectionPhase != null) {
                    put(
                        type,
                        AgentChannelConnectionState(
                            channel = type,
                            phase = connectionPhase,
                            detail = getStringExtra(connectionDetailExtra(type)).orEmpty(),
                        ),
                    )
                }
            }
        }
        return SenseAgentChannelStatus(
            phase = phase,
            connections = connections,
            queuedMessages = getIntExtra(EXTRA_QUEUED, 0).coerceAtLeast(0),
            activeSessionKey = getStringExtra(EXTRA_ACTIVE_SESSION),
            detail = getStringExtra(EXTRA_DETAIL).orEmpty(),
            configRevision = getLongExtra(EXTRA_CONFIG_REVISION, 0L).coerceAtLeast(0L),
        )
    }

    private fun connectionPhaseExtra(type: AgentChannelType) = "connection.${type.name}.phase"
    private fun connectionDetailExtra(type: AgentChannelType) = "connection.${type.name}.detail"

    private const val ACTION_STATUS =
        "io.github.ethanbird.senseime.action.AGENT_CHANNEL_STATUS"
    private const val EXTRA_PHASE = "phase"
    private const val EXTRA_QUEUED = "queued"
    private const val EXTRA_ACTIVE_SESSION = "active_session"
    private const val EXTRA_DETAIL = "detail"
    private const val EXTRA_CONFIG_REVISION = "config_revision"
}
