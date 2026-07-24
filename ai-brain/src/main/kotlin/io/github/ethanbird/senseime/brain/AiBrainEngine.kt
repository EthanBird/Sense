package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessLimits
import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessSession
import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessState
import io.github.ethanbird.senseime.ai.protocol.EditorPatchJsonCodec
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessDispatch
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessPhase
import io.github.ethanbird.senseime.ai.protocol.ProtocolValidator
import io.github.ethanbird.senseime.brain.api.BrainEventSink
import io.github.ethanbird.senseime.brain.api.BrainRunHandle
import io.github.ethanbird.senseime.brain.api.BrainRunSpec
import io.github.ethanbird.senseime.brain.api.CompletedProviderCall
import io.github.ethanbird.senseime.brain.api.MonotonicClock
import io.github.ethanbird.senseime.brain.api.ProviderCall
import io.github.ethanbird.senseime.brain.api.ProviderFailureKind
import io.github.ethanbird.senseime.brain.api.ProviderResponseMetadata
import io.github.ethanbird.senseime.brain.api.ProviderStreamSink
import io.github.ethanbird.senseime.brain.api.ProviderTransport
import io.github.ethanbird.senseime.brain.api.ProviderTransportFailure

/**
 * Provider-neutral M8 editor harness.
 *
 * The engine owns no thread or Android component. A private-process Service injects its transport,
 * forwards [AiEvent]s over Binder, invokes [BrainRunHandle.tick] from a monotonic scheduler, and
 * synchronously cancels the handle when the long-press pointer is released.
 */
class AiBrainEngine(
    private val transport: ProviderTransport,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
) {
    fun start(spec: BrainRunSpec, sink: BrainEventSink): BrainRunHandle {
        spec.provider.requireValid()
        return Run(spec, sink).also(Run::start)
    }

    private inner class Run(
        private val spec: BrainRunSpec,
        private val eventSink: BrainEventSink,
    ) : BrainRunHandle {
        private val lock = Any()
        private val session = BoundedHarnessSession(
            request = spec.harnessRequest,
            limits = BoundedHarnessLimits(
                firstEventTimeoutMs = spec.provider.timeouts.firstEventTimeoutMs,
                streamIdleTimeoutMs = spec.provider.timeouts.streamIdleTimeoutMs,
                totalTimeoutMs = spec.provider.timeouts.totalTimeoutMs,
                maxProviderEvents = MAX_PROVIDER_EVENTS,
                maxPreviewChars = spec.harnessRequest.maxOutputChars,
                maxPreviewResets = 1,
            ),
        )
        private var tokenCounter = 0L
        private var activeToken = -1L
        private var activeAttempt = -1
        private var activeCall: ProviderCall? = null
        private var decoder: OpenAiResponseDecoder? = null
        private var preview: StreamingPatchPreview? = null

        override val requestId: String
            get() = spec.harnessRequest.requestId

        override val runGeneration: Long
            get() = spec.harnessRequest.runGeneration

        override val isTerminal: Boolean
            get() = synchronized(lock) { session.state.isTerminal }

        fun start() {
            val dispatch = synchronized(lock) { session.start(clock.nowMs()) }
            publish(listOf(dispatch))
            if (!isTerminal) openAttempt(attempt = 0, repair = null)
        }

        override fun tick() {
            val outcome = synchronized(lock) {
                val dispatch = session.advanceTo(clock.nowMs())
                terminalOutcome(listOf(dispatch))
            }
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
        }

        override fun cancel(reason: HarnessCancelReason) {
            val outcome = synchronized(lock) {
                val dispatch = session.cancel(
                    requestId = requestId,
                    runGeneration = runGeneration,
                    reason = reason,
                    nowMonotonicMs = clock.nowMs(),
                )
                terminalOutcome(listOf(dispatch))
            }
            // Invalidate before touching a transport. A synchronous late callback is now harmless.
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
        }

        private fun openAttempt(attempt: Int, repair: RepairContext?) {
            val wireRequest = try {
                OpenAiRequestFactory.create(
                    profile = spec.provider,
                    request = spec.harnessRequest,
                    credential = spec.credential,
                    attempt = attempt,
                    repair = repair,
                )
            } catch (_: Exception) {
                failLocally(HarnessErrorCode.INTERNAL_FAILURE, retryable = false)
                return
            }

            val token = synchronized(lock) {
                if (session.state.isTerminal) return
                tokenCounter = Math.addExact(tokenCounter, 1)
                activeToken = tokenCounter
                activeAttempt = attempt
                activeCall = null
                decoder = OpenAiResponseDecoder(
                    apiStyle = spec.provider.apiStyle,
                    streaming = spec.provider.streaming,
                )
                preview = StreamingPatchPreview()
                activeToken
            }
            val returnedCall = try {
                transport.open(wireRequest, AttemptSink(token, attempt))
            } catch (error: Exception) {
                onTransportFailure(
                    token,
                    attempt,
                    ProviderTransportFailure(
                        kind = ProviderFailureKind.INTERNAL,
                        message = error.message ?: "transport open failed",
                    ),
                )
                CompletedProviderCall
            }
            val shouldCancel = synchronized(lock) {
                if (
                    token != activeToken ||
                    attempt != activeAttempt ||
                    session.state.isTerminal
                ) {
                    true
                } else {
                    activeCall = returnedCall
                    false
                }
            }
            if (shouldCancel) returnedCall.cancel()
        }

        private inner class AttemptSink(
            private val token: Long,
            private val attempt: Int,
        ) : ProviderStreamSink {
            override fun onOpen(metadata: ProviderResponseMetadata) =
                onTransportOpen(token, attempt, metadata)

            override fun onBytes(bytes: ByteArray, offset: Int, length: Int) =
                onTransportBytes(token, attempt, bytes, offset, length)

            override fun onComplete() = onTransportComplete(token, attempt)

            override fun onFailure(failure: ProviderTransportFailure) =
                onTransportFailure(token, attempt, failure)
        }

        private fun onTransportOpen(
            token: Long,
            attempt: Int,
            metadata: ProviderResponseMetadata,
        ) {
            val outcome = synchronized(lock) {
                if (!isActive(token, attempt)) return
                if (metadata.statusCode !in 200..299) {
                    return@synchronized failOutcome(
                        HarnessErrorCode.PROVIDER_FAILURE,
                        retryable = metadata.statusCode == 408 ||
                            metadata.statusCode == 429 ||
                            metadata.statusCode >= 500,
                    )
                }
                if (
                    spec.provider.streaming &&
                    metadata.contentType.isJsonContentType()
                ) {
                    // Some compatible endpoints accept stream=true but deliberately fall back to
                    // one JSON document. Decode what the server actually returned.
                    decoder = OpenAiResponseDecoder(
                        apiStyle = spec.provider.apiStyle,
                        streaming = false,
                    )
                }
                val dispatch = session.accept(
                    AiEvent.Status(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        phase = HarnessPhase.UNDERSTANDING,
                        label = "provider_connected",
                    ),
                    clock.nowMs(),
                )
                terminalOutcome(listOf(dispatch))
            }
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
        }

        private fun onTransportBytes(
            token: Long,
            attempt: Int,
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val outcome = synchronized(lock) {
                if (!isActive(token, attempt)) return
                if (length > 0) {
                    val activity = session.noteProviderActivity(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        nowMonotonicMs = clock.nowMs(),
                    )
                    if (activity is HarnessDispatch.Emitted) {
                        return@synchronized terminalOutcome(listOf(activity))
                    }
                }
                val normalized = try {
                    decoder!!.feed(bytes, offset, length)
                } catch (_: ProviderPayloadException) {
                    return@synchronized failOutcome(
                        HarnessErrorCode.PROVIDER_FAILURE,
                        retryable = false,
                    )
                } catch (_: Exception) {
                    return@synchronized failOutcome(
                        HarnessErrorCode.INTERNAL_FAILURE,
                        retryable = false,
                    )
                }
                consumeNormalized(normalized, attempt)
            }
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
            outcome.repair?.let { openAttempt(attempt = 1, repair = it) }
        }

        private fun onTransportComplete(token: Long, attempt: Int) {
            val outcome = synchronized(lock) {
                if (!isActive(token, attempt)) return
                val normalized = try {
                    decoder!!.finish()
                } catch (_: ProviderPayloadException) {
                    return@synchronized failOutcome(
                        HarnessErrorCode.PROVIDER_FAILURE,
                        retryable = false,
                    )
                } catch (_: Exception) {
                    return@synchronized failOutcome(
                        HarnessErrorCode.INTERNAL_FAILURE,
                        retryable = false,
                    )
                }
                consumeNormalized(normalized, attempt)
            }
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
            outcome.repair?.let { openAttempt(attempt = 1, repair = it) }
        }

        private fun onTransportFailure(
            token: Long,
            attempt: Int,
            failure: ProviderTransportFailure,
        ) {
            val outcome = synchronized(lock) {
                if (!isActive(token, attempt)) return
                val code = when (failure.kind) {
                    ProviderFailureKind.CONNECT_TIMEOUT -> HarnessErrorCode.FIRST_EVENT_TIMEOUT
                    ProviderFailureKind.READ_TIMEOUT -> HarnessErrorCode.STREAM_IDLE_TIMEOUT
                    ProviderFailureKind.CANCELLED -> HarnessErrorCode.PROVIDER_FAILURE
                    else -> HarnessErrorCode.PROVIDER_FAILURE
                }
                failOutcome(code, failure.retryable)
            }
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
        }

        private fun consumeNormalized(
            normalized: List<ProviderContentEvent>,
            attempt: Int,
        ): Outcome {
            val dispatches = mutableListOf<HarnessDispatch>()
            for (event in normalized) {
                if (session.state.isTerminal) break
                when (event) {
                    is ProviderContentEvent.TextDelta -> {
                        val visible = try {
                            preview!!.append(event.text)
                        } catch (_: ProviderPayloadException) {
                            return failOutcome(
                                HarnessErrorCode.PREVIEW_LIMIT_EXCEEDED,
                                retryable = false,
                                prior = dispatches,
                            )
                        }
                        if (visible.isNotEmpty()) {
                            dispatches += session.accept(
                                AiEvent.PreviewDelta(
                                    requestId = requestId,
                                    runGeneration = runGeneration,
                                    text = visible,
                                ),
                                clock.nowMs(),
                            )
                        }
                    }
                    is ProviderContentEvent.Usage -> dispatches += session.accept(
                        AiEvent.Usage(
                            requestId = requestId,
                            runGeneration = runGeneration,
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                        ),
                        clock.nowMs(),
                    )
                    is ProviderContentEvent.Error -> return failOutcome(
                        HarnessErrorCode.PROVIDER_FAILURE,
                        event.retryable,
                        prior = dispatches,
                    )
                    is ProviderContentEvent.Completed -> {
                        if (
                            preview!!.fullDocument().isEmpty() &&
                            !event.finalText.isNullOrEmpty()
                        ) {
                            preview!!.append(event.finalText)
                        }
                        return finalizePatch(attempt, dispatches)
                    }
                }
            }
            return terminalOutcome(dispatches)
        }

        private fun finalizePatch(
            attempt: Int,
            prior: MutableList<HarnessDispatch>,
        ): Outcome {
            val document = preview!!.fullDocument()
            val decoded = EditorPatchJsonCodec.decode(document)
            val errors = when (decoded) {
                is io.github.ethanbird.senseime.ai.protocol.PatchDecodeResult.Failure ->
                    decoded.errors
                is io.github.ethanbird.senseime.ai.protocol.PatchDecodeResult.Success -> {
                    val validation = ProtocolValidator.validate(
                        decoded.patch,
                        spec.harnessRequest.snapshot,
                    )
                    if (validation.isValid) {
                        prior += session.accept(
                            AiEvent.FinalPatch(
                                requestId = requestId,
                                runGeneration = runGeneration,
                                patch = decoded.patch,
                            ),
                            clock.nowMs(),
                        )
                        return terminalOutcome(prior)
                    }
                    validation.errors
                }
            }

            if (attempt == 0) {
                prior += session.accept(
                    AiEvent.PreviewReset(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        // BoundedHarnessSession numbers the initial stream as attempt 1.
                        attempt = 2,
                    ),
                    clock.nowMs(),
                )
                val oldCall = invalidateActive()
                return Outcome(
                    dispatches = prior,
                    cancelCall = oldCall,
                    repair = RepairContext(
                        rejectedDocument = document,
                        validationSummary = errors.joinToString("; ") {
                            "${it.path}: ${it.message}"
                        },
                    ),
                )
            }
            return failOutcome(
                HarnessErrorCode.PROTOCOL_INVALID,
                retryable = false,
                prior = prior,
            )
        }

        private fun failLocally(code: HarnessErrorCode, retryable: Boolean) {
            val outcome = synchronized(lock) { failOutcome(code, retryable) }
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
        }

        private fun failOutcome(
            code: HarnessErrorCode,
            retryable: Boolean,
            prior: MutableList<HarnessDispatch> = mutableListOf(),
        ): Outcome {
            if (!session.state.isTerminal) {
                prior += session.accept(
                    AiEvent.Failed(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        code = code,
                        retryable = retryable,
                    ),
                    clock.nowMs(),
                )
            }
            return terminalOutcome(prior)
        }

        private fun terminalOutcome(dispatches: List<HarnessDispatch>): Outcome {
            if (!session.state.isTerminal) return Outcome(dispatches)
            return Outcome(dispatches, cancelCall = invalidateActive())
        }

        /**
         * Must be called under [lock]. The token is invalidated before the transport is cancelled.
         */
        private fun invalidateActive(): ProviderCall? {
            activeToken = -1L
            activeAttempt = -1
            decoder = null
            preview = null
            return activeCall.also { activeCall = null }
        }

        private fun isActive(token: Long, attempt: Int): Boolean =
            !session.state.isTerminal &&
                token == activeToken &&
                attempt == activeAttempt

        private fun publish(dispatches: List<HarnessDispatch>) {
            dispatches.forEach { dispatch ->
                if (dispatch is HarnessDispatch.Emitted) eventSink.onEvent(dispatch.event)
            }
        }

        private fun String?.isJsonContentType(): Boolean {
            val mediaType = this?.substringBefore(';')?.trim()?.lowercase() ?: return false
            return mediaType == "application/json" || mediaType.endsWith("+json")
        }
    }

    private data class Outcome(
        val dispatches: List<HarnessDispatch>,
        val cancelCall: ProviderCall? = null,
        val repair: RepairContext? = null,
    )

    private companion object {
        const val MAX_PROVIDER_EVENTS = 4_096
    }
}
