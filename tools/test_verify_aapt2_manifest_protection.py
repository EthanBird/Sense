#!/usr/bin/env python3

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from verify_aapt2_manifest_protection import (
    Aapt2ManifestProtectionError,
    EXPECTED_PACKAGE_NAME,
    EXPECTED_PERMISSION_NAME,
    EXPECTED_USED_PERMISSIONS,
    verify_aapt2_manifest_protection,
)


CANONICAL_ATTRIBUTE = (
    "    A: http://schemas.android.com/apk/res/android:"
    "protectionLevel(0x01010009)=0x00000002"
)


def xmltree(*attributes: str) -> str:
    body = "\n".join(attributes)
    return (
        "N: android=http://schemas.android.com/apk/res/android\n"
        "E: manifest (line=2)\n"
        "  E: permission (line=7)\n"
        f"{body}\n"
        "  E: application (line=12)\n"
    )


def permissions_dump(
    *,
    package_name: str = EXPECTED_PACKAGE_NAME,
    declared_permissions: tuple[str, ...] = (EXPECTED_PERMISSION_NAME,),
    used_permissions: tuple[tuple[str, str], ...] = tuple(
        ("uses-permission", name)
        for name in sorted(EXPECTED_USED_PERMISSIONS)
    ),
    extra_lines: tuple[str, ...] = (),
) -> str:
    lines = [f"package: {package_name}"]
    lines.extend(
        f"permission: {permission}"
        for permission in declared_permissions
    )
    lines.extend(
        f"{tag}: name='{permission}'"
        for tag, permission in used_permissions
    )
    lines.extend(extra_lines)
    return "\n".join(lines) + "\n"


class Aapt2ManifestProtectionVerifierTest(unittest.TestCase):
    def verify(
        self,
        content: str,
        *,
        permissions: str | None = None,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            xmltree_path = Path(temporary) / "apk-manifest.xmltree"
            xmltree_path.write_text(content, encoding="utf-8")
            permissions_path = Path(temporary) / "apk-permissions.txt"
            permissions_path.write_text(
                permissions
                if permissions is not None
                else permissions_dump(),
                encoding="utf-8",
            )
            verify_aapt2_manifest_protection(
                xmltree_path,
                permissions_path,
            )

    def assertRejected(
        self,
        content: str,
        pattern: str,
        *,
        permissions: str | None = None,
    ) -> None:
        with self.assertRaisesRegex(Aapt2ManifestProtectionError, pattern):
            self.verify(content, permissions=permissions)

    def test_accepts_exact_typed_signature_value(self) -> None:
        self.verify(xmltree(CANONICAL_ATTRIBUTE))

    def test_accepts_raw_suffix_without_using_it_as_evidence(self) -> None:
        self.verify(
            xmltree(CANONICAL_ATTRIBUTE + ' (Raw: "signature")'),
        )
        self.verify(
            xmltree(CANONICAL_ATTRIBUTE + ' (Raw: "not-evidence")'),
        )

    def test_raw_value_cannot_mask_wrong_typed_value(self) -> None:
        self.assertRejected(
            xmltree(
                "    A: http://schemas.android.com/apk/res/android:"
                "protectionLevel(0x01010009)="
                '0x00000012 (Raw: "0x00000002")',
            ),
            "typed value must be exactly 0x00000002.*0x00000012",
        )

    def test_rejects_non_exact_typed_encodings(self) -> None:
        for typed in (
            "0x00000012",
            "0x2",
            "0X00000002",
            "0x000000002",
            "2",
            '"0x00000002"',
        ):
            with self.subTest(typed=typed):
                self.assertRejected(
                    xmltree(
                        "    A: http://schemas.android.com/apk/res/android:"
                        "protectionLevel(0x01010009)="
                        f"{typed}",
                    ),
                    "typed value must be exactly 0x00000002",
                )

    def test_rejects_missing_attribute(self) -> None:
        self.assertRejected(
            xmltree(
                '    A: android:name(0x01010003)="example.permission.TEST"',
            ),
            "expected exactly one global android:protectionLevel.*found 0",
        )

    def test_rejects_multiple_attributes_globally(self) -> None:
        self.assertRejected(
            xmltree(CANONICAL_ATTRIBUTE, CANONICAL_ATTRIBUTE),
            "expected exactly one global android:protectionLevel.*found 2",
        )

    def test_rejects_attribute_id_and_format_drift(self) -> None:
        for attribute in (
            "    A: android:protectionLevel(0x01010009)=0x00000002",
            (
                "    A: http://schemas.android.com/apk/res/android:"
                "protectionLevel(0x0101000a)=0x00000002"
            ),
            "    A: android:protectionLevel=0x00000002",
            (
                "    A: http://schemas.android.com/apk/res/android:"
                "protectionLevel(0x01010009) =0x00000002"
            ),
            (
                "    A: http://schemas.android.com/apk/res/android:"
                "protectionLevel(0x01010009)= 0x00000002"
            ),
            (
                "    A: http://schemas.android.com/apk/res/android:"
                "protectionLevel(0x01010009)="
                '0x00000002 Raw: "signature"'
            ),
            (
                "    A: http://schemas.android.com/apk/res/android:"
                "protectionLevel(0x01010009)="
                '0x00000002 (Raw: "signature") trailing'
            ),
        ):
            with self.subTest(attribute=attribute):
                self.assertRejected(
                    xmltree(attribute),
                    "malformed or drifted AAPT2",
                )

    def test_requires_exact_typed_custom_permission_name(self) -> None:
        self.verify(
            xmltree(CANONICAL_ATTRIBUTE),
            permissions=permissions_dump(),
        )
        self.assertRejected(
            xmltree(CANONICAL_ATTRIBUTE),
            "typed custom permission name must be exactly",
            permissions=permissions_dump(
                declared_permissions=("example.permission.DECOY",),
            ),
        )

    def test_rejects_missing_and_duplicate_permission_declarations(self) -> None:
        self.assertRejected(
            xmltree(CANONICAL_ATTRIBUTE),
            "expected exactly one AAPT2 typed custom permission.*found 0",
            permissions=permissions_dump(
                declared_permissions=(),
            ),
        )
        self.assertRejected(
            xmltree(CANONICAL_ATTRIBUTE),
            "expected exactly one AAPT2 typed custom permission.*found 2",
            permissions=permissions_dump(
                declared_permissions=(
                    EXPECTED_PERMISSION_NAME,
                    "example.permission.EXTRA",
                ),
            ),
        )

    def test_rejects_permission_dump_format_drift(self) -> None:
        for declaration in (
            f" permission: {EXPECTED_PERMISSION_NAME}",
            f"permission:  {EXPECTED_PERMISSION_NAME}",
            f"permission: name='{EXPECTED_PERMISSION_NAME}'",
            f"permission-tree: {EXPECTED_PERMISSION_NAME}",
        ):
            with self.subTest(declaration=declaration):
                valid_lines = permissions_dump().splitlines()
                valid_lines[1] = declaration
                self.assertRejected(
                    xmltree(CANONICAL_ATTRIBUTE),
                    "unexpected, modified, or format-drifted",
                    permissions="\n".join(valid_lines) + "\n",
                )

    def test_requires_one_exact_package_line(self) -> None:
        self.assertRejected(
            xmltree(CANONICAL_ATTRIBUTE),
            "AAPT2 package name must be exactly",
            permissions=permissions_dump(
                package_name="example.wrong.package",
            ),
        )
        without_package = "\n".join(
            permissions_dump().splitlines()[1:]
        ) + "\n"
        self.assertRejected(
            xmltree(CANONICAL_ATTRIBUTE),
            "expected exactly one AAPT2 package line",
            permissions=without_package,
        )
        self.assertRejected(
            xmltree(CANONICAL_ATTRIBUTE),
            "expected exactly one AAPT2 package line",
            permissions=(
                permissions_dump()
                + f"package: {EXPECTED_PACKAGE_NAME}\n"
            ),
        )

    def test_requires_exact_used_permission_triplet(self) -> None:
        required = tuple(
            ("uses-permission", name)
            for name in sorted(EXPECTED_USED_PERMISSIONS)
        )
        for used in (
            required[:-1],
            required + (required[0],),
            required
            + (("uses-permission", "android.permission.READ_CONTACTS"),),
        ):
            with self.subTest(used=used):
                self.assertRejected(
                    xmltree(CANONICAL_ATTRIBUTE),
                    "expected exactly the AAPT2 typed uses-permission triplet",
                    permissions=permissions_dump(
                        used_permissions=used,
                    ),
                )

    def test_accepts_sdk_23_spelling_without_modifiers(self) -> None:
        used = tuple(
            (
                "uses-permission-sdk-23"
                if index == 1
                else "uses-permission",
                name,
            )
            for index, name in enumerate(
                sorted(EXPECTED_USED_PERMISSIONS)
            )
        )
        self.verify(
            xmltree(CANONICAL_ATTRIBUTE),
            permissions=permissions_dump(used_permissions=used),
        )

    def test_rejects_modifiers_implied_features_and_unknown_lines(self) -> None:
        internet = "android.permission.INTERNET"
        required = tuple(
            ("uses-permission", name)
            for name in sorted(EXPECTED_USED_PERMISSIONS)
            if name != internet
        )
        modified_cases = (
            (f"uses-permission: name='{internet}' maxSdkVersion='28'",),
            (f"uses-permission: name='{internet}' optional='true'",),
            (f"uses-permission-sdk-24: name='{internet}'",),
            ("uses-implied-permission: name='android.permission.CAMERA'",),
            ("required-feature: name='android.hardware.microphone'",),
            ("required-not-feature: name='android.hardware.telephony'",),
            ("unexpected: value",),
        )
        for extra_lines in modified_cases:
            with self.subTest(extra_lines=extra_lines):
                self.assertRejected(
                    xmltree(CANONICAL_ATTRIBUTE),
                    "unexpected, modified, or format-drifted",
                    permissions=permissions_dump(
                        used_permissions=required,
                        extra_lines=extra_lines,
                    ),
                )

    def test_rejects_unreadable_xmltree_utf8(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            xmltree_path = Path(temporary) / "apk-manifest.xmltree"
            xmltree_path.write_bytes(b"\xff")
            permissions_path = Path(temporary) / "apk-permissions.txt"
            permissions_path.write_text(
                permissions_dump(),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                Aapt2ManifestProtectionError,
                "cannot read AAPT2 manifest xmltree",
            ):
                verify_aapt2_manifest_protection(
                    xmltree_path,
                    permissions_path,
                )

    def test_rejects_unreadable_permissions_utf8(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            xmltree_path = Path(temporary) / "apk-manifest.xmltree"
            xmltree_path.write_text(
                xmltree(CANONICAL_ATTRIBUTE),
                encoding="utf-8",
            )
            permissions_path = Path(temporary) / "apk-permissions.txt"
            permissions_path.write_bytes(b"\xff")
            with self.assertRaisesRegex(
                Aapt2ManifestProtectionError,
                "cannot read AAPT2 permissions dump",
            ):
                verify_aapt2_manifest_protection(
                    xmltree_path,
                    permissions_path,
                )


if __name__ == "__main__":
    unittest.main()
