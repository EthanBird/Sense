#!/usr/bin/env python3

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from verify_manifest_permissions import (
    DYNAMIC_RECEIVER_PERMISSION,
    ManifestPermissionError,
    verify_manifest_permissions,
)


ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"


def manifest(
    *,
    declarations: tuple[tuple[str, str], ...] = (
        (DYNAMIC_RECEIVER_PERMISSION, "signature"),
    ),
    used_permissions: tuple[tuple[str, str], ...] = (
        ("uses-permission", "android.permission.INTERNET"),
        ("uses-permission", "android.permission.RECORD_AUDIO"),
        ("uses-permission", "android.permission.FOREGROUND_SERVICE"),
        (
            "uses-permission",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        ),
        ("uses-permission", "android.permission.POST_NOTIFICATIONS"),
        ("uses-permission", "android.permission.WAKE_LOCK"),
        ("uses-permission", DYNAMIC_RECEIVER_PERMISSION),
    ),
) -> str:
    declaration_xml = "\n".join(
        (
            f'    <permission android:name="{name}" '
            f'android:protectionLevel="{protection}" />'
        )
        for name, protection in declarations
    )
    uses_xml = "\n".join(
        f'    <{tag} android:name="{name}" />'
        for tag, name in used_permissions
    )
    return (
        f'<manifest xmlns:android="{ANDROID_NAMESPACE}">\n'
        f"{declaration_xml}\n"
        f"{uses_xml}\n"
        "</manifest>\n"
    )


class ManifestPermissionVerifierTest(unittest.TestCase):
    def verify(self, xml: str, *, packaged: bool = False) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "AndroidManifest.xml"
            path.write_text(xml, encoding="utf-8")
            verify_manifest_permissions(path, packaged=packaged)

    def assertRejected(
        self,
        xml: str,
        pattern: str,
        *,
        packaged: bool = False,
    ) -> None:
        with self.assertRaisesRegex(ManifestPermissionError, pattern):
            self.verify(xml, packaged=packaged)

    def test_source_manifest_requires_literal_signature(self) -> None:
        self.verify(manifest())
        for numeric in ("2", "0x2", "0x00000002"):
            with self.subTest(numeric=numeric):
                self.assertRejected(
                    manifest(
                        declarations=((DYNAMIC_RECEIVER_PERMISSION, numeric),),
                    ),
                    "literal value 'signature'",
                )

    def test_packaged_manifest_accepts_only_controlled_numeric_two_encodings(
        self,
    ) -> None:
        for protection in (
            "2",
            "0x2",
            "0X2",
            "0x02",
            "0x00000002",
            "0X00000002",
        ):
            with self.subTest(protection=protection):
                self.verify(
                    manifest(
                        declarations=(
                            (DYNAMIC_RECEIVER_PERMISSION, protection),
                        ),
                    ),
                    packaged=True,
                )

    def test_packaged_manifest_rejects_non_signature_values(self) -> None:
        for protection in (
            "signature",
            "normal",
            "dangerous",
            "signature|privileged",
            "18",
            "0x12",
            "0x00000012",
            "+2",
            " 2",
            "2 ",
            "02",
            "0x000000002",
        ):
            with self.subTest(protection=protection):
                self.assertRejected(
                    manifest(
                        declarations=(
                            (DYNAMIC_RECEIVER_PERMISSION, protection),
                        ),
                    ),
                    "packaged numeric value 2",
                    packaged=True,
                )

    def test_exactly_one_expected_custom_permission_is_required(self) -> None:
        cases = (
            ((), "exactly one custom permission"),
            (
                (
                    (DYNAMIC_RECEIVER_PERMISSION, "signature"),
                    (DYNAMIC_RECEIVER_PERMISSION, "signature"),
                ),
                "exactly one custom permission",
            ),
            (
                (("example.permission.UNEXPECTED", "signature"),),
                "unexpected custom permission",
            ),
            (
                (
                    (DYNAMIC_RECEIVER_PERMISSION, "signature"),
                    ("example.permission.UNEXPECTED", "signature"),
                ),
                "exactly one custom permission",
            ),
        )
        for declarations, pattern in cases:
            with self.subTest(declarations=declarations):
                self.assertRejected(
                    manifest(declarations=declarations),
                    pattern,
                )

    def test_missing_declaration_attributes_are_rejected(self) -> None:
        without_name = manifest().replace(
            f' android:name="{DYNAMIC_RECEIVER_PERMISSION}"',
            "",
            1,
        )
        self.assertRejected(
            without_name,
            "must contain only android:name and android:protectionLevel",
        )

        without_protection = manifest().replace(
            ' android:protectionLevel="signature"',
            "",
            1,
        )
        self.assertRejected(
            without_protection,
            "must contain only android:name and android:protectionLevel",
        )

    def test_extra_declaration_attributes_are_rejected(self) -> None:
        self.assertRejected(
            manifest().replace(
                ' android:protectionLevel="signature"',
                ' android:protectionLevel="signature" android:label="Sense"',
                1,
            ),
            "must contain only android:name and android:protectionLevel",
        )

    def test_permission_declaration_content_is_rejected(self) -> None:
        self.assertRejected(
            manifest().replace(
                'android:protectionLevel="signature" />',
                (
                    'android:protectionLevel="signature">'
                    "<meta-data />"
                    "</permission>"
                ),
                1,
            ),
            "custom permission declaration must be empty",
        )

    def test_uses_permission_set_rejects_missing_extra_and_duplicate(self) -> None:
        required = (
            ("uses-permission", "android.permission.INTERNET"),
            ("uses-permission", "android.permission.RECORD_AUDIO"),
            ("uses-permission", "android.permission.FOREGROUND_SERVICE"),
            (
                "uses-permission",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            ),
            ("uses-permission", "android.permission.POST_NOTIFICATIONS"),
            ("uses-permission", "android.permission.WAKE_LOCK"),
            ("uses-permission", DYNAMIC_RECEIVER_PERMISSION),
        )
        cases = (
            required[:-1],
            required
            + (("uses-permission", "android.permission.READ_CONTACTS"),),
            required + (required[0],),
            (
                ("uses-permission", "android.permission.INTERNET"),
                ("uses-permission", "android.permission.RECORD_AUDIO"),
                ("uses-permission", None),
            ),
        )
        for used_permissions in cases:
            with self.subTest(used_permissions=used_permissions):
                xml = manifest(
                    used_permissions=tuple(
                        (tag, "" if name is None else name)
                        for tag, name in used_permissions
                    ),
                )
                self.assertRejected(xml, "exact uses-permission set")

    def test_permission_scope_attributes_are_rejected(self) -> None:
        for extra_attribute in (
            'android:maxSdkVersion="28"',
            'android:usesPermissionFlags="neverForLocation"',
            'example="unexpected"',
        ):
            with self.subTest(extra_attribute=extra_attribute):
                self.assertRejected(
                    manifest().replace(
                        (
                            '<uses-permission android:name='
                            '"android.permission.RECORD_AUDIO"'
                        ),
                        (
                            '<uses-permission android:name='
                            '"android.permission.RECORD_AUDIO" '
                            f"{extra_attribute}"
                        ),
                        1,
                    ),
                    "must contain only android:name",
                )

    def test_conditional_permission_children_and_text_are_rejected(self) -> None:
        closing = (
            '<uses-permission android:name="android.permission.RECORD_AUDIO" />'
        )
        for replacement in (
            (
                '<uses-permission android:name="android.permission.RECORD_AUDIO">'
                '<required-feature android:name="android.hardware.microphone" />'
                "</uses-permission>"
            ),
            (
                '<uses-permission android:name="android.permission.RECORD_AUDIO">'
                "conditional"
                "</uses-permission>"
            ),
        ):
            with self.subTest(replacement=replacement):
                self.assertRejected(
                    manifest().replace(closing, replacement, 1),
                    "must not contain conditional children or text",
                )

    def test_sdk_qualified_uses_permission_is_counted(self) -> None:
        self.verify(
            manifest(
                used_permissions=(
                    ("uses-permission", "android.permission.INTERNET"),
                    ("uses-permission-sdk-23", "android.permission.RECORD_AUDIO"),
                    ("uses-permission", "android.permission.FOREGROUND_SERVICE"),
                    (
                        "uses-permission",
                        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                    ),
                    ("uses-permission", "android.permission.POST_NOTIFICATIONS"),
                    ("uses-permission", "android.permission.WAKE_LOCK"),
                    ("uses-permission", DYNAMIC_RECEIVER_PERMISSION),
                ),
            ),
        )

    def test_unknown_permission_bearing_element_is_rejected(self) -> None:
        for tag in (
            "uses-permission-shadow",
            "uses-permission-sdk-22",
            "uses-permission-sdk-24",
            "uses-permission-sdk-023",
            "uses-permission-sdk-999",
        ):
            with self.subTest(tag=tag):
                self.assertRejected(
                    manifest(
                        used_permissions=(
                            ("uses-permission", "android.permission.INTERNET"),
                            ("uses-permission", "android.permission.RECORD_AUDIO"),
                            (
                                tag,
                                DYNAMIC_RECEIVER_PERMISSION,
                            ),
                        ),
                    ),
                    "unexpected permission-bearing element",
                )

    def test_namespaced_permission_elements_are_rejected(self) -> None:
        namespaced_declaration = manifest().replace(
            "<manifest ",
            '<manifest xmlns:evil="urn:sense:test" ',
        ).replace("<permission ", "<evil:permission ", 1)
        self.assertRejected(
            namespaced_declaration,
            "unexpected or namespaced custom permission element",
        )

        namespaced_use = manifest().replace(
            "<manifest ",
            '<manifest xmlns:evil="urn:sense:test" ',
        ).replace("<uses-permission ", "<evil:uses-permission ", 1)
        self.assertRejected(
            namespaced_use,
            "permission-bearing element must be unnamespaced",
        )

    def test_permission_tree_and_group_are_rejected(self) -> None:
        for tag in ("permission-tree", "permission-group"):
            with self.subTest(tag=tag):
                self.assertRejected(
                    manifest().replace(
                        "</manifest>",
                        f'    <{tag} android:name="example.extra" />\n'
                        "</manifest>",
                    ),
                    "unexpected or namespaced custom permission element",
                )

    def test_malformed_manifest_is_rejected_with_path_context(self) -> None:
        self.assertRejected("<manifest>", "cannot parse manifest")

    def test_non_manifest_root_is_rejected(self) -> None:
        self.assertRejected(
            "<application />",
            "expected unnamespaced <manifest> root",
        )

    def test_namespaced_manifest_root_is_rejected(self) -> None:
        self.assertRejected(
            '<evil:manifest xmlns:evil="urn:sense:test" />',
            "expected unnamespaced <manifest> root",
        )


if __name__ == "__main__":
    unittest.main()
