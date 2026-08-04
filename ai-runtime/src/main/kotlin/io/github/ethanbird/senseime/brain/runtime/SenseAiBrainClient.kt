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
import io.github.ethanbird.senseime.ai.protocol.isTerminal

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
    private var pendingAttach: Pair<String, Long>? = null
    private var awaitingAttachResult: Pair<String, Long>? = null
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
                return
            }
            pendingAttach?.also {
                pendingAttach = null
                sendAttach(it)
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
        /*
         * Transport size admission deliberately precedes semantic Brain admission. An invalid
         * object that is also oversized is rejected locally as IPC_ENVELOPE_TOO_LARGE so it can
         * never reach Bundle/Binder. Replacing a run still revokes the old remote identity first.
         * BrainMessageCodec repeats this immutable check as defense for any future direct caller.
         */
        if (
            BrainRequestEnvelopePolicy.assess(request) is
            BrainRequestEnvelopePolicy.Admission.Rejected
        ) {
            activeIdentity?.let { (oldRequest, oldGeneration) ->
                sendCancel(oldRequest, oldGeneration, HarnessCancelReason.CALLER_REQUESTED)
            }
            activeIdentity = null
            pending = null
            clearBindingTimeout()
            if (bound || binding) resetBinding()
            eventSink(
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.IPC_ENVELOPE_TOO_LARGE,
                    retryable = false,
                ),
            )
            return
        }
        activeIdentity?.let { (oldRequest, oldGeneration) ->
            sendCancel(oldRequest, oldGeneration, HarnessCancelReason.CALLER_REQUESTED)
        }
        activeIdentity = request.requestId to request.runGeneration
        pendingAttach = null
        awaitingAttachResult = null
        val service = remote
        if (service != null) {
            sendStart(request)
            return
        }
        pending = request
        scheduleBindingTimeout(request.requestId to request.runGeneration)
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

    /** Reattaches a recreated foreground to a run still owned by the Brain service. */
    fun attach(requestId: String, generation: Long) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SenseAiBrainClient must be driven from the IME main thread"
        }
        val identity = requestId to generation
        if (closed) {
            eventSink(
                AiEvent.Failed(
                    requestId,
                    generation,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = true,
                ),
            )
            return
        }
        activeIdentity = identity
        pending = null
        awaitingAttachResult = null
        val service = remote
        if (service != null) {
            pendingAttach = null
            sendAttach(identity)
            return
        }
        pendingAttach = identity
        scheduleBindingTimeout(identity)
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
                pendingAttach = null
                activeIdentity = null
                eventSink(
                    AiEvent.Failed(
                        requestId,
                        generation,
                        HarnessErrorCode.INTERNAL_FAILURE,
                        retryable = true,
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
        pendingAttach = pendingAttach?.takeUnless { it == (requestId to generation) }
        awaitingAttachResult = awaitingAttachResult?.takeUnless {
            it == (requestId to generation)
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
        pendingAttach = null
        awaitingAttachResult = null
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

    private fun sendAttach(identity: Pair<String, Long>) {
        val message = Message.obtain(null, BrainMessageProtocol.ATTACH).apply {
            data = BrainMessageCodec.encodeIdentity(identity.first, identity.second)
            replyTo = incoming
        }
        try {
            remote?.send(message) ?: error("Brain is not connected")
            awaitingAttachResult = identity
            scheduleBindingTimeout(identity)
        } catch (_: Throwable) {
            resetBinding()
            activeIdentity = null
            eventSink(
                AiEvent.Failed(
                    identity.first,
                    identity.second,
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
        pendingAttach = null
        awaitingAttachResult = null
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

    private fun scheduleBindingTimeout(identity: Pair<String, Long>) {
        clearBindingTimeout()
        val timeout = Runnable {
            bindingTimeout = null
            if (
                closed ||
                remote != null ||
                activeIdentity != identity ||
                (
                    pending?.let { it.requestId to it.runGeneration } != identity &&
                        pendingAttach != identity &&
                        awaitingAttachResult != identity
                )
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
            if (closed) return
            if (message.what == BrainMessageProtocol.ATTACH_RESULT) {
                val (requestId, generation, attached) = runCatching {
                    BrainMessageCodec.decodeAttachResult(message.data)
                }.getOrNull() ?: return
                if (activeIdentity != (requestId to generation)) return
                clearBindingTimeout()
                awaitingAttachResult = null
                if (!attached) {
                    activeIdentity = null
                    eventSink(
                        AiEvent.Failed(
                            requestId,
                            generation,
                            HarnessErrorCode.INTERNAL_FAILURE,
                            retryable = true,
                        ),
                    )
                }
                return
            }
            if (message.what != BrainMessageProtocol.EVENT) return
            val event = runCatching { BrainMessageCodec.decodeEvent(message.data) }.getOrNull()
                ?: return
            if (activeIdentity != (event.requestId to event.runGeneration)) return
            val terminal = event.isTerminal
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
