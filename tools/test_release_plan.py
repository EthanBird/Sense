#!/usr/bin/env python3

from __future__ import annotations

import base64
import hashlib
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from release_plan import (
    AndroidVersion,
    ReleasePlanError,
    decide_release,
    parse_android_version,
)


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "release_plan.py"
WORKFLOW = ROOT / ".github" / "workflows" / "android.yml"
APP_BUILD = ROOT / "app" / "build.gradle.kts"
OFFLINE_VERIFY = ROOT / "tools" / "offline_verify.sh"
OFFLINE_MANIFEST = ROOT / "tools" / "offline" / "AndroidManifest.xml"
RELEASE_NOTES = ROOT / "docs" / "releases" / "v0.4.5.beta.1.md"
RELEASE_CERT = (
    ROOT
    / "docs"
    / "releases"
    / "signing"
    / "sense-release-v1-cert.pem"
)
AURORA_DEVICE_TEST = (
    ROOT
    / "ime-ui"
    / "src"
    / "androidTest"
    / "kotlin"
    / "io"
    / "github"
    / "ethanbird"
    / "senseime"
    / "ui"
    / "SkillAuroraDevicePerformanceTest.kt"
)
CURRENT_SHA = "a" * 40
OLD_SHA = "b" * 40


def gradle_version(name: str = "0.4.2", code: int = 17) -> str:
    return f"""
android {{
    defaultConfig {{
        versionCode = {code}
        versionName = "{name}"
    }}
    buildTypes {{
        debug {{
            versionNameSuffix = "-debug"
        }}
    }}
}}
"""


class AndroidVersionParsingTest(unittest.TestCase):
    def test_extracts_one_literal_default_version(self) -> None:
        self.assertEqual(
            AndroidVersion(name="0.4.2", code=17),
            parse_android_version(gradle_version()),
        )

    def test_commented_versions_are_ignored(self) -> None:
        text = '// versionName = "9.9.9"\n// versionCode = 999\n' + gradle_version()
        self.assertEqual("0.4.2", parse_android_version(text).name)

    def test_dynamic_version_is_rejected(self) -> None:
        text = gradle_version().replace('versionName = "0.4.2"', "versionName = releaseName")
        with self.assertRaisesRegex(ReleasePlanError, "literal versionName"):
            parse_android_version(text)

    def test_duplicate_version_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "found 2"):
            parse_android_version(gradle_version() + '\nversionName = "0.4.2"\n')

    def test_unsupported_version_name_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "not a supported"):
            parse_android_version(gradle_version(name="next"))

    def test_dotted_prerelease_version_is_supported(self) -> None:
        self.assertEqual(
            AndroidVersion(name="0.4.5.beta.1", code=22),
            parse_android_version(gradle_version(name="0.4.5.beta.1", code=22)),
        )

    def test_nonpositive_version_code_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "must be positive"):
            parse_android_version(gradle_version(code=0))


class ReleaseDecisionTest(unittest.TestCase):
    def decide(
        self,
        *,
        previous: AndroidVersion = AndroidVersion("0.4.2", 17),
        current: AndroidVersion = AndroidVersion("0.4.2", 17),
        release_tag: str = "v0.4.2",
        release_apk: str = "Sense-v0.4.2.apk",
        current_sha: str = CURRENT_SHA,
        tag_target: str | None = OLD_SHA,
    ):
        return decide_release(
            previous=previous,
            current=current,
            release_tag=release_tag,
            release_apk=release_apk,
            current_sha=current_sha,
            tag_target=tag_target,
        )

    def test_unchanged_version_skips_before_old_tag_collision(self) -> None:
        decision = self.decide(tag_target=OLD_SHA)
        self.assertEqual("SKIPPED_VERSION_UNCHANGED", decision.status)
        self.assertFalse(decision.should_release)

    def test_version_bump_with_missing_tag_releases(self) -> None:
        decision = self.decide(
            current=AndroidVersion("0.4.3", 18),
            release_tag="v0.4.3",
            release_apk="Sense-v0.4.3.apk",
            tag_target=None,
        )
        self.assertEqual("RELEASE_NEW_TAG", decision.status)
        self.assertTrue(decision.should_release)

    def test_version_bump_with_same_sha_tag_is_idempotent(self) -> None:
        decision = self.decide(
            current=AndroidVersion("0.4.3", 18),
            release_tag="v0.4.3",
            release_apk="Sense-v0.4.3.apk",
            tag_target=CURRENT_SHA,
        )
        self.assertEqual("RELEASE_IDEMPOTENT_TAG", decision.status)

    def test_version_bump_with_foreign_tag_fails_closed(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "already targets"):
            self.decide(
                current=AndroidVersion("0.4.3", 18),
                release_tag="v0.4.3",
                release_apk="Sense-v0.4.3.apk",
                tag_target=OLD_SHA,
            )

    def test_name_only_bump_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "must change together"):
            self.decide(
                current=AndroidVersion("0.4.3", 17),
                release_tag="v0.4.3",
                release_apk="Sense-v0.4.3.apk",
            )

    def test_code_only_bump_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "must change together"):
            self.decide(current=AndroidVersion("0.4.2", 18))

    def test_version_code_regression_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "must increase"):
            self.decide(
                current=AndroidVersion("0.4.1", 16),
                release_tag="v0.4.1",
                release_apk="Sense-v0.4.1.apk",
            )

    def test_release_tag_must_match_version(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "RELEASE_TAG"):
            self.decide(release_tag="v0.4.1")

    def test_release_apk_must_match_version(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "RELEASE_APK"):
            self.decide(release_apk="Sense.apk")

    def test_invalid_current_sha_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "current SHA"):
            self.decide(current_sha="main")

    def test_invalid_tag_target_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "tag target"):
            self.decide(tag_target="refs/tags/v0.4.2")


class ReleasePlanCliTest(unittest.TestCase):
    def test_cli_writes_auditable_skip_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            previous = root / "previous.gradle.kts"
            current = root / "current.gradle.kts"
            github_output = root / "github-output"
            summary = root / "summary.md"
            previous.write_text(gradle_version(), encoding="utf-8")
            current.write_text(gradle_version(), encoding="utf-8")

            result = subprocess.run(
                (
                    sys.executable,
                    str(SCRIPT),
                    "--previous",
                    str(previous),
                    "--current",
                    str(current),
                    "--release-tag",
                    "v0.4.2",
                    "--release-apk",
                    "Sense-v0.4.2.apk",
                    "--current-sha",
                    CURRENT_SHA,
                    "--tag-target",
                    OLD_SHA,
                    "--github-output",
                    str(github_output),
                    "--step-summary",
                    str(summary),
                ),
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('"status": "SKIPPED_VERSION_UNCHANGED"', result.stdout)
            self.assertIn("should_release=false", github_output.read_text())
            self.assertIn("status=SKIPPED_VERSION_UNCHANGED", github_output.read_text())
            self.assertIn("SKIPPED_VERSION_UNCHANGED", summary.read_text())

    def test_cli_rejection_does_not_create_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            previous = root / "previous.gradle.kts"
            current = root / "current.gradle.kts"
            github_output = root / "github-output"
            previous.write_text(gradle_version(), encoding="utf-8")
            current.write_text(gradle_version(name="0.4.3", code=17), encoding="utf-8")

            result = subprocess.run(
                (
                    sys.executable,
                    str(SCRIPT),
                    "--previous",
                    str(previous),
                    "--current",
                    str(current),
                    "--release-tag",
                    "v0.4.3",
                    "--release-apk",
                    "Sense-v0.4.3.apk",
                    "--current-sha",
                    CURRENT_SHA,
                    "--github-output",
                    str(github_output),
                ),
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(2, result.returncode)
            self.assertIn("RELEASE_PLAN_REJECTED", result.stderr)
            self.assertFalse(github_output.exists())


class WorkflowContractTest(unittest.TestCase):
    def test_release_identity_matches_current_android_version(self) -> None:
        current = parse_android_version(
            APP_BUILD.read_text(encoding="utf-8"),
            str(APP_BUILD),
        )
        self.assertEqual(AndroidVersion(name="0.4.5.beta.1", code=22), current)

        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("RELEASE_TAG: v0.4.5.beta.1", workflow)
        self.assertIn("RELEASE_APK: Sense-v0.4.5.beta.1.apk", workflow)
        self.assertEqual(
            2,
            workflow.count(
                "versionCode='22' versionName='0.4.5.beta.1'",
            ),
        )
        self.assertIn("--notes-file docs/releases/v0.4.5.beta.1.md", workflow)
        self.assertIn("--prerelease", workflow)
        self.assertTrue(RELEASE_NOTES.is_file())
        offline_verify = OFFLINE_VERIFY.read_text(encoding="utf-8")
        self.assertIn("--version-code 22", offline_verify)
        self.assertIn("--version-name 0.4.5.beta.1", offline_verify)
        self.assertIn(
            "versionCode='22' versionName='0.4.5.beta.1'",
            offline_verify,
        )

    def test_release_signer_is_persistent_and_pinned(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        app_build = APP_BUILD.read_text(encoding="utf-8")
        pin_match = re.search(
            r"^\s*RELEASE_CERT_SHA256:\s*([0-9a-f]{64})\s*$",
            workflow,
            flags=re.MULTILINE,
        )
        self.assertIsNotNone(pin_match)
        pinned_digest = pin_match.group(1)

        pem = RELEASE_CERT.read_text(encoding="ascii")
        certificate_der = base64.b64decode(
            "".join(
                line
                for line in pem.splitlines()
                if not line.startswith("-----")
            ),
            validate=True,
        )
        self.assertEqual(
            pinned_digest,
            hashlib.sha256(certificate_der).hexdigest(),
        )
        for variable in (
            "SENSE_RELEASE_STORE_FILE",
            "SENSE_RELEASE_STORE_PASSWORD",
            "SENSE_RELEASE_KEY_ALIAS",
            "SENSE_RELEASE_KEY_PASSWORD",
        ):
            self.assertIn(variable, app_build)
        self.assertIn('storeType = "PKCS12"', app_build)
        self.assertIn(
            'signingConfig = signingConfigs.getByName("release")',
            app_build,
        )
        self.assertNotIn(
            "app/build/outputs/apk/benchmark -type f "
            "-name '*.apk' -print -quit",
            workflow.split("\n  release-0-4-5-beta-1:\n", 1)[1],
        )

    def test_workflow_uses_push_before_and_release_job_output(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        release_job = workflow.split(
            "\n  release-0-4-5-beta-1:\n",
            1,
        )[1]
        self.assertIn("release_plan:", workflow)
        self.assertIn("python3 tools/test_release_plan.py", workflow)
        self.assertIn("${{ github.event.before }}", workflow)
        self.assertIn("fetch-depth: 0", workflow)
        self.assertIn("needs: [verify, device]", workflow)
        self.assertIn("needs: [verify, package_release]", workflow)
        self.assertIn(
            "needs: [verify, package_release, release_plan]",
            workflow,
        )
        self.assertIn(
            "needs.release_plan.outputs.should_release == 'true'",
            workflow,
        )
        self.assertIn("\n    environment: release\n", release_job)
        self.assertEqual(
            1,
            workflow.count("name: sense-v0.4.5.beta.1-clean-apks"),
        )
        self.assertIn(
            "apk=$(find app/build/outputs/apk/release "
            "-type f -name '*.apk' -print -quit)",
            workflow,
        )
        self.assertIn(":app:assembleRelease", workflow)
        self.assertIn("SENSE_RELEASE_KEYSTORE_BASE64", workflow)
        self.assertIn("SENSE_RELEASE_STORE_PASSWORD", workflow)
        self.assertIn("SENSE_RELEASE_KEY_ALIAS", workflow)
        self.assertIn("SENSE_RELEASE_KEY_PASSWORD", workflow)
        self.assertIn("RELEASE_CERT_SHA256:", workflow)
        self.assertIn(
            "Signer #1 certificate SHA-256 digest: $RELEASE_CERT_SHA256",
            workflow,
        )
        self.assertNotIn('git show "HEAD^:', workflow)

    def test_release_path_executes_android_device_gates(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        aurora_gate = AURORA_DEVICE_TEST.read_text(encoding="utf-8")
        device_job = workflow.split("\n  device:\n", 1)[1].split(
            "\n  package_release:\n",
            1,
        )[0]
        self.assertIn("device:\n    name: Execute Android device gates", workflow)
        self.assertIn(
            "ReactiveCircus/android-emulator-runner@"
            "e89f39f1abbbd05b1113a29cf4db69e7540cae5a",
            workflow,
        )
        self.assertIn("api-level: 36", workflow)
        self.assertIn("disable-animations: false", workflow)
        self.assertIn(":ime-service:assembleDebugAndroidTest", workflow)
        for task in (
            ":ai-runtime:connectedDebugAndroidTest",
            ":ime-service:connectedDebugAndroidTest",
            ":ime-ui:connectedDebugAndroidTest",
            ":app:connectedDebugAndroidTest",
        ):
            self.assertIn(task, device_job)
        self.assertIn("--continue", device_job)
        self.assertNotIn("continue-on-error:", device_job)
        self.assertNotIn("|| true", device_job)
        self.assertNotIn("MIN_OVERLAY_DRAWS_PER_SAMPLE", aurora_gate)
        self.assertNotIn("MIN_WINDOW_FRAMES_PER_SAMPLE", aurora_gate)
        for contract in (
            "LIVENESS_CHECKPOINT_MILLIS = 5_000L",
            "MAX_CALLBACK_DRAW_SKEW_PER_SLICE = 1L",
            "MAX_DRAW_REPORT_SKEW_PER_SLICE = 2L",
            "fixedPhysicalAuroraMeetsAbsoluteP95AndFrameRateGate",
            "MAX_FIXED_DEVICE_TOTAL_P95_NANOS = 32L * NANOS_PER_MILLI",
            "MIN_FIXED_DEVICE_TARGET_PERCENT = 80L",
            "instrumentationArguments.getString(ARG_PHYSICAL_GATE) == \"true\"",
        ):
            self.assertIn(contract, aurora_gate)
        self.assertIn(
            "ime-service/build/outputs/androidTest-results/connected/**",
            workflow,
        )
        self.assertIn(
            "ime-service/build/reports/androidTests/connected/**",
            workflow,
        )

    def test_packaged_permission_allowlist_includes_exact_androidx_signature_permission(
        self,
    ) -> None:
        permission = (
            "io.github.ethanbird.senseime."
            "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        workflow = WORKFLOW.read_text(encoding="utf-8")
        offline_verify = OFFLINE_VERIFY.read_text(encoding="utf-8")

        self.assertEqual(
            2,
            workflow.count(f"-e '{permission}'"),
        )
        self.assertEqual(
            1,
            offline_verify.count(f'-e "{permission}"'),
        )
        self.assertEqual(
            3,
            workflow.count("tools/verify_manifest_permissions.py"),
        )
        self.assertEqual(
            1,
            offline_verify.count("tools/verify_manifest_permissions.py"),
        )
        self.assertEqual(
            2,
            workflow.count("--packaged"),
        )
        self.assertEqual(
            1,
            offline_verify.count("--packaged"),
        )
        self.assertEqual(
            1,
            workflow.count("tools/test_verify_manifest_permissions.py"),
        )
        self.assertEqual(
            1,
            offline_verify.count("tools/test_verify_manifest_permissions.py"),
        )
        self.assertEqual(
            2,
            workflow.count("tools/verify_aapt2_manifest_protection.py"),
        )
        self.assertEqual(
            1,
            offline_verify.count(
                "tools/verify_aapt2_manifest_protection.py",
            ),
        )
        self.assertEqual(
            1,
            workflow.count(
                "tools/test_verify_aapt2_manifest_protection.py",
            ),
        )
        self.assertEqual(
            1,
            offline_verify.count(
                "tools/test_verify_aapt2_manifest_protection.py",
            ),
        )
        self.assertEqual(
            2,
            workflow.count(
                'dump xmltree "$apk" --file AndroidManifest.xml',
            ),
        )
        self.assertEqual(
            1,
            offline_verify.count(
                'dump xmltree "$APK" --file AndroidManifest.xml',
            ),
        )
        self.assertIn("/tmp/apk-manifest.xmltree", workflow)
        self.assertIn("release/apk-manifest.xmltree", workflow)
        self.assertIn('"$OUT/apk-manifest.xmltree"', offline_verify)
        self.assertEqual(
            1,
            workflow.count("--permissions /tmp/apk-permissions.txt"),
        )
        self.assertEqual(
            1,
            workflow.count("--permissions release/apk-permissions.txt"),
        )
        self.assertEqual(
            1,
            offline_verify.count(
                '--permissions "$OUT/apk-permissions.txt"',
            ),
        )
        merged_manifest_step = workflow.split(
            "      - name: Verify AI process and dependency boundaries\n",
            maxsplit=1,
        )[1].split(
            "      - name: Verify M0 through M6 host benchmarks\n",
            maxsplit=1,
        )[0]
        self.assertIn(
            'tools/verify_manifest_permissions.py "$merged_manifest"',
            merged_manifest_step,
        )
        self.assertNotIn(
            "verify_aapt2_manifest_protection.py",
            merged_manifest_step,
        )
        self.assertNotIn("custom_permission_declarations", workflow)
        self.assertNotIn("custom_permission_declarations", offline_verify)
        self.assertEqual(
            2,
            workflow.count(
                "AndroidX signature receiver permission is missing",
            ),
        )
        self.assertEqual(
            1,
            offline_verify.count(
                "AndroidX signature receiver permission is missing",
            ),
        )
        self.assertEqual(
            2,
            workflow.count("Release gate failed: unexpected APK permissions"),
        )
        self.assertEqual(
            1,
            offline_verify.count("Release gate failed: unexpected APK permissions"),
        )

    def test_offline_manifest_declares_exact_permission_triplet(self) -> None:
        android = "{http://schemas.android.com/apk/res/android}"
        root = ET.parse(OFFLINE_MANIFEST).getroot()
        dynamic_receiver_permission = (
            "io.github.ethanbird.senseime."
            "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        custom_permissions = root.findall("permission")
        self.assertEqual(1, len(custom_permissions))
        self.assertEqual(
            dynamic_receiver_permission,
            custom_permissions[0].get(android + "name"),
        )
        self.assertEqual(
            "signature",
            custom_permissions[0].get(android + "protectionLevel"),
        )
        used_permissions = [
            child.get(android + "name")
            for child in root
            if child.tag.startswith("uses-permission")
        ]
        self.assertEqual(
            {
                "android.permission.INTERNET",
                "android.permission.RECORD_AUDIO",
                dynamic_receiver_permission,
            },
            set(used_permissions),
        )
        self.assertEqual(3, len(used_permissions))

    def test_publish_path_peels_and_rechecks_the_remote_tag(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        release_step = workflow.split(
            "      - name: Create Sense 0.4.5.beta.1 prerelease",
            maxsplit=1,
        )[1]
        self.assertIn(
            "git/matching-refs/tags/$RELEASE_TAG",
            release_step,
        )
        self.assertIn(
            "git/matching-refs/heads/$RELEASE_TAG",
            release_step,
        )
        self.assertIn(
            "repos/$GITHUB_REPOSITORY/commits/$RELEASE_TAG",
            release_step,
        )
        self.assertNotIn("targetCommitish", release_step)
        self.assertNotIn("git/ref/tags/$RELEASE_TAG", release_step)
        self.assertGreaterEqual(release_step.count("assert_remote_tag_target"), 4)
        self.assertLess(
            release_step.index('gh release create "$RELEASE_TAG"'),
            release_step.index('gh release upload "$RELEASE_TAG"'),
        )


if __name__ == "__main__":
    unittest.main()
