package io.github.ethanbird.senseime.memory.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageDomainsV1Test {
    @Test
    fun stageWireTokensRoundTripAndUnknownFailsClosed() {
        FeatureStageV1.entries.forEach { stage ->
            assertEquals(stage, FeatureStageV1.fromWireValue(stage.wireValue))
        }
        assertNull(FeatureStageV1.fromWireValue("FUTURE_STAGE"))
        assertNull(FeatureStageV1.fromWireValue("schema_only"))
    }

    @Test
    fun explicitStageOrderMatchesAcceptedContract() {
        val ordered = listOf(
            FeatureStageV1.OFF,
            FeatureStageV1.SCHEMA_ONLY,
            FeatureStageV1.DARK,
            FeatureStageV1.SHADOW,
            FeatureStageV1.CANARY,
            FeatureStageV1.DEFAULT,
        )

        ordered.forEachIndexed { leftIndex, left ->
            ordered.forEachIndexed { rightIndex, right ->
                assertEquals(
                    leftIndex <= rightIndex,
                    FeatureStageOrderV1.isAtMost(left, right),
                )
                assertEquals(
                    ordered[minOf(leftIndex, rightIndex)],
                    FeatureStageOrderV1.min(left, right),
                )
            }
        }
    }

    @Test
    fun closedDomainsRejectUnknownWireTokens() {
        GateVerdictV1.entries.forEach { verdict ->
            assertEquals(verdict, GateVerdictV1.fromWireValue(verdict.wireValue))
        }
        PermitDecisionV1.entries.forEach { decision ->
            assertEquals(decision, PermitDecisionV1.fromWireValue(decision.wireValue))
        }
        ProfileExecutionClassV1.entries.forEach { executionClass ->
            assertEquals(
                executionClass,
                ProfileExecutionClassV1.fromWireValue(executionClass.wireValue),
            )
        }
        NormalProfileCapabilityIdV1.entries.forEach { capability ->
            assertEquals(
                capability,
                NormalProfileCapabilityIdV1.fromWireValue(capability.wireValue),
            )
        }

        assertNull(GateVerdictV1.fromWireValue("NOT_RUN"))
        assertNull(PermitDecisionV1.fromWireValue("PASS"))
        assertNull(ProfileExecutionClassV1.fromWireValue("MIXED"))
        assertNull(NormalProfileCapabilityIdV1.fromWireValue("SHADOW_PLUS"))
        assertTrue(
            NormalProfileCapabilityIdV1.entries.none {
                it.wireValue == "PERSISTENT_SUBSTRATE" || it.wireValue == "JOURNAL_WRITE"
            },
        )
    }
}
