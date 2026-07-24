package io.github.ethanbird.senseime.service.ai.editor

enum class EditorTransactionState {
    STREAMING,
    VALIDATING,
    APPLYING,
    APPLIED,
    CANCELLED,
    STALE,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == APPLIED || this == CANCELLED || this == STALE || this == FAILED
}

enum class EditorTransactionCancelReason {
    POINTER_RELEASED,
    POINTER_CANCELLED,
    INPUT_CONNECTION_LOST,
    WINDOW_HIDDEN,
    CONFIGURATION_CHANGED,
    BRAIN_DIED,
    CALLER_REQUESTED,
}

enum class EditorStaleReason {
    START_INPUT,
    FINISH_INPUT,
    SELECTION_CHANGED,
    TEXT_CHANGED,
    FIELD_IDENTITY_CHANGED,
    INPUT_CONNECTION_CHANGED,
}

enum class EditorTransactionFailure {
    PATCH_REJECTED,
    EDITOR_REJECTED,
    POST_APPLY_VERIFICATION_FAILED,
    INTERNAL_FAILURE,
}

data class EditorTransactionSnapshot(
    val requestId: String,
    val runGeneration: Long,
    val editorGeneration: Long,
    val state: EditorTransactionState,
    val cancelReason: EditorTransactionCancelReason?,
    val staleReason: EditorStaleReason?,
    val failure: EditorTransactionFailure?,
    val applicationToken: Long?,
)

enum class EditorTransitionRejectReason {
    REQUEST_MISMATCH,
    RUN_GENERATION_MISMATCH,
    INVALID_STATE,
    APPLICATION_TOKEN_MISMATCH,
    TERMINATED,
}

sealed interface EditorTransactionTransition {
    data class Changed(
        val previous: EditorTransactionState,
        val current: EditorTransactionSnapshot,
    ) : EditorTransactionTransition

    /** Callback caused by this transaction's own in-flight patch; it does not stale the lease. */
    data class OwnApplyObservationIgnored(
        val current: EditorTransactionSnapshot,
    ) : EditorTransactionTransition

    data class Rejected(
        val reason: EditorTransitionRejectReason,
        val current: EditorTransactionSnapshot,
    ) : EditorTransactionTransition
}

/**
 * Thread-safe local CAS boundary for cancellation, stale editor callbacks, and patch application.
 *
 * A pointer cancellation and a final patch may race. Whichever obtains the lock first wins:
 * cancellation makes validation/apply impossible; a successful VALIDATING -> APPLYING transition
 * makes a later cancellation too late to interrupt a partly-issued editor transaction.
 */
class EditorTransactionStateMachine(
    val requestId: String,
    val runGeneration: Long,
    val editorGeneration: Long,
) {
    init {
        require(requestId.isNotBlank())
        require(runGeneration > 0)
        require(editorGeneration > 0)
    }

    private val lock = Any()
    private var mutableState = EditorTransactionState.STREAMING
    private var mutableCancelReason: EditorTransactionCancelReason? = null
    private var mutableStaleReason: EditorStaleReason? = null
    private var mutableFailure: EditorTransactionFailure? = null
    private var mutableApplicationToken: Long? = null

    val snapshot: EditorTransactionSnapshot
        get() = synchronized(lock) { snapshotLocked() }

    fun beginValidation(
        requestId: String,
        runGeneration: Long,
    ): EditorTransactionTransition = synchronized(lock) {
        identityError(requestId, runGeneration)?.let { return@synchronized rejected(it) }
        if (mutableState.isTerminal) {
            return@synchronized rejected(EditorTransitionRejectReason.TERMINATED)
        }
        if (mutableState != EditorTransactionState.STREAMING) {
            return@synchronized rejected(EditorTransitionRejectReason.INVALID_STATE)
        }
        transitionTo(EditorTransactionState.VALIDATING)
    }

    fun tryBeginApply(
        requestId: String,
        runGeneration: Long,
        applicationToken: Long,
    ): EditorTransactionTransition = synchronized(lock) {
        identityError(requestId, runGeneration)?.let { return@synchronized rejected(it) }
        if (mutableState.isTerminal) {
            return@synchronized rejected(EditorTransitionRejectReason.TERMINATED)
        }
        if (mutableState != EditorTransactionState.VALIDATING || applicationToken <= 0) {
            return@synchronized rejected(EditorTransitionRejectReason.INVALID_STATE)
        }
        mutableApplicationToken = applicationToken
        transitionTo(EditorTransactionState.APPLYING)
    }

    fun cancel(
        requestId: String,
        runGeneration: Long,
        reason: EditorTransactionCancelReason,
    ): EditorTransactionTransition = synchronized(lock) {
        identityError(requestId, runGeneration)?.let { return@synchronized rejected(it) }
        if (mutableState.isTerminal) {
            return@synchronized rejected(EditorTransitionRejectReason.TERMINATED)
        }
        // Applying is the CAS point. Stopping after beginBatchEdit could leave an editor half-mutated.
        if (mutableState == EditorTransactionState.APPLYING) {
            return@synchronized rejected(EditorTransitionRejectReason.INVALID_STATE)
        }
        mutableCancelReason = reason
        transitionTo(EditorTransactionState.CANCELLED)
    }

    /**
     * Invalidates the frozen snapshot for any external editor mutation.
     *
     * During APPLYING, only a callback carrying the exact local [ownApplicationToken] is ignored.
     * A missing, wrong, or replayed token is an external change and makes the transaction stale.
     */
    fun markEditorChanged(
        reason: EditorStaleReason,
        ownApplicationToken: Long? = null,
    ): EditorTransactionTransition = synchronized(lock) {
        if (mutableState.isTerminal) {
            return@synchronized rejected(EditorTransitionRejectReason.TERMINATED)
        }
        if (
            mutableState == EditorTransactionState.APPLYING &&
            ownApplicationToken != null &&
            ownApplicationToken == mutableApplicationToken &&
            (
                reason == EditorStaleReason.TEXT_CHANGED ||
                    reason == EditorStaleReason.SELECTION_CHANGED
                )
        ) {
            return@synchronized EditorTransactionTransition.OwnApplyObservationIgnored(
                snapshotLocked(),
            )
        }
        mutableStaleReason = reason
        transitionTo(EditorTransactionState.STALE)
    }

    fun markApplied(
        requestId: String,
        runGeneration: Long,
        applicationToken: Long,
    ): EditorTransactionTransition = synchronized(lock) {
        identityError(requestId, runGeneration)?.let { return@synchronized rejected(it) }
        if (mutableState.isTerminal) {
            return@synchronized rejected(EditorTransitionRejectReason.TERMINATED)
        }
        if (mutableState != EditorTransactionState.APPLYING) {
            return@synchronized rejected(EditorTransitionRejectReason.INVALID_STATE)
        }
        if (applicationToken != mutableApplicationToken) {
            return@synchronized rejected(
                EditorTransitionRejectReason.APPLICATION_TOKEN_MISMATCH,
            )
        }
        transitionTo(EditorTransactionState.APPLIED)
    }

    fun fail(
        failure: EditorTransactionFailure,
        applicationToken: Long? = null,
    ): EditorTransactionTransition = synchronized(lock) {
        if (mutableState.isTerminal) {
            return@synchronized rejected(EditorTransitionRejectReason.TERMINATED)
        }
        if (
            mutableState == EditorTransactionState.APPLYING &&
            applicationToken != mutableApplicationToken
        ) {
            return@synchronized rejected(
                EditorTransitionRejectReason.APPLICATION_TOKEN_MISMATCH,
            )
        }
        mutableFailure = failure
        transitionTo(EditorTransactionState.FAILED)
    }

    private fun identityError(
        actualRequestId: String,
        actualRunGeneration: Long,
    ): EditorTransitionRejectReason? = when {
        actualRequestId != requestId -> EditorTransitionRejectReason.REQUEST_MISMATCH
        actualRunGeneration != runGeneration ->
            EditorTransitionRejectReason.RUN_GENERATION_MISMATCH
        else -> null
    }

    private fun transitionTo(state: EditorTransactionState): EditorTransactionTransition {
        val previous = mutableState
        mutableState = state
        return EditorTransactionTransition.Changed(previous, snapshotLocked())
    }

    private fun rejected(
        reason: EditorTransitionRejectReason,
    ): EditorTransactionTransition.Rejected =
        EditorTransactionTransition.Rejected(reason, snapshotLocked())

    private fun snapshotLocked(): EditorTransactionSnapshot = EditorTransactionSnapshot(
        requestId = requestId,
        runGeneration = runGeneration,
        editorGeneration = editorGeneration,
        state = mutableState,
        cancelReason = mutableCancelReason,
        staleReason = mutableStaleReason,
        failure = mutableFailure,
        applicationToken = mutableApplicationToken,
    )
}
