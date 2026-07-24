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
import io.github.ethanbird.senseime.brain.api.BrainRunHandle
import io.github.ethanbird.senseime.brain.api.BrainRunSpec

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

    private val ticker = object : Runnable {
        override fun run() {
            val current = synchronized(activeLock) { active } ?: return
            current.handle?.tick()
            if (synchronized(activeLock) { active?.identity == current.identity }) {
                mainHandler.postDelayed(this, TICK_INTERVAL_MS)
            }
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
        mainHandler.removeCallbacks(ticker)
        val previous = synchronized(activeLock) {
            active.also { active = null }
        }
        previous?.handle?.cancel(HarnessCancelReason.BRAIN_DIED)
        transport.close()
        super.onDestroy()
    }

    private fun handleStart(message: Message) {
        val request = runCatching { BrainMessageCodec.decodeRequest(message.data) }.getOrNull()
        val reply = message.replyTo
        if (request == null || reply == null) return
        val identity = request.requestId to request.runGeneration
        val previous = synchronized(activeLock) {
            active.also {
                active = ActiveRun(identity = identity, reply = reply)
            }
        }
        previous?.handle?.cancel(HarnessCancelReason.CALLER_REQUESTED)
        mainHandler.removeCallbacks(ticker)

        val config = settings.load().getOrNull()
        if (config == null) {
            emit(
                identity,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.PROVIDER_FAILURE,
                    retryable = false,
                ),
            )
            return
        }

        val handle = runCatching {
            engine.start(
                BrainRunSpec(
                    harnessRequest = request,
                    provider = config.profile,
                    credential = config.credential,
                ),
                sink = { event -> emit(identity, event) },
            )
        }.getOrElse {
            emit(
                identity,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                ),
            )
            return
        }
        val keep = synchronized(activeLock) {
            val current = active
            if (current?.identity == identity) {
                active = current.copy(handle = handle)
                true
            } else {
                false
            }
        }
        if (keep && !handle.isTerminal) {
            mainHandler.postDelayed(ticker, TICK_INTERVAL_MS)
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
            active?.takeIf { it.identity == identity }?.also { active = null }
        } ?: return
        mainHandler.removeCallbacks(ticker)
        current.handle?.cancel(reason)
    }

    private fun emit(identity: Pair<String, Long>, event: AiEvent) {
        val terminal =
            event is AiEvent.FinalPatch ||
                event is AiEvent.Cancelled ||
                event is AiEvent.Failed
        val current = synchronized(activeLock) {
            active?.takeIf { it.identity == identity }?.also {
                // A terminal event revokes the run before any cross-process callback.
                // Synchronous engine completion and late provider callbacks therefore
                // cannot race a newly-started request back into authority.
                if (terminal) active = null
            }
        } ?: return
        val outboundEvents = if (event is AiEvent.PreviewDelta) {
            BrainIpcTextChunker.chunk(event.text).map { chunk -> event.copy(text = chunk) }
        } else {
            listOf(event)
        }
        try {
            outboundEvents.forEach { outboundEvent ->
                current.reply.send(
                    Message.obtain(null, BrainMessageProtocol.EVENT).apply {
                        data = BrainMessageCodec.encodeEvent(outboundEvent)
                    },
                )
            }
        } catch (_: RemoteException) {
            synchronized(activeLock) {
                if (active?.identity == identity) active = null
            }
            current.handle?.cancel(HarnessCancelReason.BRAIN_DIED)
            return
        }
        if (terminal) {
            mainHandler.removeCallbacks(ticker)
        }
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

    private data class ActiveRun(
        val identity: Pair<String, Long>,
        val reply: Messenger,
        val handle: BrainRunHandle? = null,
    )

    companion object {
        private const val TICK_INTERVAL_MS = 100L
    }
}
