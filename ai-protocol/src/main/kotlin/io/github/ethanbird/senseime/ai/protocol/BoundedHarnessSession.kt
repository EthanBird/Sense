package io.github.ethanbird.senseime.ai.protocol

/**
 * Hard limits for a single, provider-neutral harness run.
 *
 * Time is supplied by the caller as a monotonic value. The state machine never sleeps or creates a
 * thread, which keeps it deterministic in both host tests and the future isolated brain process.
 */
data class BoundedHarnessLimits(
    val firstEventTimeoutMs: Long = 5_000,
    val streamIdleTimeoutMs: Long = 5_000,
    val totalTimeoutMs: Long = 15_000,
    val maxProviderEvents: Int = 2_048,
    val maxPreviewChars: Int = SenseAiProtocol.DEFAULT_MAX_OUTPUT_CHARS,
    val maxPreviewResets: Int = 1,
) {
    init {
        require(firstEventTimeoutMs > 0)
        require(streamIdleTimeoutMs > 0)
        require(totalTimeoutMs > 0)
        require(firstEventTimeoutMs <= totalTimeoutMs)
        require(streamIdleTimeoutMs <= totalTimeoutMs)
        require(maxProviderEvents > 0)
        require(maxPreviewChars > 0)
        require(maxPreviewResets >= 0)
    }
}

enum class BoundedHarnessState {
    CREATED,
    STREAMING,
    VALIDATING,
    FINAL_PATCH,
    CANCELLED,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == FINAL_PATCH || this == CANCELLED || this == FAILED
}

enum class HarnessDropReason {
    REQUEST_MISMATCH,
    GENERATION_MISMATCH,
    NOT_STARTED,
    TERMINATED,
    INVALID_STATE,
}

sealed interface HarnessDispatch {
    val state: BoundedHarnessState

    data class Emitted(
        val event: AiEvent,
        override val state: BoundedHarnessState,
    ) : HarnessDispatch

    data class Dropped(
        val sourceEvent: AiEvent?,
        val reason: HarnessDropReason,
        override val state: BoundedHarnessState,
    ) : HarnessDispatch

    data class NoEvent(
        override val state: BoundedHarnessState,
    ) : HarnessDispatch
}

/**
 * Atomic request/generation gate plus the bounded lifecycle for one AI run.
 *
 * The class is thread-safe so a pointer release on the IME thread can race a provider callback
 * without allowing a late patch through. Cancellation wins as soon as [cancel] obtains the lock;
 * transport cancellation remains a separate resource-cleanup action performed by the caller.
 */
class BoundedHarnessSession(
    val request: HarnessRequestV1,
    val limits: BoundedHarnessLimits = BoundedHarnessLimits(),
) {
    private val lock = Any()
    private var mutableState = BoundedHarnessState.CREATED
    private var startedAtMs: Long? = null
    private var lastProviderEventAtMs: Long? = null
    private var lastObservedAtMs: Long? = null
    private var providerEventCount = 0
    private var currentPreviewChars = 0
    private var previewResetCount = 0
    private var terminalEvent: AiEvent? = null

    val state: BoundedHarnessState
        get() = synchronized(lock) { mutableState }

    val terminal: AiEvent?
        get() = synchronized(lock) { terminalEvent }

    val acceptedProviderEvents: Int
        get() = synchronized(lock) { providerEventCount }

    fun start(nowMonotonicMs: Long): HarnessDispatch = synchronized(lock) {
        observeTime(nowMonotonicMs)
        if (mutableState != BoundedHarnessState.CREATED) {
            return@synchronized HarnessDispatch.Dropped(
                sourceEvent = null,
                reason = HarnessDropReason.INVALID_STATE,
                state = mutableState,
            )
        }

        startedAtMs = nowMonotonicMs
        val validation = ProtocolValidator.validate(request)
        if (!validation.isValid) {
            return@synchronized fail(HarnessErrorCode.REQUEST_INVALID)
        }

        mutableState = BoundedHarnessState.STREAMING
        emit(
            AiEvent.Started(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                startedAtMonotonicMs = nowMonotonicMs,
            ),
        )
    }

    /**
     * Accepts one provider/brain event after checking timeout, request identity, and generation.
     *
     * A malformed current-generation terminal proposal is converted into one [AiEvent.Failed].
     * Wrong-request, stale-generation, pre-start, and post-terminal events are silently dropped.
     */
    fun accept(event: AiEvent, nowMonotonicMs: Long): HarnessDispatch = synchronized(lock) {
        observeTime(nowMonotonicMs)

        if (event.requestId != request.requestId) {
            return@synchronized dropped(event, HarnessDropReason.REQUEST_MISMATCH)
        }
        if (event.runGeneration != request.runGeneration) {
            return@synchronized dropped(event, HarnessDropReason.GENERATION_MISMATCH)
        }
        if (mutableState == BoundedHarnessState.CREATED) {
            return@synchronized dropped(event, HarnessDropReason.NOT_STARTED)
        }
        if (mutableState.isTerminal) {
            return@synchronized dropped(event, HarnessDropReason.TERMINATED)
        }

        expireIfNeeded(nowMonotonicMs)?.let { return@synchronized it }

        if (event is AiEvent.Started) {
            return@synchronized dropped(event, HarnessDropReason.INVALID_STATE)
        }
        if (providerEventCount >= limits.maxProviderEvents) {
            return@synchronized fail(HarnessErrorCode.EVENT_LIMIT_EXCEEDED)
        }

        providerEventCount += 1
        return@synchronized when (event) {
            is AiEvent.Status -> acceptStatus(event, nowMonotonicMs)
            is AiEvent.PreviewReset -> acceptPreviewReset(event, nowMonotonicMs)
            is AiEvent.PreviewDelta -> acceptPreviewDelta(event, nowMonotonicMs)
            is AiEvent.Usage -> acceptUsage(event, nowMonotonicMs)
            is AiEvent.FinalPatch -> acceptFinalPatch(event, nowMonotonicMs)
            is AiEvent.Cancelled -> {
                noteProviderEvent(nowMonotonicMs)
                mutableState = BoundedHarnessState.CANCELLED
                terminalEvent = event
                emit(event)
            }

            is AiEvent.Failed -> {
                noteProviderEvent(nowMonotonicMs)
                mutableState = BoundedHarnessState.FAILED
                terminalEvent = event
                emit(event)
            }

            is AiEvent.Started -> error("handled above")
        }
    }

    /**
     * Notes validated transport progress without surfacing provider-private reasoning or
     * consuming the typed event budget.
     *
     * The total deadline remains absolute. Identity and generation checks prevent stale network
     * callbacks from extending a newer run.
     */
    fun noteProviderActivity(
        requestId: String,
        runGeneration: Long,
        nowMonotonicMs: Long,
    ): HarnessDispatch = synchronized(lock) {
        observeTime(nowMonotonicMs)
        if (requestId != request.requestId) {
            return@synchronized HarnessDispatch.Dropped(
                sourceEvent = null,
                reason = HarnessDropReason.REQUEST_MISMATCH,
                state = mutableState,
            )
        }
        if (runGeneration != request.runGeneration) {
            return@synchronized HarnessDispatch.Dropped(
                sourceEvent = null,
                reason = HarnessDropReason.GENERATION_MISMATCH,
                state = mutableState,
            )
        }
        if (mutableState == BoundedHarnessState.CREATED) {
            return@synchronized HarnessDispatch.Dropped(
                sourceEvent = null,
                reason = HarnessDropReason.NOT_STARTED,
                state = mutableState,
            )
        }
        if (mutableState.isTerminal) {
            return@synchronized HarnessDispatch.Dropped(
                sourceEvent = null,
                reason = HarnessDropReason.TERMINATED,
                state = mutableState,
            )
        }
        expireIfNeeded(nowMonotonicMs)?.let { return@synchronized it }
        noteProviderEvent(nowMonotonicMs)
        HarnessDispatch.NoEvent(mutableState)
    }

    /**
     * Synchronously invalidates this generation. The caller should cancel Binder/coroutine/HTTP
     * work after this transition has completed.
     *
     * This method intentionally does not run timeout checks first: a pointer release at a deadline
     * is still a user cancellation and must win over a provider timeout callback.
     */
    fun cancel(
        requestId: String,
        runGeneration: Long,
        reason: HarnessCancelReason,
        nowMonotonicMs: Long,
    ): HarnessDispatch = synchronized(lock) {
        observeTime(nowMonotonicMs)
        if (requestId != request.requestId) {
            return@synchronized HarnessDispatch.Dropped(
                sourceEvent = null,
                reason = HarnessDropReason.REQUEST_MISMATCH,
                state = mutableState,
            )
        }
        if (runGeneration != request.runGeneration) {
            return@synchronized HarnessDispatch.Dropped(
                sourceEvent = null,
                reason = HarnessDropReason.GENERATION_MISMATCH,
                state = mutableState,
            )
        }
        if (mutableState == BoundedHarnessState.CREATED) {
            return@synchronized HarnessDispatch.Dropped(
                sourceEvent = null,
                reason = HarnessDropReason.NOT_STARTED,
                state = mutableState,
            )
        }
        if (mutableState.isTerminal) {
            return@synchronized HarnessDispatch.Dropped(
                sourceEvent = null,
                reason = HarnessDropReason.TERMINATED,
                state = mutableState,
            )
        }

        mutableState = BoundedHarnessState.CANCELLED
        val cancelled = AiEvent.Cancelled(
            requestId = request.requestId,
            runGeneration = request.runGeneration,
            reason = reason,
        )
        terminalEvent = cancelled
        emit(cancelled)
    }

    /** Advances deterministic time and emits at most one terminal timeout event. */
    fun advanceTo(nowMonotonicMs: Long): HarnessDispatch = synchronized(lock) {
        observeTime(nowMonotonicMs)
        if (mutableState == BoundedHarnessState.CREATED || mutableState.isTerminal) {
            return@synchronized HarnessDispatch.NoEvent(mutableState)
        }
        expireIfNeeded(nowMonotonicMs) ?: HarnessDispatch.NoEvent(mutableState)
    }

    private fun acceptStatus(event: AiEvent.Status, nowMonotonicMs: Long): HarnessDispatch {
        if (
            event.label.isBlank() ||
            event.label.length > MAX_STATUS_LABEL_CHARS ||
            !event.label.hasValidUnicodeScalars() ||
            event.label.any(Character::isISOControl)
        ) {
            return fail(HarnessErrorCode.INVALID_EVENT)
        }
        noteProviderEvent(nowMonotonicMs)
        return emit(event)
    }

    private fun acceptPreviewReset(
        event: AiEvent.PreviewReset,
        nowMonotonicMs: Long,
    ): HarnessDispatch {
        val expectedAttempt = previewResetCount + 2
        if (previewResetCount >= limits.maxPreviewResets) {
            return fail(HarnessErrorCode.REPAIR_LIMIT_EXCEEDED)
        }
        if (event.attempt != expectedAttempt) {
            return fail(HarnessErrorCode.INVALID_EVENT)
        }
        previewResetCount += 1
        currentPreviewChars = 0
        noteProviderEvent(nowMonotonicMs)
        return emit(event)
    }

    private fun acceptPreviewDelta(
        event: AiEvent.PreviewDelta,
        nowMonotonicMs: Long,
    ): HarnessDispatch {
        if (event.text.isEmpty() || !event.text.hasValidUnicodeScalars()) {
            return fail(HarnessErrorCode.INVALID_EVENT)
        }
        val previewLimit = minOf(limits.maxPreviewChars, request.maxOutputChars)
        if (event.text.length > previewLimit - currentPreviewChars) {
            return fail(HarnessErrorCode.PREVIEW_LIMIT_EXCEEDED)
        }
        currentPreviewChars += event.text.length
        noteProviderEvent(nowMonotonicMs)
        return emit(event)
    }

    private fun acceptUsage(event: AiEvent.Usage, nowMonotonicMs: Long): HarnessDispatch {
        if (event.inputTokens < 0 || event.outputTokens < 0) {
            return fail(HarnessErrorCode.INVALID_EVENT)
        }
        noteProviderEvent(nowMonotonicMs)
        return emit(event)
    }

    private fun acceptFinalPatch(
        event: AiEvent.FinalPatch,
        nowMonotonicMs: Long,
    ): HarnessDispatch {
        noteProviderEvent(nowMonotonicMs)
        mutableState = BoundedHarnessState.VALIDATING
        val validation = ProtocolValidator.validate(event.patch, request.snapshot)
        if (
            event.patch.requestId != event.requestId ||
            !validation.isValid
        ) {
            return fail(HarnessErrorCode.PROTOCOL_INVALID)
        }

        mutableState = BoundedHarnessState.FINAL_PATCH
        terminalEvent = event
        return emit(event)
    }

    private fun expireIfNeeded(nowMonotonicMs: Long): HarnessDispatch? {
        val started = startedAtMs ?: return null
        if (nowMonotonicMs - started >= limits.totalTimeoutMs) {
            return fail(HarnessErrorCode.TOTAL_TIMEOUT)
        }

        val lastProvider = lastProviderEventAtMs
        if (lastProvider == null) {
            if (nowMonotonicMs - started >= limits.firstEventTimeoutMs) {
                return fail(HarnessErrorCode.FIRST_EVENT_TIMEOUT)
            }
        } else if (nowMonotonicMs - lastProvider >= limits.streamIdleTimeoutMs) {
            return fail(HarnessErrorCode.STREAM_IDLE_TIMEOUT)
        }
        return null
    }

    private fun noteProviderEvent(nowMonotonicMs: Long) {
        lastProviderEventAtMs = nowMonotonicMs
    }

    private fun fail(code: HarnessErrorCode): HarnessDispatch {
        mutableState = BoundedHarnessState.FAILED
        val failure = AiEvent.Failed(
            requestId = request.requestId,
            runGeneration = request.runGeneration,
            code = code,
        )
        terminalEvent = failure
        return emit(failure)
    }

    private fun emit(event: AiEvent): HarnessDispatch.Emitted =
        HarnessDispatch.Emitted(event = event, state = mutableState)

    private fun dropped(event: AiEvent, reason: HarnessDropReason): HarnessDispatch.Dropped =
        HarnessDispatch.Dropped(sourceEvent = event, reason = reason, state = mutableState)

    private fun observeTime(nowMonotonicMs: Long) {
        require(nowMonotonicMs >= 0) { "monotonic time must be non-negative" }
        val previous = lastObservedAtMs
        require(previous == null || nowMonotonicMs >= previous) {
            "monotonic time moved backwards: $nowMonotonicMs < $previous"
        }
        lastObservedAtMs = nowMonotonicMs
    }

    private fun String.hasValidUnicodeScalars(): Boolean {
        for (index in indices) {
            val current = this[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) {
                        return false
                    }
                }

                Character.isLowSurrogate(current) -> {
                    if (index == 0 || !Character.isHighSurrogate(this[index - 1])) {
                        return false
                    }
                }
            }
        }
        return indexOf('\u0000') < 0
    }

    private companion object {
        const val MAX_STATUS_LABEL_CHARS = 128
    }
}
