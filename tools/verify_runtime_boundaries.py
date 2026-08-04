#!/usr/bin/env python3

"""Verify Sense's merged runtime components and IME network isolation."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
SETTINGS_ACTIVITY = "io.github.ethanbird.senseime.SettingsActivity"
AGENT_HUB_ACTIVITY = "io.github.ethanbird.senseime.AgentHubActivity"
BRAIN_SERVICE = (
    "io.github.ethanbird.senseime.brain.runtime.SenseAiBrainService"
)
IME_SERVICE = "io.github.ethanbird.senseime.service.SenseInputMethodService"
NETWORK_DEPENDENCY_PATTERN = re.compile(
    r"java\.net\.|javax\.net\.|okhttp|retrofit"
)
REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_INTERMEDIATES = REPOSITORY_ROOT / "app" / "build" / "intermediates"
DEFAULT_SOURCE_ROOTS = (
    REPOSITORY_ROOT / "ime-service",
    REPOSITORY_ROOT / "ime-ui",
)


class RuntimeBoundaryError(ValueError):
    """Raised when an assembled runtime boundary does not match its contract."""


@dataclass(frozen=True)
class NetworkDependencyLeak:
    """One forbidden network dependency reference in Kotlin source."""

    path: Path
    line_number: int
    line: str
    dependency: str

    def render(self) -> str:
        return f"{self.path}:{self.line_number}:{self.line}"


def _kotlin_files(source_root: Path) -> tuple[Path, ...]:
    if not source_root.exists():
        raise RuntimeBoundaryError(
            f"{source_root}: network boundary source root does not exist"
        )
    if source_root.is_file():
        if source_root.suffix != ".kt":
            raise RuntimeBoundaryError(
                f"{source_root}: network boundary source must be a .kt file "
                "or directory"
            )
        return (source_root,)
    if not source_root.is_dir():
        raise RuntimeBoundaryError(
            f"{source_root}: network boundary source root is not a directory"
        )
    return tuple(
        sorted(
            source_root.rglob("*.kt"),
            key=lambda path: path.as_posix(),
        )
    )


def find_network_dependency_leaks(
    source_roots: tuple[str | Path, ...] | list[str | Path],
) -> tuple[NetworkDependencyLeak, ...]:
    """Return deterministic Kotlin references to forbidden network clients."""

    if not source_roots:
        raise RuntimeBoundaryError(
            "at least one network boundary source root is required"
        )

    leaks: list[NetworkDependencyLeak] = []
    seen_files: set[Path] = set()
    for raw_root in source_roots:
        root = Path(raw_root)
        for source in _kotlin_files(root):
            identity = source.resolve()
            if identity in seen_files:
                continue
            seen_files.add(identity)
            try:
                lines = source.read_text(encoding="utf-8").splitlines()
            except (OSError, UnicodeError) as error:
                raise RuntimeBoundaryError(
                    f"{source}: cannot read Kotlin source: {error}"
                ) from error
            for line_number, line in enumerate(lines, start=1):
                match = NETWORK_DEPENDENCY_PATTERN.search(line)
                if match is not None:
                    leaks.append(
                        NetworkDependencyLeak(
                            path=source,
                            line_number=line_number,
                            line=line,
                            dependency=match.group(0),
                        )
                    )
    return tuple(leaks)


def verify_no_network_dependency_leaks(
    source_roots: tuple[str | Path, ...] | list[str | Path],
) -> None:
    """Reject network transport references in IME and UI Kotlin sources."""

    leaks = find_network_dependency_leaks(source_roots)
    if leaks:
        details = "\n".join(leak.render() for leak in leaks)
        raise RuntimeBoundaryError(
            "network transport leaked into the IME or UI module:\n"
            f"{details}"
        )


def discover_benchmark_manifests(
    intermediates: str | Path,
) -> tuple[Path, ...]:
    """Find AGP singular and plural merged benchmark manifest layouts."""

    root = Path(intermediates)
    manifests = {
        path
        for pattern in (
            "merged_manifest/benchmark/*/AndroidManifest.xml",
            "merged_manifests/benchmark/*/AndroidManifest.xml",
        )
        for path in root.glob(pattern)
        if path.is_file()
    }
    if not manifests:
        raise RuntimeBoundaryError(
            f"{root}: no merged benchmark AndroidManifest.xml found"
        )
    return tuple(sorted(manifests, key=lambda path: path.as_posix()))


def verify_runtime_boundaries(
    manifest_paths: tuple[str | Path, ...] | list[str | Path],
    source_roots: tuple[str | Path, ...] | list[str | Path],
) -> None:
    """Run the complete runtime component and IME dependency gate."""

    if not manifest_paths:
        raise RuntimeBoundaryError(
            "at least one merged benchmark manifest is required"
        )
    for manifest_path in manifest_paths:
        verify_runtime_manifest(manifest_path)
    verify_no_network_dependency_leaks(source_roots)


def _component_by_name(
    elements: list[ET.Element],
    *,
    component_name: str,
    manifest_path: Path,
) -> ET.Element:
    matches = [
        element
        for element in elements
        if element.get(ANDROID + "name") == component_name
    ]
    if len(matches) != 1:
        raise RuntimeBoundaryError(
            f"{manifest_path}: expected one {component_name}, "
            f"found {len(matches)}"
        )
    return matches[0]


def verify_runtime_manifest(manifest_path: str | Path) -> None:
    """Verify runtime components in an assembled merged Android manifest."""

    path = Path(manifest_path)
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise RuntimeBoundaryError(
            f"{path}: cannot parse manifest: {error}"
        ) from error

    if root.tag != "manifest":
        raise RuntimeBoundaryError(
            f"{path}: expected unnamespaced <manifest> root, found <{root.tag}>"
        )

    applications = root.findall("application")
    if len(applications) != 1:
        raise RuntimeBoundaryError(
            f"{path}: expected one application, found {len(applications)}"
        )
    application = applications[0]

    activities = application.findall("activity")
    settings = _component_by_name(
        activities,
        component_name=SETTINGS_ACTIVITY,
        manifest_path=path,
    )
    if settings.get(ANDROID + "exported") != "true":
        raise RuntimeBoundaryError(
            f"{path}: SettingsActivity must be exported=true"
        )

    launcher_filters = []
    for intent_filter in settings.findall("intent-filter"):
        actions = {
            action.get(ANDROID + "name")
            for action in intent_filter.findall("action")
        }
        categories = {
            category.get(ANDROID + "name")
            for category in intent_filter.findall("category")
        }
        if (
            "android.intent.action.MAIN" in actions
            and "android.intent.category.LAUNCHER" in categories
        ):
            launcher_filters.append(intent_filter)
    if len(launcher_filters) != 1:
        raise RuntimeBoundaryError(
            f"{path}: SettingsActivity must have exactly one "
            "MAIN+LAUNCHER filter"
        )

    agent_hub = _component_by_name(
        activities,
        component_name=AGENT_HUB_ACTIVITY,
        manifest_path=path,
    )
    if agent_hub.get(ANDROID + "exported") != "false":
        raise RuntimeBoundaryError(
            f"{path}: AgentHubActivity must be exported=false"
        )
    if agent_hub.get(ANDROID + "process") != ":brain":
        raise RuntimeBoundaryError(
            f"{path}: AgentHubActivity must run in :brain"
        )
    if agent_hub.get(ANDROID + "windowSoftInputMode") != "adjustResize":
        raise RuntimeBoundaryError(
            f"{path}: AgentHubActivity must use adjustResize"
        )

    services = application.findall("service")
    brain = _component_by_name(
        services,
        component_name=BRAIN_SERVICE,
        manifest_path=path,
    )
    if brain.get(ANDROID + "exported") != "false":
        raise RuntimeBoundaryError(
            f"{path}: Brain service must be exported=false"
        )
    if brain.get(ANDROID + "process") != ":brain":
        raise RuntimeBoundaryError(
            f"{path}: Brain service must run in :brain"
        )
    if brain.get(ANDROID + "foregroundServiceType") != "specialUse":
        raise RuntimeBoundaryError(
            f"{path}: Brain service must use specialUse foreground type"
        )
    special_use_properties = [
        child
        for child in brain.findall("property")
        if child.get(ANDROID + "name")
        == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    ]
    if len(special_use_properties) != 1:
        raise RuntimeBoundaryError(
            f"{path}: Brain service must declare one special-use subtype"
        )
    if (
        special_use_properties[0].get(ANDROID + "value")
        != "user_initiated_agent_task"
    ):
        raise RuntimeBoundaryError(
            f"{path}: Brain service special-use subtype drifted"
        )

    ime = _component_by_name(
        services,
        component_name=IME_SERVICE,
        manifest_path=path,
    )
    if (
        ime.get(ANDROID + "permission")
        != "android.permission.BIND_INPUT_METHOD"
    ):
        raise RuntimeBoundaryError(
            f"{path}: IME service must require BIND_INPUT_METHOD"
        )
    actions = {
        action.get(ANDROID + "name")
        for action in ime.findall("./intent-filter/action")
    }
    if "android.view.InputMethod" not in actions:
        raise RuntimeBoundaryError(
            f"{path}: IME service is missing InputMethod action"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify merged benchmark runtime components and prevent network "
            "dependencies in Sense IME Kotlin sources."
        ),
    )
    parser.add_argument(
        "--manifest",
        action="append",
        type=Path,
        dest="manifests",
        help=(
            "merged benchmark AndroidManifest.xml; repeat to verify multiple "
            "outputs (default: discover AGP merged benchmark manifests)"
        ),
    )
    parser.add_argument(
        "--intermediates",
        type=Path,
        default=DEFAULT_INTERMEDIATES,
        help="AGP intermediates directory used for manifest discovery",
    )
    parser.add_argument(
        "--source-root",
        action="append",
        type=Path,
        dest="source_roots",
        help=(
            "IME/UI module or Kotlin source root; repeat as needed "
            "(default: ime-service and ime-ui)"
        ),
    )
    args = parser.parse_args(argv)

    try:
        manifests = (
            tuple(args.manifests)
            if args.manifests
            else discover_benchmark_manifests(args.intermediates)
        )
        source_roots = (
            tuple(args.source_roots)
            if args.source_roots
            else DEFAULT_SOURCE_ROOTS
        )
        verify_runtime_boundaries(manifests, source_roots)
    except RuntimeBoundaryError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
