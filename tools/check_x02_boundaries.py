#!/usr/bin/env python3
"""Mechanical, fail-closed boundaries for the X-02 Memory substrate.

X-02 is intentionally smaller than the future Memory system.  It contains
closed enums, pure fail-closed reducers, process-local immutable views, and a
non-persistent event-journal scaffold.  This checker prevents that substrate
from quietly acquiring a persistent wire, Android integration, storage,
cryptography, networking, or a dependency path into a product APK.

Only the Python standard library is used so the source checks can run before
Gradle, Android, or any third-party package is available.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import re
import sys
import tomllib
import zipfile
from collections import Counter
from pathlib import Path
from typing import Iterable, Mapping, Sequence

try:
    from check_gate0_contract import EXPECTED_GATES, EXPECTED_SCOPE_GATES
except ModuleNotFoundError:  # Imported as tools.check_x02_boundaries.
    from tools.check_gate0_contract import EXPECTED_GATES, EXPECTED_SCOPE_GATES


MEMORY_PROTOCOL = "memory-protocol"
EVENT_JOURNAL = "event-journal"

GATE_REGISTRY_PATH = Path(
    "memory-protocol/src/main/kotlin/"
    "io/github/ethanbird/senseime/memory/protocol/GateRegistryV1.kt"
)
STAGE_DOMAINS_PATH = Path(
    "memory-protocol/src/main/kotlin/"
    "io/github/ethanbird/senseime/memory/protocol/StageDomainsV1.kt"
)
FEATURE_POLICY_PATH = Path(
    "memory-protocol/src/main/kotlin/"
    "io/github/ethanbird/senseime/memory/protocol/X02FeatureStagePolicyV1.kt"
)
FAIL_CLOSED_VIEW_PATH = Path(
    "memory-protocol/src/main/kotlin/"
    "io/github/ethanbird/senseime/memory/protocol/X02FailClosedStageViewV1.kt"
)
EVENT_SCAFFOLD_PATH = Path(
    "event-journal/src/main/kotlin/"
    "io/github/ethanbird/senseime/memory/journal/core/"
    "X02EventJournalScaffoldV1.kt"
)

EXPECTED_MAIN_FILES: Mapping[str, frozenset[Path]] = {
    MEMORY_PROTOCOL: frozenset(
        {
            GATE_REGISTRY_PATH,
            STAGE_DOMAINS_PATH,
            FEATURE_POLICY_PATH,
            FAIL_CLOSED_VIEW_PATH,
        }
    ),
    EVENT_JOURNAL: frozenset({EVENT_SCAFFOLD_PATH}),
}

EXPECTED_MAIN_SHA256: Mapping[Path, str] = {
    GATE_REGISTRY_PATH:
        "d7c7fccdc735f498b3461b0ef63bca42ec6c9a6e319086b558d31192503e8e42",
    STAGE_DOMAINS_PATH:
        "615fb700149b89010c7f66921d63e8cc7e2fce5d9515ffddb1d9d779905993fe",
    FEATURE_POLICY_PATH:
        "5241874aea4b10d8d0238772419dd7108dae9e37ae135e4eb196214f02c5e829",
    FAIL_CLOSED_VIEW_PATH:
        "36c557b36627ff1884505cd1138f60b15a2e67df0d34d966d978d60c3e220c09",
    EVENT_SCAFFOLD_PATH:
        "bcc17555a487b2a1fa857c04b4d3509f600731362bc8407346491de1c340f57f",
}

EXPECTED_IMPORTS: Mapping[Path, frozenset[str]] = {
    GATE_REGISTRY_PATH: frozenset(),
    STAGE_DOMAINS_PATH: frozenset(),
    FEATURE_POLICY_PATH: frozenset(),
    FAIL_CLOSED_VIEW_PATH: frozenset(
        {"java.util.concurrent.atomic.AtomicReference"}
    ),
    EVENT_SCAFFOLD_PATH: frozenset(
        {
            "io.github.ethanbird.senseime.memory.protocol.FeatureStageV1",
        }
    ),
}

EXPECTED_ENUMS: Mapping[tuple[Path, str], tuple[str, ...]] = {
    (GATE_REGISTRY_PATH, "GateScopeKindV1"): tuple(EXPECTED_SCOPE_GATES),
    (STAGE_DOMAINS_PATH, "FeatureStageV1"): (
        "OFF",
        "SCHEMA_ONLY",
        "DARK",
        "SHADOW",
        "CANARY",
        "DEFAULT",
    ),
    (STAGE_DOMAINS_PATH, "GateVerdictV1"): (
        "PASS",
        "BLOCKED",
        "FAIL",
        "INVALID",
        "INCONCLUSIVE",
        "UNSUPPORTED",
        "MEASURED_NO_BUDGET",
    ),
    (STAGE_DOMAINS_PATH, "PermitDecisionV1"): (
        "ALLOW",
        "NOT_RUN_BLOCKED",
    ),
    (STAGE_DOMAINS_PATH, "ProfileExecutionClassV1"): (
        "SCHEMA_ONLY",
        "PRODUCT_SYNTHETIC",
        "REAL_DATA",
    ),
    (STAGE_DOMAINS_PATH, "NormalProfileCapabilityIdV1"): (
        "SCHEMA_CODEC",
        "CAPTURE",
        "WARM_RECALL",
        "COLD_RECALL",
        "HOT_SNAPSHOT",
        "INDEX_REBUILD",
        "MAINTENANCE",
        "KEY_ROTATION",
        "EXPORT_EGRESS",
    ),
    (STAGE_DOMAINS_PATH, "X02StageDecisionDispositionV1"): (
        "VALID_OFF_REQUEST",
        "VALID_SCHEMA_REQUEST",
        "BLOCKED_FAIL_CLOSED",
    ),
    (FEATURE_POLICY_PATH, "X02StageDecisionReasonV1"): (
        "REQUESTED_OFF",
        "SCHEMA_CODEC_ONLY",
        "X02_HARD_MAXIMUM",
        "EXECUTION_CLASS_NOT_SCHEMA_ONLY",
        "PERSISTENT_CAPABILITY_BLOCKED",
        "GATE_SET_MUST_BE_EMPTY",
        "DUPLICATE_GATE_OBSERVATION",
        "CONFIGURED_CEILING_CONTRACTED",
    ),
    (FAIL_CLOSED_VIEW_PATH, "X02RejectedStageInputV1"): (
        "ABSENT",
        "CORRUPT",
        "UNKNOWN",
        "UNAUTHENTICATED",
    ),
    (FAIL_CLOSED_VIEW_PATH, "X02SafeStageCauseV1"): (
        "PROCESS_START",
        "SOURCE_ABSENT",
        "SOURCE_CORRUPT",
        "SOURCE_UNKNOWN",
        "SOURCE_UNAUTHENTICATED",
    ),
    (FAIL_CLOSED_VIEW_PATH, "X02StageConsumerRoleV1"): (
        "MAIN",
        "IME",
        "BRAIN",
    ),
    (EVENT_SCAFFOLD_PATH, "X02EventJournalAvailabilityV1"): (
        "SCHEMA_ONLY_NO_STORAGE",
    ),
}

WIRE_VALUE_ENUMS = frozenset(
    {
        "FeatureStageV1",
        "GateVerdictV1",
        "PermitDecisionV1",
        "ProfileExecutionClassV1",
        "NormalProfileCapabilityIdV1",
    }
)

FORBIDDEN_RUNTIME_ROOTS = (
    "app",
    "ime-service",
    "ime-ui",
    "brain-api",
    "ai-brain",
    "ai-runtime",
)

FORBIDDEN_SOURCE_COMPONENTS = frozenset(
    {
        "proto",
        "res",
        "assets",
        "resources",
        "manifest",
        "cpp",
        "jni",
        "jnilibs",
        "native",
    }
)
FORBIDDEN_SOURCE_SUFFIXES = frozenset(
    {".proto", ".aidl", ".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".so", ".a"}
)

ALLOWED_SPECIAL_IDENTIFIERS = frozenset(EXPECTED_GATES) | frozenset(
    {
        "SCHEMA_CODEC",
        "SCHEMA_CODEC_ONLY",
        "HOT_SNAPSHOT",
    }
)

FORBIDDEN_EXACT_IDENTIFIERS = frozenset(
    {
        "ByteArray",
        "Class",
        "ClassLoader",
        "File",
        "Files",
        "Path",
        "Paths",
        "Process",
        "ProcessBuilder",
        "Runtime",
        "System",
        "InputStream",
        "OutputStream",
        "Reader",
        "Writer",
        "RandomAccessFile",
        "FileChannel",
        "IOException",
        "Socket",
        "ServerSocket",
        "URL",
        "URI",
        "HttpClient",
        "HttpURLConnection",
        "Network",
        "Cipher",
        "Mac",
        "MessageDigest",
        "Signature",
        "KeyStore",
        "KeyGenerator",
        "SecretKey",
        "SecureRandom",
        "SQLiteDatabase",
        "Database",
        "Room",
        "WorkManager",
        "Worker",
        "DataStore",
        "ProtoDataStore",
        "append",
        "decode",
        "deserialize",
        "encode",
        "load",
        "persist",
        "read",
        "recall",
        "save",
        "serialize",
        "store",
        "write",
    }
)

FORBIDDEN_QUALIFIED_PATTERNS = (
    re.compile(r"\bandroid(?:x)?\."),
    re.compile(r"\bjava\.io\."),
    re.compile(r"\bjava\.net\."),
    re.compile(r"\bjava\.nio\."),
    re.compile(r"\bjava\.nio\.file\."),
    re.compile(r"\bjava\.security\."),
    re.compile(r"\bjava\.sql\."),
    re.compile(r"\bjava\.util\.prefs\."),
    re.compile(r"\bjava\.lang\.reflect\."),
    re.compile(r"\bjavax\.crypto\."),
    re.compile(r"\bjavax\.net\."),
    re.compile(r"\bjavax\.sql\."),
    re.compile(r"\bkotlin\.io\."),
    re.compile(r"\bokhttp(?:3)?\."),
    re.compile(r"\bretrofit2?\."),
)

MEMORY_REQUIRED_CLASSES = frozenset(
    {
        "io/github/ethanbird/senseime/memory/protocol/GateScopeKindV1.class",
        "io/github/ethanbird/senseime/memory/protocol/GateIdV1.class",
        "io/github/ethanbird/senseime/memory/protocol/GateRegistryV1.class",
        "io/github/ethanbird/senseime/memory/protocol/FeatureStageV1.class",
        "io/github/ethanbird/senseime/memory/protocol/FeatureStageOrderV1.class",
        "io/github/ethanbird/senseime/memory/protocol/GateVerdictV1.class",
        "io/github/ethanbird/senseime/memory/protocol/PermitDecisionV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "ProfileExecutionClassV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "NormalProfileCapabilityIdV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02StageDecisionDispositionV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02GateObservationV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02NormalStageRequestV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02StageDecisionReasonV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02StageDecisionV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02FeatureStagePolicyV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02RejectedStageInputV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02SafeStageCauseV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02SafeStageViewV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02FailClosedStageReducerV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02StageConsumerRoleV1.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02ProcessStageHolderV1.class",
    }
)

EVENT_REQUIRED_CLASSES = frozenset(
    {
        "io/github/ethanbird/senseime/memory/journal/core/"
        "X02EventJournalAvailabilityV1.class",
        "io/github/ethanbird/senseime/memory/journal/core/"
        "X02EventJournalScaffoldV1.class",
    }
)

MEMORY_ALLOWED_SYNTHETIC_CLASSES = frozenset(
    {
        "io/github/ethanbird/senseime/memory/protocol/"
        "FeatureStageOrderV1$WhenMappings.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "FeatureStageV1$Companion.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "GateIdV1$Companion.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "GateVerdictV1$Companion.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "NormalProfileCapabilityIdV1$Companion.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "PermitDecisionV1$Companion.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "ProfileExecutionClassV1$Companion.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02ProcessStageHolderV1$Companion.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02SafeStageViewV1$Companion.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02SafeStageViewV1$Companion$WhenMappings.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02StageDecisionV1$Companion.class",
        "io/github/ethanbird/senseime/memory/protocol/"
        "X02StageDecisionV1$WhenMappings.class",
    }
)
EVENT_ALLOWED_SYNTHETIC_CLASSES = frozenset()

EXPECTED_PROTECTED_BUILD_SCRIPTS: Mapping[str, str] = {
    MEMORY_PROTOCOL: """plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
""",
    EVENT_JOURNAL: """plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":memory-protocol"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
""",
}

EXPECTED_ROOT_BUILD_SCRIPT = """plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
"""

EXPECTED_OFFLINE_VERIFY_SHA256 = (
    "57ea146a06d91931f58d376ec305e8f4c288e198b5a4616806093148dd7668b7"
)
EXPECTED_VERIFY_JOB_SHA256 = (
    "b7c4fc7f19d236e729b495be22fad42791bd60934e202be814484dd9d9c6f9e9"
)
EXPECTED_PACKAGE_JOB_SHA256 = (
    "e2bddec5ce94f9d9a0fc8a001eb0de7693c07cab246b4a97b4f904e1ca3a4ebd"
)
EXPECTED_ANDROID_WORKFLOW_SHA256 = (
    "0009564b3cd9e9fe5df13f30d299799f7b27468485dd5d3a3520f2dbb6dd3943"
)
EXPECTED_BUILD_AUTHORITY_SHA256: Mapping[Path, str] = {
    Path("settings.gradle.kts"):
        "1bd8cae694d3b353d47cd37d146fabbb0a89121cddf854250477c91a317044a4",
    Path("gradle/libs.versions.toml"):
        "0819b9d7d066ea8e233c5d9d12ce7a77b6b26b553ee7ddc155554919c6c802ac",
    Path("gradle.properties"):
        "b2dd33a40d0083d65d3feda9aa8662013c3cb55e0b0abe2c29e5e0ebbbc19238",
    Path("gradlew"):
        "734b3879d3501dce471cf0522d3bcbafe76873d9fc5129345b67fb43bd15e933",
    Path("gradle/wrapper/gradle-wrapper.properties"):
        "3b6b8df392b92f86332d907707f3d952a32c8d6e1fedabafe2b5ec3d933a78c4",
    Path("gradle/wrapper/gradle-wrapper.jar"):
        "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f",
    Path("build.gradle.kts"):
        "16beebbe1449f27de48f7d86d6802f2c66ddeeb1081bae857e21c9f031102c9b",
    Path("ai-brain/build.gradle.kts"):
        "6fe66f16e24069d71a5cb15251032311bf018e8ae744dad9ef1e790cc00b1566",
    Path("ai-protocol/build.gradle.kts"):
        "b1d9c1acc7f6d19450691e461fa4da6643297fff752d3f9159e7445bc91ad9ab",
    Path("ai-runtime/build.gradle.kts"):
        "9f1997745054d4fb16f5fcc579dbd347c68b24c7faa5b6f071f8fc452a99df65",
    Path("app/build.gradle.kts"):
        "261866aefda5b2b6b1fdc0a7a76a82f2b55585c44492808dc3a40961b45f1411",
    Path("benchmark/build.gradle.kts"):
        "999d9aba8d2813df7e108a9dece00c7f6406b3c5273e59f323d0565e650abf8b",
    Path("brain-api/build.gradle.kts"):
        "c70c74e1a71c3f64a329722b9fa2337ad582df9dbef98c53fa4e781d8f29ee5a",
    Path("core-input/build.gradle.kts"):
        "bba1bc12517ff0e4dd269c56c5d7ac4d5f4530a401d40c77cbf6c7962639bc69",
    Path("event-journal/build.gradle.kts"):
        "696b4999da20f0b2def5b6adf5f341f702c46a14d31ee80a0f790561f6bd4930",
    Path("ime-service/build.gradle.kts"):
        "a105ba4efa040d6026437991c733797bf024d4859b73b4498d1e52b812142dd7",
    Path("ime-ui/build.gradle.kts"):
        "cb8aaeadaa3450eed3441e5cec29f02c8f727662697477a51c0970aea4acee96",
    Path("memory-protocol/build.gradle.kts"):
        "b1d9c1acc7f6d19450691e461fa4da6643297fff752d3f9159e7445bc91ad9ab",
}
EXPECTED_GRADLE_SCRIPT_PATHS = frozenset(
    relative
    for relative in EXPECTED_BUILD_AUTHORITY_SHA256
    if relative.name.endswith((".gradle", ".gradle.kts"))
)

FORBIDDEN_ARTIFACT_NAME_PARTS = ("TestOnly", "Test", "Fake", "Authenticator")
DEX_MEMORY_PATHS = (
    b"io/github/ethanbird/senseime/memory/",
    b"io.github.ethanbird.senseime.memory.",
)
NESTED_ZIP_MAGICS = (
    b"PK\x03\x04",
    b"PK\x05\x06",
    b"PK\x07\x08",
)
MAX_JAR_CLASS_BYTES = 1 * 1024 * 1024
MAX_JAR_METADATA_BYTES = 1 * 1024 * 1024
MAX_DEX_BYTES = 128 * 1024 * 1024
MAX_TOTAL_DEX_BYTES = 256 * 1024 * 1024
MAX_APK_ENTRY_BYTES = 256 * 1024 * 1024
MAX_APK_SCAN_BYTES = 512 * 1024 * 1024

_IDENTIFIER_RE = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")
_IMPORT_RE = re.compile(r"^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$")
_PROJECT_REFERENCE_RE = re.compile(
    r"""project\s*\(\s*(?:path\s*=\s*)?["']:([^"']+)["']\s*\)"""
)
_PROJECT_CALL_RE = re.compile(r"\bproject\s*\(")
_SETTINGS_MODULE_RE = re.compile(r"""["']:([^"']+)["']""")


class BoundaryError(ValueError):
    """Raised when the X-02 substrate exceeds its frozen boundary."""


def _read_text(root: Path, relative: Path) -> str:
    path = root / relative
    if not path.is_file():
        raise BoundaryError(f"missing required file: {relative.as_posix()}")
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeError as exc:
        raise BoundaryError(f"{relative.as_posix()}: not valid UTF-8") from exc


def _sanitize_kotlin(text: str, *, strip_strings: bool) -> str:
    """Replace comments and optionally strings with spaces, preserving offsets."""

    result = list(text)
    length = len(text)
    index = 0
    block_depth = 0
    state = "normal"

    def erase(position: int) -> None:
        if result[position] not in "\r\n":
            result[position] = " "

    while index < length:
        if state == "line_comment":
            erase(index)
            if text[index] in "\r\n":
                state = "normal"
            index += 1
            continue

        if state == "block_comment":
            if text.startswith("/*", index):
                erase(index)
                if index + 1 < length:
                    erase(index + 1)
                block_depth += 1
                index += 2
                continue
            if text.startswith("*/", index):
                erase(index)
                if index + 1 < length:
                    erase(index + 1)
                block_depth -= 1
                index += 2
                if block_depth == 0:
                    state = "normal"
                continue
            erase(index)
            index += 1
            continue

        if state == "string":
            if text[index] == "\\":
                if strip_strings:
                    erase(index)
                    if index + 1 < length:
                        erase(index + 1)
                index += 2
                continue
            if strip_strings:
                erase(index)
            if text[index] == '"':
                state = "normal"
            index += 1
            continue

        if state == "triple_string":
            if text.startswith('"""', index):
                if strip_strings:
                    erase(index)
                    erase(index + 1)
                    erase(index + 2)
                index += 3
                state = "normal"
                continue
            if strip_strings:
                erase(index)
            index += 1
            continue

        if state == "char":
            if text[index] == "\\":
                if strip_strings:
                    erase(index)
                    if index + 1 < length:
                        erase(index + 1)
                index += 2
                continue
            if strip_strings:
                erase(index)
            if text[index] == "'":
                state = "normal"
            index += 1
            continue

        if text.startswith("//", index):
            erase(index)
            erase(index + 1)
            index += 2
            state = "line_comment"
            continue
        if text.startswith("/*", index):
            erase(index)
            erase(index + 1)
            index += 2
            block_depth = 1
            state = "block_comment"
            continue
        if text.startswith('"""', index):
            if strip_strings:
                erase(index)
                erase(index + 1)
                erase(index + 2)
            index += 3
            state = "triple_string"
            continue
        if text[index] == '"':
            if strip_strings:
                erase(index)
            index += 1
            state = "string"
            continue
        if text[index] == "'":
            if strip_strings:
                erase(index)
            index += 1
            state = "char"
            continue
        index += 1

    if state in {"block_comment", "string", "triple_string", "char"}:
        raise BoundaryError(f"unterminated Kotlin {state.replace('_', ' ')}")
    return "".join(result)


def _find_matching_brace(structural: str, opening: int) -> int:
    depth = 0
    for index in range(opening, len(structural)):
        character = structural[index]
        if character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                return index
    raise BoundaryError("unterminated Kotlin enum body")


def _split_top_level_commas(original: str, structural: str) -> list[str]:
    segments: list[str] = []
    start = 0
    paren = bracket = brace = 0
    for index, character in enumerate(structural):
        if character == "(":
            paren += 1
        elif character == ")":
            paren -= 1
        elif character == "[":
            bracket += 1
        elif character == "]":
            bracket -= 1
        elif character == "{":
            brace += 1
        elif character == "}":
            brace -= 1
        elif character == "," and paren == bracket == brace == 0:
            segment = original[start:index].strip()
            if segment:
                segments.append(segment)
            start = index + 1
    tail = original[start:].strip()
    if tail:
        segments.append(tail)
    return segments


def _parse_enum_entries(text: str, enum_name: str) -> list[tuple[str, str]]:
    without_comments = _sanitize_kotlin(text, strip_strings=False)
    structural = _sanitize_kotlin(text, strip_strings=True)
    header = re.search(rf"\benum\s+class\s+{re.escape(enum_name)}\b", structural)
    if header is None:
        raise BoundaryError(f"missing enum class {enum_name}")
    opening = structural.find("{", header.end())
    if opening < 0:
        raise BoundaryError(f"{enum_name}: missing enum body")
    closing = _find_matching_brace(structural, opening)

    body_original = without_comments[opening + 1 : closing]
    body_structural = structural[opening + 1 : closing]
    paren = bracket = brace = 0
    entries_end = len(body_structural)
    for index, character in enumerate(body_structural):
        if character == "(":
            paren += 1
        elif character == ")":
            paren -= 1
        elif character == "[":
            bracket += 1
        elif character == "]":
            bracket -= 1
        elif character == "{":
            brace += 1
        elif character == "}":
            brace -= 1
        elif character == ";" and paren == bracket == brace == 0:
            entries_end = index
            break

    segments = _split_top_level_commas(
        body_original[:entries_end],
        body_structural[:entries_end],
    )
    entries: list[tuple[str, str]] = []
    for segment in segments:
        match = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\b", segment)
        if match is None:
            raise BoundaryError(f"{enum_name}: malformed enum entry: {segment!r}")
        entries.append((match.group(1), segment))
    if not entries:
        raise BoundaryError(f"{enum_name}: empty enum")
    return entries


def _check_gate_registry(root: Path) -> None:
    text = _read_text(root, GATE_REGISTRY_PATH)
    scope_entries = _parse_enum_entries(text, "GateScopeKindV1")
    actual_scopes = tuple(name for name, _ in scope_entries)
    if actual_scopes != tuple(EXPECTED_SCOPE_GATES):
        raise BoundaryError(
            "GateScopeKindV1 drift: "
            f"expected {tuple(EXPECTED_SCOPE_GATES)!r}, got {actual_scopes!r}"
        )
    for name, segment in scope_entries:
        if not re.fullmatch(rf"{re.escape(name)}", segment.strip()):
            raise BoundaryError(f"GateScopeKindV1.{name}: unexpected constructor")

    gate_entries = _parse_enum_entries(text, "GateIdV1")
    actual_gates = tuple(name for name, _ in gate_entries)
    if actual_gates != tuple(EXPECTED_GATES):
        raise BoundaryError(
            "GateIdV1 order/set drift: "
            f"expected {tuple(EXPECTED_GATES)!r}, got {actual_gates!r}"
        )

    actual_by_scope: dict[str, set[str]] = {
        scope: set() for scope in EXPECTED_SCOPE_GATES
    }
    for gate_name, segment in gate_entries:
        match = re.fullmatch(
            rf"{re.escape(gate_name)}\s*\(\s*"
            r"GateScopeKindV1\.([A-Za-z_][A-Za-z0-9_]*)\s*\)",
            segment.strip(),
        )
        if match is None:
            raise BoundaryError(f"GateIdV1.{gate_name}: malformed exact scope")
        scope = match.group(1)
        if scope not in actual_by_scope:
            raise BoundaryError(f"GateIdV1.{gate_name}: unknown scope {scope}")
        actual_by_scope[scope].add(gate_name)

    for scope, expected_gates in EXPECTED_SCOPE_GATES.items():
        if actual_by_scope[scope] != set(expected_gates):
            raise BoundaryError(
                f"GateIdV1 scope drift for {scope}: "
                f"expected {sorted(expected_gates)!r}, "
                f"got {sorted(actual_by_scope[scope])!r}"
            )


def _check_exact_enums(root: Path) -> None:
    cache: dict[Path, str] = {}
    for (relative, enum_name), expected_entries in EXPECTED_ENUMS.items():
        text = cache.setdefault(relative, _read_text(root, relative))
        parsed = _parse_enum_entries(text, enum_name)
        actual_entries = tuple(name for name, _ in parsed)
        if actual_entries != expected_entries:
            raise BoundaryError(
                f"{enum_name} drift: expected {expected_entries!r}, "
                f"got {actual_entries!r}"
            )
        for name, segment in parsed:
            stripped = segment.strip()
            if enum_name in WIRE_VALUE_ENUMS:
                expected_shape = rf'{re.escape(name)}\s*\(\s*"{re.escape(name)}"\s*\)'
            else:
                expected_shape = re.escape(name)
            if re.fullmatch(expected_shape, stripped) is None:
                raise BoundaryError(
                    f"{enum_name}.{name}: unexpected enum constructor/wire value"
                )


def _check_main_file_allowlist(root: Path) -> None:
    for module, expected in EXPECTED_MAIN_FILES.items():
        main_root = root / module / "src/main"
        if not main_root.is_dir():
            raise BoundaryError(f"{module}: missing src/main")
        actual: set[Path] = set()
        for path in main_root.rglob("*"):
            if path.is_symlink():
                raise BoundaryError(
                    f"{path.relative_to(root).as_posix()}: symlink is forbidden"
                )
            if path.is_file():
                actual.add(path.relative_to(root))
        if actual != set(expected):
            missing = sorted(path.as_posix() for path in set(expected) - actual)
            extra = sorted(path.as_posix() for path in actual - set(expected))
            raise BoundaryError(
                f"{module}: main file allowlist drift; "
                f"missing={missing!r}, extra={extra!r}"
            )


def _check_forbidden_source_layouts(root: Path) -> None:
    for module in (MEMORY_PROTOCOL, EVENT_JOURNAL):
        source_root = root / module / "src"
        if not source_root.is_dir():
            raise BoundaryError(f"{module}: missing src")
        for path in source_root.rglob("*"):
            relative = path.relative_to(root)
            lowered_parts = {part.lower() for part in relative.parts}
            if lowered_parts & FORBIDDEN_SOURCE_COMPONENTS:
                raise BoundaryError(
                    f"{relative.as_posix()}: forbidden source directory"
                )
            if path.name.lower() == "androidmanifest.xml":
                raise BoundaryError(f"{relative.as_posix()}: manifest is forbidden")
            if path.is_file() and path.suffix.lower() in FORBIDDEN_SOURCE_SUFFIXES:
                raise BoundaryError(
                    f"{relative.as_posix()}: proto/native source is forbidden"
                )


def _check_production_sources(root: Path) -> None:
    for expected_files in EXPECTED_MAIN_FILES.values():
        for relative in expected_files:
            text = _read_text(root, relative)
            without_comments = _sanitize_kotlin(text, strip_strings=False)
            code = _sanitize_kotlin(text, strip_strings=True)

            if "`" in code:
                raise BoundaryError(
                    f"{relative.as_posix()}: backtick production identifier "
                    "is forbidden"
                )

            import_lines = [
                line
                for line in without_comments.splitlines()
                if re.match(r"^\s*import\b", line)
            ]
            parsed_imports: list[str] = []
            for line in import_lines:
                match = _IMPORT_RE.fullmatch(line)
                if match is None:
                    raise BoundaryError(
                        f"{relative.as_posix()}: aliased, wildcard, or malformed "
                        f"production import is forbidden: {line.strip()!r}"
                    )
                parsed_imports.append(match.group(1))
            if len(parsed_imports) != len(set(parsed_imports)):
                raise BoundaryError(
                    f"{relative.as_posix()}: duplicate production import"
                )
            actual_imports = frozenset(parsed_imports)
            allowed_imports = EXPECTED_IMPORTS[relative]
            if actual_imports != allowed_imports:
                raise BoundaryError(
                    f"{relative.as_posix()}: production import drift; "
                    f"expected={sorted(allowed_imports)!r}, "
                    f"actual={sorted(actual_imports)!r}"
                )

            for pattern in FORBIDDEN_QUALIFIED_PATTERNS:
                normalized_qualified_code = re.sub(r"\s*\.\s*", ".", code)
                if pattern.search(normalized_qualified_code):
                    raise BoundaryError(
                        f"{relative.as_posix()}: forbidden production dependency "
                        f"matching {pattern.pattern!r}"
                    )

            for identifier in _IDENTIFIER_RE.findall(code):
                if identifier in ALLOWED_SPECIAL_IDENTIFIERS:
                    continue
                lowered = identifier.lower()
                if identifier in FORBIDDEN_EXACT_IDENTIFIERS:
                    raise BoundaryError(
                        f"{relative.as_posix()}: forbidden identifier {identifier}"
                    )
                if any(
                    marker in lowered
                    for marker in (
                        "snapshot",
                        "generation",
                        "codec",
                        "authenticator",
                        "keystore",
                        "workmanager",
                        "sqlite",
                        "classloader",
                        "loadclass",
                        "javaclass",
                        "getdeclared",
                        "reflection",
                        "thread",
                    )
                ):
                    raise BoundaryError(
                        f"{relative.as_posix()}: forbidden production identifier "
                        f"{identifier}"
                    )


def _check_main_source_fingerprints(root: Path) -> None:
    for relative, expected_sha256 in EXPECTED_MAIN_SHA256.items():
        path = root / relative
        try:
            actual_sha256 = hashlib.sha256(path.read_bytes()).hexdigest()
        except OSError as exc:
            raise BoundaryError(
                f"{relative.as_posix()}: unable to read frozen production source"
            ) from exc
        if actual_sha256 != expected_sha256:
            raise BoundaryError(
                f"{relative.as_posix()}: frozen production source fingerprint "
                f"drift; expected={expected_sha256}, actual={actual_sha256}"
            )


def _settings_modules(text: str) -> set[str]:
    return set(_SETTINGS_MODULE_RE.findall(text))


def _production_project_dependencies(text: str, module: str) -> tuple[str, ...]:
    without_comments = _sanitize_kotlin(text, strip_strings=False)
    references = tuple(_PROJECT_REFERENCE_RE.findall(without_comments))
    if len(_PROJECT_CALL_RE.findall(without_comments)) != len(references):
        raise BoundaryError(
            f"{module}: unparsed production project dependency syntax"
        )
    if re.search(r"\bprojects\s*\.", without_comments):
        raise BoundaryError(
            f"{module}: type-safe project dependency syntax is not allowed"
        )
    return references


def _compact_gradle_script(text: str) -> str:
    without_comments = _sanitize_kotlin(text, strip_strings=False)
    compact: list[str] = []
    index = 0
    state = "normal"
    while index < len(without_comments):
        if state == "normal":
            if without_comments.startswith('"""', index):
                compact.append('"""')
                index += 3
                state = "triple_string"
                continue
            character = without_comments[index]
            if character == '"':
                compact.append(character)
                state = "string"
            elif character == "'":
                compact.append(character)
                state = "char"
            elif not character.isspace():
                compact.append(character)
            index += 1
            continue

        if state == "triple_string":
            if without_comments.startswith('"""', index):
                compact.append('"""')
                index += 3
                state = "normal"
                continue
            compact.append(without_comments[index])
            index += 1
            continue

        character = without_comments[index]
        compact.append(character)
        if character == "\\" and index + 1 < len(without_comments):
            compact.append(without_comments[index + 1])
            index += 2
            continue
        if (state == "string" and character == '"') or (
            state == "char" and character == "'"
        ):
            state = "normal"
        index += 1
    return "".join(compact)


def _check_dependency_graph(root: Path) -> None:
    settings = _read_text(root, Path("settings.gradle.kts"))
    structural_settings = _sanitize_kotlin(settings, strip_strings=True)
    for forbidden in (
        "projectDir",
        "findProject",
        "includeBuild",
        "dependencySubstitution",
    ):
        if re.search(rf"\b{forbidden}\b", structural_settings):
            raise BoundaryError(
                f"settings.gradle.kts: forbidden module redirection {forbidden}"
            )
    modules = _settings_modules(settings)
    for required in (MEMORY_PROTOCOL, EVENT_JOURNAL):
        if required not in modules:
            raise BoundaryError(f"settings.gradle.kts: missing :{required}")

    root_build_path = root / "build.gradle.kts"
    if not root_build_path.is_file():
        raise BoundaryError("missing protected root build.gradle.kts")
    root_build = root_build_path.read_text(encoding="utf-8")
    if _compact_gradle_script(root_build) != _compact_gradle_script(
        EXPECTED_ROOT_BUILD_SCRIPT
    ):
        raise BoundaryError("protected root build script drift")

    catalog_path = root / "gradle/libs.versions.toml"
    if catalog_path.is_file():
        try:
            catalog = tomllib.loads(catalog_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, tomllib.TOMLDecodeError) as exc:
            raise BoundaryError("invalid Gradle version catalog") from exc
        expected_kotlin_plugin = {
            "id": "org.jetbrains.kotlin.jvm",
            "version": {"ref": "kotlin"},
        }
        if catalog.get("plugins", {}).get("kotlin-jvm") != expected_kotlin_plugin:
            raise BoundaryError(
                "Gradle version catalog remaps the Kotlin/JVM plugin"
            )

    graph: dict[str, tuple[str, ...]] = {}
    build_texts: dict[str, str] = {}
    for module in modules:
        build_path = root / module / "build.gradle.kts"
        if not build_path.is_file():
            continue
        text = build_path.read_text(encoding="utf-8")
        build_texts[module] = text
        graph[module] = _production_project_dependencies(text, module)

    for root_module in FORBIDDEN_RUNTIME_ROOTS:
        text = _sanitize_kotlin(
            build_texts.get(root_module, ""),
            strip_strings=False,
        )
        if re.search(
            r"""["']:(?:memory-protocol|event-journal)["']""",
            text,
        ):
            raise BoundaryError(
                f":{root_module} build script references an X-02 module"
            )

    for module in (MEMORY_PROTOCOL, EVENT_JOURNAL):
        if module not in build_texts:
            raise BoundaryError(f"{module}: missing build.gradle.kts")
        text = build_texts[module]
        if "alias(libs.plugins.kotlin.jvm)" not in text:
            raise BoundaryError(f"{module}: Kotlin/JVM plugin is required")
        if re.search(r"libs\.plugins\.(?:android|kotlin\.android)", text):
            raise BoundaryError(f"{module}: Android plugin is forbidden")

    if graph.get(MEMORY_PROTOCOL, ()) != ():
        raise BoundaryError("memory-protocol must have no production project dependency")
    if graph.get(EVENT_JOURNAL, ()) != (MEMORY_PROTOCOL,):
        raise BoundaryError(
            "event-journal production project dependencies must be exactly "
            "(:memory-protocol)"
        )
    if (
        re.search(
            r"""(?m)^\s*implementation\s*\(\s*
                project\s*\(\s*["']:memory-protocol["']\s*\)\s*\)\s*$""",
            build_texts[EVENT_JOURNAL],
            re.VERBOSE,
        )
        is None
    ):
        raise BoundaryError(
            "event-journal must use implementation(project(\":memory-protocol\"))"
        )

    def closure(start: str) -> set[str]:
        visited: set[str] = set()
        stack = list(graph.get(start, ()))
        while stack:
            dependency = stack.pop()
            if dependency in visited:
                continue
            visited.add(dependency)
            stack.extend(graph.get(dependency, ()))
        return visited

    for root_module in FORBIDDEN_RUNTIME_ROOTS:
        reached = closure(root_module)
        leaked = reached & {MEMORY_PROTOCOL, EVENT_JOURNAL}
        if leaked:
            raise BoundaryError(
                f":{root_module} production graph reaches forbidden "
                f"X-02 modules: {sorted(leaked)!r}"
            )

    for module, expected in EXPECTED_PROTECTED_BUILD_SCRIPTS.items():
        if _compact_gradle_script(build_texts[module]) != _compact_gradle_script(
            expected
        ):
            raise BoundaryError(
                f"{module}: protected build script drift"
            )
    if (root / "buildSrc").exists():
        raise BoundaryError("buildSrc is forbidden by the X-02 build boundary")
    unexpected_gradle_scripts: list[str] = []
    for pattern in ("*.gradle", "*.gradle.kts"):
        for path in root.rglob(pattern):
            relative = path.relative_to(root)
            if any(
                part in {".git", ".gradle", "build"}
                for part in relative.parts[:-1]
            ):
                continue
            if relative not in EXPECTED_GRADLE_SCRIPT_PATHS:
                unexpected_gradle_scripts.append(relative.as_posix())
    if unexpected_gradle_scripts:
        raise BoundaryError(
            "unexpected Gradle script authority: "
            f"{sorted(set(unexpected_gradle_scripts))!r}"
        )
    wrapper_properties = _read_text(
        root,
        Path("gradle/wrapper/gradle-wrapper.properties"),
    )
    expected_distribution_checksum = (
        "distributionSha256Sum="
        "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
    )
    if wrapper_properties.count(expected_distribution_checksum) != 1:
        raise BoundaryError(
            "Gradle 8.13 distribution SHA-256 is missing or changed"
        )
    for relative, expected_sha256 in EXPECTED_BUILD_AUTHORITY_SHA256.items():
        path = root / relative
        if not path.is_file():
            raise BoundaryError(
                f"missing frozen build authority: {relative.as_posix()}"
            )
        try:
            actual_sha256 = hashlib.sha256(path.read_bytes()).hexdigest()
        except OSError as exc:
            raise BoundaryError(
                f"{relative.as_posix()}: unable to read frozen build authority"
            ) from exc
        if actual_sha256 != expected_sha256:
            raise BoundaryError(
                f"{relative.as_posix()}: frozen build authority fingerprint "
                f"drift; expected={expected_sha256}, actual={actual_sha256}"
            )


def _check_ci_and_offline_coverage(root: Path) -> None:
    ci = _read_text(root, Path(".github/workflows/android.yml"))
    verify_job = _extract_verify_job(ci)
    package_job = _extract_workflow_job(ci, "package_x02")
    for job_name, job in (
        ("verify", verify_job),
        ("package_x02", package_job),
    ):
        if re.search(
            r"(?m)^    (?:if|defaults|continue-on-error|uses)\s*:",
            job,
        ):
            raise BoundaryError(
                f"Android CI {job_name} job must be unconditional, "
                "non-reusable, and have no shell defaults"
            )
    if re.search(r"(?m)^defaults\s*:", ci):
        raise BoundaryError("Android CI workflow-level shell defaults are forbidden")
    if package_job.count("    needs: verify\n") != 1:
        raise BoundaryError(
            "Android CI package_x02 job must depend exactly on verify"
        )
    if package_job.count("actions/checkout@v6") != 1:
        raise BoundaryError(
            "Android CI package_x02 job must use one fresh checkout"
        )
    if "gradle/actions/setup-gradle" in package_job:
        raise BoundaryError(
            "Android CI package_x02 job must not restore shared Gradle state"
        )
    if ci.count("name: sense-v0.4.2-clean-apks") != 2:
        raise BoundaryError(
            "Android CI isolated APK artifact must have one producer "
            "and one release consumer"
        )
    if "needs: [verify, package_x02]\n" not in ci:
        raise BoundaryError("Android CI release planning must await package_x02")
    if "needs: [verify, package_x02, release_plan]\n" not in ci:
        raise BoundaryError("Android CI release must await package_x02")

    ci_required = (
        "python3 tools/test_check_x02_boundaries.py",
        ":memory-protocol:test",
        ":memory-protocol:jar",
        ":event-journal:test",
        ":event-journal:jar",
        ":app:assembleDebug",
        ":app:assembleBenchmark",
        "python3 tools/check_x02_boundaries.py --check-artifacts",
        "sense-v0.4.2-clean-apks",
    )
    for snippet in ci_required:
        if snippet not in ci:
            raise BoundaryError(f"Android CI missing X-02 coverage: {snippet}")

    def containing_step(document: str, command: str) -> str:
        lines = document.splitlines()
        command_line = next(
            (index for index, line in enumerate(lines) if command in line),
            None,
        )
        if command_line is None:
            raise BoundaryError(f"Android CI missing X-02 command: {command}")
        start = None
        indentation = None
        for index in range(command_line, -1, -1):
            match = re.match(r"^(\s*)-\s+(?:name|run|uses)\s*:", lines[index])
            if match is not None:
                start = index
                indentation = len(match.group(1))
                break
        if start is None or indentation is None:
            raise BoundaryError(f"Android CI has unscoped X-02 command: {command}")
        end = len(lines)
        for index in range(start + 1, len(lines)):
            match = re.match(r"^(\s*)-\s+", lines[index])
            if match is not None and len(match.group(1)) == indentation:
                end = index
                break
        return "\n".join(lines[start:end])

    protected_steps: dict[str, str] = {
        "verify_source": containing_step(
            verify_job,
            "python3 tools/test_check_x02_boundaries.py",
        ),
        "package_source": containing_step(
            package_job,
            "python3 tools/check_x02_boundaries.py",
        ),
        "package_build": containing_step(
            package_job,
            ":event-journal:jar",
        ),
        "package_artifact": containing_step(
            package_job,
            "python3 tools/check_x02_boundaries.py --check-artifacts",
        ),
    }
    for step_name, step in protected_steps.items():
        if re.search(
            r"(?m)^\s+(?:if|continue-on-error|shell)\s*:",
            step,
        ):
            raise BoundaryError(
                f"Android CI X-02 step has non-enforcing control: {step_name}"
            )
        if re.search(r"\|\|\s*true\b", step):
            raise BoundaryError(
                f"Android CI X-02 step suppresses failure: {step_name}"
            )
    source_step = protected_steps["verify_source"]
    for exact_command in (
        "python3 tools/test_check_x02_boundaries.py",
        "python3 tools/check_x02_boundaries.py",
    ):
        if re.search(
            rf"(?m)^\s+{re.escape(exact_command)}\s*$",
            source_step,
        ) is None:
            raise BoundaryError(
                f"Android CI X-02 source step is not exact: {exact_command}"
            )
    package_source_step = protected_steps["package_source"]
    if re.search(
        r"(?m)^\s+run:\s*python3 tools/check_x02_boundaries\.py\s*$",
        package_source_step,
    ) is None:
        raise BoundaryError(
            "Android CI isolated package source command is not exact"
        )
    package_build_step = protected_steps["package_build"]
    if package_build_step.count(
        "GRADLE_USER_HOME: ${{ runner.temp }}/sense-x02-gradle-home"
    ) != 1:
        raise BoundaryError(
            "Android CI isolated package build must use an isolated Gradle user home"
        )
    isolated_tasks = (
        "--no-build-cache",
        "--no-configuration-cache",
        ":memory-protocol:jar",
        ":event-journal:jar",
        ":app:assembleDebug",
        ":app:assembleBenchmark",
    )
    for task in isolated_tasks:
        if package_build_step.count(task) != 1:
            raise BoundaryError(
                f"Android CI isolated package build task drift: {task}"
            )
    if re.search(r"(?i):[^\s]*test", package_build_step):
        raise BoundaryError(
            "Android CI isolated package build must not execute repository tests"
        )
    artifact_step = protected_steps["package_artifact"]
    if re.search(
        r"(?m)^\s+run:\s*"
        r"python3 tools/check_x02_boundaries\.py --check-artifacts\s*$",
        artifact_step,
    ) is None:
        raise BoundaryError("Android CI X-02 artifact command is not exact")
    if (
        re.search(
            r"(?m)^\s*python3 tools/check_x02_boundaries\.py(?: --check)?\s*$",
            ci,
        )
        is None
    ):
        raise BoundaryError("Android CI missing source-only X-02 boundary check")
    artifact_position = package_job.find(
        "python3 tools/check_x02_boundaries.py --check-artifacts"
    )
    if artifact_position < package_job.find(":event-journal:jar"):
        raise BoundaryError("Android CI checks X-02 artifacts before building jars")
    if artifact_position < package_job.find(":app:assembleDebug"):
        raise BoundaryError("Android CI checks X-02 artifacts before building APKs")
    actual_verify_sha256 = hashlib.sha256(
        verify_job.encode("utf-8")
    ).hexdigest()
    if actual_verify_sha256 != EXPECTED_VERIFY_JOB_SHA256:
        raise BoundaryError(
            "Android CI verify job: frozen execution fingerprint drift; "
            f"expected={EXPECTED_VERIFY_JOB_SHA256}, "
            f"actual={actual_verify_sha256}"
        )
    actual_package_sha256 = hashlib.sha256(
        package_job.encode("utf-8")
    ).hexdigest()
    if actual_package_sha256 != EXPECTED_PACKAGE_JOB_SHA256:
        raise BoundaryError(
            "Android CI package_x02 job: frozen execution fingerprint drift; "
            f"expected={EXPECTED_PACKAGE_JOB_SHA256}, "
            f"actual={actual_package_sha256}"
        )
    actual_workflow_sha256 = hashlib.sha256(ci.encode("utf-8")).hexdigest()
    if actual_workflow_sha256 != EXPECTED_ANDROID_WORKFLOW_SHA256:
        raise BoundaryError(
            "Android CI workflow: frozen execution fingerprint drift; "
            f"expected={EXPECTED_ANDROID_WORKFLOW_SHA256}, "
            f"actual={actual_workflow_sha256}"
        )

    offline = _read_text(root, Path("tools/offline_verify.sh"))
    if offline.count("set -euo pipefail") != 1:
        raise BoundaryError(
            "offline_verify.sh must enable errexit, nounset, and pipefail exactly once"
        )
    forbidden_offline_control = (
        (r"(?m)^\s*exit\s+0(?:\s|$)", "early successful exit"),
        (r"(?m)^\s*(?:if|while)\s+false\b", "statically dead branch"),
        (r"(?m)^\s*until\s+true\b", "statically dead loop"),
        (r"(?m)^\s*set\s+\+e\b", "errexit disable"),
        (r"(?m)^\s*set\s+\+o\s+pipefail\b", "pipefail disable"),
        (r"(?m)^\s*trap\b", "trap override"),
    )
    for pattern, description in forbidden_offline_control:
        if re.search(pattern, offline):
            raise BoundaryError(
                f"offline_verify.sh: forbidden {description}"
            )
    if re.search(
        r"""(?m)^\s*(?:function\s+[A-Za-z_][A-Za-z0-9_]*
            (?:\s*\(\s*\))?|[A-Za-z_][A-Za-z0-9_]*\s*\(\s*\))\s*\{""",
        offline,
        re.VERBOSE,
    ):
        raise BoundaryError(
            "offline_verify.sh: shell functions are forbidden in the "
            "linear release gate"
        )
    offline_required = (
        "tools/test_check_x02_boundaries.py",
        "tools/check_x02_boundaries.py",
        "memory-protocol/src/main/kotlin",
        "memory-protocol/src/test/kotlin",
        "event-journal/src/main/kotlin",
        "event-journal/src/test/kotlin",
        'OUT/memory-protocol-main',
        'OUT/memory-protocol-test',
        'OUT/event-journal-main',
        'OUT/event-journal-test',
        "MEMORY_PROTOCOL_SOURCES",
        "MEMORY_PROTOCOL_TEST_SOURCES",
        "EVENT_JOURNAL_SOURCES",
        "EVENT_JOURNAL_TEST_SOURCES",
        "JAR_TOOL",
        "MEMORY_PROTOCOL_JAR",
        "EVENT_JOURNAL_JAR",
        "--check-artifacts",
        '--memory-jar "$MEMORY_PROTOCOL_JAR"',
        '--event-journal-jar "$EVENT_JOURNAL_JAR"',
        '--app-apk "$APK"',
    )
    for snippet in offline_required:
        if snippet not in offline:
            raise BoundaryError(f"offline_verify.sh missing X-02 coverage: {snippet}")
    if offline.count('"$JAR_TOOL" --create') != 2:
        raise BoundaryError(
            "offline_verify.sh must package exactly two X-02 main jars"
        )
    packaged_call = offline.rfind(
        'python3 "$ROOT/tools/check_x02_boundaries.py"'
    )
    source_call = offline.find(
        'python3 "$ROOT/tools/check_x02_boundaries.py"'
    )
    if packaged_call == source_call:
        raise BoundaryError(
            "offline_verify.sh is missing the final packaged X-02 check"
        )
    final_call_tail = offline[packaged_call:]
    if re.search(r"\|\|\s*true\b", final_call_tail):
        raise BoundaryError(
            "offline packaged X-02 check suppresses failure"
        )
    source_log = 'tee "$OUT/x02-boundaries.txt"'
    source_log_position = offline.find(source_log, source_call)
    if source_log_position < 0:
        raise BoundaryError("offline source X-02 check has no result log")
    source_call_block = offline[
        source_call:source_log_position + len(source_log)
    ]
    if re.search(r"\|\|\s*true\b", source_call_block):
        raise BoundaryError("offline source X-02 check suppresses failure")
    explicit_flag_order = (
        "--check-artifacts",
        "--memory-jar",
        "--event-journal-jar",
        "--app-apk",
    )
    positions = [final_call_tail.find(flag) for flag in explicit_flag_order]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        raise BoundaryError(
            "offline packaged X-02 check must contain the complete explicit "
            "artifact group in canonical order"
        )
    prerequisites = (
        '"$JAR_TOOL" --create',
        'sha256sum "$APK"',
    )
    for prerequisite in prerequisites:
        if offline.rfind(prerequisite, 0, packaged_call) < 0:
            raise BoundaryError(
                "offline packaged X-02 check occurs before artifact finalization: "
                f"{prerequisite}"
            )
    actual_offline_sha256 = hashlib.sha256(offline.encode("utf-8")).hexdigest()
    if actual_offline_sha256 != EXPECTED_OFFLINE_VERIFY_SHA256:
        raise BoundaryError(
            "offline_verify.sh: frozen release-gate fingerprint drift; "
            f"expected={EXPECTED_OFFLINE_VERIFY_SHA256}, "
            f"actual={actual_offline_sha256}"
            )


def _extract_workflow_job(ci: str, job_name: str) -> str:
    job_match = re.search(
        rf"(?m)^  {re.escape(job_name)}:\s*$",
        ci,
    )
    if job_match is None:
        raise BoundaryError(f"Android CI is missing the {job_name} job")
    next_job = re.search(
        r"(?m)^  [A-Za-z0-9_-]+:\s*$",
        ci[job_match.end():],
    )
    job_end = (
        job_match.end() + next_job.start()
        if next_job is not None
        else len(ci)
    )
    return ci[job_match.start():job_end]


def _extract_verify_job(ci: str) -> str:
    return _extract_workflow_job(ci, "verify")


def _find_single_main_jar(root: Path, module: str) -> Path:
    candidates = []
    for path in sorted((root / module / "build/libs").glob("*.jar")):
        lowered = path.stem.lower()
        if any(
            marker in lowered
            for marker in ("-sources", "-javadoc", "-tests", "-test-fixtures")
        ):
            continue
        candidates.append(path)
    if len(candidates) != 1:
        raise BoundaryError(
            f"{module}: expected exactly one main jar, got "
            f"{[path.name for path in candidates]!r}"
        )
    return candidates[0]


def _check_main_jar(
    jar_path: Path,
    required_classes: frozenset[str],
    allowed_synthetic_classes: frozenset[str],
    module: str,
) -> None:
    try:
        with zipfile.ZipFile(jar_path) as archive:
            infos = archive.infolist()
    except (OSError, zipfile.BadZipFile) as exc:
        raise BoundaryError(f"{module}: invalid main jar: {jar_path}") from exc

    names = [info.filename for info in infos]
    duplicates = sorted(
        name for name, count in Counter(names).items() if count != 1
    )
    if duplicates:
        raise BoundaryError(f"{module}: duplicate jar entries: {duplicates!r}")
    allowed_files = required_classes | allowed_synthetic_classes
    allowed_directories = {"META-INF/"}
    for file_name in allowed_files:
        parts = file_name.split("/")[:-1]
        for index in range(1, len(parts) + 1):
            allowed_directories.add("/".join(parts[:index]) + "/")

    kotlin_modules = [
        name
        for name in names
        if re.fullmatch(r"META-INF/[^/]+\.kotlin_module", name)
    ]
    if len(kotlin_modules) != 1:
        raise BoundaryError(
            f"{module}: expected exactly one Kotlin module metadata entry, "
            f"got {kotlin_modules!r}"
        )

    try:
        content_archive = zipfile.ZipFile(jar_path)
    except (OSError, zipfile.BadZipFile) as exc:
        raise BoundaryError(f"{module}: invalid main jar: {jar_path}") from exc
    try:
        for info in infos:
            name = info.filename
            if name.startswith("/") or ".." in Path(name).parts:
                raise BoundaryError(f"{module}: unsafe jar entry {name!r}")
            if any(part in name for part in FORBIDDEN_ARTIFACT_NAME_PARTS):
                raise BoundaryError(
                    f"{module}: forbidden test/fake class in jar: {name}"
                )
            if name.endswith("/"):
                if name not in allowed_directories or info.file_size != 0:
                    raise BoundaryError(
                        f"{module}: non-whitelisted or non-empty jar "
                        f"directory: {name}"
                    )
                continue
            if name.endswith(".class"):
                if name not in allowed_files:
                    raise BoundaryError(
                        f"{module}: non-whitelisted class in main jar: {name}"
                    )
                if info.file_size > MAX_JAR_CLASS_BYTES:
                    raise BoundaryError(
                        f"{module}: oversized class in main jar: {name}"
                    )
                class_bytes = content_archive.read(info)
                if not class_bytes.startswith(b"\xca\xfe\xba\xbe"):
                    raise BoundaryError(
                        f"{module}: invalid JVM class magic in main jar: {name}"
                    )
                continue
            if name == "META-INF/MANIFEST.MF":
                if info.file_size > MAX_JAR_METADATA_BYTES:
                    raise BoundaryError(f"{module}: oversized jar manifest")
                content_archive.read(info)
                continue
            if re.fullmatch(r"META-INF/[^/]+\.kotlin_module", name):
                if info.file_size > MAX_JAR_METADATA_BYTES:
                    raise BoundaryError(
                        f"{module}: oversized Kotlin module metadata"
                    )
                content_archive.read(info)
                continue
            raise BoundaryError(
                f"{module}: non-whitelisted jar resource: {name}"
            )
    except zipfile.BadZipFile as exc:
        raise BoundaryError(
            f"{module}: corrupt main jar entry: {jar_path}"
        ) from exc
    finally:
        content_archive.close()
    missing = sorted(required_classes - set(names))
    if missing:
        raise BoundaryError(f"{module}: main jar missing required classes: {missing!r}")


def _check_app_apks(root: Path, explicit_apks: Sequence[Path] | None = None) -> None:
    if explicit_apks is None:
        apk_root = root / "app/build/outputs/apk"
        apks = sorted(apk_root.rglob("*.apk")) if apk_root.is_dir() else []
        if apks and not any(
            "benchmark" in path.as_posix().lower() for path in apks
        ):
            raise BoundaryError(
                "default artifact mode requires a benchmark app APK"
            )
    else:
        apks = list(explicit_apks)
    if not apks:
        raise BoundaryError("no app APK found for --check-artifacts")
    for apk in apks:
        if not apk.is_file():
            raise BoundaryError(f"explicit app APK does not exist: {apk}")
        try:
            with zipfile.ZipFile(apk) as archive:
                infos = archive.infolist()
                names = [info.filename for info in infos]
                duplicates = sorted(
                    name
                    for name, count in Counter(names).items()
                    if count != 1
                )
                if duplicates:
                    raise BoundaryError(
                        f"{apk}: duplicate APK entries: {duplicates!r}"
                    )
                scan_bytes = 0
                for info in infos:
                    name = info.filename
                    if name.startswith("/") or ".." in Path(name).parts:
                        raise BoundaryError(f"{apk}: unsafe APK entry {name!r}")
                    if name.endswith("/"):
                        if info.file_size != 0:
                            raise BoundaryError(
                                f"{apk}: non-empty APK directory entry {name!r}"
                            )
                        continue
                    if info.file_size > MAX_APK_ENTRY_BYTES:
                        raise BoundaryError(f"{apk}: oversized APK entry {name!r}")
                    scan_bytes += info.file_size
                    if scan_bytes > MAX_APK_SCAN_BYTES:
                        raise BoundaryError(f"{apk}: APK scan budget exceeded")
                dex_names = [
                    name
                    for name in names
                    if re.fullmatch(r"(?:.*/)?classes[0-9]*\.dex", name)
                ]
                if not dex_names:
                    raise BoundaryError(f"{apk}: APK contains no classes*.dex")
                for name in names:
                    lowered = name.lower()
                    if lowered.endswith(".dex") and name not in dex_names:
                        raise BoundaryError(
                            f"{apk}: nonstandard DEX payload is forbidden: {name}"
                        )
                    if lowered.endswith((".class", ".jar")):
                        raise BoundaryError(
                            f"{apk}: nested JVM payload is forbidden: {name}"
                        )
                total_dex_bytes = sum(
                    archive.getinfo(name).file_size for name in dex_names
                )
                if total_dex_bytes > MAX_TOTAL_DEX_BYTES:
                    raise BoundaryError(f"{apk}: total DEX payload is oversized")
                for dex_name in dex_names:
                    dex_info = archive.getinfo(dex_name)
                    if dex_info.file_size > MAX_DEX_BYTES:
                        raise BoundaryError(
                            f"{apk}: {dex_name} is oversized"
                        )
                    dex = archive.read(dex_name)
                    if (
                        len(dex) < 8
                        or dex[:4] != b"dex\n"
                        or not dex[4:7].isdigit()
                        or dex[7] != 0
                    ):
                        raise BoundaryError(
                            f"{apk}: {dex_name} has invalid DEX magic"
                        )
                for info in infos:
                    if info.filename.endswith("/"):
                        continue
                    payload = archive.read(info)
                    if info.filename not in dex_names:
                        if (
                            len(payload) >= 8
                            and payload[:4] == b"dex\n"
                            and payload[4:7].isdigit()
                            and payload[7] == 0
                        ):
                            raise BoundaryError(
                                f"{apk}: disguised DEX payload is forbidden: "
                                f"{info.filename}"
                            )
                        if payload.startswith(b"\xca\xfe\xba\xbe"):
                            raise BoundaryError(
                                f"{apk}: disguised JVM class payload is forbidden: "
                                f"{info.filename}"
                            )
                        if payload.startswith(
                            NESTED_ZIP_MAGICS
                        ) or zipfile.is_zipfile(io.BytesIO(payload)):
                            raise BoundaryError(
                                f"{apk}: nested archive payload is forbidden: "
                                f"{info.filename}"
                            )
                    for forbidden_path in DEX_MEMORY_PATHS:
                        if forbidden_path in payload:
                            raise BoundaryError(
                                f"{apk}: {info.filename} contains X-02 Memory path"
                            )
        except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
            raise BoundaryError(f"invalid APK: {apk}") from exc


def check_artifacts(
    root: Path,
    *,
    memory_jar: Path | None = None,
    event_journal_jar: Path | None = None,
    app_apks: Sequence[Path] | None = None,
) -> None:
    explicit_values = (memory_jar, event_journal_jar, app_apks)
    if any(value is not None for value in explicit_values) and not all(
        value is not None for value in explicit_values
    ):
        raise BoundaryError(
            "explicit artifact mode requires --memory-jar, "
            "--event-journal-jar, and at least one --app-apk"
        )
    if memory_jar is None:
        memory_jar = _find_single_main_jar(root, MEMORY_PROTOCOL)
        event_journal_jar = _find_single_main_jar(root, EVENT_JOURNAL)
    assert event_journal_jar is not None
    _check_main_jar(
        memory_jar,
        MEMORY_REQUIRED_CLASSES,
        MEMORY_ALLOWED_SYNTHETIC_CLASSES,
        MEMORY_PROTOCOL,
    )
    _check_main_jar(
        event_journal_jar,
        EVENT_REQUIRED_CLASSES,
        EVENT_ALLOWED_SYNTHETIC_CLASSES,
        EVENT_JOURNAL,
    )
    _check_app_apks(root, app_apks)


def check_repository(
    root: Path,
    *,
    check_built_artifacts: bool = False,
    memory_jar: Path | None = None,
    event_journal_jar: Path | None = None,
    app_apks: Sequence[Path] | None = None,
) -> None:
    root = root.resolve()
    _check_main_file_allowlist(root)
    _check_forbidden_source_layouts(root)
    _check_gate_registry(root)
    _check_exact_enums(root)
    _check_production_sources(root)
    _check_main_source_fingerprints(root)
    _check_dependency_graph(root)
    _check_ci_and_offline_coverage(root)
    if check_built_artifacts:
        check_artifacts(
            root,
            memory_jar=memory_jar,
            event_journal_jar=event_journal_jar,
            app_apks=app_apks,
        )
    elif any(value is not None for value in (memory_jar, event_journal_jar, app_apks)):
        raise BoundaryError(
            "explicit artifact paths require --check-artifacts"
        )


def _build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (defaults to this script's parent repository)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="compatibility spelling for the default source check",
    )
    parser.add_argument(
        "--check-artifacts",
        action="store_true",
        help="also inspect built main jars and every app APK",
    )
    parser.add_argument(
        "--memory-jar",
        type=Path,
        help="explicit memory-protocol main jar (requires complete artifact mode)",
    )
    parser.add_argument(
        "--event-journal-jar",
        type=Path,
        help="explicit event-journal main jar (requires complete artifact mode)",
    )
    parser.add_argument(
        "--app-apk",
        type=Path,
        action="append",
        help="explicit app APK; repeat to inspect multiple APKs",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _build_argument_parser().parse_args(argv)
    try:
        check_repository(
            args.root,
            check_built_artifacts=args.check_artifacts,
            memory_jar=args.memory_jar,
            event_journal_jar=args.event_journal_jar,
            app_apks=args.app_apk,
        )
    except BoundaryError as exc:
        print(f"X-02 boundary check failed: {exc}", file=sys.stderr)
        return 1
    suffix = " + artifacts" if args.check_artifacts else ""
    print(f"X-02 boundary check passed{suffix}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
