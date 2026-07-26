package io.github.ethanbird.senseime.memory.protocol

enum class GateScopeKindV1 {
    RELEASE_BUILD,
    PHASE_SCHEMA,
    DEVICE_CAPABILITY,
    INSTALLATION_ROOT,
    DYNAMIC_OPERATION,
    SPECIAL_CANDIDATE,
}

/**
 * The exact 45-entry GateId registry frozen by ADR 0018.
 *
 * Scope is data, not naming convention. Runtime code must not infer scope from a gate's suffix.
 */
enum class GateIdV1(
    val scope: GateScopeKindV1,
) {
    WireCompatibilityGateV1(GateScopeKindV1.RELEASE_BUILD),
    ReleaseIdentityGateV1(GateScopeKindV1.INSTALLATION_ROOT),
    ReleaseOwnerContinuityGateV1(GateScopeKindV1.INSTALLATION_ROOT),
    ReleaseSigningAuthorityGateV1(GateScopeKindV1.RELEASE_BUILD),
    PreCertificationCandidateControlPhaseGateV1(GateScopeKindV1.SPECIAL_CANDIDATE),
    ReleasePolicySemanticsPhaseGateV1(GateScopeKindV1.PHASE_SCHEMA),
    PlatformCertificationGateV1(GateScopeKindV1.RELEASE_BUILD),
    RootBootstrapControlPhaseGateV1(GateScopeKindV1.PHASE_SCHEMA),
    DataRootContinuityGateV1(GateScopeKindV1.INSTALLATION_ROOT),
    InstallationKeyringIdentityGateV1(GateScopeKindV1.INSTALLATION_ROOT),
    KeyAuthorizationProfileGateV1(GateScopeKindV1.INSTALLATION_ROOT),
    UnlockedKeyBehaviorGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    CredentialUnlockedRuntimeGateV1(GateScopeKindV1.DYNAMIC_OPERATION),
    KeyUseSafetyGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    KeyringBootstrapControlPhaseGateV1(GateScopeKindV1.PHASE_SCHEMA),
    KeyringBootstrapCapabilityGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    JournalFrontierDurabilityGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    BlobWireLocatorLeaseGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    CapturePolicyConsentGateV1(GateScopeKindV1.DYNAMIC_OPERATION),
    MemoryUsePolicyGateV1(GateScopeKindV1.DYNAMIC_OPERATION),
    ProviderMemoryDisclosureGateV1(GateScopeKindV1.DYNAMIC_OPERATION),
    ExportAuthorizationGateV1(GateScopeKindV1.DYNAMIC_OPERATION),
    BackupExclusionGateV1(GateScopeKindV1.RELEASE_BUILD),
    SourceManifestPhaseGateV1(GateScopeKindV1.PHASE_SCHEMA),
    RecordIdentityTombstonePhaseGateV1(GateScopeKindV1.PHASE_SCHEMA),
    DerivedIndexAuthorityGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    HotSnapshotPlaintextExclusionGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    MaintenancePeakCapacityGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    RotationControlPhaseGateV1(GateScopeKindV1.PHASE_SCHEMA),
    RotationReceiptCommittedGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    LocalErasureControlPhaseGateV1(GateScopeKindV1.PHASE_SCHEMA),
    LocalErasureCapabilityGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    ErasureSafetyReceiptGateV1(GateScopeKindV1.INSTALLATION_ROOT),
    CumulativeErasureViewGateV1(GateScopeKindV1.DYNAMIC_OPERATION),
    EgressDrainEvidenceGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    RebootNamespaceCensusGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    TransferOutcomeConservationGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    StageRevocationFreshnessGateV1(GateScopeKindV1.INSTALLATION_ROOT),
    BudgetProfileGateV1(GateScopeKindV1.RELEASE_BUILD),
    BudgetEvidenceWirePhaseGateV1(GateScopeKindV1.PHASE_SCHEMA),
    SyntheticMeasurementControlPhaseGateV1(GateScopeKindV1.SPECIAL_CANDIDATE),
    BuildAttestationGateV1(GateScopeKindV1.RELEASE_BUILD),
    StageSnapshotAuthenticationPhaseGateV1(GateScopeKindV1.PHASE_SCHEMA),
    StageSnapshotAuthenticationCapabilityGateV1(GateScopeKindV1.DEVICE_CAPABILITY),
    SourceErasureAuthorityGateV1(GateScopeKindV1.DYNAMIC_OPERATION),
    ;

    val wireValue: String
        get() = name

    companion object {
        fun fromWireValue(value: String): GateIdV1? =
            entries.singleOrNull { it.wireValue == value }
    }
}

object GateRegistryV1 {
    /**
     * Returns a defensive value set. No mutable collection is retained as registry authority.
     */
    fun all(): Set<GateIdV1> = GateIdV1.entries.toSet()

    /**
     * Returns a defensive value set for the requested exact scope.
     */
    fun forScope(scope: GateScopeKindV1): Set<GateIdV1> =
        GateIdV1.entries.filterTo(linkedSetOf()) { it.scope == scope }
}
