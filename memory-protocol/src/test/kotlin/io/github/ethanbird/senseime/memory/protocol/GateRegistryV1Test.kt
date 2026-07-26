package io.github.ethanbird.senseime.memory.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GateRegistryV1Test {
    @Test
    fun registryHasExactClosedCardinalityAndScopePartition() {
        assertEquals(45, GateRegistryV1.all().size)
        assertEquals(GateIdV1.entries.toSet(), GateRegistryV1.all())
        assertEquals(
            mapOf(
                GateScopeKindV1.RELEASE_BUILD to setOf(
                    GateIdV1.WireCompatibilityGateV1,
                    GateIdV1.ReleaseSigningAuthorityGateV1,
                    GateIdV1.PlatformCertificationGateV1,
                    GateIdV1.BackupExclusionGateV1,
                    GateIdV1.BudgetProfileGateV1,
                    GateIdV1.BuildAttestationGateV1,
                ),
                GateScopeKindV1.PHASE_SCHEMA to setOf(
                    GateIdV1.ReleasePolicySemanticsPhaseGateV1,
                    GateIdV1.RootBootstrapControlPhaseGateV1,
                    GateIdV1.KeyringBootstrapControlPhaseGateV1,
                    GateIdV1.SourceManifestPhaseGateV1,
                    GateIdV1.RecordIdentityTombstonePhaseGateV1,
                    GateIdV1.RotationControlPhaseGateV1,
                    GateIdV1.LocalErasureControlPhaseGateV1,
                    GateIdV1.BudgetEvidenceWirePhaseGateV1,
                    GateIdV1.StageSnapshotAuthenticationPhaseGateV1,
                ),
                GateScopeKindV1.DEVICE_CAPABILITY to setOf(
                    GateIdV1.UnlockedKeyBehaviorGateV1,
                    GateIdV1.KeyUseSafetyGateV1,
                    GateIdV1.KeyringBootstrapCapabilityGateV1,
                    GateIdV1.JournalFrontierDurabilityGateV1,
                    GateIdV1.BlobWireLocatorLeaseGateV1,
                    GateIdV1.DerivedIndexAuthorityGateV1,
                    GateIdV1.HotSnapshotPlaintextExclusionGateV1,
                    GateIdV1.MaintenancePeakCapacityGateV1,
                    GateIdV1.RotationReceiptCommittedGateV1,
                    GateIdV1.LocalErasureCapabilityGateV1,
                    GateIdV1.EgressDrainEvidenceGateV1,
                    GateIdV1.RebootNamespaceCensusGateV1,
                    GateIdV1.TransferOutcomeConservationGateV1,
                    GateIdV1.StageSnapshotAuthenticationCapabilityGateV1,
                ),
                GateScopeKindV1.INSTALLATION_ROOT to setOf(
                    GateIdV1.ReleaseIdentityGateV1,
                    GateIdV1.ReleaseOwnerContinuityGateV1,
                    GateIdV1.DataRootContinuityGateV1,
                    GateIdV1.InstallationKeyringIdentityGateV1,
                    GateIdV1.KeyAuthorizationProfileGateV1,
                    GateIdV1.ErasureSafetyReceiptGateV1,
                    GateIdV1.StageRevocationFreshnessGateV1,
                ),
                GateScopeKindV1.DYNAMIC_OPERATION to setOf(
                    GateIdV1.CredentialUnlockedRuntimeGateV1,
                    GateIdV1.CapturePolicyConsentGateV1,
                    GateIdV1.MemoryUsePolicyGateV1,
                    GateIdV1.ProviderMemoryDisclosureGateV1,
                    GateIdV1.ExportAuthorizationGateV1,
                    GateIdV1.CumulativeErasureViewGateV1,
                    GateIdV1.SourceErasureAuthorityGateV1,
                ),
                GateScopeKindV1.SPECIAL_CANDIDATE to setOf(
                    GateIdV1.PreCertificationCandidateControlPhaseGateV1,
                    GateIdV1.SyntheticMeasurementControlPhaseGateV1,
                ),
            ),
            GateScopeKindV1.entries.associateWith(GateRegistryV1::forScope),
        )
        assertEquals(
            GateRegistryV1.all(),
            GateScopeKindV1.entries.flatMap(GateRegistryV1::forScope).toSet(),
        )
        assertEquals(
            GateRegistryV1.all().size,
            GateScopeKindV1.entries.sumOf { GateRegistryV1.forScope(it).size },
        )
    }

    @Test
    fun gateWireTokensAreExactNamesAndUnknownFailsClosed() {
        GateIdV1.entries.forEach { gate ->
            assertEquals(gate.name, gate.wireValue)
            assertEquals(gate, GateIdV1.fromWireValue(gate.wireValue))
        }
        assertNull(GateIdV1.fromWireValue("FutureGateV2"))
        assertNull(GateIdV1.fromWireValue(""))
    }

    @Test
    fun callerMutationCannotChangeRegistryAuthority() {
        val allCopy = GateRegistryV1.all() as MutableSet<GateIdV1>
        val dynamicCopy =
            GateRegistryV1.forScope(GateScopeKindV1.DYNAMIC_OPERATION) as
                MutableSet<GateIdV1>

        allCopy.clear()
        dynamicCopy.clear()

        assertEquals(45, GateRegistryV1.all().size)
        assertEquals(
            7,
            GateRegistryV1.forScope(GateScopeKindV1.DYNAMIC_OPERATION).size,
        )
    }

    @Test
    fun dynamicAndSpecialScopesCannotBeMistakenForStaticReceipts() {
        assertEquals(
            setOf(
                GateIdV1.CredentialUnlockedRuntimeGateV1,
                GateIdV1.CapturePolicyConsentGateV1,
                GateIdV1.MemoryUsePolicyGateV1,
                GateIdV1.ProviderMemoryDisclosureGateV1,
                GateIdV1.ExportAuthorizationGateV1,
                GateIdV1.CumulativeErasureViewGateV1,
                GateIdV1.SourceErasureAuthorityGateV1,
            ),
            GateRegistryV1.forScope(GateScopeKindV1.DYNAMIC_OPERATION),
        )
        assertEquals(
            setOf(
                GateIdV1.PreCertificationCandidateControlPhaseGateV1,
                GateIdV1.SyntheticMeasurementControlPhaseGateV1,
            ),
            GateRegistryV1.forScope(GateScopeKindV1.SPECIAL_CANDIDATE),
        )
    }
}
