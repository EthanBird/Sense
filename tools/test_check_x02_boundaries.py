#!/usr/bin/env python3
"""Mutation tests for the fail-closed X-02 boundary checker."""

from __future__ import annotations

import hashlib
import io
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

import check_x02_boundaries as checker


def _write(root: Path, relative: str | Path, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _wire_enum(name: str, entries: tuple[str, ...]) -> str:
    constants = "\n".join(f'    {entry}("{entry}"),' for entry in entries)
    return f"""
enum class {name}(val wireValue: String) {{
{constants}
    ;
}}
"""


def _plain_enum(name: str, entries: tuple[str, ...]) -> str:
    constants = "\n".join(f"    {entry}," for entry in entries)
    return f"""
enum class {name} {{
{constants}
}}
"""


def _good_gate_registry() -> str:
    gate_scope: dict[str, str] = {}
    for scope, gates in checker.EXPECTED_SCOPE_GATES.items():
        for gate in gates:
            gate_scope[gate] = scope
    scopes = "\n".join(
        f"    {scope}," for scope in checker.EXPECTED_SCOPE_GATES
    )
    gates = "\n".join(
        f"    {gate}(GateScopeKindV1.{gate_scope[gate]}),"
        for gate in checker.EXPECTED_GATES
    )
    return f"""package io.github.ethanbird.senseime.memory.protocol

enum class GateScopeKindV1 {{
{scopes}
}}

enum class GateIdV1(val scope: GateScopeKindV1) {{
{gates}
    ;
}}

object GateRegistryV1
"""


def _good_stage_domains() -> str:
    enum_values = {
        enum_name: entries
        for (path, enum_name), entries in checker.EXPECTED_ENUMS.items()
        if path == checker.STAGE_DOMAINS_PATH
    }
    parts = ["package io.github.ethanbird.senseime.memory.protocol\n"]
    for name, entries in enum_values.items():
        if name in checker.WIRE_VALUE_ENUMS:
            parts.append(_wire_enum(name, entries))
        else:
            parts.append(_plain_enum(name, entries))
    parts.append("object FeatureStageOrderV1\n")
    return "\n".join(parts)


def _good_feature_policy() -> str:
    reasons = checker.EXPECTED_ENUMS[
        (checker.FEATURE_POLICY_PATH, "X02StageDecisionReasonV1")
    ]
    return (
        "package io.github.ethanbird.senseime.memory.protocol\n"
        + _plain_enum("X02StageDecisionReasonV1", reasons)
        + """
class X02GateObservationV1
class X02NormalStageRequestV1
class X02StageDecisionV1
object X02FeatureStagePolicyV1
"""
    )


def _good_fail_closed_view() -> str:
    enum_values = {
        enum_name: entries
        for (path, enum_name), entries in checker.EXPECTED_ENUMS.items()
        if path == checker.FAIL_CLOSED_VIEW_PATH
    }
    parts = [
        "package io.github.ethanbird.senseime.memory.protocol\n",
        "import java.util.concurrent.atomic.AtomicReference\n",
    ]
    for name, entries in enum_values.items():
        parts.append(_plain_enum(name, entries))
    parts.append(
        """
class X02SafeStageViewV1
object X02FailClosedStageReducerV1
class X02ProcessStageHolderV1 {
    private val state = AtomicReference(X02SafeStageCauseV1.PROCESS_START)
}
"""
    )
    return "\n".join(parts)


def _good_event_scaffold() -> str:
    availability = checker.EXPECTED_ENUMS[
        (checker.EVENT_SCAFFOLD_PATH, "X02EventJournalAvailabilityV1")
    ]
    return (
        "package io.github.ethanbird.senseime.memory.journal.core\n\n"
        "import io.github.ethanbird.senseime.memory.protocol.FeatureStageV1\n"
        + _plain_enum("X02EventJournalAvailabilityV1", availability)
        + """
object X02EventJournalScaffoldV1 {
    fun normalStageCeiling(): FeatureStageV1 = FeatureStageV1.SCHEMA_ONLY
}
"""
    )


def _good_module_build(*, event: bool) -> str:
    module = checker.EVENT_JOURNAL if event else checker.MEMORY_PROTOCOL
    return checker.EXPECTED_PROTECTED_BUILD_SCRIPTS[module]


def _write_good_tree(root: Path) -> None:
    _write(root, checker.GATE_REGISTRY_PATH, _good_gate_registry())
    _write(root, checker.STAGE_DOMAINS_PATH, _good_stage_domains())
    _write(root, checker.FEATURE_POLICY_PATH, _good_feature_policy())
    _write(root, checker.FAIL_CLOSED_VIEW_PATH, _good_fail_closed_view())
    _write(root, checker.EVENT_SCAFFOLD_PATH, _good_event_scaffold())

    modules = (
        "app",
        "ai-protocol",
        "ime-config",
        "ime-service",
        "ime-ui",
        "brain-api",
        "ai-brain",
        "ai-runtime",
        "core-input",
        "memory-protocol",
        "event-journal",
        "benchmark",
    )
    include_lines = "\n".join(f'    ":{module}",' for module in modules)
    _write(
        root,
        "settings.gradle.kts",
        f'rootProject.name = "fixture"\ninclude(\n{include_lines}\n)\n',
    )
    _write(root, "build.gradle.kts", checker.EXPECTED_ROOT_BUILD_SCRIPT)
    _write(
        root,
        "gradle/libs.versions.toml",
        """
[versions]
kotlin = "2.2.21"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
""",
    )
    _write(
        root,
        "memory-protocol/build.gradle.kts",
        _good_module_build(event=False),
    )
    _write(
        root,
        "event-journal/build.gradle.kts",
        _good_module_build(event=True),
    )
    _write(
        root,
        "app/build.gradle.kts",
        'dependencies { implementation(project(":ai-runtime")) }\n',
    )
    for module in (
        "ai-protocol",
        "ime-config",
        "ime-service",
        "ime-ui",
        "brain-api",
        "ai-brain",
        "ai-runtime",
        "core-input",
        "benchmark",
    ):
        _write(root, f"{module}/build.gradle.kts", "dependencies {}\n")
    _write(root, "gradle.properties", "fixture=true\n")
    _write(root, "gradlew", "#!/bin/sh\nexit 1\n")
    _write(
        root,
        "gradle/wrapper/gradle-wrapper.properties",
        "distributionUrl=https://example.invalid/gradle.zip\n"
        "distributionSha256Sum="
        "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78\n",
    )
    _write(root, "gradle/wrapper/gradle-wrapper.jar", "fixture-wrapper\n")

    _write(
        root,
        "tools/local_release.ps1",
        """$ErrorActionPreference = "Stop"
python tools/test_check_x02_boundaries.py
python tools/check_x02_boundaries.py
& .\\gradlew.bat `
    :memory-protocol:test `
    :memory-protocol:jar `
    :event-journal:test `
    :event-journal:jar `
    :app:assembleDebug `
    :app:assembleBenchmark `
    :app:assembleRelease
python tools/check_x02_boundaries.py --check-artifacts
""",
    )
    _write(
        root,
        "tools/offline_verify.sh",
        """#!/usr/bin/env bash
set -euo pipefail

JAR_TOOL=jar
OUT=build/out
MEMORY_PROTOCOL_JAR="$OUT/memory-protocol-main.jar"
EVENT_JOURNAL_JAR="$OUT/event-journal-main.jar"
APK=app/build/outputs/apk/offline/app.apk
python3 "$ROOT/tools/test_check_x02_boundaries.py" 2>&1 |
    tee "$OUT/x02-boundary-checker-tests.txt"
python3 "$ROOT/tools/check_x02_boundaries.py" 2>&1 |
    tee "$OUT/x02-boundaries.txt"
MEMORY_PROTOCOL_SOURCES=memory-protocol/src/main/kotlin
MEMORY_PROTOCOL_TEST_SOURCES=memory-protocol/src/test/kotlin
EVENT_JOURNAL_SOURCES=event-journal/src/main/kotlin
EVENT_JOURNAL_TEST_SOURCES=event-journal/src/test/kotlin
mkdir "$OUT/memory-protocol-main" "$OUT/memory-protocol-test"
mkdir "$OUT/event-journal-main" "$OUT/event-journal-test"
"$JAR_TOOL" --create --file "$MEMORY_PROTOCOL_JAR"
"$JAR_TOOL" --create --file "$EVENT_JOURNAL_JAR"
build_apk "$APK"
sha256sum "$APK"
python3 "$ROOT/tools/check_x02_boundaries.py" \\
    --check-artifacts \\
    --memory-jar "$MEMORY_PROTOCOL_JAR" \\
    --event-journal-jar "$EVENT_JOURNAL_JAR" \\
    --app-apk "$APK"
""",
    )


def _write_zip(path: Path, entries: dict[str, bytes]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)


def _good_artifacts(root: Path) -> tuple[Path, Path, Path, Path]:
    memory_jar = root / "memory-protocol/build/libs/memory-protocol.jar"
    event_jar = root / "event-journal/build/libs/event-journal.jar"
    memory_entries = {
        name: b"\xca\xfe\xba\xbe-fixture"
        for name in checker.MEMORY_REQUIRED_CLASSES
    }
    memory_entries["META-INF/main.kotlin_module"] = b"fixture"
    memory_entries["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\n"
    event_entries = {
        name: b"\xca\xfe\xba\xbe-fixture"
        for name in checker.EVENT_REQUIRED_CLASSES
    }
    event_entries["META-INF/main.kotlin_module"] = b"fixture"
    event_entries["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\n"
    _write_zip(memory_jar, memory_entries)
    _write_zip(event_jar, event_entries)

    debug_apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    benchmark_apk = (
        root / "app/build/outputs/apk/benchmark/app-benchmark.apk"
    )
    _write_zip(debug_apk, {"classes.dex": b"dex\n035\x00safe-debug"})
    _write_zip(
        benchmark_apk,
        {"classes.dex": b"dex\n035\x00safe-benchmark"},
    )
    return memory_jar, event_jar, debug_apk, benchmark_apk


def _rewrite_zip(
    path: Path,
    mutate: callable,
) -> None:
    with zipfile.ZipFile(path) as archive:
        entries = {name: archive.read(name) for name in archive.namelist()}
    mutate(entries)
    _write_zip(path, entries)


class X02BoundaryMutationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        _write_good_tree(self.root)
        self.original_main_sha256 = checker.EXPECTED_MAIN_SHA256
        checker.EXPECTED_MAIN_SHA256 = {
            relative: hashlib.sha256(
                (self.root / relative).read_bytes()
            ).hexdigest()
            for relative in self.original_main_sha256
        }
        self.original_offline_sha256 = checker.EXPECTED_OFFLINE_VERIFY_SHA256
        checker.EXPECTED_OFFLINE_VERIFY_SHA256 = hashlib.sha256(
            (self.root / "tools/offline_verify.sh")
            .read_text(encoding="utf-8")
            .encode("utf-8")
        ).hexdigest()
        self.original_build_authority_sha256 = (
            checker.EXPECTED_BUILD_AUTHORITY_SHA256
        )
        checker.EXPECTED_BUILD_AUTHORITY_SHA256 = {
            relative: hashlib.sha256(
                (self.root / relative).read_bytes()
            ).hexdigest()
            for relative in self.original_build_authority_sha256
        }

    def tearDown(self) -> None:
        checker.EXPECTED_MAIN_SHA256 = self.original_main_sha256
        checker.EXPECTED_OFFLINE_VERIFY_SHA256 = self.original_offline_sha256
        checker.EXPECTED_BUILD_AUTHORITY_SHA256 = (
            self.original_build_authority_sha256
        )
        self.temporary.cleanup()

    def replace(self, relative: str | Path, old: str, new: str) -> None:
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1), encoding="utf-8")

    def append(self, relative: str | Path, text: str) -> None:
        path = self.root / relative
        path.write_text(path.read_text(encoding="utf-8") + text, encoding="utf-8")

    def assert_source_rejected(self, contains: str | None = None) -> None:
        with self.assertRaises(checker.BoundaryError) as caught:
            checker.check_repository(self.root)
        if contains is not None:
            self.assertIn(contains, str(caught.exception))

    def assert_artifact_rejected(self, contains: str | None = None) -> None:
        with self.assertRaises(checker.BoundaryError) as caught:
            checker.check_repository(self.root, check_built_artifacts=True)
        if contains is not None:
            self.assertIn(contains, str(caught.exception))

    def test_good_source_fixture_passes(self) -> None:
        checker.check_repository(self.root)

    def test_gate_order_swap_is_rejected(self) -> None:
        first, second = checker.EXPECTED_GATES[:2]
        scope_by_gate = {
            gate: scope
            for scope, gates in checker.EXPECTED_SCOPE_GATES.items()
            for gate in gates
        }
        old = (
            f"    {first}(GateScopeKindV1.{scope_by_gate[first]}),\n"
            f"    {second}(GateScopeKindV1.{scope_by_gate[second]}),"
        )
        new = (
            f"    {second}(GateScopeKindV1.{scope_by_gate[second]}),\n"
            f"    {first}(GateScopeKindV1.{scope_by_gate[first]}),"
        )
        self.replace(checker.GATE_REGISTRY_PATH, old, new)
        self.assert_source_rejected("order/set drift")

    def test_gate_scope_mutation_is_rejected(self) -> None:
        gate = checker.EXPECTED_GATES[0]
        self.replace(
            checker.GATE_REGISTRY_PATH,
            f"{gate}(GateScopeKindV1.RELEASE_BUILD)",
            f"{gate}(GateScopeKindV1.PHASE_SCHEMA)",
        )
        self.assert_source_rejected("scope drift")

    def test_stage_enum_extra_value_is_rejected(self) -> None:
        self.replace(
            checker.STAGE_DOMAINS_PATH,
            '    DEFAULT("DEFAULT"),',
            '    DEFAULT("DEFAULT"),\n    FUTURE("FUTURE"),',
        )
        self.assert_source_rejected("FeatureStageV1 drift")

    def test_verdict_enum_missing_value_is_rejected(self) -> None:
        self.replace(
            checker.STAGE_DOMAINS_PATH,
            '    INVALID("INVALID"),\n',
            "",
        )
        self.assert_source_rejected("GateVerdictV1 drift")

    def test_permit_enum_reorder_is_rejected(self) -> None:
        self.replace(
            checker.STAGE_DOMAINS_PATH,
            '    ALLOW("ALLOW"),\n    NOT_RUN_BLOCKED("NOT_RUN_BLOCKED"),',
            '    NOT_RUN_BLOCKED("NOT_RUN_BLOCKED"),\n    ALLOW("ALLOW"),',
        )
        self.assert_source_rejected("PermitDecisionV1 drift")

    def test_profile_class_enum_extra_value_is_rejected(self) -> None:
        self.replace(
            checker.STAGE_DOMAINS_PATH,
            '    REAL_DATA("REAL_DATA"),',
            '    REAL_DATA("REAL_DATA"),\n    UNKNOWN("UNKNOWN"),',
        )
        self.assert_source_rejected("ProfileExecutionClassV1 drift")

    def test_capability_enum_missing_value_is_rejected(self) -> None:
        self.replace(
            checker.STAGE_DOMAINS_PATH,
            '    CAPTURE("CAPTURE"),\n',
            "",
        )
        self.assert_source_rejected("NormalProfileCapabilityIdV1 drift")

    def test_disposition_enum_extra_value_is_rejected(self) -> None:
        self.replace(
            checker.STAGE_DOMAINS_PATH,
            "    BLOCKED_FAIL_CLOSED,\n",
            "    BLOCKED_FAIL_CLOSED,\n    ALLOW_HIGHER,\n",
        )
        self.assert_source_rejected("X02StageDecisionDispositionV1 drift")

    def test_reason_enum_extra_value_is_rejected(self) -> None:
        self.replace(
            checker.FEATURE_POLICY_PATH,
            "    CONFIGURED_CEILING_CONTRACTED,\n",
            "    CONFIGURED_CEILING_CONTRACTED,\n    AUTHORIZED,\n",
        )
        self.assert_source_rejected("X02StageDecisionReasonV1 drift")

    def test_rejected_input_enum_extra_value_is_rejected(self) -> None:
        self.replace(
            checker.FAIL_CLOSED_VIEW_PATH,
            "    UNAUTHENTICATED,\n",
            "    UNAUTHENTICATED,\n    VALID,\n",
        )
        self.assert_source_rejected("X02RejectedStageInputV1 drift")

    def test_cause_enum_reorder_is_rejected(self) -> None:
        self.replace(
            checker.FAIL_CLOSED_VIEW_PATH,
            "    PROCESS_START,\n    SOURCE_ABSENT,",
            "    SOURCE_ABSENT,\n    PROCESS_START,",
        )
        self.assert_source_rejected("X02SafeStageCauseV1 drift")

    def test_role_enum_extra_value_is_rejected(self) -> None:
        self.replace(
            checker.FAIL_CLOSED_VIEW_PATH,
            "    BRAIN,\n",
            "    BRAIN,\n    BROKER,\n",
        )
        self.assert_source_rejected("X02StageConsumerRoleV1 drift")

    def test_event_availability_enum_extra_value_is_rejected(self) -> None:
        self.replace(
            checker.EVENT_SCAFFOLD_PATH,
            "    SCHEMA_ONLY_NO_STORAGE,\n",
            "    SCHEMA_ONLY_NO_STORAGE,\n    WRITABLE,\n",
        )
        self.assert_source_rejected("X02EventJournalAvailabilityV1 drift")

    def test_extra_memory_main_file_is_rejected(self) -> None:
        _write(
            self.root,
            "memory-protocol/src/main/kotlin/Backdoor.kt",
            "class Backdoor\n",
        )
        self.assert_source_rejected("main file allowlist")

    def test_extra_event_main_file_is_rejected(self) -> None:
        _write(
            self.root,
            "event-journal/src/main/kotlin/Writer.kt",
            "class Writer\n",
        )
        self.assert_source_rejected("main file allowlist")

    def test_android_import_is_rejected(self) -> None:
        self.replace(
            checker.FEATURE_POLICY_PATH,
            "package io.github.ethanbird.senseime.memory.protocol\n",
            "package io.github.ethanbird.senseime.memory.protocol\n"
            "import android.content.Context\n",
        )
        self.assert_source_rejected("import drift")

    def test_aliased_kotlin_io_import_is_rejected(self) -> None:
        self.replace(
            checker.FEATURE_POLICY_PATH,
            "package io.github.ethanbird.senseime.memory.protocol\n",
            "package io.github.ethanbird.senseime.memory.protocol\n"
            "import kotlin.io.path.writeText as persist\n",
        )
        self.assert_source_rejected("aliased")

    def test_arbitrary_aliased_import_is_rejected(self) -> None:
        self.replace(
            checker.FEATURE_POLICY_PATH,
            "package io.github.ethanbird.senseime.memory.protocol\n",
            "package io.github.ethanbird.senseime.memory.protocol\n"
            "import java.util.Date as Clock\n",
        )
        self.assert_source_rejected("aliased")

    def test_fully_qualified_kotlin_io_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            "\nval leak = kotlin.io.path.createTempFile()\n",
        )
        self.assert_source_rejected("forbidden production dependency")

    def test_spaced_fully_qualified_kotlin_io_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            "\nval leak = kotlin . io . path . createTempFile()\n",
        )
        self.assert_source_rejected("forbidden production dependency")

    def test_fully_qualified_preferences_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            "\nval leak = java.util.prefs.Preferences.userRoot()\n",
        )
        self.assert_source_rejected("forbidden production dependency")

    def test_backtick_qualified_kotlin_io_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            "\nval leak = kotlin.`io`.path.createTempFile()\n",
        )
        self.assert_source_rejected("backtick production identifier")

    def test_reflective_class_lookup_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            '\nval leak = Class.forName("java.io.File")\n',
        )
        self.assert_source_rejected("forbidden identifier Class")

    def test_class_loader_load_class_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            '\nval leak = Any::class.java.classLoader.loadClass("java.io.File")\n',
        )
        self.assert_source_rejected("classLoader")

    def test_class_loader_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            "\nval leak: ClassLoader? = null\n",
        )
        self.assert_source_rejected("forbidden identifier ClassLoader")

    def test_process_builder_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            '\nval leak = ProcessBuilder("sh")\n',
        )
        self.assert_source_rejected("forbidden identifier ProcessBuilder")

    def test_runtime_exec_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            '\nval leak = Runtime.getRuntime().exec("sh")\n',
        )
        self.assert_source_rejected("forbidden identifier Runtime")

    def test_system_native_load_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            '\nval leak = System.loadLibrary("x")\n',
        )
        self.assert_source_rejected("forbidden identifier System")

    def test_file_handler_side_effect_is_rejected_by_fingerprint(self) -> None:
        self.append(
            checker.EVENT_SCAFFOLD_PATH,
            "\nprivate val hidden = java.util.logging.FileHandler()\n",
        )
        self.assert_source_rejected("frozen production source fingerprint drift")

    def test_resource_connection_is_rejected_by_fingerprint(self) -> None:
        self.append(
            checker.EVENT_SCAFFOLD_PATH,
            '\nprivate val hidden = Any::class.java.getResource("/")!!'
            ".openConnection()\n",
        )
        self.assert_source_rejected("frozen production source fingerprint drift")

    def test_private_write_method_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            "\nprivate fun write() = Unit\n",
        )
        self.assert_source_rejected("forbidden identifier write")

    def test_file_api_is_rejected(self) -> None:
        self.append(checker.FEATURE_POLICY_PATH, "\nval leak = java.io.File(\"x\")\n")
        self.assert_source_rejected("forbidden production dependency")

    def test_network_api_is_rejected(self) -> None:
        self.append(checker.FEATURE_POLICY_PATH, "\nval leak = java.net.URL(\"x\")\n")
        self.assert_source_rejected("forbidden production dependency")

    def test_crypto_api_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            '\nval leak = javax.crypto.Cipher.getInstance("AES")\n',
        )
        self.assert_source_rejected("forbidden production dependency")

    def test_room_api_is_rejected(self) -> None:
        self.append(checker.FEATURE_POLICY_PATH, "\nval leak: Room? = null\n")
        self.assert_source_rejected("forbidden identifier")

    def test_byte_array_api_is_rejected(self) -> None:
        self.append(checker.FEATURE_POLICY_PATH, "\nval leak = ByteArray(1)\n")
        self.assert_source_rejected("ByteArray")

    def test_snapshot_identifier_is_rejected(self) -> None:
        self.append(
            checker.FEATURE_POLICY_PATH,
            "\nclass FuturePersistentSnapshotWire\n",
        )
        self.assert_source_rejected("Snapshot")

    def test_generation_identifier_is_rejected(self) -> None:
        self.append(checker.FEATURE_POLICY_PATH, "\nval snapshotGeneration = 1\n")
        self.assert_source_rejected("snapshotGeneration")

    def test_codec_identifier_is_rejected(self) -> None:
        self.append(checker.FEATURE_POLICY_PATH, "\nobject PersistentCodec\n")
        self.assert_source_rejected("PersistentCodec")

    def test_memory_protocol_project_dependency_is_rejected(self) -> None:
        self.append(
            "memory-protocol/build.gradle.kts",
            '\ndependencies { implementation(project(":core-input")) }\n',
        )
        self.assert_source_rejected("must have no production project dependency")

    def test_event_journal_extra_dependency_is_rejected(self) -> None:
        self.append(
            "event-journal/build.gradle.kts",
            '\ndependencies { implementation(project(":core-input")) }\n',
        )
        self.assert_source_rejected("exactly (:memory-protocol)")

    def test_dynamic_external_production_dependency_is_rejected(self) -> None:
        self.append(
            "memory-protocol/build.gradle.kts",
            '\ndependencies.add("implementation", libs.junit)\n',
        )
        self.assert_source_rejected("protected build script drift")

    def test_extra_plugin_is_rejected(self) -> None:
        self.replace(
            "memory-protocol/build.gradle.kts",
            "plugins {\n",
            'plugins {\n    id("application")\n',
        )
        self.assert_source_rejected("protected build script drift")

    def test_source_set_injection_is_rejected(self) -> None:
        self.append(
            "event-journal/build.gradle.kts",
            '\nsourceSets.main { resources.srcDir("generated") }\n',
        )
        self.assert_source_rejected("protected build script drift")

    def test_jar_task_injection_is_rejected(self) -> None:
        self.append(
            "memory-protocol/build.gradle.kts",
            '\ntasks.jar { from("payload") }\n',
        )
        self.assert_source_rejected("protected build script drift")

    def test_protected_build_script_comments_require_re_review(self) -> None:
        self.replace(
            "memory-protocol/build.gradle.kts",
            "plugins {\n",
            "/* no implementation dependencies */\n"
            "plugins { // exact implementation-free plugin\n",
        )
        self.assert_source_rejected("frozen build authority fingerprint drift")

    def test_app_direct_dependency_is_rejected(self) -> None:
        self.append(
            "app/build.gradle.kts",
            '\ndependencies { implementation(project(":memory-protocol")) }\n',
        )
        self.assert_source_rejected("references an X-02 module")

    def test_type_safe_project_reference_is_rejected(self) -> None:
        self.append(
            "app/build.gradle.kts",
            "\ndependencies { implementation(projects.memoryProtocol) }\n",
        )
        self.assert_source_rejected("type-safe project dependency syntax")

    def test_runtime_find_project_reference_is_rejected(self) -> None:
        self.append(
            "app/build.gradle.kts",
            '\ndependencies { implementation(rootProject.findProject('
            '":memory-protocol")!!) }\n',
        )
        self.assert_source_rejected("references an X-02 module")

    def test_settings_project_dir_redirect_is_rejected(self) -> None:
        self.append(
            "settings.gradle.kts",
            '\nproject(":memory-protocol").projectDir = file("shadow")\n',
        )
        self.assert_source_rejected("forbidden module redirection projectDir")

    def test_settings_include_build_is_rejected(self) -> None:
        self.append("settings.gradle.kts", '\nincludeBuild("shadow")\n')
        self.assert_source_rejected("forbidden module redirection includeBuild")

    def test_settings_plugin_remap_is_rejected_by_fingerprint(self) -> None:
        self.replace(
            "settings.gradle.kts",
            'rootProject.name = "fixture"\n',
            """
pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.jetbrains.kotlin.jvm") {
                useModule("com.example:effectful-plugin:1.0")
            }
        }
    }
}
rootProject.name = "fixture"
""",
        )
        self.assert_source_rejected("frozen build authority fingerprint drift")

    def test_settings_compile_then_restore_is_rejected_by_fingerprint(
        self,
    ) -> None:
        self.append(
            "settings.gradle.kts",
            "\ngradle.settingsEvaluated { file(\"event-journal/src/main/kotlin/"
            "Payload.kt\").writeText(\"effect\") }\n",
        )
        self.assert_source_rejected("frozen build authority fingerprint drift")

    def test_root_build_x02_task_injection_is_rejected(self) -> None:
        _write(
            self.root,
            "build.gradle.kts",
            'project(":event-journal") { tasks.jar { from("../payload") } }\n',
        )
        self.assert_source_rejected("protected root build script drift")

    def test_root_build_subprojects_injection_is_rejected(self) -> None:
        self.append(
            "build.gradle.kts",
            '\nsubprojects { if (name == "event-journal") '
            '{ tasks.jar { from("../payload") } } }\n',
        )
        self.assert_source_rejected("protected root build script drift")

    def test_kotlin_jvm_plugin_catalog_remap_is_rejected(self) -> None:
        self.replace(
            "gradle/libs.versions.toml",
            'id = "org.jetbrains.kotlin.jvm"',
            'id = "com.example.effectful-plugin"',
        )
        self.assert_source_rejected("remaps the Kotlin/JVM plugin")

    def test_kotlin_plugin_version_drift_is_rejected_by_fingerprint(self) -> None:
        self.replace(
            "gradle/libs.versions.toml",
            'kotlin = "2.2.21"',
            'kotlin = "2.1.21"',
        )
        self.assert_source_rejected("frozen build authority fingerprint drift")

    def test_gradle_wrapper_drift_is_rejected_by_fingerprint(self) -> None:
        self.append(
            "gradle/wrapper/gradle-wrapper.jar",
            "effectful-wrapper",
        )
        self.assert_source_rejected("frozen build authority fingerprint drift")

    def test_gradle_distribution_checksum_drift_is_rejected(self) -> None:
        self.replace(
            "gradle/wrapper/gradle-wrapper.properties",
            "20f1b1176237254a6fc204d8434196fa"
            "11a4cfb387567519c61556e8710aed78",
            "00f1b1176237254a6fc204d8434196fa"
            "11a4cfb387567519c61556e8710aed78",
        )
        self.assert_source_rejected("distribution SHA-256")

    def test_unrelated_module_build_mutation_requires_re_review(self) -> None:
        self.append(
            "app/build.gradle.kts",
            '\ntasks.configureEach { doFirst { file("effect").writeText("x") } }\n',
        )
        self.assert_source_rejected("frozen build authority fingerprint drift")

    def test_build_src_is_rejected(self) -> None:
        _write(
            self.root,
            "buildSrc/build.gradle.kts",
            'println("effect")\n',
        )
        self.assert_source_rejected("buildSrc is forbidden")

    def test_gradle_work_state_directory_is_not_script_authority(self) -> None:
        _write(
            self.root,
            ".gradle/8.13/fileHashes/fileHashes.bin",
            "generated state\n",
        )
        checker.check_repository(self.root)

    def test_gradle_work_state_script_is_rejected(self) -> None:
        _write(
            self.root,
            ".gradle/hidden.gradle",
            'println("effect")\n',
        )
        self.assert_source_rejected("unexpected Gradle script authority")

    def test_gradle_work_state_script_symlink_is_rejected(self) -> None:
        _write(self.root, ".gradle/generated-state.txt", "state\n")
        script = self.root / ".gradle/hidden.gradle.kts"
        try:
            script.symlink_to(self.root / ".gradle/generated-state.txt")
        except OSError as error:
            if getattr(error, "winerror", None) == 1314:
                self.skipTest("Windows symbolic-link privilege is unavailable")
            raise
        self.assert_source_rejected("unexpected Gradle script authority")

    def test_parallel_groovy_settings_script_is_rejected(self) -> None:
        _write(self.root, "settings.gradle", 'println("effect")\n')
        self.assert_source_rejected("unexpected Gradle script authority")

    def test_parallel_groovy_module_build_is_rejected(self) -> None:
        _write(self.root, "event-journal/build.gradle", 'println("effect")\n')
        self.assert_source_rejected("unexpected Gradle script authority")

    def test_unreferenced_gradle_script_plugin_is_rejected(self) -> None:
        _write(self.root, "gradle/effect.gradle.kts", 'println("effect")\n')
        self.assert_source_rejected("unexpected Gradle script authority")

    def test_app_transitive_dependency_is_rejected(self) -> None:
        self.append(
            "ai-runtime/build.gradle.kts",
            '\ndependencies { implementation(project(":event-journal")) }\n',
        )
        self.assert_source_rejected("references an X-02 module")

    def test_missing_local_release_script_is_rejected(self) -> None:
        (self.root / "tools/local_release.ps1").unlink()
        self.assert_source_rejected("missing required file: tools/local_release.ps1")

    def test_github_workflow_is_not_an_x02_dependency(self) -> None:
        _write(
            self.root,
            ".github/workflows/android.yml",
            "this file is deliberately outside the local X-02 gate\n",
        )
        checker.check_repository(self.root)

    def test_missing_local_boundary_test_is_rejected(self) -> None:
        self.replace(
            "tools/local_release.ps1",
            "python tools/test_check_x02_boundaries.py\n",
            "",
        )
        self.assert_source_rejected(
            "local_release.ps1 missing X-02 coverage: "
            "test_check_x02_boundaries.py"
        )

    def test_missing_local_source_checker_is_rejected(self) -> None:
        path = self.root / "tools/local_release.ps1"
        text = path.read_text(encoding="utf-8")
        path.write_text(
            text.replace(
                "python tools/check_x02_boundaries.py\n",
                "",
            ).replace(
                "python tools/check_x02_boundaries.py --check-artifacts\n",
                "python tools/x02_artifact_gate.py --check-artifacts\n",
            ),
            encoding="utf-8",
        )
        self.assert_source_rejected(
            "local_release.ps1 missing X-02 coverage: check_x02_boundaries.py"
        )

    def test_missing_local_gradle_tasks_are_rejected(self) -> None:
        tasks = (
            ":memory-protocol:test",
            ":memory-protocol:jar",
            ":event-journal:test",
            ":event-journal:jar",
            ":app:assembleDebug",
            ":app:assembleBenchmark",
            ":app:assembleRelease",
        )
        path = self.root / "tools/local_release.ps1"
        original = path.read_text(encoding="utf-8")
        for task in tasks:
            with self.subTest(task=task):
                path.write_text(
                    original.replace(f"    {task} `\n", "").replace(
                        f"    {task}\n",
                        "",
                    ),
                    encoding="utf-8",
                )
                self.assert_source_rejected(
                    f"local_release.ps1 missing X-02 coverage: {task}"
                )
        path.write_text(original, encoding="utf-8")

    def test_missing_local_artifact_flag_is_rejected(self) -> None:
        self.replace(
            "tools/local_release.ps1",
            " --check-artifacts\n",
            "\n",
        )
        self.assert_source_rejected(
            "local_release.ps1 missing X-02 coverage: --check-artifacts"
        )

    def test_local_artifact_check_must_follow_required_builds(self) -> None:
        path = self.root / "tools/local_release.ps1"
        text = path.read_text(encoding="utf-8")
        artifact_call = (
            "python tools/check_x02_boundaries.py --check-artifacts\n"
        )
        path.write_text(
            text.replace(artifact_call, "").replace(
                "& .\\gradlew.bat `\n",
                artifact_call + "& .\\gradlew.bat `\n",
            ),
            encoding="utf-8",
        )
        self.assert_source_rejected(
            "checks X-02 artifacts before building required output"
        )

    def test_missing_offline_event_tests_is_rejected(self) -> None:
        self.replace(
            "tools/offline_verify.sh",
            "EVENT_JOURNAL_TEST_SOURCES=event-journal/src/test/kotlin\n",
            "",
        )
        self.assert_source_rejected("offline_verify.sh missing")

    def test_missing_offline_explicit_artifact_flag_is_rejected(self) -> None:
        self.replace(
            "tools/offline_verify.sh",
            '    --event-journal-jar "$EVENT_JOURNAL_JAR" \\\n',
            "",
        )
        self.assert_source_rejected("--event-journal-jar")

    def test_early_offline_artifact_call_is_rejected(self) -> None:
        path = self.root / "tools/offline_verify.sh"
        text = path.read_text(encoding="utf-8")
        marker = 'python3 "$ROOT/tools/check_x02_boundaries.py" \\\n    --check-artifacts'
        start = text.rfind(marker)
        self.assertGreaterEqual(start, 0)
        call = text[start:]
        text = text[:start]
        insertion = text.find('"$JAR_TOOL" --create')
        path.write_text(text[:insertion] + call + text[insertion:], encoding="utf-8")
        self.assert_source_rejected("before artifact finalization")

    def test_offline_commands_hidden_in_function_are_rejected(self) -> None:
        path = self.root / "tools/offline_verify.sh"
        text = path.read_text(encoding="utf-8")
        path.write_text("never_called() {\n" + text + "\n}\n", encoding="utf-8")
        self.assert_source_rejected("shell functions are forbidden")

    def test_offline_early_success_exit_is_rejected(self) -> None:
        self.replace(
            "tools/offline_verify.sh",
            "set -euo pipefail\n",
            "set -euo pipefail\nexit 0\n",
        )
        self.assert_source_rejected("early successful exit")

    def test_offline_dead_artifact_branch_is_rejected(self) -> None:
        path = self.root / "tools/offline_verify.sh"
        text = path.read_text(encoding="utf-8")
        marker = 'python3 "$ROOT/tools/check_x02_boundaries.py" \\\n'
        start = text.rfind(marker)
        self.assertGreaterEqual(start, 0)
        path.write_text(
            text[:start] + "if false; then\n" + text[start:] + "\nfi\n",
            encoding="utf-8",
        )
        self.assert_source_rejected("statically dead branch")

    def test_offline_indirect_success_exit_is_rejected_by_fingerprint(self) -> None:
        self.replace(
            "tools/offline_verify.sh",
            "set -euo pipefail\n",
            'set -euo pipefail\nZERO=0\nexit "$ZERO"\n',
        )
        self.assert_source_rejected("frozen release-gate fingerprint drift")

    def test_offline_empty_loop_is_rejected_by_fingerprint(self) -> None:
        path = self.root / "tools/offline_verify.sh"
        text = path.read_text(encoding="utf-8")
        marker = 'python3 "$ROOT/tools/check_x02_boundaries.py" \\\n'
        start = text.rfind(marker)
        self.assertGreaterEqual(start, 0)
        path.write_text(
            text[:start] + "for x in; do\n" + text[start:] + "\ndone\n",
            encoding="utf-8",
        )
        self.assert_source_rejected("frozen release-gate fingerprint drift")

    def test_offline_test_checker_failure_suppression_is_rejected(self) -> None:
        self.replace(
            "tools/offline_verify.sh",
            'python3 "$ROOT/tools/test_check_x02_boundaries.py" 2>&1 |\n',
            'python3 "$ROOT/tools/test_check_x02_boundaries.py" || true 2>&1 |\n',
        )
        self.assert_source_rejected("frozen release-gate fingerprint drift")

    def test_proto_source_is_rejected(self) -> None:
        _write(self.root, "memory-protocol/src/test/proto/x.proto", "message X {}")
        self.assert_source_rejected("forbidden source directory")

    def test_asset_is_rejected(self) -> None:
        _write(self.root, "event-journal/src/test/assets/x.bin", "x")
        self.assert_source_rejected("forbidden source directory")

    def test_manifest_is_rejected(self) -> None:
        _write(self.root, "memory-protocol/src/main/AndroidManifest.xml", "<manifest/>")
        self.assert_source_rejected()

    def test_native_source_is_rejected(self) -> None:
        _write(self.root, "event-journal/src/test/cpp/x.cpp", "int x;")
        self.assert_source_rejected("forbidden source directory")

    def test_good_default_artifacts_pass(self) -> None:
        _good_artifacts(self.root)
        checker.check_repository(self.root, check_built_artifacts=True)

    def test_fake_test_only_class_in_main_jar_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        with zipfile.ZipFile(memory_jar, "a") as archive:
            archive.writestr(
                "io/github/ethanbird/senseime/memory/protocol/"
                "X02TestOnlyBypass.class",
                b"",
            )
        self.assert_artifact_rejected("forbidden test/fake class")

    def test_fake_authenticator_class_in_main_jar_is_rejected(self) -> None:
        _, event_jar, _, _ = _good_artifacts(self.root)
        with zipfile.ZipFile(event_jar, "a") as archive:
            archive.writestr(
                "io/github/ethanbird/senseime/memory/journal/core/"
                "LocalAuthenticator.class",
                b"",
            )
        self.assert_artifact_rejected("forbidden test/fake class")

    def test_hidden_extra_class_in_main_jar_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        with zipfile.ZipFile(memory_jar, "a") as archive:
            archive.writestr(
                "io/github/ethanbird/senseime/memory/protocol/"
                "StorageBackdoor.class",
                b"",
            )
        self.assert_artifact_rejected("non-whitelisted class")

    def test_kotlin_synthetic_inner_class_is_allowed(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        with zipfile.ZipFile(memory_jar, "a") as archive:
            archive.writestr(
                "io/github/ethanbird/senseime/memory/protocol/"
                "X02StageDecisionV1$Companion.class",
                b"\xca\xfe\xba\xbe-fixture",
            )
            archive.writestr(
                "io/github/ethanbird/senseime/memory/protocol/"
                "FeatureStageOrderV1$WhenMappings.class",
                b"\xca\xfe\xba\xbe-fixture",
            )
        checker.check_repository(self.root, check_built_artifacts=True)

    def test_arbitrary_inner_class_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        with zipfile.ZipFile(memory_jar, "a") as archive:
            archive.writestr(
                "io/github/ethanbird/senseime/memory/protocol/"
                "X02StageDecisionV1$Evil.class",
                b"",
            )
        self.assert_artifact_rejected("non-whitelisted class")

    def test_extra_kotlin_module_metadata_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        with zipfile.ZipFile(memory_jar, "a") as archive:
            archive.writestr("META-INF/other.kotlin_module", b"fixture")
        self.assert_artifact_rejected("exactly one Kotlin module")

    def test_arbitrary_empty_jar_directory_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        with zipfile.ZipFile(memory_jar, "a") as archive:
            archive.writestr("hidden/", b"")
        self.assert_artifact_rejected("non-whitelisted or non-empty jar directory")

    def test_nonempty_expected_jar_directory_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        with zipfile.ZipFile(memory_jar, "a") as archive:
            archive.writestr("io/", b"payload")
        self.assert_artifact_rejected("non-whitelisted or non-empty jar directory")

    def test_extra_jar_resource_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        with zipfile.ZipFile(memory_jar, "a") as archive:
            archive.writestr("memory/config.bin", b"x")
        self.assert_artifact_rejected("non-whitelisted jar resource")

    def test_missing_required_jar_class_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        missing = next(iter(checker.MEMORY_REQUIRED_CLASSES))
        _rewrite_zip(memory_jar, lambda entries: entries.pop(missing))
        self.assert_artifact_rejected("missing required classes")

    def test_invalid_required_class_magic_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        target = next(iter(checker.MEMORY_REQUIRED_CLASSES))
        _rewrite_zip(
            memory_jar,
            lambda entries: entries.__setitem__(target, b"not-a-class"),
        )
        self.assert_artifact_rejected("invalid JVM class magic")

    def test_oversized_required_class_is_rejected(self) -> None:
        memory_jar, _, _, _ = _good_artifacts(self.root)
        target = next(iter(checker.MEMORY_REQUIRED_CLASSES))
        _rewrite_zip(
            memory_jar,
            lambda entries: entries.__setitem__(
                target,
                b"\xca\xfe\xba\xbe"
                + b"x" * checker.MAX_JAR_CLASS_BYTES,
            ),
        )
        self.assert_artifact_rejected("oversized class")

    def test_fake_memory_path_in_apk_dex_is_rejected(self) -> None:
        _, _, _, benchmark = _good_artifacts(self.root)
        _write_zip(
            benchmark,
            {
                "classes.dex": (
                    b"dex\n035\x00Lio/github/ethanbird/senseime/memory/"
                    b"protocol/X02SafeStageViewV1;"
                )
            },
        )
        self.assert_artifact_rejected("contains X-02 Memory path")

    def test_every_app_apk_is_scanned(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _write_zip(
            debug,
            {
                "classes.dex": b"dex\n035\x00safe-debug",
                "classes2.dex":
                    b"dex\n035\x00io/github/ethanbird/senseime/memory/hidden",
            },
        )
        self.assert_artifact_rejected("contains X-02 Memory path")

    def test_invalid_dex_magic_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _write_zip(debug, {"classes.dex": b"not-a-dex"})
        self.assert_artifact_rejected("invalid DEX magic")

    def test_nonstandard_dex_payload_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "assets/payload.dex",
                b"dex\n035\x00io/github/ethanbird/senseime/memory/hidden",
            ),
        )
        self.assert_artifact_rejected("nonstandard DEX payload")

    def test_subdirectory_classes_dex_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "assets/classes2.dex",
                b"dex\n035\x00safe",
            ),
        )
        self.assert_artifact_rejected("nonstandard DEX payload")

    def test_classes_one_dex_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "classes1.dex",
                b"dex\n035\x00safe",
            ),
        )
        self.assert_artifact_rejected("nonstandard DEX payload")

    def test_multidex_gap_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "classes3.dex",
                b"dex\n035\x00safe",
            ),
        )
        self.assert_artifact_rejected("non-canonical multidex sequence")

    def test_unbounded_multidex_suffix_is_rejected_without_allocation(
        self,
    ) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "classes1000000000.dex",
                b"dex\n035\x00safe",
            ),
        )
        self.assert_artifact_rejected("nonstandard DEX payload")

    def test_nested_jar_payload_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "assets/payload.jar",
                b"PK-fixture",
            ),
        )
        self.assert_artifact_rejected("nested JVM payload")

    def test_disguised_nested_zip_payload_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(
                "classes.dex",
                b"dex\n035\x00io/github/ethanbird/senseime/memory/hidden",
            )
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "assets/payload.bin",
                nested.getvalue(),
            ),
        )
        self.assert_artifact_rejected("nested archive payload")

    def test_prefixed_nested_zip_payload_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(
                "classes.dex",
                b"dex\n035\x00io/github/ethanbird/senseime/memory/hidden",
            )
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "assets/payload.bin",
                b"prefix" + nested.getvalue(),
            ),
        )
        self.assert_artifact_rejected("nested archive payload")

    def test_disguised_dex_payload_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "assets/payload.bin",
                b"dex\n035\x00safe",
            ),
        )
        self.assert_artifact_rejected("disguised DEX payload")

    def test_disguised_class_payload_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "assets/payload.bin",
                b"\xca\xfe\xba\xbe-fixture",
            ),
        )
        self.assert_artifact_rejected("disguised JVM class payload")

    def test_plain_non_dex_memory_payload_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "assets/payload.bin",
                b"io/github/ethanbird/senseime/memory/hidden",
            ),
        )
        self.assert_artifact_rejected("contains X-02 Memory path")

    def test_apk_entry_name_memory_path_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "assets/io/github/ethanbird/senseime/memory/payload.bin",
                b"encoded",
            ),
        )
        self.assert_artifact_rejected(
            "APK entry name contains X-02 Memory path"
        )

    def test_nonempty_apk_directory_entry_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        _rewrite_zip(
            debug,
            lambda entries: entries.__setitem__(
                "hidden/",
                b"io/github/ethanbird/senseime/memory/hidden",
            ),
        )
        self.assert_artifact_rejected("non-empty APK directory entry")

    def test_duplicate_apk_entry_is_rejected(self) -> None:
        _, _, debug, _ = _good_artifacts(self.root)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(debug, "a") as archive:
                archive.writestr("classes.dex", b"dex-safe-last")
        self.assert_artifact_rejected("duplicate APK entries")

    def test_default_artifacts_require_benchmark_apk(self) -> None:
        _, _, _, benchmark = _good_artifacts(self.root)
        benchmark.unlink()
        self.assert_artifact_rejected("requires a benchmark")

    def test_good_explicit_artifact_group_passes(self) -> None:
        memory_jar, event_jar, debug, _ = _good_artifacts(self.root)
        checker.check_repository(
            self.root,
            check_built_artifacts=True,
            memory_jar=memory_jar,
            event_journal_jar=event_jar,
            app_apks=[debug],
        )

    def test_incomplete_explicit_artifact_group_is_rejected(self) -> None:
        memory_jar, _, debug, _ = _good_artifacts(self.root)
        with self.assertRaises(checker.BoundaryError) as caught:
            checker.check_repository(
                self.root,
                check_built_artifacts=True,
                memory_jar=memory_jar,
                app_apks=[debug],
            )
        self.assertIn("requires --memory-jar", str(caught.exception))

    def test_explicit_paths_without_artifact_mode_are_rejected(self) -> None:
        memory_jar, event_jar, debug, _ = _good_artifacts(self.root)
        with self.assertRaises(checker.BoundaryError) as caught:
            checker.check_repository(
                self.root,
                memory_jar=memory_jar,
                event_journal_jar=event_jar,
                app_apks=[debug],
            )
        self.assertIn("require --check-artifacts", str(caught.exception))

    def test_wrong_explicit_jar_path_is_rejected_without_fallback(self) -> None:
        _, event_jar, debug, _ = _good_artifacts(self.root)
        with self.assertRaises(checker.BoundaryError):
            checker.check_repository(
                self.root,
                check_built_artifacts=True,
                memory_jar=self.root / "missing-memory.jar",
                event_journal_jar=event_jar,
                app_apks=[debug],
            )

    def test_wrong_explicit_apk_path_is_rejected(self) -> None:
        memory_jar, event_jar, _, _ = _good_artifacts(self.root)
        with self.assertRaises(checker.BoundaryError) as caught:
            checker.check_repository(
                self.root,
                check_built_artifacts=True,
                memory_jar=memory_jar,
                event_journal_jar=event_jar,
                app_apks=[self.root / "missing.apk"],
            )
        self.assertIn("does not exist", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
