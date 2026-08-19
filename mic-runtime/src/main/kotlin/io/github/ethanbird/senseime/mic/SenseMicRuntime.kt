package io.github.ethanbird.senseime.mic

import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.atomic.AtomicReference

enum class SenseMicPhase {
    OFF,
    STARTING,
    WAITING,
    AUTHENTICATING,
    STREAMING,
    MUTED,
    ERROR,
}

data class SenseMicStatus(
    val phase: SenseMicPhase = SenseMicPhase.OFF,
    val clientName: String? = null,
    val endpointSummary: String = "",
    val sentPackets: Long = 0,
    val receivedPackets: Long = 0,
    val lostPackets: Long = 0,
    val jitterMillis: Int = 0,
    val errorMessage: String? = null,
) {
    val active: Boolean
        get() = phase != SenseMicPhase.OFF && phase != SenseMicPhase.ERROR
}

object SenseMicRuntime {
    private val status = AtomicReference(SenseMicStatus())

    fun status(): SenseMicStatus = status.get()

    internal fun publish(value: SenseMicStatus) {
        status.set(value)
    }

    fun start(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, SenseMicService::class.java).setAction(SenseMicService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        app.startService(
            Intent(app, SenseMicService::class.java).setAction(SenseMicService.ACTION_STOP),
        )
    }

    fun toggleMute(context: Context) {
        val app = context.applicationContext
        app.startService(
            Intent(app, SenseMicService::class.java).setAction(SenseMicService.ACTION_TOGGLE_MUTE),
        )
    }

    fun reloadSettings(context: Context) {
        val app = context.applicationContext
        app.startService(
            Intent(app, SenseMicService::class.java).setAction(SenseMicService.ACTION_RELOAD),
        )
    }
}
