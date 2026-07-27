package io.github.ethanbird.senseime.memory.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class X02FeatureStagePolicyV1Test {
    @Test
    fun everyTypedInputRespectsAllStageCeilingsAndX02HardMaximum() {
        NormalProfileCapabilityIdV1.entries.forEach { capability ->
            FeatureStageV1.entries.forEach { requested ->
                ProfileExecutionClassV1.entries.forEach { executionClass ->
                    FeatureStageV1.entries.forEach { build ->
                        FeatureStageV1.entries.forEach { local ->
                            FeatureStageV1.entries.forEach { dependency ->
                                val decision = X02FeatureStagePolicyV1.reduce(
                                    schemaRequest(
                                        capability = capability,
                                        requestedStage = requested,
                                        executionClass = executionClass,
                                        buildProfileMax = build,
                                        localRequestedStage = local,
                                        dependencyStage = dependency,
                                    ),
                                )

                                assertTrue(
                                    FeatureStageOrderV1.isAtMost(
                                        decision.effectiveStage,
                                        requested,
                                    ),
                                )
                                assertTrue(
                                    FeatureStageOrderV1.isAtMost(
                                        decision.effectiveStage,
                                        build,
                                    ),
                                )
                                assertTrue(
                                    FeatureStageOrderV1.isAtMost(
                                        decision.effectiveStage,
                                        local,
                                    ),
                                )
                                assertTrue(
                                    FeatureStageOrderV1.isAtMost(
                                        decision.effectiveStage,
                                        dependency,
                                    ),
                                )
                                assertTrue(
                                    FeatureStageOrderV1.isAtMost(
                                        decision.effectiveStage,
                                        FeatureStageV1.SCHEMA_ONLY,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun validSchemaCodecRequestCannotExceedAnyConfiguredCeiling() {
        FeatureStageV1.entries.forEach { build ->
            FeatureStageV1.entries.forEach { local ->
                FeatureStageV1.entries.forEach { dependency ->
                    val decision = X02FeatureStagePolicyV1.reduce(
                        schemaRequest(
                            buildProfileMax = build,
                            localRequestedStage = local,
                            dependencyStage = dependency,
                        ),
                    )
                    val expected = FeatureStageOrderV1.minOf(
                        FeatureStageV1.SCHEMA_ONLY,
                        build,
                        local,
                        dependency,
                    )

                    assertEquals(expected, decision.effectiveStage)
                    assertTrue(
                        FeatureStageOrderV1.isAtMost(decision.effectiveStage, build),
                    )
                    assertTrue(
                        FeatureStageOrderV1.isAtMost(decision.effectiveStage, local),
                    )
                    assertTrue(
                        FeatureStageOrderV1.isAtMost(decision.effectiveStage, dependency),
                    )
                    assertEquals(
                        X02StageDecisionDispositionV1.VALID_SCHEMA_REQUEST,
                        decision.disposition,
                    )
                }
            }
        }
    }

    @Test
    fun requestedOffNeverGetsRaisedByFallback() {
        NormalProfileCapabilityIdV1.entries.forEach { capability ->
            ProfileExecutionClassV1.entries.forEach { executionClass ->
                val decision = X02FeatureStagePolicyV1.reduce(
                    schemaRequest(
                        capability = capability,
                        requestedStage = FeatureStageV1.OFF,
                        executionClass = executionClass,
                        buildProfileMax = FeatureStageV1.DEFAULT,
                        localRequestedStage = FeatureStageV1.DEFAULT,
                        dependencyStage = FeatureStageV1.DEFAULT,
                    ),
                )

                assertEquals(FeatureStageV1.OFF, decision.effectiveStage)
                assertEquals(
                    X02StageDecisionDispositionV1.VALID_OFF_REQUEST,
                    decision.disposition,
                )
                assertTrue(X02StageDecisionReasonV1.REQUESTED_OFF in decision.reasons)
            }
        }
    }

    @Test
    fun everyPersistentCapabilityIsBlockedAtX02() {
        NormalProfileCapabilityIdV1.entries
            .filterNot { it == NormalProfileCapabilityIdV1.SCHEMA_CODEC }
            .forEach { capability ->
                FeatureStageV1.entries
                    .filterNot { it == FeatureStageV1.OFF }
                    .forEach { requested ->
                        val decision = X02FeatureStagePolicyV1.reduce(
                            schemaRequest(
                                capability = capability,
                                requestedStage = requested,
                                buildProfileMax = FeatureStageV1.DEFAULT,
                                localRequestedStage = FeatureStageV1.DEFAULT,
                                dependencyStage = FeatureStageV1.DEFAULT,
                            ),
                        )

                        assertEquals(
                            X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED,
                            decision.disposition,
                        )
                        assertTrue(
                            FeatureStageOrderV1.isAtMost(
                                decision.effectiveStage,
                                FeatureStageV1.SCHEMA_ONLY,
                            ),
                        )
                        assertTrue(
                            X02StageDecisionReasonV1.PERSISTENT_CAPABILITY_BLOCKED in
                                decision.reasons,
                        )
                    }
            }
    }

    @Test
    fun higherStageAndNonSchemaExecutionClassAreBlocked() {
        FeatureStageV1.entries
            .filter {
                it != FeatureStageV1.OFF && it != FeatureStageV1.SCHEMA_ONLY
            }
            .forEach { requested ->
                ProfileExecutionClassV1.entries
                    .filterNot { it == ProfileExecutionClassV1.SCHEMA_ONLY }
                    .forEach { executionClass ->
                        val decision = X02FeatureStagePolicyV1.reduce(
                            schemaRequest(
                                requestedStage = requested,
                                executionClass = executionClass,
                                buildProfileMax = FeatureStageV1.DEFAULT,
                                localRequestedStage = FeatureStageV1.DEFAULT,
                                dependencyStage = FeatureStageV1.DEFAULT,
                            ),
                        )

                        assertEquals(FeatureStageV1.SCHEMA_ONLY, decision.effectiveStage)
                        assertEquals(
                            X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED,
                            decision.disposition,
                        )
                        assertTrue(
                            X02StageDecisionReasonV1.X02_HARD_MAXIMUM in decision.reasons,
                        )
                        assertTrue(
                            X02StageDecisionReasonV1.EXECUTION_CLASS_NOT_SCHEMA_ONLY in
                                decision.reasons,
                        )
                    }
            }
    }

    @Test
    fun fabricatedPassObservationsCannotAuthorizeAnything() {
        val allPass = GateIdV1.entries.map {
            X02GateObservationV1(it, GateVerdictV1.PASS)
        }
        val decision = X02FeatureStagePolicyV1.reduce(
            schemaRequest(
                capability = NormalProfileCapabilityIdV1.CAPTURE,
                requestedStage = FeatureStageV1.DEFAULT,
                executionClass = ProfileExecutionClassV1.REAL_DATA,
                buildProfileMax = FeatureStageV1.DEFAULT,
                localRequestedStage = FeatureStageV1.DEFAULT,
                dependencyStage = FeatureStageV1.DEFAULT,
                exactGateObservations = allPass,
            ),
        )

        assertEquals(FeatureStageV1.SCHEMA_ONLY, decision.effectiveStage)
        assertEquals(
            X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED,
            decision.disposition,
        )
        assertTrue(X02StageDecisionReasonV1.GATE_SET_MUST_BE_EMPTY in decision.reasons)
    }

    @Test
    fun everyVerdictAndDuplicateGateInputFailsClosed() {
        GateVerdictV1.entries.forEach { verdict ->
            val observations = listOf(
                X02GateObservationV1(GateIdV1.WireCompatibilityGateV1, verdict),
                X02GateObservationV1(GateIdV1.WireCompatibilityGateV1, verdict),
            )
            val decision = X02FeatureStagePolicyV1.reduce(
                schemaRequest(exactGateObservations = observations),
            )

            assertEquals(FeatureStageV1.SCHEMA_ONLY, decision.effectiveStage)
            assertEquals(
                X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED,
                decision.disposition,
            )
            assertTrue(
                X02StageDecisionReasonV1.DUPLICATE_GATE_OBSERVATION in decision.reasons,
            )
            assertFalse(
                decision.disposition == X02StageDecisionDispositionV1.VALID_SCHEMA_REQUEST,
            )
        }
    }

    @Test
    fun uniqueExtraGateAndOffDuplicateInputsRemainNonAuthorizing() {
        val uniqueExtra = X02FeatureStagePolicyV1.reduce(
            schemaRequest(
                exactGateObservations = listOf(
                    X02GateObservationV1(
                        GateIdV1.WireCompatibilityGateV1,
                        GateVerdictV1.PASS,
                    ),
                ),
            ),
        )
        val offWithDuplicate = X02FeatureStagePolicyV1.reduce(
            schemaRequest(
                requestedStage = FeatureStageV1.OFF,
                exactGateObservations = listOf(
                    X02GateObservationV1(
                        GateIdV1.WireCompatibilityGateV1,
                        GateVerdictV1.PASS,
                    ),
                    X02GateObservationV1(
                        GateIdV1.WireCompatibilityGateV1,
                        GateVerdictV1.PASS,
                    ),
                ),
            ),
        )

        assertEquals(
            X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED,
            uniqueExtra.disposition,
        )
        assertEquals(FeatureStageV1.SCHEMA_ONLY, uniqueExtra.effectiveStage)
        assertEquals(
            X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED,
            offWithDuplicate.disposition,
        )
        assertEquals(FeatureStageV1.OFF, offWithDuplicate.effectiveStage)
    }

    private fun schemaRequest(
        capability: NormalProfileCapabilityIdV1 =
            NormalProfileCapabilityIdV1.SCHEMA_CODEC,
        requestedStage: FeatureStageV1 = FeatureStageV1.SCHEMA_ONLY,
        executionClass: ProfileExecutionClassV1 = ProfileExecutionClassV1.SCHEMA_ONLY,
        buildProfileMax: FeatureStageV1 = FeatureStageV1.SCHEMA_ONLY,
        localRequestedStage: FeatureStageV1 = FeatureStageV1.SCHEMA_ONLY,
        dependencyStage: FeatureStageV1 = FeatureStageV1.SCHEMA_ONLY,
        exactGateObservations: List<X02GateObservationV1> = emptyList(),
    ) = X02NormalStageRequestV1(
        capability = capability,
        requestedStage = requestedStage,
        executionClass = executionClass,
        buildProfileMax = buildProfileMax,
        localRequestedStage = localRequestedStage,
        dependencyStage = dependencyStage,
        exactGateObservations = exactGateObservations,
    )
}
