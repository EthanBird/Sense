package io.github.ethanbird.senseime.brain

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.AgentProgressKind
import io.github.ethanbird.senseime.ai.protocol.AgentProgressState
import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessLimits
import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessSession
import io.github.ethanbird.senseime.ai.protocol.BoundedHarnessState
import io.github.ethanbird.senseime.ai.protocol.EditorPatchJsonCodec
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessDispatch
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.HarnessPhase
import io.github.ethanbird.senseime.ai.protocol.HarnessResultMode
import io.github.ethanbird.senseime.ai.protocol.PatchOperationType
import io.github.ethanbird.senseime.ai.protocol.ProtocolValidator
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.brain.api.BrainEventSink
import io.github.ethanbird.senseime.brain.api.BrainRunHandle
import io.github.ethanbird.senseime.brain.api.BrainRunSpec
import io.github.ethanbird.senseime.brain.api.BrainTraceEvent
import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import io.github.ethanbird.senseime.brain.api.AgentToolCall
import io.github.ethanbird.senseime.brain.api.AgentToolExecutionResult
import io.github.ethanbird.senseime.brain.api.AgentToolExecutor
import io.github.ethanbird.senseime.brain.api.AgentToolId
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalogSnapshot
import io.github.ethanbird.senseime.brain.api.AgentSkillSummary
import io.github.ethanbird.senseime.brain.api.CompletedProviderCall
import io.github.ethanbird.senseime.brain.api.MonotonicClock
import io.github.ethanbird.senseime.brain.api.ProviderCall
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
import io.github.ethanbird.senseime.brain.api.ProviderFailureKind
import io.github.ethanbird.senseime.brain.api.ProviderResponseMetadata
import io.github.ethanbird.senseime.brain.api.ProviderStreamSink
import io.github.ethanbird.senseime.brain.api.ProviderTransport
import io.github.ethanbird.senseime.brain.api.ProviderTransportFailure
import java.nio.charset.StandardCharsets

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
    private val toolExecutor: AgentToolExecutor = AgentToolExecutor.UNAVAILABLE,
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
        private val nativeToolArguments = StringBuilder()
        private val privateReasoning = StringBuilder()
        private val privateResponsesReasoningItems = mutableListOf<String>()
        private var privateResponsesReasoningChars = 0
        private val assistantContent = StringBuilder()
        private var nativeToolIndex: Int? = null
        private var nativeToolId: String? = null
        private var nativeToolName: String? = null
        private var nativeToolError: String? = null
        private var nativeToolProgressStarted = false
        private var lastHarnessPhase: HarnessPhase? = null
        private var reasoningProgressEmitted = false
        private var draftingProgressEmitted = false
        private var agentProgressRevision = 0L
        private var agentProgressTurns = 0
        private var agentToolTurns = 0
        private var toolExecutionCounter = 0L
        private var activeToolExecutionToken = -1L
        private var authorizedSkillCatalogGeneration =
            spec.skillCatalogGeneration
                ?: spec.harnessRequest.skillCatalogGeneration
                ?: spec.harnessRequest.activeSkill?.catalogGeneration
        private var authorizedSkillCatalog = spec.skillCatalog.toList()
        private var agentConversation = AgentConversationContext(emptyList())
        private val runStartedAtMs = clock.nowMs()
        private var lastHeartbeatAtMs = runStartedAtMs
        private var heartbeatTitle = "正在连接模型"
        private val emittedDescription = StringBuilder()
        private val emittedPreview = StringBuilder()
        private var retryVisible: StableRetryVisibleStream? = null
        private val requiresEditorPatch =
            spec.harnessRequest.resultMode == HarnessResultMode.EDITOR_PATCH
        private val usesNativeToolProtocol =
            spec.provider.apiStyle ==
                io.github.ethanbird.senseime.brain.api.ProviderApiStyle
                    .OPENAI_COMPATIBLE_CHAT_COMPLETIONS &&
                ProviderCompatibility.isOfficialDeepSeek(spec.provider.baseUrl)
        private val usesNativePatchTool = usesNativeToolProtocol && requiresEditorPatch
        private val acceptsAgentToolCalls = OpenAiRequestFactory.exposedAgentTools(
            enabledTools = spec.enabledTools,
            requestMode = requestMode,
            secondAttempt = null,
            conversation = null,
            skillCatalogGeneration = spec.skillCatalogGeneration
                ?: spec.harnessRequest.skillCatalogGeneration
                ?: spec.harnessRequest.activeSkill?.catalogGeneration,
            hasReadableSkills = spec.skillCatalog.isNotEmpty(),
        ).isNotEmpty()

        override val requestId: String
            get() = spec.harnessRequest.requestId

        override val runGeneration: Long
            get() = spec.harnessRequest.runGeneration

        override val isTerminal: Boolean
            get() = synchronized(lock) { session.state.isTerminal }

        fun start() {
            val dispatches = synchronized(lock) {
                val now = clock.nowMs()
                mutableListOf(session.start(now)).also { output ->
                    if (!session.state.isTerminal) {
                        output += emitAgentProgress(
                            kind = AgentProgressKind.OBSERVATION,
                            state = AgentProgressState.COMPLETED,
                            stepId = "snapshot",
                            title = "已安全读取并冻结当前输入框",
                            nowMonotonicMs = now,
                        )
                    }
                }
            }
            publish(dispatches)
            if (!isTerminal) openAttempt(attempt = 0, secondAttempt = null)
        }

        override fun tick() {
            val outcome = synchronized(lock) {
                val now = clock.nowMs()
                val pending = session.pendingTimeoutCode(now)
                val prior = mutableListOf<HarnessDispatch>()
                if (
                    pending == null &&
                    now - lastHeartbeatAtMs >= HEARTBEAT_INTERVAL_MS
                ) {
                    lastHeartbeatAtMs = now
                    prior += emitAgentProgress(
                        kind = AgentProgressKind.HEARTBEAT,
                        state = AgentProgressState.RUNNING,
                        stepId = "heartbeat",
                        title = heartbeatTitle,
                        detail = "${((now - runStartedAtMs) / 1_000L).coerceAtLeast(1L)} 秒",
                        nowMonotonicMs = now,
                    )
                }
                if (
                    activeAttempt == 0 &&
                    pending != null &&
                    pending.isAutomaticRecoveryEligible()
                ) {
                    startSecondAttemptOutcome(
                        context = streamRecoveryContext("watchdog_${pending.name.lowercase()}"),
                        prior = prior,
                        nowMonotonicMs = now,
                    )
                } else {
                    val dispatch = session.advanceTo(now)
                    prior += dispatch
                    terminalOutcome(prior)
                }
            }
            dispatchOutcome(outcome)
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
            dispatchOutcome(outcome)
        }

        private fun openAttempt(
            attempt: Int,
            secondAttempt: SecondAttemptContext?,
            continuation: AgentConversationContext? = if (attempt == 0) agentConversation else null,
        ) {
            val wireRequest = try {
                OpenAiRequestFactory.create(
                    profile = spec.provider,
                    request = spec.harnessRequest,
                    credential = spec.credential,
                    attempt = attempt,
                    secondAttempt = secondAttempt,
                    agentConversation = continuation?.takeIf { it.exchanges.isNotEmpty() },
                    requestMode = requestMode,
                    enabledTools = spec.enabledTools,
                    skillCatalog = authorizedSkillCatalog,
                    skillCatalogGeneration = authorizedSkillCatalogGeneration,
                    recallFrame = spec.recallFrame,
                )
            } catch (_: Exception) {
                failLocally(HarnessErrorCode.INTERNAL_FAILURE, retryable = false)
                return
            }
            if (
                !recordTrace(
                    BrainTraceEvent.ProviderInput(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        attempt = attempt,
                        endpoint = wireRequest.url,
                        body = String(wireRequest.body, StandardCharsets.UTF_8),
                    ),
                )
            ) {
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
                nativeTool = null
                nativeToolArguments.setLength(0)
                privateReasoning.setLength(0)
                privateResponsesReasoningItems.clear()
                privateResponsesReasoningChars = 0
                assistantContent.setLength(0)
                nativeToolIndex = null
                nativeToolId = null
                nativeToolName = null
                nativeToolError = null
                nativeToolProgressStarted = false
                lastHarnessPhase = null
                reasoningProgressEmitted = false
                draftingProgressEmitted = false
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
            if (
                !recordTrace(
                    BrainTraceEvent.ProviderOpened(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        attempt = attempt,
                        statusCode = metadata.statusCode,
                        contentType = metadata.contentType,
                    ),
                )
            ) {
                failLocally(HarnessErrorCode.INTERNAL_FAILURE, retryable = false)
                return
            }
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
                val now = clock.nowMs()
                heartbeatTitle = "模型正在理解编辑任务"
                val dispatches = mutableListOf<HarnessDispatch>()
                dispatches += emitAgentProgress(
                    kind = AgentProgressKind.CONNECTION,
                    state = AgentProgressState.COMPLETED,
                    stepId = "provider",
                    title = "模型已连接",
                    nowMonotonicMs = now,
                )
                dispatches += session.accept(
                    AiEvent.Status(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        phase = HarnessPhase.UNDERSTANDING,
                        label = "provider_connected",
                    ),
                    now,
                )
                dispatches += emitAgentProgress(
                    kind = AgentProgressKind.THINKING,
                    state = AgentProgressState.RUNNING,
                    stepId = "understand",
                    title = "正在理解文字与编辑目标",
                    nowMonotonicMs = now,
                )
                terminalOutcome(dispatches)
            }
            dispatchOutcome(outcome)
        }

        private fun onTransportBytes(
            token: Long,
            attempt: Int,
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (
                length > 0 &&
                !recordTrace(
                    BrainTraceEvent.ProviderOutput(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        attempt = attempt,
                        bytes = bytes.copyOfRange(offset, offset + length),
                    ),
                )
            ) {
                failLocally(HarnessErrorCode.INTERNAL_FAILURE, retryable = false)
                return
            }
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
            dispatchOutcome(outcome)
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
            dispatchOutcome(outcome)
        }

        private fun onTransportFailure(
            token: Long,
            attempt: Int,
            failure: ProviderTransportFailure,
        ) {
            if (
                !recordTrace(
                    BrainTraceEvent.ProviderFailed(
                        requestId = requestId,
                        runGeneration = runGeneration,
                        attempt = attempt,
                        kind = failure.kind,
                        statusCode = failure.statusCode,
                        message = failure.message,
                    ),
                )
            ) {
                failLocally(HarnessErrorCode.INTERNAL_FAILURE, retryable = false)
                return
            }
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
            dispatchOutcome(outcome)
        }

        private fun consumeNormalized(
            normalized: List<ProviderContentEvent>,
            attempt: Int,
        ): Outcome {
            val dispatches = mutableListOf<HarnessDispatch>()
            for (event in normalized) {
                if (session.state.isTerminal) break
                when (event) {
                    is ProviderContentEvent.ReasoningDelta -> {
                        if (!appendPrivateBounded(privateReasoning, event.text)) {
                            return failOutcome(
                                HarnessErrorCode.PROVIDER_FAILURE,
                                retryable = false,
                                prior = dispatches,
                            )
                        }
                        emitPhaseStatus(
                            dispatches,
                            HarnessPhase.THINKING,
                            "provider_reasoning",
                        )
                        if (!reasoningProgressEmitted) {
                            reasoningProgressEmitted = true
                            heartbeatTitle = "模型正在思考"
                            dispatches += emitAgentProgress(
                                kind = AgentProgressKind.THINKING,
                                state = AgentProgressState.RUNNING,
                                stepId = "thinking",
                                title = "模型正在分析并规划编辑",
                            )
                        }
                    }
                    is ProviderContentEvent.ResponsesReasoningItem -> {
                        if (
                            privateResponsesReasoningItems.size >= MAX_RESPONSES_REASONING_ITEMS ||
                            privateResponsesReasoningChars.toLong() + event.json.length >
                            MAX_PRIVATE_REPLAY_CHARS
                        ) {
                            return failOutcome(
                                HarnessErrorCode.PROVIDER_FAILURE,
                                retryable = false,
                                prior = dispatches,
                            )
                        }
                        privateResponsesReasoningItems += event.json
                        privateResponsesReasoningChars += event.json.length
                    }
                    is ProviderContentEvent.ToolCallDelta -> {
                        if (!usesNativeToolProtocol && !acceptsAgentToolCalls) {
                            return failOutcome(
                                HarnessErrorCode.PROTOCOL_INVALID,
                                retryable = false,
                                prior = dispatches,
                            )
                        }
                        emitPhaseStatus(
                            dispatches,
                            HarnessPhase.TOOL_RUNNING,
                            "provider_tool_call",
                        )
                        consumeNativeToolDelta(event, dispatches)
                    }
                    is ProviderContentEvent.TextDelta -> {
                        if (!draftingProgressEmitted) {
                            draftingProgressEmitted = true
                            heartbeatTitle = "正在整理可写入的结果"
                            dispatches += emitAgentProgress(
                                kind = AgentProgressKind.THINKING,
                                state = AgentProgressState.COMPLETED,
                                stepId = "thinking",
                                title = "已完成内容分析",
                            )
                            dispatches += emitAgentProgress(
                                kind = AgentProgressKind.DRAFTING,
                                state = AgentProgressState.RUNNING,
                                stepId = "drafting",
                                title = "正在整理可直接写入的结果",
                            )
                        }
                        emitPhaseStatus(
                            dispatches,
                            HarnessPhase.GENERATING,
                            "provider_generating",
                        )
                        val textFailure = consumeAssistantText(event.text, dispatches)
                        if (textFailure != null) {
                            return failOutcome(
                                textFailure,
                                retryable = false,
                                prior = dispatches,
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
                        if (
                            !recordTrace(
                                BrainTraceEvent.ProviderCompleted(
                                    requestId = requestId,
                                    runGeneration = runGeneration,
                                    attempt = attempt,
                                ),
                            )
                        ) {
                            return failOutcome(
                                HarnessErrorCode.INTERNAL_FAILURE,
                                retryable = false,
                                prior = dispatches,
                            )
                        }
                        if (assistantContent.isEmpty() &&
                            !event.finalText.isNullOrEmpty()
                        ) {
                            val textFailure = consumeAssistantText(event.finalText, dispatches)
                            if (textFailure != null) {
                                return failOutcome(
                                    textFailure,
                                    retryable = false,
                                    prior = dispatches,
                                )
                            }
                        }
                        finishRetryVisibility(dispatches)
                        if (session.state.isTerminal) return terminalOutcome(dispatches)
                        return finalizeResult(attempt, dispatches)
                    }
                }
            }
            return terminalOutcome(dispatches)
        }

        private fun consumeAssistantText(
            text: String,
            dispatches: MutableList<HarnessDispatch>,
        ): HarnessErrorCode? {
            if (!appendPrivateBounded(assistantContent, text)) {
                return HarnessErrorCode.PROVIDER_FAILURE
            }
            val visible = if (!requiresEditorPatch) {
                text
            } else {
                try {
                    // Native patch mode retains ordinary content only as bounded evidence. In all
                    // patch modes the scanner exposes replacement text, never model-provided IDs.
                    val extracted = preview!!.append(text)
                    if (usesNativePatchTool) "" else extracted
                } catch (_: ProviderPayloadException) {
                    return HarnessErrorCode.PREVIEW_LIMIT_EXCEEDED
                }
            }
            if (visible.isNotEmpty()) {
                emitVisiblePreview(visible, dispatches)
            }
            return null
        }

        private fun finalizeResult(
            attempt: Int,
            prior: MutableList<HarnessDispatch>,
        ): Outcome {
            if (
                usesNativeToolProtocol &&
                nativeToolError == null &&
                nativeToolName == OpenAiRequestFactory.NATIVE_PROGRESS_TOOL_NAME
            ) {
                return continueAfterProgressTool(attempt, prior)
            }
            if (
                nativeToolError == null &&
                AgentToolId.fromWireValue(nativeToolName.orEmpty()) != null
            ) {
                return executeEnabledAgentTool(attempt, prior)
            }

            val nativeFailure = nativeToolError
            if (!requiresEditorPatch) {
                if (nativeToolIndex != null) {
                    completeNativeToolProgress(prior, succeeded = false)
                    return repairOrFail(
                        attempt = attempt,
                        prior = prior,
                        rejectedDocument = nativeToolArguments.toString(),
                        validationSummary =
                            nativeFailure ?: "unexpected or disabled Agent tool call",
                    )
                }
                return finalizeAnswer(attempt, prior, assistantContent.toString())
            }
            if (!usesNativePatchTool && nativeToolIndex != null) {
                completeNativeToolProgress(prior, succeeded = false)
                return repairOrFail(
                    attempt = attempt,
                    prior = prior,
                    rejectedDocument = nativeToolArguments.toString(),
                    validationSummary =
                        nativeFailure ?: "unexpected or disabled Agent tool call",
                )
            }
            val document = if (usesNativePatchTool && nativeFailure == null) {
                if (
                    nativeToolIndex == null ||
                    nativeToolName != OpenAiRequestFactory.NATIVE_PATCH_TOOL_NAME
                ) {
                    val ordinaryAnswer = assistantContent.toString()
                    if (attempt == 0 && ordinaryAnswer.isPlainAssistantAnswerCandidate()) {
                        completeNativeToolProgress(prior, succeeded = false)
                        return finalizeAnswer(attempt, prior, ordinaryAnswer)
                    }
                    completeNativeToolProgress(prior, succeeded = false)
                    return repairOrFail(
                        attempt = attempt,
                        prior = prior,
                        rejectedDocument = nativeToolArguments.toString()
                            .takeIf(String::isNotEmpty)
                            ?: preview!!.fullDocument(),
                        validationSummary = "terminal sense_submit_patch tool call is missing",
                    )
                }
                val terminalCallId = nativeToolId.orEmpty()
                if (
                    !recordToolCall(
                        callId = terminalCallId,
                        toolName = OpenAiRequestFactory.NATIVE_PATCH_TOOL_NAME,
                        arguments = nativeToolArguments.toString(),
                    )
                ) {
                    return failOutcome(
                        HarnessErrorCode.INTERNAL_FAILURE,
                        retryable = false,
                        prior = prior,
                    )
                }
                try {
                    val patchDocument = nativeTool?.finish()?.patchDocument
                        ?: throw ProviderPayloadException("native patch tool is incomplete")
                    completeNativeToolProgress(prior, succeeded = true)
                    patchDocument
                } catch (error: ProviderPayloadException) {
                    if (
                        !recordToolResult(
                            callId = terminalCallId,
                            toolName = OpenAiRequestFactory.NATIVE_PATCH_TOOL_NAME,
                            content = error.message ?: "invalid native tool arguments",
                            isError = true,
                        )
                    ) {
                        return failOutcome(
                            HarnessErrorCode.INTERNAL_FAILURE,
                            retryable = false,
                            prior = prior,
                        )
                    }
                    completeNativeToolProgress(prior, succeeded = false)
                    return repairOrFail(
                        attempt = attempt,
                        prior = prior,
                        rejectedDocument = nativeToolArguments.toString(),
                        validationSummary = error.message ?: "invalid native tool arguments",
                    )
                }
            } else if (usesNativePatchTool) {
                completeNativeToolProgress(prior, succeeded = false)
                return repairOrFail(
                    attempt = attempt,
                    prior = prior,
                    rejectedDocument = nativeToolArguments.toString(),
                    validationSummary = nativeFailure ?: "invalid native tool call",
                )
            } else {
                preview!!.fullDocument()
            }
            if (attempt == 0 && document.isPlainAssistantAnswerCandidate()) {
                return finalizeAnswer(attempt, prior, document)
            }
            heartbeatTitle = "正在校验结构化编辑结果"
            prior += session.accept(
                AiEvent.Status(
                    requestId = requestId,
                    runGeneration = runGeneration,
                    phase = HarnessPhase.VALIDATING,
                    label = "validating_patch",
                ),
                clock.nowMs(),
            )
            prior += emitAgentProgress(
                kind = AgentProgressKind.VALIDATION,
                state = AgentProgressState.RUNNING,
                stepId = "validation",
                title = "正在校验结果与输入框权限",
            )
            if (session.state.isTerminal) return terminalOutcome(prior)

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
                        if (
                            usesNativePatchTool &&
                            !recordToolResult(
                                callId = nativeToolId.orEmpty(),
                                toolName = OpenAiRequestFactory.NATIVE_PATCH_TOOL_NAME,
                                content = "{\"accepted\":true}",
                                isError = false,
                            )
                        ) {
                            return failOutcome(
                                HarnessErrorCode.INTERNAL_FAILURE,
                                retryable = false,
                                prior = prior,
                            )
                        }
                        prior += emitAgentProgress(
                            kind = AgentProgressKind.VALIDATION,
                            state = AgentProgressState.COMPLETED,
                            stepId = "validation",
                            title = "结构化结果校验通过",
                        )
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

            if (
                usesNativePatchTool &&
                nativeToolName == OpenAiRequestFactory.NATIVE_PATCH_TOOL_NAME &&
                !recordToolResult(
                    callId = nativeToolId.orEmpty(),
                    toolName = OpenAiRequestFactory.NATIVE_PATCH_TOOL_NAME,
                    content = validationSummary,
                    isError = true,
                )
            ) {
                return failOutcome(
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                    prior = prior,
                )
            }
            return repairOrFail(
                attempt = attempt,
                prior = prior,
                rejectedDocument = document,
                validationSummary = validationSummary,
            )
        }

        private fun finalizeAnswer(
            attempt: Int,
            prior: MutableList<HarnessDispatch>,
            document: String,
        ): Outcome {
            val answer = document.trim()
            if (answer.isEmpty() || answer.length > spec.harnessRequest.maxOutputChars) {
                return repairOrFail(
                    attempt = attempt,
                    prior = prior,
                    rejectedDocument = document,
                    validationSummary = if (answer.isEmpty()) {
                        "assistant answer is empty"
                    } else {
                        "assistant answer exceeds max_output_chars"
                    },
                )
            }
            heartbeatTitle = "正在校验回答"
            prior += session.accept(
                AiEvent.Status(
                    requestId = requestId,
                    runGeneration = runGeneration,
                    phase = HarnessPhase.VALIDATING,
                    label = "validating_answer",
                ),
                clock.nowMs(),
            )
            prior += emitAgentProgress(
                kind = AgentProgressKind.VALIDATION,
                state = AgentProgressState.COMPLETED,
                stepId = "validation",
                title = "回答已准备好",
            )
            if (session.state.isTerminal) return terminalOutcome(prior)
            prior += session.accept(
                AiEvent.FinalAnswer(
                    requestId = requestId,
                    runGeneration = runGeneration,
                    text = answer,
                ),
                clock.nowMs(),
            )
            return terminalOutcome(prior)
        }

        private fun String.isPlainAssistantAnswerCandidate(): Boolean {
            val candidate = trimStart()
            if (candidate.isEmpty()) return false
            if (
                candidate.length < MIN_PLAIN_ANSWER_CHARS &&
                candidate.all { it in 'A'..'Z' || it in 'a'..'z' }
            ) {
                return false
            }
            return !candidate.startsWith('{') &&
                !candidate.startsWith('[') &&
                !candidate.startsWith("```")
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

        private fun emitPhaseStatus(
            dispatches: MutableList<HarnessDispatch>,
            phase: HarnessPhase,
            label: String,
        ) {
            if (lastHarnessPhase == phase || session.state.isTerminal) return
            lastHarnessPhase = phase
            dispatches += session.accept(
                AiEvent.Status(
                    requestId = requestId,
                    runGeneration = runGeneration,
                    phase = phase,
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

            var visible = NativeToolVisibleDelta()
            if (
                nativeToolName == OpenAiRequestFactory.NATIVE_PATCH_TOOL_NAME &&
                nativeTool == null
            ) {
                val accumulator = NativePatchToolAccumulator()
                try {
                    if (nativeToolArguments.isNotEmpty()) {
                        visible = accumulator.append(nativeToolArguments.toString())
                    }
                } catch (error: ProviderPayloadException) {
                    nativeToolError = error.message ?: "invalid native tool argument stream"
                    return
                }
                nativeTool = accumulator
            }
            if (!nativeToolProgressStarted && nativeToolId != null && nativeToolName != null) {
                nativeToolProgressStarted = true
                heartbeatTitle = if (
                    nativeToolName == OpenAiRequestFactory.NATIVE_PROGRESS_TOOL_NAME
                ) {
                    "Agent 正在同步进度"
                } else {
                    "正在执行安全编辑工具"
                }
                dispatches += emitAgentProgress(
                    kind = AgentProgressKind.TOOL,
                    state = AgentProgressState.RUNNING,
                    stepId = currentToolStepId(),
                    title = heartbeatTitle,
                    toolCallId = nativeToolId,
                    toolName = nativeToolName,
                )
            }
            if (event.arguments.isNotEmpty()) {
                if (
                    nativeToolArguments.length.toLong() + event.arguments.length >
                    ProviderJson.MAX_DOCUMENT_CHARS
                ) {
                    nativeToolError = "native tool arguments exceed the bounded document size"
                    return
                }
                nativeToolArguments.append(event.arguments)
                if (nativeToolName == OpenAiRequestFactory.NATIVE_PATCH_TOOL_NAME) {
                    visible = try {
                        nativeTool!!.append(event.arguments)
                    } catch (error: ProviderPayloadException) {
                        nativeToolError = error.message ?: "invalid native tool argument stream"
                        return
                    }
                }
            }
            if (visible.description.isNotEmpty()) {
                emitVisibleDescription(visible.description, dispatches)
            }
            if (visible.patchText.isNotEmpty() && !session.state.isTerminal) {
                emitVisiblePreview(visible.patchText, dispatches)
            }
        }

        private fun continueAfterProgressTool(
            attempt: Int,
            prior: MutableList<HarnessDispatch>,
        ): Outcome {
            if (attempt != 0) {
                completeNativeToolProgress(prior, succeeded = false)
                return repairOrFail(
                    attempt = attempt,
                    prior = prior,
                    rejectedDocument = nativeToolArguments.toString(),
                    validationSummary = "repair/recovery must submit the terminal patch",
                )
            }
            if (agentProgressTurns >= MAX_AGENT_PROGRESS_TURNS) {
                completeNativeToolProgress(prior, succeeded = false)
                return repairOrFail(
                    attempt = attempt,
                    prior = prior,
                    rejectedDocument = nativeToolArguments.toString(),
                    validationSummary = "agent progress turn limit exceeded",
                )
            }
            val callId = nativeToolId
            val toolName = nativeToolName
            val message = try {
                if (
                    callId == null ||
                    toolName != OpenAiRequestFactory.NATIVE_PROGRESS_TOOL_NAME
                ) {
                    throw ProviderPayloadException("progress tool identity is incomplete")
                }
                NativeProgressToolSubmission.decode(nativeToolArguments.toString())
            } catch (error: ProviderPayloadException) {
                completeNativeToolProgress(prior, succeeded = false)
                return repairOrFail(
                    attempt = attempt,
                    prior = prior,
                    rejectedDocument = nativeToolArguments.toString(),
                    validationSummary = error.message ?: "invalid progress tool arguments",
                )
            }

            val toolResult = if (requiresEditorPatch) {
                "{\"accepted\":true,\"instruction\":\"Continue the task and submit the " +
                    "final patch with sense_submit_patch.\"}"
            } else {
                "{\"accepted\":true,\"instruction\":\"Continue the task and finish with " +
                    "one complete user-facing answer in ordinary assistant content.\"}"
            }
            if (
                !recordToolCall(
                    callId = callId,
                    toolName = toolName,
                    arguments = nativeToolArguments.toString(),
                ) ||
                !recordToolResult(
                    callId = callId,
                    toolName = toolName,
                    content = toolResult,
                    isError = false,
                )
            ) {
                return failOutcome(
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                    prior = prior,
                )
            }
            completeNativeToolProgress(prior, succeeded = true)
            agentProgressTurns += 1
            prior += emitAgentProgress(
                kind = AgentProgressKind.ASSISTANT_UPDATE,
                state = AgentProgressState.COMPLETED,
                stepId = "agent-update-$agentProgressTurns",
                title = message,
            )
            val exchange = AgentToolExchange(
                assistantReasoning = privateReasoning.toString(),
                assistantContent = assistantContent.toString(),
                responsesReasoningItems = privateResponsesReasoningItems.toList(),
                toolCallId = callId,
                toolName = toolName,
                toolArguments = nativeToolArguments.toString(),
                toolResult = toolResult,
            )
            val continuation = AgentConversationContext(
                exchanges = agentConversation.exchanges + exchange,
                forceTerminalTool =
                    agentProgressTurns >= MAX_AGENT_PROGRESS_TURNS ||
                        agentToolTurns >= AgentToolRouter.MAX_TOOL_TURNS,
            )
            agentConversation = continuation
            heartbeatTitle = "Agent 正在继续处理"
            val oldCall = invalidateActive()
            return Outcome(
                dispatches = prior,
                cancelCall = oldCall,
                continuation = continuation,
            )
        }

        private fun executeEnabledAgentTool(
            attempt: Int,
            prior: MutableList<HarnessDispatch>,
        ): Outcome {
            if (attempt != 0 || agentToolTurns >= AgentToolRouter.MAX_TOOL_TURNS) {
                completeNativeToolProgress(prior, succeeded = false)
                return repairOrFail(
                    attempt = attempt,
                    prior = prior,
                    rejectedDocument = nativeToolArguments.toString(),
                    validationSummary = "Agent tool turn is not allowed",
                )
            }
            val callId = nativeToolId
                ?: return repairOrFail(
                    attempt,
                    prior,
                    nativeToolArguments.toString(),
                    "Agent tool call identity is incomplete",
                )
            val toolName = nativeToolName.orEmpty()
            val call = try {
                AgentToolRouter.decode(
                    callId = callId,
                    toolName = toolName,
                    argumentsDocument = nativeToolArguments.toString(),
                    enabledTools = spec.enabledTools,
                    requestId = requestId,
                    runGeneration = runGeneration,
                    sessionId = spec.harnessRequest.snapshot.fieldIdentity,
                ).also { decoded ->
                    when (val arguments = decoded.arguments) {
                        is AgentToolArguments.SkillRead -> {
                            val authorized = authorizedSkillCatalog
                                .firstOrNull { it.id == arguments.skillId }
                            if (authorized?.revision != arguments.revision) {
                                throw ProviderPayloadException(
                                    "skill_read id/revision is not authorized for this run",
                                )
                            }
                        }
                        is AgentToolArguments.SkillManage -> {
                            if (
                                arguments.expectedCatalogGeneration !=
                                authorizedSkillCatalogGeneration
                            ) {
                                throw ProviderPayloadException(
                                    "skill_manage expected_catalog_generation is not authorized " +
                                        "for this run",
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (error: ProviderPayloadException) {
                return continueAfterRejectedAgentTool(
                    prior = prior,
                    callId = callId,
                    toolName = toolName,
                    validationSummary = error.message ?: "invalid Agent tool call",
                )
            }
            if (
                !recordToolCall(
                    callId = call.callId,
                    toolName = toolName,
                    arguments = nativeToolArguments.toString(),
                )
            ) {
                return failOutcome(
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                    prior = prior,
                )
            }
            val pending = PendingToolExecution(
                token = Math.addExact(toolExecutionCounter, 1L),
                call = call,
                toolName = toolName,
                toolArguments = nativeToolArguments.toString(),
                assistantReasoning = privateReasoning.toString(),
                assistantContent = assistantContent.toString(),
                responsesReasoningItems = privateResponsesReasoningItems.toList(),
                authorizedSkillCatalogGeneration = authorizedSkillCatalogGeneration,
                authorizedSkillCatalog = authorizedSkillCatalog,
                stepId = currentToolStepId(),
            )
            toolExecutionCounter = pending.token
            val oldCall = invalidateActive()
            activeToolExecutionToken = pending.token
            heartbeatTitle = "正在执行 ${call.tool.wireValue}"
            return Outcome(
                dispatches = prior,
                cancelCall = oldCall,
                toolExecution = pending,
            )
        }

        /**
         * Keeps a malformed or unauthorized tool call inside the same conversation.
         *
         * The provider receives one compact typed error paired with its original call id and can
         * correct the arguments or finish normally. This avoids spending the one full-answer
         * repair attempt and avoids replaying the immutable editor snapshot as a protocol retry.
         */
        private fun continueAfterRejectedAgentTool(
            prior: MutableList<HarnessDispatch>,
            callId: String,
            toolName: String,
            validationSummary: String,
        ): Outcome {
            val result = invalidToolResult(validationSummary.take(512))
            if (
                !recordToolCall(
                    callId = callId,
                    toolName = toolName,
                    arguments = nativeToolArguments.toString(),
                ) ||
                !recordToolResult(
                    callId = callId,
                    toolName = toolName,
                    content = result.content,
                    isError = true,
                )
            ) {
                return failOutcome(
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                    prior = prior,
                )
            }
            completeNativeToolProgress(prior, succeeded = false)
            agentToolTurns += 1
            val exchange = AgentToolExchange(
                assistantReasoning = privateReasoning.toString(),
                assistantContent = assistantContent.toString(),
                responsesReasoningItems = privateResponsesReasoningItems.toList(),
                toolCallId = callId,
                toolName = toolName,
                toolArguments = nativeToolArguments.toString(),
                toolResult = result.content,
            )
            val continuation = AgentConversationContext(
                exchanges = agentConversation.exchanges + exchange,
                forceTerminalTool = agentToolTurns >= AgentToolRouter.MAX_TOOL_TURNS,
            )
            agentConversation = continuation
            heartbeatTitle = "工具参数已反馈，Agent 正在继续处理"
            val oldCall = invalidateActive()
            return Outcome(
                dispatches = prior,
                cancelCall = oldCall,
                continuation = continuation,
            )
        }

        private fun executeTool(pending: PendingToolExecution) {
            // This lock acquisition is the tool-start linearization point. If cancellation won
            // first, no executor code (and therefore no side effect) may run. If this transition
            // wins first, the bounded executor is allowed to finish atomically; cancellation can
            // still invalidate and discard its provider continuation, but cannot roll it back.
            val executionStarted = synchronized(lock) {
                !session.state.isTerminal &&
                    activeToolExecutionToken == pending.token
            }
            if (!executionStarted) return
            val raw = runCatching { toolExecutor.execute(pending.call) }
                .getOrElse {
                    AgentToolExecutionResult(
                        content = "{\"ok\":false,\"error\":\"tool execution failed\"}",
                        isError = true,
                    )
                }
            val manage = pending.call.arguments as? AgentToolArguments.SkillManage
            val result = normalizeToolResult(pending, raw)
            if (
                !recordToolResult(
                    callId = pending.call.callId,
                    toolName = pending.toolName,
                    content = result.content,
                    isError = result.isError,
                )
            ) {
                failLocally(HarnessErrorCode.INTERNAL_FAILURE, retryable = false)
                return
            }
            val outcome = synchronized(lock) {
                if (
                    session.state.isTerminal ||
                    activeToolExecutionToken != pending.token
                ) {
                    return@synchronized null
                }
                activeToolExecutionToken = -1L
                if (manage != null && !result.isError) {
                    val snapshot = requireNotNull(result.skillCatalogSnapshot)
                    if (
                        authorizedSkillCatalogGeneration !=
                        pending.authorizedSkillCatalogGeneration ||
                        authorizedSkillCatalog != pending.authorizedSkillCatalog
                    ) {
                        return@synchronized failOutcome(
                            HarnessErrorCode.INTERNAL_FAILURE,
                            retryable = false,
                        )
                    }
                    authorizedSkillCatalogGeneration = snapshot.generation
                    authorizedSkillCatalog = snapshot.skills
                }
                val dispatches = mutableListOf<HarnessDispatch>()
                dispatches += emitAgentProgress(
                    kind = AgentProgressKind.TOOL,
                    state = if (result.isError) {
                        AgentProgressState.FAILED
                    } else {
                        AgentProgressState.COMPLETED
                    },
                    stepId = pending.stepId,
                    title = when {
                        result.isError -> "工具返回错误，Agent 正在调整"
                        pending.toolName == "terminal_exec" -> "终端命令已完成"
                        pending.toolName == "browser_use" -> "浏览器操作已完成"
                        else -> "工具调用已完成"
                    },
                    detail = result.content.toPublicToolDetail(),
                    toolCallId = pending.call.callId,
                    toolName = pending.toolName,
                )
                agentToolTurns += 1
                val exchange = AgentToolExchange(
                    assistantReasoning = pending.assistantReasoning,
                    assistantContent = pending.assistantContent,
                    responsesReasoningItems = pending.responsesReasoningItems,
                    toolCallId = pending.call.callId,
                    toolName = pending.toolName,
                    toolArguments = pending.toolArguments,
                    toolResult = result.content,
                )
                val continuation = AgentConversationContext(
                    exchanges = agentConversation.exchanges + exchange,
                    forceTerminalTool =
                        agentProgressTurns >= MAX_AGENT_PROGRESS_TURNS ||
                            agentToolTurns >= AgentToolRouter.MAX_TOOL_TURNS,
                )
                agentConversation = continuation
                heartbeatTitle = "Agent 正在根据工具结果继续处理"
                Outcome(dispatches = dispatches, continuation = continuation)
            }
            outcome?.let(::dispatchOutcome)
        }

        private fun normalizeToolResult(
            pending: PendingToolExecution,
            raw: AgentToolExecutionResult,
        ): AgentToolExecutionResult {
            if (raw.content.length > AgentToolRouter.MAX_TOOL_RESULT_CHARS) {
                return invalidToolResult("tool result exceeded the bounded limit")
            }
            val manage = pending.call.arguments as? AgentToolArguments.SkillManage
            if (manage == null) {
                return if (raw.skillCatalogSnapshot == null) {
                    raw
                } else {
                    invalidToolResult("non-management tool returned Skill catalog authority")
                }
            }
            if (raw.isError) return raw
            val snapshot = raw.skillCatalogSnapshot
                ?: return invalidToolResult(
                    "skill mutation result omitted the authoritative catalog snapshot",
                )
            return if (
                validateSkillMutationSnapshot(
                    mutation = manage,
                    before = pending.authorizedSkillCatalog,
                    beforeGeneration = pending.authorizedSkillCatalogGeneration,
                    after = snapshot,
                )
            ) {
                raw
            } else {
                invalidToolResult("skill mutation returned an invalid catalog snapshot")
            }
        }

        private fun validateSkillMutationSnapshot(
            mutation: AgentToolArguments.SkillManage,
            before: List<AgentSkillSummary>,
            beforeGeneration: Long?,
            after: AgentSkillCatalogSnapshot,
        ): Boolean {
            if (
                beforeGeneration != mutation.expectedCatalogGeneration ||
                after.generation <= mutation.expectedCatalogGeneration
            ) {
                return false
            }
            val beforeById = before.associateBy(AgentSkillSummary::id)
            val afterById = after.skills.associateBy(AgentSkillSummary::id)
            if (beforeById.size != before.size || afterById.size != after.skills.size) return false

            return when (mutation) {
                is AgentToolArguments.SkillManage.Create -> {
                    if (
                        mutation.skillId in beforeById ||
                        afterById.keys != beforeById.keys + mutation.skillId
                    ) {
                        false
                    } else {
                        beforeById.all { (id, summary) -> afterById[id] == summary } &&
                            afterById[mutation.skillId]?.let { created ->
                                created.revision > 0L &&
                                    created.name == mutation.name &&
                                    created.description == mutation.description
                            } == true
                    }
                }
                is AgentToolArguments.SkillManage.Update -> {
                    val prior = beforeById[mutation.skillId] ?: return false
                    val updated = afterById[mutation.skillId] ?: return false
                    beforeById.keys == afterById.keys &&
                        beforeById.all { (id, summary) ->
                            id == mutation.skillId || afterById[id] == summary
                        } &&
                        updated.revision > prior.revision &&
                        updated.name == (mutation.name ?: prior.name) &&
                        updated.description == (mutation.description ?: prior.description)
                }
                is AgentToolArguments.SkillManage.Bind,
                is AgentToolArguments.SkillManage.Unbind,
                is AgentToolArguments.SkillManage.UnbindSkill,
                -> beforeById == afterById
            }
        }

        private fun invalidToolResult(message: String): AgentToolExecutionResult =
            AgentToolExecutionResult(
                content = "{\"ok\":false,\"error\":${JsonWriter().string(message)}}",
                isError = true,
            )

        private fun String.toPublicToolDetail(): String =
            replace('\n', ' ').replace('\r', ' ').take(160)

        private fun completeNativeToolProgress(
            dispatches: MutableList<HarnessDispatch>,
            succeeded: Boolean,
        ) {
            if (!nativeToolProgressStarted) return
            val callId = nativeToolId ?: return
            val toolName = nativeToolName ?: return
            dispatches += emitAgentProgress(
                kind = AgentProgressKind.TOOL,
                state = if (succeeded) {
                    AgentProgressState.COMPLETED
                } else {
                    AgentProgressState.FAILED
                },
                stepId = currentToolStepId(),
                title = when {
                    !succeeded -> "工具调用未通过协议校验"
                    toolName == OpenAiRequestFactory.NATIVE_PROGRESS_TOOL_NAME ->
                        "已接收 Agent 进度"
                    else -> "安全编辑工具已完成"
                },
                toolCallId = callId,
                toolName = toolName,
            )
            nativeToolProgressStarted = false
        }

        private fun currentToolStepId(): String = "tool-${agentConversation.exchanges.size + 1}"

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
            heartbeatTitle = "连接波动，正在恢复会话"
            prior += emitAgentProgress(
                kind = AgentProgressKind.RECOVERY,
                state = AgentProgressState.RUNNING,
                stepId = "recovery",
                title = when (context) {
                    is RepairContext -> "协议结果需校准，正在修复"
                    is StreamRecoveryContext -> "连接波动，正在恢复会话"
                },
                nowMonotonicMs = nowMonotonicMs,
            )

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

        private fun currentAttemptDocument(): String = when {
            !requiresEditorPatch -> assistantContent.toString()
            usesNativePatchTool -> nativeToolArguments.toString().ifEmpty {
                preview?.fullDocument().orEmpty()
            }
            else -> preview?.fullDocument().orEmpty()
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
            dispatchOutcome(outcome)
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

        private fun emitAgentProgress(
            kind: AgentProgressKind,
            state: AgentProgressState,
            stepId: String,
            title: String,
            detail: String = "",
            toolCallId: String? = null,
            toolName: String? = null,
            nowMonotonicMs: Long = clock.nowMs(),
        ): HarnessDispatch {
            agentProgressRevision = Math.addExact(agentProgressRevision, 1L)
            return session.accept(
                AiEvent.AgentProgress(
                    requestId = requestId,
                    runGeneration = runGeneration,
                    revision = agentProgressRevision,
                    stepId = stepId,
                    kind = kind,
                    state = state,
                    title = title,
                    detail = detail,
                    toolCallId = toolCallId,
                    toolName = toolName,
                ),
                nowMonotonicMs,
            )
        }

        private fun appendPrivateBounded(target: StringBuilder, text: String): Boolean {
            if (target.length.toLong() + text.length > MAX_PRIVATE_REPLAY_CHARS) return false
            target.append(text)
            return true
        }

        private fun recordToolCall(
            callId: String,
            toolName: String,
            arguments: String,
        ): Boolean = recordTrace(
            BrainTraceEvent.ToolCall(
                requestId = requestId,
                runGeneration = runGeneration,
                callId = callId,
                toolName = toolName,
                arguments = arguments,
                privateReasoning = privateReasoning.toString(),
                assistantContent = assistantContent.toString(),
            ),
        )

        private fun recordToolResult(
            callId: String,
            toolName: String,
            content: String,
            isError: Boolean,
        ): Boolean = recordTrace(
            BrainTraceEvent.ToolResult(
                requestId = requestId,
                runGeneration = runGeneration,
                callId = callId,
                toolName = toolName,
                content = content,
                isError = isError,
            ),
        )

        private fun recordTrace(event: BrainTraceEvent): Boolean =
            runCatching { spec.traceSink.onTrace(event) }.isSuccess

        private fun dispatchOutcome(outcome: Outcome) {
            outcome.cancelCall?.cancel()
            publish(outcome.dispatches)
            outcome.secondAttempt?.let {
                openAttempt(attempt = 1, secondAttempt = it, continuation = null)
            }
            outcome.continuation?.let {
                openAttempt(attempt = 0, secondAttempt = null, continuation = it)
            }
            outcome.toolExecution?.let(::executeTool)
        }

        /**
         * Must be called under [lock]. The token is invalidated before the transport is cancelled.
         */
        private fun invalidateActive(): ProviderCall? {
            activeToolExecutionToken = -1L
            activeToken = -1L
            activeAttempt = -1
            decoder = null
            preview = null
            nativeTool = null
            nativeToolArguments.setLength(0)
            privateReasoning.setLength(0)
            privateResponsesReasoningItems.clear()
            privateResponsesReasoningChars = 0
            assistantContent.setLength(0)
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
        val continuation: AgentConversationContext? = null,
        val toolExecution: PendingToolExecution? = null,
    )

    private data class PendingToolExecution(
        val token: Long,
        val call: AgentToolCall,
        val toolName: String,
        val toolArguments: String,
        val assistantReasoning: String,
        val assistantContent: String,
        val responsesReasoningItems: List<String>,
        val authorizedSkillCatalogGeneration: Long?,
        val authorizedSkillCatalog: List<AgentSkillSummary>,
        val stepId: String,
    )

    private companion object {
        const val MIN_PROVIDER_EVENTS = 4_096
        const val PROVIDER_EVENT_OVERHEAD = 2_048
        const val CONNECTIVITY_TOTAL_TIMEOUT_MS = 30_000L
        const val HEARTBEAT_INTERVAL_MS = 2_500L
        const val MAX_AGENT_PROGRESS_TURNS = 2
        const val MAX_PRIVATE_REPLAY_CHARS = 262_144
        const val MAX_RESPONSES_REASONING_ITEMS = 8
        const val MIN_PLAIN_ANSWER_CHARS = 8

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
