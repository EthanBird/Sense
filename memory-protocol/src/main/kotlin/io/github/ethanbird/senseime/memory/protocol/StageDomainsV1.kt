package io.github.ethanbird.senseime.memory.protocol

/**
 * Closed normal product stage domain from ADR 0018.
 *
 * This domain is deliberately separate from [GateVerdictV1] and
 * [X02StageDecisionDispositionV1]. Callers must use [FeatureStageOrderV1] instead of enum ordinals.
 */
enum class FeatureStageV1(
    val wireValue: String,
) {
    OFF("OFF"),
    SCHEMA_ONLY("SCHEMA_ONLY"),
    DARK("DARK"),
    SHADOW("SHADOW"),
    CANARY("CANARY"),
    DEFAULT("DEFAULT"),
    ;

    companion object {
        fun fromWireValue(value: String): FeatureStageV1? =
            entries.singleOrNull { it.wireValue == value }
    }
}

/**
 * Explicit stage order. Enum ordinals are not part of the protocol or authorization contract.
 */
object FeatureStageOrderV1 {
    fun min(
        left: FeatureStageV1,
        right: FeatureStageV1,
    ): FeatureStageV1 =
        if (isAtMost(left, right)) left else right

    fun minOf(
        first: FeatureStageV1,
        vararg rest: FeatureStageV1,
    ): FeatureStageV1 = rest.fold(first, ::min)

    fun isAtMost(
        candidate: FeatureStageV1,
        ceiling: FeatureStageV1,
    ): Boolean = rank(candidate) <= rank(ceiling)

    private fun rank(stage: FeatureStageV1): Int =
        when (stage) {
            FeatureStageV1.OFF -> 0
            FeatureStageV1.SCHEMA_ONLY -> 1
            FeatureStageV1.DARK -> 2
            FeatureStageV1.SHADOW -> 3
            FeatureStageV1.CANARY -> 4
            FeatureStageV1.DEFAULT -> 5
        }
}

/**
 * Closed gate result domain from ADR 0018.
 *
 * Only [PASS] can satisfy a future normal-stage prerequisite. X-02 does not install any PASS
 * authority and never converts this enum into [FeatureStageV1].
 */
enum class GateVerdictV1(
    val wireValue: String,
) {
    PASS("PASS"),
    BLOCKED("BLOCKED"),
    FAIL("FAIL"),
    INVALID("INVALID"),
    INCONCLUSIVE("INCONCLUSIVE"),
    UNSUPPORTED("UNSUPPORTED"),
    MEASURED_NO_BUDGET("MEASURED_NO_BUDGET"),
    ;

    companion object {
        fun fromWireValue(value: String): GateVerdictV1? =
            entries.singleOrNull { it.wireValue == value }
    }
}

/**
 * Closed measurement admission result. It is neither a product stage nor a gate verdict.
 *
 * X-02 provides no permit issuer; this type only prevents future APIs from reusing a boolean or
 * accidentally casting a measurement result into [FeatureStageV1].
 */
enum class PermitDecisionV1(
    val wireValue: String,
) {
    ALLOW("ALLOW"),
    NOT_RUN_BLOCKED("NOT_RUN_BLOCKED"),
    ;

    companion object {
        fun fromWireValue(value: String): PermitDecisionV1? =
            entries.singleOrNull { it.wireValue == value }
    }
}

/**
 * Closed finite profile class. X-02 only accepts [SCHEMA_ONLY].
 *
 * A future operational caller must receive this value from an authenticated provenance authority;
 * X-02 intentionally provides no such authority.
 */
enum class ProfileExecutionClassV1(
    val wireValue: String,
) {
    SCHEMA_ONLY("SCHEMA_ONLY"),
    PRODUCT_SYNTHETIC("PRODUCT_SYNTHETIC"),
    REAL_DATA("REAL_DATA"),
    ;

    companion object {
        fun fromWireValue(value: String): ProfileExecutionClassV1? =
            entries.singleOrNull { it.wireValue == value }
    }
}

/**
 * Public finite-profile capabilities frozen by ADR 0018 §14.3.
 *
 * Internal DAG nodes and rollout aggregates are intentionally absent. In X-02, only
 * [SCHEMA_CODEC] is a valid request and it cannot issue an operation or persistent child effect.
 */
enum class NormalProfileCapabilityIdV1(
    val wireValue: String,
) {
    SCHEMA_CODEC("SCHEMA_CODEC"),
    CAPTURE("CAPTURE"),
    WARM_RECALL("WARM_RECALL"),
    COLD_RECALL("COLD_RECALL"),
    HOT_SNAPSHOT("HOT_SNAPSHOT"),
    INDEX_REBUILD("INDEX_REBUILD"),
    MAINTENANCE("MAINTENANCE"),
    KEY_ROTATION("KEY_ROTATION"),
    EXPORT_EGRESS("EXPORT_EGRESS"),
    ;

    companion object {
        fun fromWireValue(value: String): NormalProfileCapabilityIdV1? =
            entries.singleOrNull { it.wireValue == value }
    }
}

/**
 * Reducer result domain. This is not a gate verdict and cannot authorize an operation.
 */
enum class X02StageDecisionDispositionV1 {
    VALID_OFF_REQUEST,
    VALID_SCHEMA_REQUEST,
    BLOCKED_FAIL_CLOSED,
}
