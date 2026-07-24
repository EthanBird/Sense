package io.github.ethanbird.senseime.ai.protocol

/**
 * Manually-clocked fake used to exercise the complete bounded-session contract without Android,
 * networking, sleeping, or background threads.
 *
 * Tests and the M8 fake brain can inject events in any order, including callbacks that arrive
 * after cancellation. [events] contains only accepted/emitted events; [dropped] records callbacks
 * rejected by the request/generation/state gate.
 */
class DeterministicFakeHarness(
    request: HarnessRequestV1,
    limits: BoundedHarnessLimits = BoundedHarnessLimits(),
    initialMonotonicMs: Long = 0,
) {
    private val session = BoundedHarnessSession(request, limits)
    private val mutableEvents = mutableListOf<AiEvent>()
    private val mutableDropped = mutableListOf<HarnessDispatch.Dropped>()
    private var mutableNowMonotonicMs = initialMonotonicMs

    val request: HarnessRequestV1
        get() = session.request

    val nowMonotonicMs: Long
        get() = mutableNowMonotonicMs

    val state: BoundedHarnessState
        get() = session.state

    val events: List<AiEvent>
        get() = mutableEvents.toList()

    val dropped: List<HarnessDispatch.Dropped>
        get() = mutableDropped.toList()

    val terminalEvents: List<AiEvent>
        get() = mutableEvents.filter(AiEvent::isTerminal)

    var transportCancellationRequested: Boolean = false
        private set

    fun start(): HarnessDispatch = record(session.start(mutableNowMonotonicMs))

    fun emit(event: AiEvent): HarnessDispatch =
        record(session.accept(event, mutableNowMonotonicMs))

    fun status(phase: HarnessPhase, label: String): HarnessDispatch =
        emit(
            AiEvent.Status(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                phase = phase,
                label = label,
            ),
        )

    fun previewDelta(text: String): HarnessDispatch =
        emit(
            AiEvent.PreviewDelta(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                text = text,
            ),
        )

    fun previewReset(attempt: Int): HarnessDispatch =
        emit(
            AiEvent.PreviewReset(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                attempt = attempt,
            ),
        )

    fun finalPatch(patch: EditorPatchV1): HarnessDispatch =
        emit(
            AiEvent.FinalPatch(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                patch = patch,
            ),
        )

    fun providerFailure(
        code: HarnessErrorCode = HarnessErrorCode.PROVIDER_FAILURE,
        retryable: Boolean = false,
    ): HarnessDispatch =
        emit(
            AiEvent.Failed(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                code = code,
                retryable = retryable,
            ),
        )

    fun release(
        reason: HarnessCancelReason = HarnessCancelReason.POINTER_RELEASED,
    ): HarnessDispatch {
        val dispatch = session.cancel(
            requestId = request.requestId,
            runGeneration = request.runGeneration,
            reason = reason,
            nowMonotonicMs = mutableNowMonotonicMs,
        )
        if (dispatch is HarnessDispatch.Emitted && dispatch.event is AiEvent.Cancelled) {
            transportCancellationRequested = true
        }
        return record(dispatch)
    }

    fun advanceBy(deltaMs: Long): HarnessDispatch {
        require(deltaMs >= 0) { "delta must be non-negative" }
        mutableNowMonotonicMs = Math.addExact(mutableNowMonotonicMs, deltaMs)
        return record(session.advanceTo(mutableNowMonotonicMs))
    }

    fun advanceTo(nowMonotonicMs: Long): HarnessDispatch {
        val dispatch = session.advanceTo(nowMonotonicMs)
        mutableNowMonotonicMs = nowMonotonicMs
        return record(dispatch)
    }

    private fun record(dispatch: HarnessDispatch): HarnessDispatch {
        when (dispatch) {
            is HarnessDispatch.Emitted -> mutableEvents += dispatch.event
            is HarnessDispatch.Dropped -> mutableDropped += dispatch
            is HarnessDispatch.NoEvent -> Unit
        }
        return dispatch
    }
}
