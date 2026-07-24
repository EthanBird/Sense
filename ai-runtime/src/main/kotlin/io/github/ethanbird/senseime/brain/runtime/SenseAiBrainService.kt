package io.github.ethanbird.senseime.brain.runtime

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.brain.AiBrainEngine
import io.github.ethanbird.senseime.brain.BrainRequestMode
import io.github.ethanbird.senseime.brain.api.BrainRunHandle
import io.github.ethanbird.senseime.brain.api.BrainRunSpec
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility

/**
 * Non-exported process host for Provider networking and model response parsing.
 *
 * It receives only immutable protocol values. InputConnection, keyboard View, editor Binder and
 * application package metadata never enter :brain.
 */
class SenseAiBrainService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(IncomingHandler())
    private val activeLock = Any()
    private lateinit var transport: HttpUrlConnectionProviderTransport
    private lateinit var engine: AiBrainEngine
    private lateinit var settings: ProviderSettingsStore
    private var active: ActiveRun? = null
    private val outboundDeliveries =
        BrainIpcSerialDeliveryQueue<OutboundDelivery>()
    private val deliverOutboundEvents = Runnable {
        while (true) {
            val delivery = outboundDeliveries.poll() ?: return@Runnable
            sendEventsNow(delivery)
        }
    }

    override fun onCreate() {
        super.onCreate()
        transport = HttpUrlConnectionProviderTransport()
        engine = AiBrainEngine(transport)
        settings = ProviderSettingsStore(this)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        val previous = synchronized(activeLock) {
            active.also {
                active = null
                it?.let { current ->
                    cancelTickerLocked(current)
                    cancelVisibleFlushLocked(current)
                }
            }
        }
        mainHandler.removeCallbacks(deliverOutboundEvents)
        outboundDeliveries.clear()
        previous?.handle?.cancel(HarnessCancelReason.BRAIN_DIED)
        transport.close()
        super.onDestroy()
    }

    private fun handleStart(message: Message) {
        val request = runCatching { BrainMessageCodec.decodeRequest(message.data) }.getOrNull()
        val reply = message.replyTo
        if (request == null || reply == null) return
        val identity = request.requestId to request.runGeneration
        val previous = synchronized(activeLock) { active }
        // Cancellation emits the previous terminal event synchronously. Keep its ActiveRun
        // installed until that callback is captured for its original Messenger; replacing it
        // first would make emit() drop the cancellation as stale and strand the old client's UI.
        previous?.handle?.cancel(HarnessCancelReason.CALLER_REQUESTED)
        val current = ActiveRun(identity = identity, reply = reply)
        synchronized(activeLock) {
            // A previous run with no installed handle cannot emit its own
            // terminal event. Its pending fragments still must not leak into
            // this run, and a stale frame callback must not target the new one.
            active?.let { remaining ->
                cancelTickerLocked(remaining)
                cancelVisibleFlushLocked(remaining)
                remaining.ipcEvents.clear()
            }
            active = current
        }

        val configResult = settings.load()
        if (configResult.isFailure) {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                ),
            )
            return
        }
        val config = configResult.getOrNull()
        if (config == null) {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.PROVIDER_NOT_CONFIGURED,
                    retryable = false,
                ),
            )
            return
        }
        if (ProviderCompatibility.issues(config.profile).isNotEmpty()) {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.PROVIDER_CONFIGURATION,
                    retryable = false,
                ),
            )
            return
        }

        val handle = runCatching {
            val requestMode = if (ProviderConnectionTestProtocol.isProbe(request)) {
                BrainRequestMode.CONNECTIVITY_TEST
            } else {
                BrainRequestMode.NORMAL
            }
            engine.start(
                BrainRunSpec(
                    harnessRequest = request,
                    provider = config.profile,
                    credential = config.credential,
                ),
                sink = { event -> emit(current, event) },
                requestMode = requestMode,
            )
        }.getOrElse {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                ),
            )
            return
        }
        val keep = synchronized(activeLock) {
            if (active === current) {
                current.handle = handle
                true
            } else {
                false
            }
        }
        if (keep && !handle.isTerminal) {
            scheduleTicker(current)
        } else {
            handle.cancel(HarnessCancelReason.CALLER_REQUESTED)
        }
    }

    private fun handleCancel(message: Message) {
        val (requestId, generation, reason) = runCatching {
            BrainMessageCodec.decodeCancel(message.data)
        }.getOrNull() ?: return
        val identity = requestId to generation
        val current = synchronized(activeLock) {
            active?.takeIf { it.identity == identity }?.also {
                active = null
                cancelTickerLocked(it)
                cancelVisibleFlushLocked(it)
                it.ipcEvents.clear()
            }
        } ?: return
        current.handle?.cancel(reason)
    }

    private fun emit(expectedRun: ActiveRun, event: AiEvent) {
        if ((event.requestId to event.runGeneration) != expectedRun.identity) return
        val terminal =
            event is AiEvent.FinalPatch ||
            event is AiEvent.Cancelled ||
                event is AiEvent.Failed

        if (event is AiEvent.PreviewDelta || event is AiEvent.DescriptionDelta) {
            synchronized(activeLock) {
                if (active !== expectedRun) return@synchronized
                val thresholdReached = expectedRun.ipcEvents.append(event)
                when {
                    thresholdReached && !expectedRun.ipcFlushUrgent -> {
                        cancelVisibleFlushLocked(expectedRun)
                        scheduleVisibleFlushLocked(expectedRun, delayMillis = 0L)
                    }
                    !expectedRun.ipcFlushScheduled -> {
                        scheduleVisibleFlushLocked(
                            expectedRun,
                            delayMillis = IPC_FRAME_INTERVAL_MS,
                        )
                    }
                }
            }
            return
        }

        val scheduleDrain = synchronized(activeLock) {
            if (active !== expectedRun) return@synchronized false
            cancelVisibleFlushLocked(expectedRun)
            val outbound = expectedRun.ipcEvents.drainBefore(event)
            // A terminal event revokes the run before any cross-process callback.
            // Synchronous engine completion and late provider callbacks therefore
            // cannot race a newly-started request back into authority.
            if (terminal) {
                active = null
                cancelTickerLocked(expectedRun)
            }
            enqueueDeliveryLocked(expectedRun, outbound)
        }
        scheduleOutboundDrain(scheduleDrain)
    }

    /**
     * Runs only on [mainHandler]. Every provider thread first enters the
     * serial delivery queue, so a terminal event can never overtake a frame
     * flush that already drained visible text.
     */
    private fun sendEventsNow(delivery: OutboundDelivery) {
        val current = delivery.run
        try {
            delivery.events.forEach { event ->
                val chunks = if (event is AiEvent.PreviewDelta) {
                    BrainIpcTextChunker.chunk(event.text).map { chunk -> event.copy(text = chunk) }
                } else {
                    listOf(event)
                }
                chunks.forEach { outboundEvent ->
                    current.reply.send(
                        Message.obtain(null, BrainMessageProtocol.EVENT).apply {
                            data = BrainMessageCodec.encodeEvent(outboundEvent)
                        },
                    )
                }
            }
        } catch (_: RemoteException) {
            synchronized(activeLock) {
                cancelTickerLocked(current)
                if (active === current) {
                    active = null
                    cancelVisibleFlushLocked(current)
                }
            }
            outboundDeliveries.removeAll { it.run === current }
            current.ipcEvents.clear()
            current.handle?.cancel(HarnessCancelReason.BRAIN_DIED)
        }
    }

    /**
     * Installs a timer owned by one exact [ActiveRun].
     *
     * Provider callbacks may terminate an old run on a worker thread while the
     * main thread is starting a new run. Keeping the Runnable on the run object
     * prevents an old terminal path from removing or rescheduling the new
     * run's timer.
     */
    private fun scheduleTicker(current: ActiveRun) {
        lateinit var callback: Runnable
        callback = Runnable {
            val handle = synchronized(activeLock) {
                if (
                    active !== current ||
                    !current.ticker.owns(callback)
                ) {
                    return@Runnable
                }
                current.handle
            }
            handle?.tick()
            val reschedule = synchronized(activeLock) {
                active === current &&
                    current.ticker.owns(callback) &&
                    current.handle?.isTerminal != true
            }
            if (reschedule) {
                mainHandler.postDelayed(callback, TICK_INTERVAL_MS)
            } else {
                synchronized(activeLock) {
                    current.ticker.clearIfOwned(callback)
                }
            }
        }
        val installed = synchronized(activeLock) {
            if (active !== current) {
                false
            } else {
                current.ticker.install(callback)
            }
        }
        if (installed) {
            mainHandler.postDelayed(callback, TICK_INTERVAL_MS)
        }
    }

    /**
     * Must be called while [activeLock] is held.
     */
    private fun cancelTickerLocked(current: ActiveRun) {
        current.ticker.clear()?.let(mainHandler::removeCallbacks)
    }

    private fun scheduleVisibleFlushLocked(
        current: ActiveRun,
        delayMillis: Long,
    ) {
        check(!current.ipcFlushScheduled)
        lateinit var callback: Runnable
        callback = Runnable { flushVisibleEvents(current, callback) }
        current.ipcFlushScheduled = true
        current.ipcFlushUrgent = delayMillis == 0L
        current.ipcFlushRunnable = callback
        if (delayMillis == 0L) {
            mainHandler.post(callback)
        } else {
            mainHandler.postDelayed(callback, delayMillis)
        }
    }

    private fun cancelVisibleFlushLocked(current: ActiveRun) {
        current.ipcFlushRunnable?.let(mainHandler::removeCallbacks)
        current.ipcFlushRunnable = null
        current.ipcFlushScheduled = false
        current.ipcFlushUrgent = false
    }

    private fun flushVisibleEvents(
        expectedRun: ActiveRun,
        expectedCallback: Runnable,
    ) {
        val scheduleDrain = synchronized(activeLock) {
            if (
                active !== expectedRun ||
                expectedRun.ipcFlushRunnable !== expectedCallback
            ) {
                return@synchronized false
            }
            expectedRun.ipcFlushRunnable = null
            expectedRun.ipcFlushScheduled = false
            expectedRun.ipcFlushUrgent = false
            enqueueDeliveryLocked(expectedRun, expectedRun.ipcEvents.drain())
        }
        scheduleOutboundDrain(scheduleDrain)
    }

    /**
     * Must be called while [activeLock] is held. This is the ordering boundary
     * shared by the timer and terminal-event paths.
     */
    private fun enqueueDeliveryLocked(
        current: ActiveRun,
        events: List<AiEvent>,
    ): Boolean {
        if (events.isEmpty()) return false
        return outboundDeliveries.enqueue(OutboundDelivery(current, events))
    }

    private fun scheduleOutboundDrain(required: Boolean) {
        if (required) mainHandler.post(deliverOutboundEvents)
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                BrainMessageProtocol.START -> handleStart(message)
                BrainMessageProtocol.CANCEL -> handleCancel(message)
                else -> super.handleMessage(message)
            }
        }
    }

    private class ActiveRun(
        val identity: Pair<String, Long>,
        val reply: Messenger,
        var handle: BrainRunHandle? = null,
        val ticker: BrainRunTickerSlot = BrainRunTickerSlot(),
        val ipcEvents: BrainIpcEventBatcher = BrainIpcEventBatcher(),
        var ipcFlushScheduled: Boolean = false,
        var ipcFlushUrgent: Boolean = false,
        var ipcFlushRunnable: Runnable? = null,
    )

    private data class OutboundDelivery(
        val run: ActiveRun,
        val events: List<AiEvent>,
    )

    companion object {
        private const val TICK_INTERVAL_MS = 100L
        private const val IPC_FRAME_INTERVAL_MS = 16L
    }
}
