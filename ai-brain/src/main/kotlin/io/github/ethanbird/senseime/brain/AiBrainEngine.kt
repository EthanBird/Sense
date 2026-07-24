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
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.ProtocolValidator
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.brain.api.BrainEventSink
import io.github.ethanbird.senseime.brain.api.BrainRunHandle
import io.github.ethanbird.senseime.brain.api.BrainRunSpec
import io.github.ethanbird.senseime.brain.api.CompletedProviderCall
import io.github.ethanbird.senseime.brain.api.MonotonicClock
import io.github.ethanbird.senseime.brain.api.ProviderCall
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
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
    fun start(
        spec: BrainRunSpec,
        sink: BrainEventSink,
        requestMode: BrainRequestMode = BrainRequestMode.NORMAL,
    ): BrainRunHandle {
        spec.provider.requireValid()
        return Run(spec, sink, requestMode).also(Run::start)
    }

    private inner class Run(
        private val spec: BrainRunSpec,
        private val eventSink: BrainEventSink,
        private val requestMode: BrainRequestMode,
    ) : BrainRunHandle {
        private val lock = Any()
        private val session = BoundedHarnessSession(
            request = spec.harnessRequest,
            limits = harnessLimits(spec, requestMode),
        )
        private var tokenCounter = 0L
        private var activeToken = -1L
        private var activeAttempt = -1
        private var activeCall: ProviderCall? = null
        private var decoder: OpenAiResponseDecoder? = null
        private var preview: StreamingPatchPreview? = null
        private var nativeTool: NativePatchToolAccumulator? = null
        private var nativeToolIndex: Int? = null
        private var nativeToolId: String? = null
        private var nativeToolName: String? = null
        private var nativeToolError: String? = null
        private var generationStatusEmitted = false
        private val emittedDescription = StringBuilder()
        private val emittedPreview = StringBuilder()
        private var retryVisible: StableRetryVisibleStream? = null
        private val usesNativePatchTool =
            spec.provider.apiStyle ==
                io.github.ethanbird.senseime.brain.api.ProviderApiStyle
                    .OPENAI_COMPATIBLE_CHAT_COMPLETIONS &&
                ProviderCompatibility.isOfficialDeepSeek(spec.provider.baseUrl)

        override val requestId: String
            get() = spec.harnessRequest.requestId

        override val runGeneration: Long
            get() = spec.harnessRequest.runGeneration

        override val isTerminal: Boolean
            get() = synchronized(lock) { session.state.isTerminal }

        fun start() {
            val dispatch = synchronized(lock) { session.start(clock.nowMs()) }
            publish(listOf(dispatch))
            if (!isTerminal) openAttempt(attempt = 0, secondAttempt = null)
        }

        override fun tick() {
            val outcome = synchronized(lock) {
                val now = clock.nowMs()
                val pending = session.pendingTimeoutCode(now)
                if (
                    activeAttempt == 0 &&
                    pending != null &&
                    pending.isAutomaticRecoveryEligible()
                ) {
                    startSecondAttemptOutcome(
                        context = streamRecoveryContext("watchdog_${pending.name.lowercase()}"),
                        prior = mutableListOf(),
                        nowMonotonicMs = now,
                    )
                } else {
                    val dispatch = session.advanceTo(now)
                    terminalOutcome(listOf(dispatch))
                }
            }
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
            outcome.secondAttempt?.let { openAttempt(attempt = 1, secondAttempt = it) }
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

        private fun openAttempt(
            attempt: Int,
            secondAttempt: SecondAttemptContext?,
        ) {
            val wireRequest = try {
                OpenAiRequestFactory.create(
                    profile = spec.provider,
                    request = spec.harnessRequest,
                    credential = spec.credential,
                    attempt = attempt,
                    secondAttempt = secondAttempt,
                    requestMode = requestMode,
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
                nativeTool = if (usesNativePatchTool) NativePatchToolAccumulator() else null
                nativeToolIndex = null
                nativeToolId = null
                nativeToolName = null
                nativeToolError = null
                generationStatusEmitted = false
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
                    val failure = ProviderErrorClassifier.fromHttpStatus(metadata.statusCode)
                    return@synchronized if (
                        attempt == 0 &&
                        failure.retryable &&
                        failure.code.isAutomaticRecoveryEligible()
                    ) {
                        startSecondAttemptOutcome(
                            context = streamRecoveryContext(
                                "http_${metadata.statusCode}",
                            ),
                            prior = mutableListOf(),
                        )
                    } else {
                        failOutcome(
                            failure.code,
                            retryable = failure.retryable,
                        )
                    }
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
            outcome.secondAttempt?.let { openAttempt(attempt = 1, secondAttempt = it) }
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
            outcome.secondAttempt?.let { openAttempt(attempt = 1, secondAttempt = it) }
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
            outcome.secondAttempt?.let { openAttempt(attempt = 1, secondAttempt = it) }
        }

        private fun onTransportFailure(
            token: Long,
            attempt: Int,
            failure: ProviderTransportFailure,
        ) {
            val outcome = synchronized(lock) {
                if (!isActive(token, attempt)) return
                val classified = when (failure.kind) {
                    ProviderFailureKind.CONNECT_TIMEOUT -> ClassifiedProviderError(
                        HarnessErrorCode.FIRST_EVENT_TIMEOUT,
                        retryable = true,
                    )
                    ProviderFailureKind.READ_TIMEOUT -> ClassifiedProviderError(
                        HarnessErrorCode.STREAM_IDLE_TIMEOUT,
                        retryable = true,
                    )
                    ProviderFailureKind.HTTP_STATUS ->
                        failure.statusCode?.let(ProviderErrorClassifier::fromHttpStatus)
                            ?: ClassifiedProviderError(
                                HarnessErrorCode.PROVIDER_FAILURE,
                                failure.retryable,
                            )
                    ProviderFailureKind.INTERNAL -> ClassifiedProviderError(
                        HarnessErrorCode.INTERNAL_FAILURE,
                        retryable = false,
                    )
                    ProviderFailureKind.CANCELLED,
                    ProviderFailureKind.IO,
                    ProviderFailureKind.MALFORMED_RESPONSE,
                    ProviderFailureKind.RESPONSE_TOO_LARGE -> ClassifiedProviderError(
                        HarnessErrorCode.PROVIDER_FAILURE,
                        failure.retryable,
                    )
                }
                if (
                    attempt == 0 &&
                    classified.retryable &&
                    classified.code.isAutomaticRecoveryEligible()
                ) {
                    startSecondAttemptOutcome(
                        context = streamRecoveryContext(
                            "transport_${failure.kind.name.lowercase()}",
                        ),
                        prior = mutableListOf(),
                    )
                } else {
                    failOutcome(classified.code, classified.retryable)
                }
            }
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
            outcome.secondAttempt?.let { openAttempt(attempt = 1, secondAttempt = it) }
        }

        private fun consumeNormalized(
            normalized: List<ProviderContentEvent>,
            attempt: Int,
        ): Outcome {
            val dispatches = mutableListOf<HarnessDispatch>()
            for (event in normalized) {
                if (session.state.isTerminal) break
                when (event) {
                    ProviderContentEvent.ReasoningActivity -> {
                        emitGeneratingStatus(dispatches, "provider_reasoning")
                    }
                    is ProviderContentEvent.ToolCallDelta -> {
                        if (!usesNativePatchTool) {
                            return failOutcome(
                                HarnessErrorCode.PROTOCOL_INVALID,
                                retryable = false,
                                prior = dispatches,
                            )
                        }
                        emitGeneratingStatus(dispatches, "provider_generating")
                        consumeNativeToolDelta(event, dispatches)
                    }
                    is ProviderContentEvent.TextDelta -> {
                        emitGeneratingStatus(dispatches, "provider_generating")
                        val visible = try {
                            // Ordinary assistant content can never authorize an edit when the
                            // provider was given the native terminal tool. It is retained only as
                            // bounded repair evidence and is never shown as the generated result.
                            val extracted = preview!!.append(event.text)
                            if (usesNativePatchTool) "" else extracted
                        } catch (_: ProviderPayloadException) {
                            return failOutcome(
                                HarnessErrorCode.PREVIEW_LIMIT_EXCEEDED,
                                retryable = false,
                                prior = dispatches,
                            )
                        }
                        if (visible.isNotEmpty()) {
                            emitVisiblePreview(visible, dispatches)
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
                    is ProviderContentEvent.Error -> {
                        val failure = ProviderErrorClassifier.fromProviderPayload(
                            message = event.message,
                            type = event.type,
                            providerCode = event.providerCode,
                            statusCode = event.statusCode,
                            providerRetryable = event.retryable,
                        )
                        return if (
                            attempt == 0 &&
                            failure.retryable &&
                            failure.code.isAutomaticRecoveryEligible()
                        ) {
                            startSecondAttemptOutcome(
                                context = streamRecoveryContext(
                                    event.providerCode ?: "provider_stream_error",
                                ),
                                prior = dispatches,
                            )
                        } else {
                            failOutcome(
                                failure.code,
                                failure.retryable,
                                prior = dispatches,
                            )
                        }
                    }
                    is ProviderContentEvent.Completed -> {
                        if (!usesNativePatchTool &&
                            preview!!.fullDocument().isEmpty() &&
                            !event.finalText.isNullOrEmpty()
                        ) {
                            val visible = preview!!.append(event.finalText)
                            if (visible.isNotEmpty()) {
                                emitVisiblePreview(visible, dispatches)
                            }
                        }
                        finishRetryVisibility(dispatches)
                        if (session.state.isTerminal) return terminalOutcome(dispatches)
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
            prior += session.accept(
                AiEvent.Status(
                    requestId = requestId,
                    runGeneration = runGeneration,
                    phase = HarnessPhase.VALIDATING,
                    label = "validating_patch",
                ),
                clock.nowMs(),
            )
            if (session.state.isTerminal) return terminalOutcome(prior)

            val nativeFailure = nativeToolError
            val document = if (usesNativePatchTool && nativeFailure == null) {
                if (
                    nativeToolIndex == null ||
                    nativeToolName != OpenAiRequestFactory.NATIVE_PATCH_TOOL_NAME
                ) {
                    return repairOrFail(
                        attempt = attempt,
                        prior = prior,
                        rejectedDocument = nativeTool?.fullDocument()
                            ?.takeIf(String::isNotEmpty)
                            ?: preview!!.fullDocument(),
                        validationSummary = "terminal sense_submit_patch tool call is missing",
                    )
                }
                try {
                    nativeTool!!.finish().patchDocument
                } catch (error: ProviderPayloadException) {
                    return repairOrFail(
                        attempt = attempt,
                        prior = prior,
                        rejectedDocument = nativeTool!!.fullDocument(),
                        validationSummary = error.message ?: "invalid native tool arguments",
                    )
                }
            } else if (usesNativePatchTool) {
                return repairOrFail(
                    attempt = attempt,
                    prior = prior,
                    rejectedDocument = nativeTool?.fullDocument().orEmpty(),
                    validationSummary = nativeFailure ?: "invalid native tool call",
                )
            } else {
                preview!!.fullDocument()
            }
            val decoded = EditorPatchJsonCodec.decode(document)
            val validationSummary = when (decoded) {
                is io.github.ethanbird.senseime.ai.protocol.PatchDecodeResult.Failure ->
                    decoded.errors.joinToString("; ") { "${it.path}: ${it.message}" }
                is io.github.ethanbird.senseime.ai.protocol.PatchDecodeResult.Success -> {
                    val validation = ProtocolValidator.validate(
                        decoded.patch,
                        spec.harnessRequest.snapshot,
                    )
                    val intentMatches =
                        decoded.patch.operation.type == PatchOperationType.NO_CHANGE ||
                            decoded.patch.intent == spec.harnessRequest.skill
                    if (validation.isValid && intentMatches) {
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
                    buildList {
                        addAll(
                            validation.errors.map { "${it.path}: ${it.message}" },
                        )
                        if (!intentMatches) {
                            add(
                                "$.intent: replace intent must equal requested skill " +
                                    spec.harnessRequest.skill.wireValue,
                            )
                        }
                    }.joinToString("; ")
                }
            }

            return repairOrFail(
                attempt = attempt,
                prior = prior,
                rejectedDocument = document,
                validationSummary = validationSummary,
            )
        }

        private fun repairOrFail(
            attempt: Int,
            prior: MutableList<HarnessDispatch>,
            rejectedDocument: String,
            validationSummary: String,
        ): Outcome {
            if (attempt == 0) {
                return startSecondAttemptOutcome(
                    context = RepairContext(
                        rejectedDocument = rejectedDocument,
                        validationSummary = validationSummary,
                    ),
                    prior = prior,
                )
            }
            return failOutcome(
                HarnessErrorCode.PROTOCOL_INVALID,
                retryable = false,
                prior = prior,
            )
        }

        private fun emitGeneratingStatus(
            dispatches: MutableList<HarnessDispatch>,
            label: String,
        ) {
            if (generationStatusEmitted || session.state.isTerminal) return
            generationStatusEmitted = true
            dispatches += session.accept(
                AiEvent.Status(
                    requestId = requestId,
                    runGeneration = runGeneration,
                    phase = HarnessPhase.GENERATING,
                    label = label,
                ),
                clock.nowMs(),
            )
        }

        private fun consumeNativeToolDelta(
            event: ProviderContentEvent.ToolCallDelta,
            dispatches: MutableList<HarnessDispatch>,
        ) {
            if (nativeToolError != null) return
            val currentIndex = nativeToolIndex
            if (currentIndex != null && currentIndex != event.index) {
                nativeToolError = "multiple native tool calls are not allowed"
                return
            }
            nativeToolIndex = event.index
            event.id?.let { incoming ->
                val current = nativeToolId
                if (current != null && current != incoming) {
                    nativeToolError = "native tool call identity changed"
                    return
                }
                nativeToolId = incoming
            }
            event.name?.let { incoming ->
                val current = nativeToolName
                if (current != null && current != incoming) {
                    nativeToolError = "native tool name changed"
                    return
                }
                nativeToolName = incoming
            }
            if (event.arguments.isEmpty()) return
            val visible = try {
                nativeTool!!.append(event.arguments)
            } catch (error: ProviderPayloadException) {
                nativeToolError = error.message ?: "invalid native tool argument stream"
                return
            }
            if (visible.description.isNotEmpty()) {
                emitVisibleDescription(visible.description, dispatches)
            }
            if (visible.patchText.isNotEmpty() && !session.state.isTerminal) {
                emitVisiblePreview(visible.patchText, dispatches)
            }
        }

        /**
         * Starts the only second provider call while preserving the original harness authority.
         *
         * Must be called under [lock]. The old token is invalidated before its socket is cancelled;
         * late callbacks therefore cannot append to the retry or reach FinalPatch.
         */
        private fun startSecondAttemptOutcome(
            context: SecondAttemptContext,
            prior: MutableList<HarnessDispatch>,
            nowMonotonicMs: Long = clock.nowMs(),
        ): Outcome {
            if (activeAttempt != 0 || session.state.isTerminal) {
                return failOutcome(
                    HarnessErrorCode.PROVIDER_FAILURE,
                    retryable = false,
                    prior = prior,
                )
            }
            prior += session.recoverProviderStream(
                requestId = requestId,
                runGeneration = runGeneration,
                attempt = 2,
                statusLabel = when (context) {
                    is RepairContext -> "provider_repairing"
                    is StreamRecoveryContext -> "provider_recovering"
                },
                nowMonotonicMs = nowMonotonicMs,
            )
            if (session.state.isTerminal) return terminalOutcome(prior)

            retryVisible = StableRetryVisibleStream(
                firstDescription = emittedDescription.toString(),
                firstPreview = emittedPreview.toString(),
            )
            val oldCall = invalidateActive()
            return Outcome(
                dispatches = prior,
                cancelCall = oldCall,
                secondAttempt = context,
            )
        }

        private fun streamRecoveryContext(reason: String): StreamRecoveryContext =
            StreamRecoveryContext(
                interruptedDocument = currentAttemptDocument(),
                stableDescription = emittedDescription.toString(),
                stablePreview = emittedPreview.toString(),
                reason = reason,
            )

        private fun currentAttemptDocument(): String =
            if (usesNativePatchTool) {
                nativeTool?.fullDocument().orEmpty()
            } else {
                preview?.fullDocument().orEmpty()
            }

        private fun emitVisibleDescription(
            text: String,
            dispatches: MutableList<HarnessDispatch>,
        ) {
            val reconciler = retryVisible
            val update = if (reconciler == null) {
                StableRetryVisibleUpdate(description = text)
            } else {
                reconciler.appendDescription(text)
            }
            emitVisibleUpdate(update, dispatches)
        }

        private fun emitVisiblePreview(
            text: String,
            dispatches: MutableList<HarnessDispatch>,
        ) {
            val reconciler = retryVisible
            val update = if (reconciler == null) {
                StableRetryVisibleUpdate(preview = text)
            } else {
                reconciler.appendPreview(text)
            }
            emitVisibleUpdate(update, dispatches)
        }

        private fun finishRetryVisibility(dispatches: MutableList<HarnessDispatch>) {
            retryVisible?.finish()?.let { emitVisibleUpdate(it, dispatches) }
        }

        private fun emitVisibleUpdate(
            update: StableRetryVisibleUpdate,
            dispatches: MutableList<HarnessDispatch>,
        ) {
            if (update.replace) {
                emittedDescription.setLength(0)
                emittedDescription.append(update.description)
                emittedPreview.setLength(0)
                emittedPreview.append(update.preview)
                dispatches += session.accept(
                    AiEvent.PreviewReplace(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        attempt = 2,
                        text = update.preview,
                        description = update.description,
                    ),
                    clock.nowMs(),
                )
                return
            }
            if (update.description.isNotEmpty()) {
                emittedDescription.append(update.description)
                dispatches += session.accept(
                    AiEvent.DescriptionDelta(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        text = update.description,
                    ),
                    clock.nowMs(),
                )
            }
            if (update.preview.isNotEmpty() && !session.state.isTerminal) {
                emittedPreview.append(update.preview)
                dispatches += session.accept(
                    AiEvent.PreviewDelta(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        text = update.preview,
                    ),
                    clock.nowMs(),
                )
            }
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
            nativeTool = null
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

        private fun HarnessErrorCode.isAutomaticRecoveryEligible(): Boolean = when (this) {
            HarnessErrorCode.FIRST_EVENT_TIMEOUT,
            HarnessErrorCode.STREAM_IDLE_TIMEOUT,
            HarnessErrorCode.PROVIDER_UNAVAILABLE,
            HarnessErrorCode.PROVIDER_FAILURE,
            -> true
            else -> false
        }
    }

    private data class Outcome(
        val dispatches: List<HarnessDispatch>,
        val cancelCall: ProviderCall? = null,
        val secondAttempt: SecondAttemptContext? = null,
    )

    private companion object {
        const val MIN_PROVIDER_EVENTS = 4_096
        const val PROVIDER_EVENT_OVERHEAD = 2_048
        const val CONNECTIVITY_TOTAL_TIMEOUT_MS = 30_000L

        fun harnessLimits(
            spec: BrainRunSpec,
            requestMode: BrainRequestMode,
        ): BoundedHarnessLimits {
            val configured = spec.provider.timeouts
            val total = if (requestMode == BrainRequestMode.CONNECTIVITY_TEST) {
                minOf(configured.totalTimeoutMs, CONNECTIVITY_TOTAL_TIMEOUT_MS)
            } else {
                configured.totalTimeoutMs
            }
            val boundedOutput = spec.harnessRequest.maxOutputChars
                .coerceIn(0, SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS)
            val eventBudget = maxOf(
                MIN_PROVIDER_EVENTS,
                boundedOutput + PROVIDER_EVENT_OVERHEAD,
            )
            return BoundedHarnessLimits(
                firstEventTimeoutMs = minOf(configured.firstEventTimeoutMs, total),
                streamIdleTimeoutMs = minOf(configured.streamIdleTimeoutMs, total),
                totalTimeoutMs = total,
                maxProviderEvents = eventBudget,
                maxPreviewChars = maxOf(1, boundedOutput),
                maxPreviewResets = 1,
            )
        }
    }
}
