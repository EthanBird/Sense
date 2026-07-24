package io.github.ethanbird.senseime.brain.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1

/**
 * One-request-at-a-time Messenger client for the private Brain process.
 *
 * Messenger keeps Android classes out of brain-api while still making the process boundary real.
 * The IME remains the only process that can see InputConnection; Brain receives immutable text.
 */
class SenseAiBrainClient(
    context: Context,
    private val eventSink: (AiEvent) -> Unit,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val incoming = Messenger(IncomingHandler())
    private var remote: Messenger? = null
    private var binding = false
    private var bound = false
    private var closed = false
    private var pending: HarnessRequestV1? = null
    private var activeIdentity: Pair<String, Long>? = null
    private var bindingTimeout: Runnable? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (closed) return
            if (!bound && !binding) return
            if (service == null) {
                resetBinding()
                failActiveConnection()
                return
            }
            clearBindingTimeout()
            remote = Messenger(service)
            binding = false
            bound = true
            pending?.also {
                pending = null
                sendStart(it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clearBindingTimeout()
            remote = null
            binding = false
            failActiveConnection()
        }

        override fun onBindingDied(name: ComponentName?) {
            resetBinding()
            failActiveConnection()
        }

        override fun onNullBinding(name: ComponentName?) {
            resetBinding()
            failActiveConnection()
        }
    }

    fun start(request: HarnessRequestV1) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SenseAiBrainClient must be driven from the IME main thread"
        }
        if (closed) {
            eventSink(
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                ),
            )
            return
        }
        activeIdentity?.let { (oldRequest, oldGeneration) ->
            sendCancel(oldRequest, oldGeneration, HarnessCancelReason.CALLER_REQUESTED)
        }
        activeIdentity = request.requestId to request.runGeneration
        val service = remote
        if (service != null) {
            sendStart(request)
            return
        }
        pending = request
        scheduleBindingTimeout(request)
        if (!bound && !binding) {
            binding = runCatching {
                applicationContext.bindService(
                    Intent(applicationContext, SenseAiBrainService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!binding) {
                clearBindingTimeout()
                pending = null
                activeIdentity = null
                eventSink(
                    AiEvent.Failed(
                        request.requestId,
                        request.runGeneration,
                        HarnessErrorCode.INTERNAL_FAILURE,
                    ),
                )
            }
        }
    }

    fun cancel(
        requestId: String,
        generation: Long,
        reason: HarnessCancelReason,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancel(requestId, generation, reason) }
            return
        }
        if (activeIdentity != (requestId to generation)) return
        pending = pending?.takeUnless {
            it.requestId == requestId && it.runGeneration == generation
        }
        clearBindingTimeout()
        activeIdentity = null
        sendCancel(requestId, generation, reason)
    }

    override fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::close)
            return
        }
        if (closed) return
        closed = true
        activeIdentity?.let { (requestId, generation) ->
            sendCancel(requestId, generation, HarnessCancelReason.CALLER_REQUESTED)
        }
        activeIdentity = null
        pending = null
        clearBindingTimeout()
        remote = null
        if (bound || binding) {
            runCatching { applicationContext.unbindService(connection) }
        }
        bound = false
        binding = false
    }

    private fun sendStart(request: HarnessRequestV1) {
        val message = Message.obtain(null, BrainMessageProtocol.START).apply {
            data = BrainMessageCodec.encodeRequest(request)
            replyTo = incoming
        }
        try {
            remote?.send(message) ?: error("Brain is not connected")
        } catch (_: Throwable) {
            resetBinding()
            activeIdentity = null
            eventSink(
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = true,
                ),
            )
        }
    }

    private fun failActiveConnection(
        errorCode: HarnessErrorCode = HarnessErrorCode.INTERNAL_FAILURE,
    ) {
        val failed = activeIdentity
        activeIdentity = null
        pending = null
        failed?.let { (requestId, generation) ->
            eventSink(
                AiEvent.Failed(
                    requestId,
                    generation,
                    errorCode,
                    retryable = true,
                ),
            )
        }
    }

    private fun resetBinding() {
        clearBindingTimeout()
        remote = null
        if (bound || binding) {
            runCatching { applicationContext.unbindService(connection) }
        }
        bound = false
        binding = false
    }

    private fun scheduleBindingTimeout(request: HarnessRequestV1) {
        clearBindingTimeout()
        val identity = request.requestId to request.runGeneration
        val timeout = Runnable {
            bindingTimeout = null
            if (
                closed ||
                remote != null ||
                activeIdentity != identity ||
                pending?.let { it.requestId to it.runGeneration } != identity
            ) {
                return@Runnable
            }
            resetBinding()
            failActiveConnection(HarnessErrorCode.FIRST_EVENT_TIMEOUT)
        }
        bindingTimeout = timeout
        mainHandler.postDelayed(timeout, BIND_TIMEOUT_MS)
    }

    private fun clearBindingTimeout() {
        bindingTimeout?.let(mainHandler::removeCallbacks)
        bindingTimeout = null
    }

    private fun sendCancel(
        requestId: String,
        generation: Long,
        reason: HarnessCancelReason,
    ) {
        val message = Message.obtain(null, BrainMessageProtocol.CANCEL).apply {
            data = BrainMessageCodec.encodeCancel(requestId, generation, reason)
            replyTo = incoming
        }
        try {
            remote?.send(message)
        } catch (_: RemoteException) {
            // The local generation was already invalidated; a dead Brain cannot regain authority.
            resetBinding()
        }
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != BrainMessageProtocol.EVENT || closed) return
            val event = runCatching { BrainMessageCodec.decodeEvent(message.data) }.getOrNull()
                ?: return
            if (activeIdentity != (event.requestId to event.runGeneration)) return
            val terminal =
                event is AiEvent.FinalPatch ||
                event is AiEvent.Cancelled ||
                event is AiEvent.Failed
            // Revoke authority before invoking application code. The callback may throw or
            // synchronously start a new run, neither of which may resurrect this identity.
            if (terminal) {
                activeIdentity = null
            }
            eventSink(event)
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 8_000L
    }
}
