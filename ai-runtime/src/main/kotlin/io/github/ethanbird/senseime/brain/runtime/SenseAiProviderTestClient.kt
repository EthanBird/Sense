package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs a small, real end-to-end test against the provider configuration saved for private Brain.
 *
 * The probe uses fixed synthetic text; it never reads an InputConnection or sends user editor
 * content. Success is reported only after URL, authentication, model, request format, response
 * streaming/decoding and the strict `sense.editor.patch.v1` validator all succeed.
 *
 * Public methods may be called from any thread. Work and [eventSink] callbacks are serialized on
 * the main thread. Calling [start] again supersedes the prior probe. [close] suppresses all future
 * callbacks and may also be called from any thread.
 */
class SenseAiProviderTestClient(
    context: Context,
    private val eventSink: (ProviderConnectionTestEvent) -> Unit,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gate = ProviderConnectionTestRunGate()
    private val closed = AtomicBoolean(false)
    private val generations = AtomicLong(0L)
    private val brainClient = SenseAiBrainClient(context, ::onBrainEvent)
    private var inputTokens: Long? = null
    private var outputTokens: Long? = null
    private var startedAtMonotonicMs: Long? = null

    /**
     * Saves no data and accepts no credential. Brain loads the already-encrypted saved Provider
     * configuration inside its private process.
     */
    fun start() {
        if (closed.get()) return
        onMain {
            if (closed.get()) return@onMain
            val identity = ProviderConnectionTestIdentity(
                requestId = "provider-test-${UUID.randomUUID()}",
                generation = nextGeneration(),
            )
            val superseded = gate.replace(identity)
            if (superseded != null) {
                brainClient.cancel(
                    superseded.requestId,
                    superseded.generation,
                    HarnessCancelReason.CALLER_REQUESTED,
                )
            }
            inputTokens = null
            outputTokens = null
            startedAtMonotonicMs = SystemClock.elapsedRealtime()
            val request = ProviderConnectionTestProtocol.buildRequest(
                requestId = identity.requestId,
                generation = identity.generation,
                capturedAtMonotonicMs = SystemClock.elapsedRealtime(),
            )
            brainClient.start(request)
            // Start Brain before invoking application code so a callback that immediately starts
            // another probe cannot be overwritten when this stack frame resumes.
            if (gate.isActive(identity.requestId, identity.generation)) {
                emit(ProviderConnectionTestEvent.Starting)
            }
        }
    }

    /** Cancels the active probe, if any, and revokes its authority before crossing Binder. */
    fun cancel() {
        if (closed.get()) return
        onMain {
            if (closed.get()) return@onMain
            val cancelled = gate.revoke() ?: return@onMain
            inputTokens = null
            outputTokens = null
            startedAtMonotonicMs = null
            brainClient.cancel(
                cancelled.requestId,
                cancelled.generation,
                HarnessCancelReason.CALLER_REQUESTED,
            )
            emit(ProviderConnectionTestEvent.Cancelled)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onMain {
            // close() itself cancels the active Brain identity. Revoke the public callback
            // authority first so a synchronous or queued terminal event cannot escape.
            gate.revoke()
            inputTokens = null
            outputTokens = null
            startedAtMonotonicMs = null
            brainClient.close()
        }
    }

    private fun onBrainEvent(event: AiEvent) {
        if (closed.get() || !gate.isActive(event.requestId, event.runGeneration)) return
        when (event) {
            is AiEvent.Started -> emit(
                ProviderConnectionTestEvent.Progress(
                    ProviderConnectionTestPhase.CONNECTING,
                ),
            )

            is AiEvent.Status -> emit(
                ProviderConnectionTestEvent.Progress(
                    ProviderConnectionTestProtocol.mapPhase(event.phase),
                ),
            )

            is AiEvent.Usage -> {
                inputTokens = event.inputTokens
                outputTokens = event.outputTokens
            }

            is AiEvent.FinalPatch -> {
                if (gate.takeIfActive(event.requestId, event.runGeneration) == null) return
                val succeeded = ProviderConnectionTestEvent.Succeeded(
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    elapsedMs = startedAtMonotonicMs
                        ?.let { started ->
                            (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                        }
                        ?: 0L,
                )
                inputTokens = null
                outputTokens = null
                startedAtMonotonicMs = null
                emit(
                    succeeded,
                )
            }

            is AiEvent.Failed -> {
                if (gate.takeIfActive(event.requestId, event.runGeneration) == null) return
                inputTokens = null
                outputTokens = null
                startedAtMonotonicMs = null
                emit(
                    ProviderConnectionTestEvent.Failed(
                        failure = ProviderConnectionTestProtocol.mapFailure(event.code),
                        retryable = event.retryable,
                    ),
                )
            }

            is AiEvent.Cancelled -> {
                // Brain-originated cancellation is terminal, but only explicit public cancel()
                // renders a cancellation state. Unexpected remote cancellation is an internal
                // connectivity failure rather than a misleading user action.
                if (gate.takeIfActive(event.requestId, event.runGeneration) == null) return
                inputTokens = null
                outputTokens = null
                startedAtMonotonicMs = null
                emit(
                    ProviderConnectionTestEvent.Failed(
                        failure = ProviderConnectionTestFailure.INTERNAL,
                        retryable = true,
                    ),
                )
            }

            is AiEvent.DescriptionDelta,
            is AiEvent.PreviewDelta,
            is AiEvent.PreviewReset,
            is AiEvent.PreviewReplace,
            -> Unit
        }
    }

    private fun emit(event: ProviderConnectionTestEvent) {
        if (closed.get()) return
        // A consumer exception must not crash the main looper or resurrect a finished Brain run.
        runCatching { eventSink(event) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun nextGeneration(): Long {
        while (true) {
            val current = generations.get()
            val next = if (current == Long.MAX_VALUE) 1L else current + 1L
            if (generations.compareAndSet(current, next)) return next
        }
    }
}
