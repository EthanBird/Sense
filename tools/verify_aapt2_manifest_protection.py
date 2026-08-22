#!/usr/bin/env python3

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys


EXPECTED_TYPED_PROTECTION = "0x00000002"
EXPECTED_PERMISSION_NAME = (
    "io.github.ethanbird.senseime."
    "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)
EXPECTED_PACKAGE_NAME = "io.github.ethanbird.senseime"
EXPECTED_USED_PERMISSIONS = frozenset(
    {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        "android.permission.RECORD_AUDIO",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        EXPECTED_PERMISSION_NAME,
    }
)
_ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
_PROTECTION_MARKER = "protectionLevel"
_PROTECTION_LABEL = "android:protectionLevel"
_TYPED_PROTECTION_ATTRIBUTE = re.compile(
    r"^[ \t]*A: "
    + re.escape(_ANDROID_NAMESPACE)
    + r':protectionLevel\(0x01010009\)='
    r'(?P<typed>\S+)'
    r'(?: \(Raw: "(?:[^"\\]|\\.)*"\))?'
    r"[ \t]*$"
)
_DECLARED_PERMISSION = re.compile(
    r"^permission: (?P<name>[A-Za-z0-9_.]+)$"
)
_PACKAGE = re.compile(r"^package: (?P<name>[A-Za-z0-9_.]+)$")
_USES_PERMISSION = re.compile(
    r"^uses-permission(?:-sdk-23)?: "
    r"name='(?P<name>[A-Za-z0-9_.]+)'$"
)


class Aapt2ManifestProtectionError(ValueError):
    pass


def verify_aapt2_manifest_protection(
    xmltree_path: str | Path,
    permissions_path: str | Path,
) -> None:
    path = Path(xmltree_path)
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise Aapt2ManifestProtectionError(
            f"{path}: cannot read AAPT2 manifest xmltree: {error}"
        ) from error

    candidates = [
        (line_number, line)
        for line_number, line in enumerate(lines, start=1)
        if _PROTECTION_MARKER in line
    ]
    if len(candidates) != 1:
        snapshot = [
            f"line {line_number}: {line.strip()!r}"
            for line_number, line in candidates
        ]
        raise Aapt2ManifestProtectionError(
            f"{path}: expected exactly one global {_PROTECTION_LABEL} "
            f"typed attribute, found {len(candidates)}: {snapshot}"
        )

    line_number, line = candidates[0]
    match = _TYPED_PROTECTION_ATTRIBUTE.fullmatch(line)
    if match is None:
        raise Aapt2ManifestProtectionError(
            f"{path}:{line_number}: malformed or drifted AAPT2 "
            f"{_PROTECTION_LABEL} typed attribute: {line.strip()!r}; "
            "expected "
            f"'A: {_ANDROID_NAMESPACE}:protectionLevel(0x01010009)="
            "0x00000002' with only an optional AAPT2 Raw suffix"
        )

    typed = match.group("typed")
    if typed != EXPECTED_TYPED_PROTECTION:
        raise Aapt2ManifestProtectionError(
            f"{path}:{line_number}: {_PROTECTION_LABEL} typed value must "
            f"be exactly {EXPECTED_TYPED_PROTECTION}, found {typed!r}; "
            f"full attribute was {line.strip()!r}"
        )

    permission_path = Path(permissions_path)
    try:
        permission_lines = permission_path.read_text(
            encoding="utf-8"
        ).splitlines()
    except (OSError, UnicodeError) as error:
        raise Aapt2ManifestProtectionError(
            f"{permission_path}: cannot read AAPT2 permissions dump: {error}"
        ) from error

    package_entries: list[tuple[int, str]] = []
    declaration_entries: list[tuple[int, str]] = []
    used_entries: list[tuple[int, str]] = []
    unexpected_entries: list[tuple[int, str]] = []
    for line_number, line in enumerate(permission_lines, start=1):
        if not line.strip():
            continue
        package_match = _PACKAGE.fullmatch(line)
        if package_match is not None:
            package_entries.append((line_number, package_match.group("name")))
            continue
        declaration_match = _DECLARED_PERMISSION.fullmatch(line)
        if declaration_match is not None:
            declaration_entries.append(
                (line_number, declaration_match.group("name"))
            )
            continue
        uses_match = _USES_PERMISSION.fullmatch(line)
        if uses_match is not None:
            used_entries.append((line_number, uses_match.group("name")))
            continue
        unexpected_entries.append((line_number, line))

    if unexpected_entries:
        snapshot = [
            f"line {line_number}: {line!r}"
            for line_number, line in unexpected_entries
        ]
        raise Aapt2ManifestProtectionError(
            f"{permission_path}: unexpected, modified, or format-drifted "
            f"AAPT2 permissions output: {snapshot}"
        )

    if len(package_entries) != 1:
        raise Aapt2ManifestProtectionError(
            f"{permission_path}: expected exactly one AAPT2 package line, "
            f"found {package_entries}"
        )
    package_line_number, package_name = package_entries[0]
    if package_name != EXPECTED_PACKAGE_NAME:
        raise Aapt2ManifestProtectionError(
            f"{permission_path}:{package_line_number}: AAPT2 package name "
            f"must be exactly {EXPECTED_PACKAGE_NAME!r}, "
            f"found {package_name!r}"
        )

    if len(declaration_entries) != 1:
        snapshot = [
            f"line {line_number}: {name!r}"
            for line_number, name in declaration_entries
        ]
        raise Aapt2ManifestProtectionError(
            f"{permission_path}: expected exactly one AAPT2 typed custom "
            f"permission declaration, found {len(declaration_entries)}: "
            f"{snapshot}"
        )

    declaration_line_number, declared_name = declaration_entries[0]
    if declared_name != EXPECTED_PERMISSION_NAME:
        raise Aapt2ManifestProtectionError(
            f"{permission_path}:{declaration_line_number}: AAPT2 typed custom "
            f"permission name must be exactly {EXPECTED_PERMISSION_NAME!r}, "
            f"found {declared_name!r}"
        )

    used_names = [name for _, name in used_entries]
    if (
        len(used_names) != len(EXPECTED_USED_PERMISSIONS)
        or set(used_names) != EXPECTED_USED_PERMISSIONS
    ):
        raise Aapt2ManifestProtectionError(
            f"{permission_path}: expected exactly the AAPT2 typed "
            f"uses-permission set {sorted(EXPECTED_USED_PERMISSIONS)}, "
            f"found {used_entries}"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify the final APK's AAPT2-typed custom-permission "
            "protection level."
        ),
    )
    parser.add_argument("xmltree", type=Path)
    parser.add_argument(
        "--permissions",
        required=True,
        type=Path,
        help="path produced by 'aapt2 dump permissions APK'",
    )
    args = parser.parse_args(argv)

    try:
        verify_aapt2_manifest_protection(args.xmltree, args.permissions)
    except Aapt2ManifestProtectionError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
