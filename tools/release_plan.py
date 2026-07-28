#!/usr/bin/env python3
"""Fail-closed release planning for Sense GitHub Actions.

The Android workflow verifies every pull request and main-branch push. A push
creates a GitHub Release when the Android application version changes, or
recovers an interrupted first release while the matching tag is still absent.
This module keeps that decision deterministic and testable outside GitHub
Actions.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence


VERSION_NAME_PATTERN = re.compile(
    r"^[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$"
)
COMMIT_SHA_PATTERN = re.compile(r"^[0-9a-f]{40,64}$")
VERSION_NAME_LINE = re.compile(
    r'^\s*versionName\s*=\s*"([^"]+)"\s*(?://.*)?$'
)
VERSION_CODE_LINE = re.compile(r"^\s*versionCode\s*=\s*([0-9]+)\s*(?://.*)?$")
MISSING_TAG = "MISSING"


class ReleasePlanError(ValueError):
    """The release inputs are ambiguous or violate a release invariant."""


@dataclass(frozen=True)
class AndroidVersion:
    name: str
    code: int

    @property
    def tag(self) -> str:
        return f"v{self.name}"

    @property
    def apk_name(self) -> str:
        return f"Sense-v{self.name}.apk"

    def validate(self, label: str) -> None:
        if not VERSION_NAME_PATTERN.fullmatch(self.name):
            raise ReleasePlanError(
                f"{label} versionName {self.name!r} is not a supported Sense version"
            )
        if self.code <= 0:
            raise ReleasePlanError(f"{label} versionCode must be positive")


@dataclass(frozen=True)
class ReleaseDecision:
    status: str
    should_release: bool
    previous_version: str
    current_version: str
    release_tag: str
    tag_target: str


def _single_match(
    text: str,
    pattern: re.Pattern[str],
    field: str,
    source: str,
) -> str:
    matches = [
        match.group(1)
        for line in text.splitlines()
        if not line.lstrip().startswith("//")
        for match in [pattern.fullmatch(line)]
        if match is not None
    ]
    if len(matches) != 1:
        raise ReleasePlanError(
            f"{source}: expected exactly one literal {field}, found {len(matches)}"
        )
    return matches[0]


def parse_android_version(text: str, source: str = "build.gradle.kts") -> AndroidVersion:
    """Extract the one literal defaultConfig version pair.

    A computed value or multiple declarations are rejected instead of guessed.
    This makes a future Gradle refactor update the release gate deliberately.
    """

    name = _single_match(text, VERSION_NAME_LINE, "versionName", source)
    code_text = _single_match(text, VERSION_CODE_LINE, "versionCode", source)
    version = AndroidVersion(name=name, code=int(code_text))
    version.validate(source)
    return version


def decide_release(
    *,
    previous: AndroidVersion,
    current: AndroidVersion,
    release_tag: str,
    release_apk: str,
    current_sha: str,
    tag_target: str | None,
) -> ReleaseDecision:
    """Return the only safe action for a verified main-branch push."""

    previous.validate("previous")
    current.validate("current")

    if release_tag != current.tag:
        raise ReleasePlanError(
            f"RELEASE_TAG {release_tag!r} must equal {current.tag!r}"
        )
    if release_apk != current.apk_name:
        raise ReleasePlanError(
            f"RELEASE_APK {release_apk!r} must equal {current.apk_name!r}"
        )
    if not COMMIT_SHA_PATTERN.fullmatch(current_sha):
        raise ReleasePlanError("current SHA must be a lowercase hexadecimal commit ID")

    normalized_target = tag_target or MISSING_TAG
    if normalized_target != MISSING_TAG and not COMMIT_SHA_PATTERN.fullmatch(
        normalized_target
    ):
        raise ReleasePlanError(
            "tag target must be MISSING or a lowercase hexadecimal commit ID"
        )

    name_changed = current.name != previous.name
    code_changed = current.code != previous.code

    if not name_changed and not code_changed:
        if normalized_target == MISSING_TAG:
            return ReleaseDecision(
                status="RELEASE_RECOVER_MISSING_TAG",
                should_release=True,
                previous_version=f"{previous.name} ({previous.code})",
                current_version=f"{current.name} ({current.code})",
                release_tag=release_tag,
                tag_target=normalized_target,
            )
        return ReleaseDecision(
            status="SKIPPED_VERSION_UNCHANGED",
            should_release=False,
            previous_version=f"{previous.name} ({previous.code})",
            current_version=f"{current.name} ({current.code})",
            release_tag=release_tag,
            tag_target=normalized_target,
        )

    if name_changed != code_changed:
        raise ReleasePlanError(
            "versionName and versionCode must change together for a release"
        )
    if current.code <= previous.code:
        raise ReleasePlanError(
            f"versionCode must increase: {previous.code} -> {current.code}"
        )

    if normalized_target == MISSING_TAG:
        status = "RELEASE_NEW_TAG"
    elif normalized_target == current_sha:
        status = "RELEASE_IDEMPOTENT_TAG"
    else:
        raise ReleasePlanError(
            f"{release_tag} already targets {normalized_target}, not {current_sha}"
        )

    return ReleaseDecision(
        status=status,
        should_release=True,
        previous_version=f"{previous.name} ({previous.code})",
        current_version=f"{current.name} ({current.code})",
        release_tag=release_tag,
        tag_target=normalized_target,
    )


def _append_lines(path: Path, lines: Sequence[str]) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as output:
        for line in lines:
            output.write(f"{line}\n")


def write_github_outputs(path: Path, decision: ReleaseDecision) -> None:
    _append_lines(
        path,
        (
            f"should_release={'true' if decision.should_release else 'false'}",
            f"status={decision.status}",
            f"version={decision.current_version.split(' ', 1)[0]}",
        ),
    )


def write_step_summary(path: Path, decision: ReleaseDecision) -> None:
    _append_lines(
        path,
        (
            "## Sense release plan",
            "",
            f"- Status: `{decision.status}`",
            f"- Previous: `{decision.previous_version}`",
            f"- Current: `{decision.current_version}`",
            f"- Tag: `{decision.release_tag}`",
            f"- Existing tag target: `{decision.tag_target}`",
            f"- Release job enabled: `{'yes' if decision.should_release else 'no'}`",
        ),
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--current", required=True, type=Path)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--release-apk", required=True)
    parser.add_argument("--current-sha", required=True)
    parser.add_argument("--tag-target", default=MISSING_TAG)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--step-summary", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        previous = parse_android_version(
            args.previous.read_text(encoding="utf-8"),
            str(args.previous),
        )
        current = parse_android_version(
            args.current.read_text(encoding="utf-8"),
            str(args.current),
        )
        decision = decide_release(
            previous=previous,
            current=current,
            release_tag=args.release_tag,
            release_apk=args.release_apk,
            current_sha=args.current_sha,
            tag_target=None if args.tag_target == MISSING_TAG else args.tag_target,
        )
    except (OSError, UnicodeError, ReleasePlanError) as error:
        print(f"RELEASE_PLAN_REJECTED: {error}", file=sys.stderr)
        return 2

    print(json.dumps(asdict(decision), ensure_ascii=False, sort_keys=True))
    if args.github_output is not None:
        write_github_outputs(args.github_output, decision)
    if args.step_summary is not None:
        write_step_summary(args.step_summary, decision)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
