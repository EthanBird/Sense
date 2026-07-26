package io.github.ethanbird.senseime.memory.protocol

import java.util.concurrent.atomic.AtomicReference

/**
 * Closed rejection inputs understood before the authenticated snapshot phase exists.
 *
 * This is not a parser result for a persistent FeatureStageSnapshotV1 wire. X-02 deliberately has
 * no production byte, file, generation, digest, slot, or authentication-envelope API.
 */
enum class X02RejectedStageInputV1 {
    ABSENT,
    CORRUPT,
    UNKNOWN,
    UNAUTHENTICATED,
}

/**
 * Process-local diagnostic only. It has no persistent or cross-process wire token.
 */
enum class X02SafeStageCauseV1 {
    PROCESS_START,
    SOURCE_ABSENT,
    SOURCE_CORRUPT,
    SOURCE_UNKNOWN,
    SOURCE_UNAUTHENTICATED,
}

/**
 * Immutable process-local safety ceiling.
 *
 * The constructor is private, the class is not a data class, and every issued instance has the
 * same [FeatureStageV1.SCHEMA_ONLY] ceiling. It cannot represent a validated higher-stage
 * snapshot or authorize an operation.
 */
class X02SafeStageViewV1 private constructor(
    val cause: X02SafeStageCauseV1,
) {
    val normalStageCeiling: FeatureStageV1
        get() = FeatureStageV1.SCHEMA_ONLY

    companion object {
        private val processStart =
            X02SafeStageViewV1(X02SafeStageCauseV1.PROCESS_START)
        private val sourceAbsent =
            X02SafeStageViewV1(X02SafeStageCauseV1.SOURCE_ABSENT)
        private val sourceCorrupt =
            X02SafeStageViewV1(X02SafeStageCauseV1.SOURCE_CORRUPT)
        private val sourceUnknown =
            X02SafeStageViewV1(X02SafeStageCauseV1.SOURCE_UNKNOWN)
        private val sourceUnauthenticated =
            X02SafeStageViewV1(X02SafeStageCauseV1.SOURCE_UNAUTHENTICATED)

        internal fun processStart(): X02SafeStageViewV1 = processStart

        internal fun rejected(input: X02RejectedStageInputV1): X02SafeStageViewV1 =
            when (input) {
                X02RejectedStageInputV1.ABSENT -> sourceAbsent
                X02RejectedStageInputV1.CORRUPT -> sourceCorrupt
                X02RejectedStageInputV1.UNKNOWN -> sourceUnknown
                X02RejectedStageInputV1.UNAUTHENTICATED -> sourceUnauthenticated
            }
    }
}

/**
 * Total fail-closed reducer for all production inputs accepted in X-02.
 */
object X02FailClosedStageReducerV1 {
    fun reduce(input: X02RejectedStageInputV1): X02SafeStageViewV1 =
        X02SafeStageViewV1.rejected(input)

    internal fun processStart(): X02SafeStageViewV1 =
        X02SafeStageViewV1.processStart()
}

/**
 * Testable process-role label only. It is not an authenticated process identity or wire token.
 */
enum class X02StageConsumerRoleV1 {
    MAIN,
    IME,
    BRAIN,
}

/**
 * Process-local, heap-only holder used to prove main/IME/Brain cold-safe semantics.
 *
 * It is not a cross-process store or watcher. It exposes no setter for a stage or arbitrary view,
 * and its AtomicReference never escapes.
 */
class X02ProcessStageHolderV1 private constructor(
    val role: X02StageConsumerRoleV1,
) {
    private val view =
        AtomicReference(X02FailClosedStageReducerV1.processStart())

    fun currentView(): X02SafeStageViewV1 = view.get()

    fun failClosedOn(input: X02RejectedStageInputV1) {
        view.set(X02FailClosedStageReducerV1.reduce(input))
    }

    companion object {
        fun newMainHolder(): X02ProcessStageHolderV1 =
            X02ProcessStageHolderV1(X02StageConsumerRoleV1.MAIN)

        fun newImeHolder(): X02ProcessStageHolderV1 =
            X02ProcessStageHolderV1(X02StageConsumerRoleV1.IME)

        fun newBrainHolder(): X02ProcessStageHolderV1 =
            X02ProcessStageHolderV1(X02StageConsumerRoleV1.BRAIN)
    }
}
