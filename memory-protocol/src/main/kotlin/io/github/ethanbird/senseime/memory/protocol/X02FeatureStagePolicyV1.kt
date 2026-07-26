package io.github.ethanbird.senseime.memory.protocol

/**
 * An untrusted X-02 test observation. It is not an authenticated snapshot receipt.
 */
data class X02GateObservationV1(
    val gateId: GateIdV1,
    val verdict: GateVerdictV1,
)

data class X02NormalStageRequestV1(
    val capability: NormalProfileCapabilityIdV1,
    val requestedStage: FeatureStageV1,
    val executionClass: ProfileExecutionClassV1,
    val buildProfileMax: FeatureStageV1,
    val localRequestedStage: FeatureStageV1,
    val dependencyStage: FeatureStageV1,
    /**
     * A list is intentional: an input map would silently erase duplicate GateIds.
     *
     * X-02's only valid SCHEMA_CODEC request has an exact empty prerequisite set.
     */
    val exactGateObservations: List<X02GateObservationV1> = emptyList(),
)

enum class X02StageDecisionReasonV1 {
    REQUESTED_OFF,
    SCHEMA_CODEC_ONLY,
    X02_HARD_MAXIMUM,
    EXECUTION_CLASS_NOT_SCHEMA_ONLY,
    PERSISTENT_CAPABILITY_BLOCKED,
    GATE_SET_MUST_BE_EMPTY,
    DUPLICATE_GATE_OBSERVATION,
    CONFIGURED_CEILING_CONTRACTED,
}

/**
 * Provisional X-02 ceiling decision. It cannot authorize an operation or mint an effect token.
 *
 * The future ADR 0018 NormalStageDecisionV1 ABI remains intentionally undefined here.
 */
class X02StageDecisionV1 private constructor(
    val effectiveStage: FeatureStageV1,
    val disposition: X02StageDecisionDispositionV1,
    reasons: List<X02StageDecisionReasonV1>,
) {
    private val stableReasons = reasons.toList()

    init {
        require(
            FeatureStageOrderV1.isAtMost(
                effectiveStage,
                FeatureStageV1.SCHEMA_ONLY,
            ),
        )
        require(stableReasons.isNotEmpty())
        require(stableReasons.distinct().size == stableReasons.size)
        when (disposition) {
            X02StageDecisionDispositionV1.VALID_OFF_REQUEST -> {
                require(effectiveStage == FeatureStageV1.OFF)
                require(
                    stableReasons ==
                        listOf(X02StageDecisionReasonV1.REQUESTED_OFF),
                )
            }

            X02StageDecisionDispositionV1.VALID_SCHEMA_REQUEST -> {
                require(X02StageDecisionReasonV1.SCHEMA_CODEC_ONLY in stableReasons)
                require(X02StageDecisionReasonV1.REQUESTED_OFF !in stableReasons)
                require(stableReasons.none { it in blockingReasons })
            }

            X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED -> {
                require(stableReasons.any { it in blockingReasons })
                require(X02StageDecisionReasonV1.SCHEMA_CODEC_ONLY !in stableReasons)
            }
        }
    }

    val reasons: List<X02StageDecisionReasonV1>
        get() = stableReasons.toList()

    companion object {
        internal fun issue(
            effectiveStage: FeatureStageV1,
            disposition: X02StageDecisionDispositionV1,
            reasons: List<X02StageDecisionReasonV1>,
        ): X02StageDecisionV1 {
            return X02StageDecisionV1(
                effectiveStage = effectiveStage,
                disposition = disposition,
                reasons = reasons,
            )
        }

        private val blockingReasons = setOf(
            X02StageDecisionReasonV1.X02_HARD_MAXIMUM,
            X02StageDecisionReasonV1.EXECUTION_CLASS_NOT_SCHEMA_ONLY,
            X02StageDecisionReasonV1.PERSISTENT_CAPABILITY_BLOCKED,
            X02StageDecisionReasonV1.GATE_SET_MUST_BE_EMPTY,
            X02StageDecisionReasonV1.DUPLICATE_GATE_OBSERVATION,
        )
    }
}

/**
 * X-02's executable stage policy.
 *
 * It proves only the safe floor and contraction behavior. It does not implement the future
 * authenticated StageSnapshot wire, a normal product DAG authority, operation admission, or any
 * persistent effect.
 */
object X02FeatureStagePolicyV1 {
    fun reduce(request: X02NormalStageRequestV1): X02StageDecisionV1 {
        val exactGateObservations = request.exactGateObservations.toList()
        val configuredCeiling = FeatureStageOrderV1.minOf(
            request.requestedStage,
            request.buildProfileMax,
            request.localRequestedStage,
            request.dependencyStage,
        )

        if (request.requestedStage == FeatureStageV1.OFF) {
            val gateReasons = gateSetReasons(exactGateObservations)
            return X02StageDecisionV1.issue(
                effectiveStage = FeatureStageV1.OFF,
                disposition = if (gateReasons.isEmpty()) {
                    X02StageDecisionDispositionV1.VALID_OFF_REQUEST
                } else {
                    X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED
                },
                reasons = listOf(X02StageDecisionReasonV1.REQUESTED_OFF) + gateReasons,
            )
        }

        val reasons = mutableListOf<X02StageDecisionReasonV1>()
        if (request.capability != NormalProfileCapabilityIdV1.SCHEMA_CODEC) {
            reasons += X02StageDecisionReasonV1.PERSISTENT_CAPABILITY_BLOCKED
        }
        if (request.requestedStage != FeatureStageV1.SCHEMA_ONLY) {
            reasons += X02StageDecisionReasonV1.X02_HARD_MAXIMUM
        }
        if (request.executionClass != ProfileExecutionClassV1.SCHEMA_ONLY) {
            reasons += X02StageDecisionReasonV1.EXECUTION_CLASS_NOT_SCHEMA_ONLY
        }
        reasons += gateSetReasons(exactGateObservations)

        val effectiveStage = FeatureStageOrderV1.min(
            configuredCeiling,
            FeatureStageV1.SCHEMA_ONLY,
        )
        val validSchemaRequest = reasons.isEmpty()
        if (validSchemaRequest) {
            reasons += X02StageDecisionReasonV1.SCHEMA_CODEC_ONLY
        }
        if (effectiveStage != request.requestedStage) {
            reasons += X02StageDecisionReasonV1.CONFIGURED_CEILING_CONTRACTED
        }

        return X02StageDecisionV1.issue(
            effectiveStage = effectiveStage,
            disposition = if (validSchemaRequest) {
                X02StageDecisionDispositionV1.VALID_SCHEMA_REQUEST
            } else {
                X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED
            },
            reasons = reasons,
        )
    }

    private fun gateSetReasons(
        observations: List<X02GateObservationV1>,
    ): List<X02StageDecisionReasonV1> {
        if (observations.isEmpty()) {
            return emptyList()
        }
        val reasons = mutableListOf(X02StageDecisionReasonV1.GATE_SET_MUST_BE_EMPTY)
        if (observations.map { it.gateId }.toSet().size != observations.size) {
            reasons += X02StageDecisionReasonV1.DUPLICATE_GATE_OBSERVATION
        }
        return reasons
    }
}
