#!/usr/bin/env python3

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
DYNAMIC_RECEIVER_PERMISSION = (
    "io.github.ethanbird.senseime."
    "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)
EXPECTED_USED_PERMISSIONS = frozenset(
    {
        "android.permission.INTERNET",
        "android.permission.RECORD_AUDIO",
        DYNAMIC_RECEIVER_PERMISSION,
    }
)
_PACKAGED_SIGNATURE_NUMBER = re.compile(r"(?:2|0[xX]0{0,7}2)")
_USES_PERMISSION_TAG = re.compile(r"uses-permission(?:-sdk-23)?")
_PERMISSION_DECLARATION_TAGS = frozenset(
    {"permission", "permission-group", "permission-tree"}
)


class ManifestPermissionError(ValueError):
    pass


def _local_name(tag: str) -> str:
    return tag.rsplit("}", maxsplit=1)[-1]


def _is_signature_protection(value: str | None, *, packaged: bool) -> bool:
    if packaged:
        return bool(
            value is not None
            and _PACKAGED_SIGNATURE_NUMBER.fullmatch(value)
        )
    return value == "signature"


def verify_manifest_permissions(
    manifest_path: str | Path,
    *,
    packaged: bool = False,
) -> None:
    path = Path(manifest_path)
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise ManifestPermissionError(f"{path}: cannot parse manifest: {error}") from error

    if root.tag != "manifest":
        raise ManifestPermissionError(
            f"{path}: expected unnamespaced <manifest> root, found <{root.tag}>"
        )

    declarations = []
    for child in root:
        if child.tag == "permission":
            declarations.append(child)
        elif _local_name(child.tag) in _PERMISSION_DECLARATION_TAGS:
            raise ManifestPermissionError(
                f"{path}: unexpected or namespaced custom permission element "
                f"<{child.tag}>"
            )
    declaration_snapshot = [
        (
            declaration.get(ANDROID + "name"),
            declaration.get(ANDROID + "protectionLevel"),
        )
        for declaration in declarations
    ]
    if len(declarations) != 1:
        raise ManifestPermissionError(
            f"{path}: expected exactly one custom permission declaration, "
            f"found {declaration_snapshot}"
        )

    declaration = declarations[0]
    name = declaration.get(ANDROID + "name")
    protection = declaration.get(ANDROID + "protectionLevel")
    expected_declaration_attributes = {
        ANDROID + "name",
        ANDROID + "protectionLevel",
    }
    if set(declaration.attrib) != expected_declaration_attributes:
        raise ManifestPermissionError(
            f"{path}: custom permission declaration must contain only "
            "android:name and android:protectionLevel; found attributes "
            f"{sorted(declaration.attrib)}"
        )
    if len(declaration) != 0 or (declaration.text or "").strip():
        raise ManifestPermissionError(
            f"{path}: custom permission declaration must be empty"
        )
    if name != DYNAMIC_RECEIVER_PERMISSION:
        raise ManifestPermissionError(
            f"{path}: unexpected custom permission declaration {name!r}; "
            f"expected {DYNAMIC_RECEIVER_PERMISSION!r}"
        )
    if not _is_signature_protection(protection, packaged=packaged):
        accepted = (
            "the packaged numeric value 2"
            if packaged
            else "the literal value 'signature'"
        )
        raise ManifestPermissionError(
            f"{path}: {DYNAMIC_RECEIVER_PERMISSION} must use {accepted}; "
            f"found protectionLevel={protection!r}"
        )

    used_permissions: list[str | None] = []
    for child in root:
        tag = child.tag
        if tag.startswith("uses-permission"):
            if not _USES_PERMISSION_TAG.fullmatch(tag):
                raise ManifestPermissionError(
                    f"{path}: unexpected permission-bearing element <{tag}>"
                )
            if set(child.attrib) != {ANDROID + "name"}:
                raise ManifestPermissionError(
                    f"{path}: <{tag}> must contain only android:name; "
                    f"found attributes {sorted(child.attrib)}"
                )
            if len(child) != 0 or (child.text or "").strip():
                raise ManifestPermissionError(
                    f"{path}: <{tag}> must not contain conditional children "
                    "or text"
                )
            used_permissions.append(child.get(ANDROID + "name"))
        elif _local_name(tag).startswith("uses-permission"):
            raise ManifestPermissionError(
                f"{path}: permission-bearing element must be unnamespaced; "
                f"found <{tag}>"
            )

    if (
        len(used_permissions) != len(EXPECTED_USED_PERMISSIONS)
        or set(used_permissions) != EXPECTED_USED_PERMISSIONS
    ):
        raise ManifestPermissionError(
            f"{path}: expected exact uses-permission triplet "
            f"{sorted(EXPECTED_USED_PERMISSIONS)}, found {used_permissions}"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify Sense's exact manifest permission contract.",
    )
    parser.add_argument(
        "--packaged",
        action="store_true",
        help=(
            "accept apkanalyzer's controlled numeric encoding of Android's "
            "signature protection level"
        ),
    )
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args(argv)

    try:
        verify_manifest_permissions(args.manifest, packaged=args.packaged)
    except ManifestPermissionError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
