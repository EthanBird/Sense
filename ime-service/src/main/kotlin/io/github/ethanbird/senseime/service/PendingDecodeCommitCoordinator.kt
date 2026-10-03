package io.github.ethanbird.senseime.service

/** Why input is waiting for an asynchronous decoder result. */
internal sealed interface PendingDecodeCommit {
    val presentationRevision: Long

    /** Space/punctuation/tool input is waiting for the active candidate list. */
    data class Candidate(
        override val presentationRevision: Long,
    ) : PendingDecodeCommit

    /** A fifth Wubi shape key is held as the transaction trigger, outside the follow-up queue. */
    data class WubiOverflow(
        override val presentationRevision: Long,
    ) : PendingDecodeCommit
}

/** Immutable transaction contents detached before a potentially reentrant host commit. */
internal data class FinishedPendingDecodeCommit<Input : Any>(
    val intent: PendingDecodeCommit,
    val triggerInput: Input?,
    val followUpInputs: List<Input>,
)

internal enum class DeferredInputOffer {
    NOT_PENDING,
    ACCEPTED,
    CAPACITY_REACHED,
}

/** Main-thread state holder for pending decode intent and bounded follow-up input. */
internal class PendingDecodeCommitCoordinator<Input : Any>(
    private val maximumDeferredInputs: Int,
) {
    init {
        require(maximumDeferredInputs > 0)
    }

    private var active: PendingDecodeCommit? = null
    private var trigger: Input? = null
    private val deferred = ArrayDeque<Input>(maximumDeferredInputs)
    var needsAttention: Boolean = false
        private set
    var capacityRejected: Boolean = false
        private set

    val intent: PendingDecodeCommit?
        get() = active
    val isPending: Boolean
        get() = active != null
    val presentationRevision: Long?
        get() = active?.presentationRevision
    val deferredCount: Int
        get() = deferred.size
    val isFull: Boolean
        get() = deferred.size >= maximumDeferredInputs
    val lastQueuedInput: Input?
        get() = deferred.lastOrNull() ?: trigger

    /** Edits the not-yet-dispatched tail, never an earlier input or an already executed action. */
    fun replaceLastQueuedInput(input: Input?) {
        check(active != null)
        if (deferred.isNotEmpty()) {
            deferred.removeLast()
            input?.let(deferred::addLast)
        } else {
            trigger = input
        }
    }

    fun start(intent: PendingDecodeCommit, triggerInput: Input? = null) {
        check(active == null) { "A pending decode commit is already active" }
        check(deferred.isEmpty()) { "Deferred input exists without a pending decode commit" }
        active = intent
        trigger = triggerInput
        needsAttention = false
        capacityRejected = false
    }

    /** A soft deadline changes presentation, never the user's confirmation intent or FIFO. */
    fun markDelayed(presentationRevision: Long): Boolean {
        if (active?.presentationRevision != presentationRevision) return false
        needsAttention = true
        return true
    }

    fun markCapacityRejected(): Boolean {
        check(isFull && active != null)
        val first = !capacityRejected
        capacityRejected = true
        return first
    }

    fun defer(input: Input): DeferredInputOffer {
        if (active == null) return DeferredInputOffer.NOT_PENDING
        if (deferred.size >= maximumDeferredInputs) {
            return DeferredInputOffer.CAPACITY_REACHED
        }
        deferred.addLast(input)
        return DeferredInputOffer.ACCEPTED
    }

    /**
     * Detaches the matching transaction before calling the host. Synchronous selection callbacks
     * may clear the coordinator, but they cannot erase the returned trigger and FIFO snapshot.
     */
    fun finish(presentationRevision: Long? = null): FinishedPendingDecodeCommit<Input>? {
        val value = active ?: return null
        if (
            presentationRevision != null &&
            value.presentationRevision != presentationRevision
        ) {
            return null
        }
        val completion = FinishedPendingDecodeCommit(
            intent = value,
            triggerInput = trigger,
            followUpInputs = deferred.toList(),
        )
        active = null
        trigger = null
        deferred.clear()
        needsAttention = false
        capacityRejected = false
        return completion
    }

    fun clearAll() {
        active = null
        trigger = null
        deferred.clear()
        needsAttention = false
        capacityRejected = false
    }
}
