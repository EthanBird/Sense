#!/usr/bin/env python3

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_gate0_contract import (
    ADR18_PATH,
    ARCHITECTURE_PATH,
    DEFAULT_REPORT_PATH,
    ENGINEERING_PLAN_PATH,
    ContractError,
    check_report,
    render_report,
    validate_contract,
    write_report,
)


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "check_gate0_contract.py"


class MutableContractRepository:
    def __init__(self) -> None:
        self._temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary_directory.name)
        contract_sources = [
            ROOT / "README.md",
            *sorted((ROOT / "docs").rglob("*.md")),
            ROOT / DEFAULT_REPORT_PATH,
        ]
        for source in contract_sources:
            relative_path = source.relative_to(ROOT)
            target = self.root / relative_path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)

    def close(self) -> None:
        self._temporary_directory.cleanup()

    def read(self, relative_path: Path) -> str:
        return (self.root / relative_path).read_text(encoding="utf-8")

    def write(self, relative_path: Path, text: str) -> None:
        (self.root / relative_path).write_text(text, encoding="utf-8", newline="\n")


class Gate0ContractMutationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.repository = MutableContractRepository()

    def tearDown(self) -> None:
        self.repository.close()

    def test_current_contract_is_valid(self) -> None:
        snapshot = validate_contract(self.repository.root)
        self.assertEqual(99, len(snapshot.fields))
        self.assertEqual(45, sum(len(gates) for gates in snapshot.gate_scopes.values()))
        self.assertGreater(len(snapshot.work_packages), 50)

    def test_duplicate_budget_field_is_rejected(self) -> None:
        text = self.repository.read(ADR18_PATH)
        row = next(
            line for line in text.splitlines() if line.startswith("| F010 |")
        )
        self.repository.write(ADR18_PATH, text.replace(row, f"{row}\n{row}", 1))
        with self.assertRaisesRegex(ContractError, "field multiplicity.*F010=2"):
            validate_contract(self.repository.root)

    def test_out_of_registry_budget_field_is_rejected(self) -> None:
        text = self.repository.read(ADR18_PATH)
        row = next(
            line for line in text.splitlines() if line.startswith("| F099 |")
        )
        extra = "| F1000 | `unknown_budget` | u64 bytes | UNSET |"
        self.repository.write(
            ADR18_PATH, text.replace(row, f"{row}\n{extra}", 1)
        )
        with self.assertRaisesRegex(ContractError, "unknown budget field ID"):
            validate_contract(self.repository.root)

    def test_malformed_final_budget_table_header_is_rejected(self) -> None:
        text = self.repository.read(ADR18_PATH)
        needle = "| id | name | unit/type | Gate 0 |"
        offset = text.rfind(needle)
        self.assertGreaterEqual(offset, 0)
        mutated = text[:offset] + text[offset:].replace(
            needle, "| Id | name | unit/type | Gate 0 |", 1
        )
        self.repository.write(ADR18_PATH, mutated)
        with self.assertRaisesRegex(ContractError, "malformed budget field table row"):
            validate_contract(self.repository.root)

    def test_duplicate_gate_registry_entry_is_rejected(self) -> None:
        text = self.repository.read(ADR18_PATH)
        needle = "WireCompatibilityGateV1\nReleaseIdentityGateV1"
        replacement = (
            "WireCompatibilityGateV1\n"
            "WireCompatibilityGateV1\n"
            "ReleaseIdentityGateV1"
        )
        self.assertIn(needle, text)
        self.repository.write(ADR18_PATH, text.replace(needle, replacement, 1))
        with self.assertRaisesRegex(ContractError, "duplicate GateId"):
            validate_contract(self.repository.root)

    def test_duplicate_numbered_gate_section_is_rejected(self) -> None:
        text = self.repository.read(ADR18_PATH)
        self.repository.write(
            ADR18_PATH, text + "\n ## 14. duplicate authority section\n"
        )
        with self.assertRaisesRegex(ContractError, "section 14 must occur exactly once"):
            validate_contract(self.repository.root)

    def test_duplicate_gate_registry_marker_is_rejected(self) -> None:
        text = self.repository.read(ADR18_PATH)
        marker = "### 14.1 Closed `GateIdV1` registry"
        self.assertEqual(1, text.count(marker))
        self.repository.write(
            ADR18_PATH, text.replace(marker, f"{marker}\n\n{marker}", 1)
        )
        with self.assertRaisesRegex(ContractError, "marker must occur exactly once"):
            validate_contract(self.repository.root)

    def test_unknown_gate_token_is_rejected(self) -> None:
        text = self.repository.read(ARCHITECTURE_PATH)
        self.repository.write(
            ARCHITECTURE_PATH,
            text + "\nUnknownMemoryAuthorityGateV1\nUnknown_MemoryGateV1\n",
        )
        with self.assertRaisesRegex(ContractError, r"unknown \*GateV1"):
            validate_contract(self.repository.root)

    def test_scope_membership_drift_is_rejected(self) -> None:
        text = self.repository.read(ADR18_PATH)
        needle = (
            "WireCompatibilityGateV1,ReleaseSigningAuthorityGateV1,"
            "PlatformCertificationGateV1,"
        )
        replacement = (
            "ReleaseSigningAuthorityGateV1,PlatformCertificationGateV1,"
        )
        self.assertIn(needle, text)
        self.repository.write(ADR18_PATH, text.replace(needle, replacement, 1))
        with self.assertRaisesRegex(ContractError, "RELEASE_BUILD GateId membership"):
            validate_contract(self.repository.root)

    def test_missing_package_dependency_is_rejected(self) -> None:
        text = self.repository.read(ENGINEERING_PLAN_PATH)
        needle = "| `G0-MECH` | G0-JOINT |"
        replacement = "| `G0-MECH` | G0-NOT-DEFINED |"
        self.assertIn(needle, text)
        self.repository.write(
            ENGINEERING_PLAN_PATH, text.replace(needle, replacement, 1)
        )
        with self.assertRaisesRegex(ContractError, "dependency is undefined"):
            validate_contract(self.repository.root)

    def test_duplicate_package_id_is_rejected(self) -> None:
        text = self.repository.read(ENGINEERING_PLAN_PATH)
        row = next(
            line for line in text.splitlines() if line.startswith("| `G0-01` |")
        )
        self.repository.write(
            ENGINEERING_PLAN_PATH, text.replace(row, f"{row}\n{row}", 1)
        )
        with self.assertRaisesRegex(ContractError, "duplicate issue-ready package ID"):
            validate_contract(self.repository.root)

    def test_malformed_leaf_package_id_is_rejected(self) -> None:
        text = self.repository.read(ENGINEERING_PLAN_PATH)
        needle = "| `M93-RC` | M93-02, R-03 |"
        replacement = "| M93_RC | M93-02, R-03 |"
        self.assertIn(needle, text)
        self.repository.write(
            ENGINEERING_PLAN_PATH, text.replace(needle, replacement, 1)
        )
        with self.assertRaisesRegex(ContractError, "package ID must use exact backticks"):
            validate_contract(self.repository.root)

    def test_truncated_leaf_package_row_is_rejected(self) -> None:
        text = self.repository.read(ENGINEERING_PLAN_PATH)
        row = next(
            line for line in text.splitlines() if line.startswith("| `M93-RC` |")
        )
        self.assertTrue(row.endswith("|"))
        self.repository.write(
            ENGINEERING_PLAN_PATH, text.replace(row, row[:-1], 1)
        )
        with self.assertRaisesRegex(ContractError, "malformed non-empty package"):
            validate_contract(self.repository.root)

    def test_malformed_final_package_table_header_is_rejected(self) -> None:
        text = self.repository.read(ENGINEERING_PLAN_PATH)
        needle = "| ID | 依赖 | 主要改动 | 退出条件 |"
        offset = text.rfind(needle)
        self.assertGreaterEqual(offset, 0)
        mutated = text[:offset] + text[offset:].replace(
            needle, "| Id | 依赖 | 主要改动 | 退出条件 |", 1
        )
        self.repository.write(ENGINEERING_PLAN_PATH, mutated)
        with self.assertRaisesRegex(ContractError, "malformed package table row"):
            validate_contract(self.repository.root)

    def test_package_dependency_cycle_is_rejected(self) -> None:
        text = self.repository.read(ENGINEERING_PLAN_PATH)
        needle = "| `G0-01` | 无 |"
        replacement = "| `G0-01` | G0-MECH |"
        self.assertIn(needle, text)
        self.repository.write(
            ENGINEERING_PLAN_PATH, text.replace(needle, replacement, 1)
        )
        with self.assertRaisesRegex(ContractError, "dependency cycle"):
            validate_contract(self.repository.root)

    def test_stale_contract_literal_is_rejected(self) -> None:
        text = self.repository.read(ARCHITECTURE_PATH)
        self.repository.write(
            ARCHITECTURE_PATH, text + "\nlegacy path: temp/projection/main\n"
        )
        with self.assertRaisesRegex(ContractError, "stale Gate 0 contract"):
            validate_contract(self.repository.root)

    def test_unterminated_markdown_fence_is_rejected(self) -> None:
        text = self.repository.read(ARCHITECTURE_PATH)
        self.repository.write(ARCHITECTURE_PATH, text + "\n```text\nunfinished\n")
        with self.assertRaisesRegex(ContractError, "unterminated Markdown fence"):
            validate_contract(self.repository.root)

    def test_missing_relative_link_is_rejected(self) -> None:
        text = self.repository.read(ARCHITECTURE_PATH)
        self.repository.write(
            ARCHITECTURE_PATH, text + "\n[missing](../missing-gate0-contract.md)\n"
        )
        with self.assertRaisesRegex(ContractError, "missing local link target"):
            validate_contract(self.repository.root)

    def test_report_mismatch_is_rejected(self) -> None:
        expected = render_report(validate_contract(self.repository.root))
        write_report(self.repository.root, DEFAULT_REPORT_PATH, expected)
        target = self.repository.root / DEFAULT_REPORT_PATH
        target.write_text(
            target.read_text(encoding="utf-8").replace(
                '"effective_stage": "SCHEMA_ONLY"',
                '"effective_stage": "DARK"',
                1,
            ),
            encoding="utf-8",
            newline="\n",
        )
        with self.assertRaisesRegex(ContractError, "derived report mismatch"):
            check_report(self.repository.root, DEFAULT_REPORT_PATH, expected)

    def test_derived_report_is_non_authoritative_and_fully_blocked(self) -> None:
        report = json.loads(render_report(validate_contract(self.repository.root)))
        self.assertIs(False, report["authoritative"])
        self.assertEqual("SCHEMA_ONLY", report["effective_stage"])
        entries = report["gate_registry"]["entries"]
        self.assertEqual(45, len(entries))
        self.assertEqual({"BLOCKED"}, {entry["verdict"] for entry in entries})
        self.assertFalse(
            {"timestamp", "generated_at", "generated_on"} & set(report)
        )

    def test_cli_generate_then_check_is_deterministic(self) -> None:
        generate = subprocess.run(
            (
                sys.executable,
                str(SCRIPT),
                "--root",
                str(self.repository.root),
            ),
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, generate.returncode, generate.stderr)
        first = (self.repository.root / DEFAULT_REPORT_PATH).read_bytes()

        check = subprocess.run(
            (
                sys.executable,
                str(SCRIPT),
                "--root",
                str(self.repository.root),
                "--check",
            ),
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, check.returncode, check.stderr)

        regenerate = subprocess.run(
            (
                sys.executable,
                str(SCRIPT),
                "--root",
                str(self.repository.root),
            ),
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, regenerate.returncode, regenerate.stderr)
        self.assertEqual(first, (self.repository.root / DEFAULT_REPORT_PATH).read_bytes())
        self.assertNotIn(b"timestamp", first)


if __name__ == "__main__":
    unittest.main()
