#!/usr/bin/env python3
"""Fail-closed mechanical checks for the Gate 0 Agent/Memory documents.

The Markdown ADRs remain the normative contract.  This tool parses their
machine-readable registries, rejects drift, and derives a non-authoritative
status report.  It deliberately uses only the Python standard library so the
check can run before Gradle or Android dependencies are available.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence
from urllib.parse import unquote


ADR15_PATH = Path("docs/adr/0015-release-identity-and-data-continuity.md")
ADR16_PATH = Path("docs/adr/0016-m9-memory-wire-and-durability.md")
ADR17_PATH = Path("docs/adr/0017-m9-memory-security-and-erasure.md")
ADR18_PATH = Path("docs/adr/0018-m9-memory-budget.md")
ARCHITECTURE_PATH = Path("docs/design/agent-event-memory-architecture-v1.0.md")
ENGINEERING_PLAN_PATH = Path("docs/development/agent-engineering-plan-v1.0.md")
DEFAULT_REPORT_PATH = Path("docs/generated/gate0-status-report.json")

CONTRACT_MARKDOWN_PATHS = (
    Path("README.md"),
    ADR15_PATH,
    ADR16_PATH,
    ADR17_PATH,
    ADR18_PATH,
    ARCHITECTURE_PATH,
    ENGINEERING_PLAN_PATH,
)

EXPECTED_FIXED_FIELD_CLASSES = {
    "F001": "FIXED",
    "F002": "FIXED",
    "F003": "FIXED_REF",
    "F004": "FIXED_REF",
    "F005": "FIXED_REF",
    "F006": "BUILD_REF",
    "F007": "BUILD_REF",
    "F008": "BUILD_REF",
    "F009": "METHOD",
}

EXPECTED_GATES = (
    "WireCompatibilityGateV1",
    "ReleaseIdentityGateV1",
    "ReleaseOwnerContinuityGateV1",
    "ReleaseSigningAuthorityGateV1",
    "PreCertificationCandidateControlPhaseGateV1",
    "ReleasePolicySemanticsPhaseGateV1",
    "PlatformCertificationGateV1",
    "RootBootstrapControlPhaseGateV1",
    "DataRootContinuityGateV1",
    "InstallationKeyringIdentityGateV1",
    "KeyAuthorizationProfileGateV1",
    "UnlockedKeyBehaviorGateV1",
    "CredentialUnlockedRuntimeGateV1",
    "KeyUseSafetyGateV1",
    "KeyringBootstrapControlPhaseGateV1",
    "KeyringBootstrapCapabilityGateV1",
    "JournalFrontierDurabilityGateV1",
    "BlobWireLocatorLeaseGateV1",
    "CapturePolicyConsentGateV1",
    "MemoryUsePolicyGateV1",
    "ProviderMemoryDisclosureGateV1",
    "ExportAuthorizationGateV1",
    "BackupExclusionGateV1",
    "SourceManifestPhaseGateV1",
    "RecordIdentityTombstonePhaseGateV1",
    "DerivedIndexAuthorityGateV1",
    "HotSnapshotPlaintextExclusionGateV1",
    "MaintenancePeakCapacityGateV1",
    "RotationControlPhaseGateV1",
    "RotationReceiptCommittedGateV1",
    "LocalErasureControlPhaseGateV1",
    "LocalErasureCapabilityGateV1",
    "ErasureSafetyReceiptGateV1",
    "CumulativeErasureViewGateV1",
    "EgressDrainEvidenceGateV1",
    "RebootNamespaceCensusGateV1",
    "TransferOutcomeConservationGateV1",
    "StageRevocationFreshnessGateV1",
    "BudgetProfileGateV1",
    "BudgetEvidenceWirePhaseGateV1",
    "SyntheticMeasurementControlPhaseGateV1",
    "BuildAttestationGateV1",
    "StageSnapshotAuthenticationPhaseGateV1",
    "StageSnapshotAuthenticationCapabilityGateV1",
    "SourceErasureAuthorityGateV1",
)

EXPECTED_SCOPE_GATES = {
    "RELEASE_BUILD": frozenset(
        {
            "WireCompatibilityGateV1",
            "ReleaseSigningAuthorityGateV1",
            "PlatformCertificationGateV1",
            "BackupExclusionGateV1",
            "BudgetProfileGateV1",
            "BuildAttestationGateV1",
        }
    ),
    "PHASE_SCHEMA": frozenset(
        {
            "ReleasePolicySemanticsPhaseGateV1",
            "RootBootstrapControlPhaseGateV1",
            "KeyringBootstrapControlPhaseGateV1",
            "SourceManifestPhaseGateV1",
            "RecordIdentityTombstonePhaseGateV1",
            "RotationControlPhaseGateV1",
            "LocalErasureControlPhaseGateV1",
            "BudgetEvidenceWirePhaseGateV1",
            "StageSnapshotAuthenticationPhaseGateV1",
        }
    ),
    "DEVICE_CAPABILITY": frozenset(
        {
            "UnlockedKeyBehaviorGateV1",
            "KeyUseSafetyGateV1",
            "KeyringBootstrapCapabilityGateV1",
            "JournalFrontierDurabilityGateV1",
            "BlobWireLocatorLeaseGateV1",
            "DerivedIndexAuthorityGateV1",
            "HotSnapshotPlaintextExclusionGateV1",
            "MaintenancePeakCapacityGateV1",
            "RotationReceiptCommittedGateV1",
            "LocalErasureCapabilityGateV1",
            "EgressDrainEvidenceGateV1",
            "RebootNamespaceCensusGateV1",
            "TransferOutcomeConservationGateV1",
            "StageSnapshotAuthenticationCapabilityGateV1",
        }
    ),
    "INSTALLATION_ROOT": frozenset(
        {
            "ReleaseIdentityGateV1",
            "ReleaseOwnerContinuityGateV1",
            "DataRootContinuityGateV1",
            "InstallationKeyringIdentityGateV1",
            "KeyAuthorizationProfileGateV1",
            "ErasureSafetyReceiptGateV1",
            "StageRevocationFreshnessGateV1",
        }
    ),
    "DYNAMIC_OPERATION": frozenset(
        {
            "CredentialUnlockedRuntimeGateV1",
            "CapturePolicyConsentGateV1",
            "MemoryUsePolicyGateV1",
            "ProviderMemoryDisclosureGateV1",
            "ExportAuthorizationGateV1",
            "CumulativeErasureViewGateV1",
            "SourceErasureAuthorityGateV1",
        }
    ),
    "SPECIAL_CANDIDATE": frozenset(
        {
            "PreCertificationCandidateControlPhaseGateV1",
            "SyntheticMeasurementControlPhaseGateV1",
        }
    ),
}

# These names or positive formulations belonged to superseded designs.  They
# are literals rather than broad words so normative negative discussion of
# WorkManager, retries, or Provider bodies remains possible.
STALE_CONTRACT_LITERALS = (
    "temp/projection/main",
    "generation_scoped_provider_attempt_commitment_key",
    "CandidateOwnerContinuityDecision",
    "CANDIDATE_PRIMARY_KEYRING_COMMIT_ELIGIBLE",
    "logical_durable_bytes",
    "data-generation-only",
    "exact final request bytes",
    "APPEND_OR_REPLACE 追加追赶",
    "Brain 仅在需要 recall",
    "个性化词频和风格快照",
    "two-receipt",
)

_GATE_TOKEN_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9]*GateV1\b")
_GATE_CANDIDATE_RE = re.compile(
    r"(?<![A-Za-z0-9_])([A-Za-z0-9_]+GateV1)(?![A-Za-z0-9_])"
)
_FIELD_ID_RE = re.compile(r"F[0-9]{3}")
_PACKAGE_ID_RE = re.compile(r"[A-Z0-9]+(?:-[A-Z0-9]+)*")
_INLINE_LINK_RE = re.compile(r"!?\[[^\]\n]*\]\(([^)\n]+)\)")
_REFERENCE_LINK_RE = re.compile(r"^\s*\[[^\]\n]+\]:\s*(\S+)", re.MULTILINE)
_FENCE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
_TIMESTAMP_KEYS = frozenset(
    {"timestamp", "generated_at", "generated_on", "created_at", "updated_at"}
)


class ContractError(ValueError):
    """Raised when a Gate 0 document violates a mechanical contract."""


@dataclass(frozen=True)
class BudgetField:
    field_id: str
    name: str
    gate0_class: str


@dataclass(frozen=True)
class WorkPackage:
    package_id: str
    dependencies: tuple[str, ...]


@dataclass(frozen=True)
class ContractSnapshot:
    fields: tuple[BudgetField, ...]
    gate_scopes: Mapping[str, frozenset[str]]
    work_packages: tuple[WorkPackage, ...]
    source_sha256: Mapping[str, str]


def _read_utf8(root: Path, relative_path: Path) -> str:
    path = root / relative_path
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError as error:
        raise ContractError(f"missing contract source: {relative_path.as_posix()}") from error
    except UnicodeDecodeError as error:
        raise ContractError(
            f"contract source is not valid UTF-8: {relative_path.as_posix()}"
        ) from error


def _extract_numbered_section(text: str, number: str, path: Path) -> str:
    lines = text.splitlines()
    heading = re.compile(
        rf"^ {{0,3}}##[ \t]+{re.escape(number)}(?:\.|[ \t]|$)"
    )
    starts = [index for index, line in enumerate(lines) if heading.match(line)]
    if not starts:
        raise ContractError(f"{path.as_posix()}: missing section {number}")
    if len(starts) != 1:
        raise ContractError(
            f"{path.as_posix()}: section {number} must occur exactly once; "
            f"found {len(starts)}"
        )
    start = starts[0]

    end = len(lines)
    next_h2 = re.compile(r"^ {0,3}##(?:[ \t]+|$)")
    for index in range(start + 1, len(lines)):
        if next_h2.match(lines[index]):
            end = index
            break
    return "\n".join(lines[start:end])


def _extract_fenced_block_after(
    text: str, marker: str, path: Path, description: str
) -> str:
    marker_count = text.count(marker)
    if marker_count == 0:
        raise ContractError(f"{path.as_posix()}: missing {description} marker")
    if marker_count != 1:
        raise ContractError(
            f"{path.as_posix()}: {description} marker must occur exactly once; "
            f"found {marker_count}"
        )
    marker_offset = text.find(marker)

    lines = text[marker_offset:].splitlines()
    opening_index: int | None = None
    opening_char = ""
    opening_length = 0
    for index, line in enumerate(lines):
        match = _FENCE_RE.match(line)
        if match:
            opening_index = index
            opening_char = match.group(1)[0]
            opening_length = len(match.group(1))
            break
    if opening_index is None:
        raise ContractError(f"{path.as_posix()}: missing fenced {description}")

    body: list[str] = []
    for line in lines[opening_index + 1 :]:
        match = _FENCE_RE.match(line)
        if (
            match
            and match.group(1)[0] == opening_char
            and len(match.group(1)) >= opening_length
            and not match.group(2).strip()
        ):
            return "\n".join(body)
        body.append(line)
    raise ContractError(f"{path.as_posix()}: unterminated fenced {description}")


def _split_markdown_row(line: str) -> list[str] | None:
    stripped = line.strip()
    if not (stripped.startswith("|") and stripped.endswith("|")):
        return None

    cells: list[str] = []
    current: list[str] = []
    escaped = False
    for character in stripped[1:-1]:
        if escaped:
            current.append(character)
            escaped = False
        elif character == "\\":
            current.append(character)
            escaped = True
        elif character == "|":
            cells.append("".join(current).strip())
            current = []
        else:
            current.append(character)
    cells.append("".join(current).strip())
    return cells


def _strip_exact_backticks(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value.startswith("`") and value.endswith("`"):
        return value[1:-1]
    return value


def parse_budget_fields(adr18: str) -> tuple[BudgetField, ...]:
    section = _extract_numbered_section(adr18, "4", ADR18_PATH)
    fields: list[BudgetField] = []
    in_field_table = False
    for line in section.splitlines():
        cells = _split_markdown_row(line)
        if not cells:
            if in_field_table and line.strip():
                raise ContractError(
                    "ADR 0018 §4 contains a malformed non-empty budget field "
                    f"table row: {line!r}"
                )
            in_field_table = False
            continue
        if cells in (
            ["id", "name", "type/source", "Gate 0 class"],
            ["id", "name", "unit/type", "Gate 0"],
        ):
            in_field_table = True
            continue
        if not in_field_table:
            raise ContractError(
                "ADR 0018 §4 contains an unexpected or malformed budget field "
                f"table row: {line!r}"
            )
        if len(cells) == 4 and all(
            re.fullmatch(r":?-{3,}:?", cell) for cell in cells
        ):
            continue
        if len(cells) != 4:
            raise ContractError(
                f"ADR 0018 §4 budget field row must have four cells: {line!r}"
            )
        field_id = _strip_exact_backticks(cells[0])
        if not _FIELD_ID_RE.fullmatch(field_id):
            raise ContractError(
                f"ADR 0018 §4 has malformed or unknown budget field ID: {field_id!r}"
            )
        fields.append(
            BudgetField(
                field_id=field_id,
                name=_strip_exact_backticks(cells[1]),
                gate0_class=_strip_exact_backticks(cells[-1]),
            )
        )

    expected_ids = {f"F{number:03d}" for number in range(1, 100)}
    counts = Counter(field.field_id for field in fields)
    duplicate_ids = sorted(field_id for field_id, count in counts.items() if count != 1)
    if duplicate_ids:
        details = ", ".join(f"{field_id}={counts[field_id]}" for field_id in duplicate_ids)
        raise ContractError(f"ADR 0018 §4 budget field multiplicity violation: {details}")

    actual_ids = set(counts)
    missing = sorted(expected_ids - actual_ids)
    extra = sorted(actual_ids - expected_ids)
    if missing or extra or len(fields) != 99:
        raise ContractError(
            "ADR 0018 §4 must define F001..F099 exactly once; "
            f"missing={missing}, extra={extra}, rows={len(fields)}"
        )

    by_id = {field.field_id: field for field in fields}
    for field_id, expected_class in EXPECTED_FIXED_FIELD_CLASSES.items():
        actual_class = by_id[field_id].gate0_class
        if actual_class != expected_class:
            raise ContractError(
                f"{field_id} Gate 0 class must be {expected_class}, got {actual_class}"
            )
    for number in range(10, 100):
        field_id = f"F{number:03d}"
        actual_class = by_id[field_id].gate0_class
        if actual_class != "UNSET":
            raise ContractError(
                f"{field_id} Gate 0 class must be UNSET, got {actual_class}"
            )

    return tuple(sorted(fields, key=lambda field: field.field_id))


def parse_gate_registry(adr18: str) -> tuple[str, ...]:
    section = _extract_numbered_section(adr18, "14", ADR18_PATH)
    registry_block = _extract_fenced_block_after(
        section, "### 14.1 Closed `GateIdV1` registry", ADR18_PATH, "GateId registry"
    )
    registry = tuple(line.strip() for line in registry_block.splitlines() if line.strip())
    invalid = [token for token in registry if not _GATE_TOKEN_RE.fullmatch(token)]
    if invalid:
        raise ContractError(f"GateId registry contains malformed tokens: {invalid}")

    counts = Counter(registry)
    duplicates = sorted(token for token, count in counts.items() if count != 1)
    if duplicates:
        raise ContractError(f"duplicate GateId in registry: {duplicates}")

    expected = set(EXPECTED_GATES)
    actual = set(registry)
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    if len(registry) != 45 or missing or extra:
        raise ContractError(
            "GateId registry must equal the closed 45-token registry; "
            f"count={len(registry)}, missing={missing}, extra={extra}"
        )
    return registry


def _parse_scope_block(block: str) -> dict[str, frozenset[str]]:
    scopes: dict[str, frozenset[str]] = {}
    lines = [line.strip() for line in block.splitlines() if line.strip()]
    index = 0
    while index < len(lines):
        start = re.fullmatch(r"([A-Z][A-Z0-9_]*)=\{", lines[index])
        if not start:
            raise ContractError(f"malformed GateScope assignment: {lines[index]!r}")
        scope = start.group(1)
        if scope in scopes:
            raise ContractError(f"duplicate GateScope assignment: {scope}")
        index += 1
        tokens: list[str] = []
        while index < len(lines) and lines[index] != "}":
            pieces = [piece.strip() for piece in lines[index].split(",")]
            tokens.extend(piece for piece in pieces if piece)
            index += 1
        if index >= len(lines):
            raise ContractError(f"unterminated GateScope assignment: {scope}")
        if len(tokens) != len(set(tokens)):
            duplicates = sorted(
                token for token, count in Counter(tokens).items() if count != 1
            )
            raise ContractError(f"{scope} contains duplicate GateId: {duplicates}")
        scopes[scope] = frozenset(tokens)
        index += 1
    return scopes


def parse_gate_scopes(adr18: str, registry: Sequence[str]) -> Mapping[str, frozenset[str]]:
    section = _extract_numbered_section(adr18, "14", ADR18_PATH)
    block = _extract_fenced_block_after(
        section,
        "Gate scope是 closed、不可互 cast 的 `GateScopeKindV1`",
        ADR18_PATH,
        "GateScope partition",
    )
    scopes = _parse_scope_block(block)

    expected_scope_names = set(EXPECTED_SCOPE_GATES)
    actual_scope_names = set(scopes)
    if actual_scope_names != expected_scope_names:
        raise ContractError(
            "GateScope registry mismatch; "
            f"missing={sorted(expected_scope_names - actual_scope_names)}, "
            f"extra={sorted(actual_scope_names - expected_scope_names)}"
        )

    for scope, expected_gates in EXPECTED_SCOPE_GATES.items():
        actual_gates = scopes[scope]
        if actual_gates != expected_gates:
            raise ContractError(
                f"{scope} GateId membership mismatch; "
                f"missing={sorted(expected_gates - actual_gates)}, "
                f"extra={sorted(actual_gates - expected_gates)}"
            )

    occurrences = Counter(gate for gates in scopes.values() for gate in gates)
    duplicate = sorted(gate for gate, count in occurrences.items() if count != 1)
    registry_set = set(registry)
    missing = sorted(registry_set - set(occurrences))
    extra = sorted(set(occurrences) - registry_set)
    if duplicate or missing or extra or sum(occurrences.values()) != 45:
        raise ContractError(
            "GateScope partition must contain each registered GateId exactly once; "
            f"duplicate={duplicate}, missing={missing}, extra={extra}"
        )
    return scopes


def parse_work_packages(plan: str) -> tuple[WorkPackage, ...]:
    section = _extract_numbered_section(plan, "27", ENGINEERING_PLAN_PATH)
    packages: list[WorkPackage] = []
    in_package_table = False
    for line in section.splitlines():
        cells = _split_markdown_row(line)
        if not cells:
            if in_package_table and line.strip():
                raise ContractError(
                    "engineering plan §27 contains a malformed non-empty package "
                    f"table row: {line!r}"
                )
            in_package_table = False
            continue
        if len(cells) == 4 and cells[0] == "ID" and cells[1] == "依赖":
            in_package_table = True
            continue
        if not in_package_table:
            raise ContractError(
                "engineering plan §27 contains an unexpected or malformed "
                f"package table row: {line!r}"
            )
        if len(cells) == 4 and all(
            re.fullmatch(r":?-{3,}:?", cell) for cell in cells
        ):
            continue
        if len(cells) != 4:
            raise ContractError(
                f"engineering plan §27 package row must have four cells: {line!r}"
            )
        if not (cells[0].startswith("`") and cells[0].endswith("`")):
            raise ContractError(
                f"engineering plan §27 package ID must use exact backticks: {cells[0]!r}"
            )
        package_id = _strip_exact_backticks(cells[0])
        if not _PACKAGE_ID_RE.fullmatch(package_id):
            raise ContractError(
                f"engineering plan §27 has malformed package ID: {package_id!r}"
            )

        dependency_cell = cells[1].strip()
        if dependency_cell == "无":
            dependencies: tuple[str, ...] = ()
        else:
            raw_dependencies = tuple(
                dependency.strip() for dependency in dependency_cell.split(",")
            )
            if (
                not raw_dependencies
                or any(not _PACKAGE_ID_RE.fullmatch(item) for item in raw_dependencies)
            ):
                raise ContractError(
                    f"{package_id} has non-canonical dependency list: {dependency_cell!r}"
                )
            if len(raw_dependencies) != len(set(raw_dependencies)):
                raise ContractError(f"{package_id} repeats a dependency")
            dependencies = raw_dependencies
        packages.append(WorkPackage(package_id, dependencies))

    if not packages:
        raise ContractError("engineering plan §27 contains no issue-ready packages")

    counts = Counter(package.package_id for package in packages)
    duplicates = sorted(package_id for package_id, count in counts.items() if count != 1)
    if duplicates:
        raise ContractError(f"duplicate issue-ready package ID: {duplicates}")

    package_ids = set(counts)
    missing_dependencies = sorted(
        {
            dependency
            for package in packages
            for dependency in package.dependencies
            if dependency not in package_ids
        }
    )
    if missing_dependencies:
        raise ContractError(
            f"issue-ready package dependency is undefined: {missing_dependencies}"
        )

    by_id = {package.package_id: package for package in packages}
    state: dict[str, int] = {}
    path: list[str] = []

    def visit(package_id: str) -> None:
        status = state.get(package_id, 0)
        if status == 2:
            return
        if status == 1:
            cycle_start = path.index(package_id)
            cycle = path[cycle_start:] + [package_id]
            raise ContractError(
                "issue-ready package dependency cycle: " + " -> ".join(cycle)
            )
        state[package_id] = 1
        path.append(package_id)
        for dependency in by_id[package_id].dependencies:
            visit(dependency)
        path.pop()
        state[package_id] = 2

    for package_id in sorted(by_id):
        visit(package_id)

    return tuple(sorted(packages, key=lambda package: package.package_id))


def _validate_fences(text: str, path: Path) -> None:
    opening_char: str | None = None
    opening_length = 0
    opening_line = 0
    for line_number, line in enumerate(text.splitlines(), start=1):
        match = _FENCE_RE.match(line)
        if not match:
            continue
        marker = match.group(1)
        suffix = match.group(2)
        if opening_char is None:
            opening_char = marker[0]
            opening_length = len(marker)
            opening_line = line_number
        elif (
            marker[0] == opening_char
            and len(marker) >= opening_length
            and not suffix.strip()
        ):
            opening_char = None
            opening_length = 0
            opening_line = 0
    if opening_char is not None:
        raise ContractError(
            f"{path.as_posix()} has an unterminated Markdown fence opened at line "
            f"{opening_line}"
        )


def _without_fenced_blocks(text: str) -> str:
    visible: list[str] = []
    opening_char: str | None = None
    opening_length = 0
    for line in text.splitlines():
        match = _FENCE_RE.match(line)
        if opening_char is None:
            if match:
                opening_char = match.group(1)[0]
                opening_length = len(match.group(1))
                visible.append("")
            else:
                visible.append(line)
        elif (
            match
            and match.group(1)[0] == opening_char
            and len(match.group(1)) >= opening_length
            and not match.group(2).strip()
        ):
            opening_char = None
            opening_length = 0
            visible.append("")
        else:
            visible.append("")
    return "\n".join(visible)


def _normalize_link_target(raw_target: str) -> str:
    target = raw_target.strip()
    if target.startswith("<") and ">" in target:
        target = target[1 : target.index(">")]
    elif re.search(r"\s+[\"']", target):
        target = re.split(r"\s+[\"']", target, maxsplit=1)[0]
    return target


def _validate_relative_links(root: Path, text: str, path: Path) -> None:
    visible = _without_fenced_blocks(text)
    targets = [match.group(1) for match in _INLINE_LINK_RE.finditer(visible)]
    targets.extend(match.group(1) for match in _REFERENCE_LINK_RE.finditer(visible))
    root_resolved = root.resolve()
    for raw_target in targets:
        target = _normalize_link_target(raw_target)
        if (
            not target
            or target.startswith("#")
            or target.startswith("//")
            or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target)
        ):
            continue
        local_part = unquote(target.split("#", 1)[0].split("?", 1)[0])
        if not local_part:
            continue
        resolved = (root / path.parent / local_part).resolve()
        try:
            resolved.relative_to(root_resolved)
        except ValueError as error:
            raise ContractError(
                f"{path.as_posix()} local link escapes repository: {target}"
            ) from error
        if not resolved.exists():
            raise ContractError(
                f"{path.as_posix()} has missing local link target: {target}"
            )


def _validate_known_gate_tokens(
    source_texts: Mapping[Path, str], registry: Sequence[str]
) -> None:
    expected = set(registry)
    unknown: dict[str, list[str]] = {}
    for path, text in source_texts.items():
        tokens = sorted(set(_GATE_CANDIDATE_RE.findall(text)) - expected)
        if tokens:
            unknown[path.as_posix()] = tokens
    if unknown:
        raise ContractError(f"unknown *GateV1 token(s): {unknown}")


def _validate_no_stale_contracts(source_texts: Mapping[Path, str]) -> None:
    matches: dict[str, list[str]] = {}
    for path, text in source_texts.items():
        found = [literal for literal in STALE_CONTRACT_LITERALS if literal in text]
        if found:
            matches[path.as_posix()] = found
    if matches:
        raise ContractError(f"stale Gate 0 contract token/phrase: {matches}")


def validate_contract(root: Path) -> ContractSnapshot:
    root = root.resolve()
    source_texts = {
        path: _read_utf8(root, path) for path in CONTRACT_MARKDOWN_PATHS
    }
    adr18 = source_texts[ADR18_PATH]
    fields = parse_budget_fields(adr18)
    registry = parse_gate_registry(adr18)
    scopes = parse_gate_scopes(adr18, registry)
    work_packages = parse_work_packages(source_texts[ENGINEERING_PLAN_PATH])

    _validate_known_gate_tokens(source_texts, registry)
    _validate_no_stale_contracts(source_texts)
    for path, text in source_texts.items():
        _validate_fences(text, path)
        _validate_relative_links(root, text, path)

    source_sha256 = {
        path.as_posix(): hashlib.sha256((root / path).read_bytes()).hexdigest()
        for path in CONTRACT_MARKDOWN_PATHS
    }
    return ContractSnapshot(fields, scopes, work_packages, source_sha256)


def _scope_by_gate(scopes: Mapping[str, Iterable[str]]) -> dict[str, str]:
    return {gate: scope for scope, gates in scopes.items() for gate in gates}


def build_report(snapshot: ContractSnapshot) -> dict[str, object]:
    scope_by_gate = _scope_by_gate(snapshot.gate_scopes)
    report: dict[str, object] = {
        "artifact": "Gate0StatusReportV1",
        "authoritative": False,
        "budget_profile": {
            "field_count": len(snapshot.fields),
            "fixed_or_reference_field_count": 9,
            "unset_field_count": 90,
        },
        "contract_source_sha256": dict(sorted(snapshot.source_sha256.items())),
        "effective_stage": "SCHEMA_ONLY",
        "gate_registry": {
            "count": len(EXPECTED_GATES),
            "entries": [
                {
                    "gate_id": gate,
                    "scope": scope_by_gate[gate],
                    "verdict": "BLOCKED",
                }
                for gate in EXPECTED_GATES
            ],
        },
        "notice": (
            "Derived CI status only; this artifact is not a runtime, release, "
            "measurement, or authorization authority."
        ),
        "work_packages": {
            "count": len(snapshot.work_packages),
            "dependency_edge_count": sum(
                len(package.dependencies) for package in snapshot.work_packages
            ),
            "graph": "ACYCLIC",
        },
    }
    _validate_report_invariants(report)
    return report


def _walk_mapping_keys(value: object) -> Iterable[str]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield str(key)
            yield from _walk_mapping_keys(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_mapping_keys(child)


def _validate_report_invariants(report: Mapping[str, object]) -> None:
    if report.get("authoritative") is not False:
        raise ContractError("Gate0StatusReport must be non-authoritative")
    if report.get("effective_stage") != "SCHEMA_ONLY":
        raise ContractError("Gate0StatusReport effective_stage must be SCHEMA_ONLY")
    forbidden_keys = sorted(set(_walk_mapping_keys(report)) & _TIMESTAMP_KEYS)
    if forbidden_keys:
        raise ContractError(f"Gate0StatusReport must not contain timestamps: {forbidden_keys}")

    gate_registry = report.get("gate_registry")
    if not isinstance(gate_registry, dict):
        raise ContractError("Gate0StatusReport gate_registry must be an object")
    entries = gate_registry.get("entries")
    if not isinstance(entries, list):
        raise ContractError("Gate0StatusReport gate entries must be an array")
    if gate_registry.get("count") != 45 or len(entries) != 45:
        raise ContractError("Gate0StatusReport gate count must be exactly 45")
    gate_ids = [
        entry.get("gate_id") for entry in entries if isinstance(entry, dict)
    ]
    if gate_ids != list(EXPECTED_GATES):
        raise ContractError("Gate0StatusReport must contain the exact 45 GateId registry")
    if any(
        not isinstance(entry, dict) or entry.get("verdict") != "BLOCKED"
        for entry in entries
    ):
        raise ContractError("every Gate0StatusReport gate verdict must be BLOCKED")
    if any(
        not isinstance(entry, dict)
        or str(entry.get("gate_id"))
        not in EXPECTED_SCOPE_GATES.get(str(entry.get("scope")), frozenset())
        for entry in entries
    ):
        raise ContractError("Gate0StatusReport gate scope membership must match Gate 0")


def render_report(snapshot: ContractSnapshot) -> str:
    return (
        json.dumps(
            build_report(snapshot),
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n"
    )


def write_report(root: Path, report_path: Path, expected: str) -> None:
    target = report_path if report_path.is_absolute() else root / report_path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(expected, encoding="utf-8", newline="\n")


def check_report(root: Path, report_path: Path, expected: str) -> None:
    target = report_path if report_path.is_absolute() else root / report_path
    try:
        actual = target.read_text(encoding="utf-8")
    except FileNotFoundError as error:
        raise ContractError(f"missing derived report: {target}") from error
    if actual != expected:
        raise ContractError(
            f"derived report mismatch: {target}; run "
            f"`python3 tools/check_gate0_contract.py` and commit the result"
        )
    try:
        parsed = json.loads(actual)
    except json.JSONDecodeError as error:
        raise ContractError(f"derived report is not valid JSON: {target}") from error
    if not isinstance(parsed, dict):
        raise ContractError("derived report root must be a JSON object")
    _validate_report_invariants(parsed)


def _parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (default: inferred from this script)",
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=DEFAULT_REPORT_PATH,
        help="derived report path, relative to --root unless absolute",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the committed report instead of rewriting it",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] = ()) -> int:
    args = _parse_args(argv)
    try:
        snapshot = validate_contract(args.root)
        expected = render_report(snapshot)
        if args.check:
            check_report(args.root, args.report, expected)
            action = "verified"
        else:
            write_report(args.root, args.report, expected)
            action = "wrote"
    except (ContractError, OSError) as error:
        print(f"GATE0_CONTRACT_REJECTED: {error}", file=sys.stderr)
        return 2

    print(
        "GATE0_CONTRACT_OK: "
        f"{action} {args.report}; 99 fields, 45 BLOCKED gates, "
        f"{len(snapshot.work_packages)} acyclic work packages"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
