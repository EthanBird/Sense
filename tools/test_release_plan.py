#!/usr/bin/env python3

from __future__ import annotations

import base64
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from release_plan import (
    AndroidVersion,
    ReleasePlanError,
    decide_release,
    parse_android_version,
)


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "release_plan.py"
LOCAL_RELEASE = ROOT / "tools" / "local_release.ps1"
APP_BUILD = ROOT / "app" / "build.gradle.kts"
RELEASE_NOTES = ROOT / "docs" / "releases" / "v0.4.5.beta.8.md"
RELEASE_CERT = (
    ROOT
    / "docs"
    / "releases"
    / "signing"
    / "sense-release-v1-cert.pem"
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


def certificate_sha256(path: Path) -> str:
    pem = path.read_text(encoding="ascii")
    certificate_der = base64.b64decode(
        "".join(
            line
            for line in pem.splitlines()
            if not line.startswith("-----")
        ),
        validate=True,
    )
    return hashlib.sha256(certificate_der).hexdigest()


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
        text = gradle_version().replace(
            'versionName = "0.4.2"',
            "versionName = releaseName",
        )
        with self.assertRaisesRegex(ReleasePlanError, "literal versionName"):
            parse_android_version(text)

    def test_duplicate_version_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "found 2"):
            parse_android_version(
                gradle_version() + '\nversionName = "0.4.2"\n'
            )

    def test_unsupported_version_name_is_rejected(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "not a supported"):
            parse_android_version(gradle_version(name="next"))

    def test_dotted_prerelease_version_is_supported(self) -> None:
        self.assertEqual(
            AndroidVersion(name="0.4.5.beta.8", code=29),
            parse_android_version(
                gradle_version(name="0.4.5.beta.8", code=29)
            ),
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

    def test_unchanged_version_with_matching_tag_is_idempotent(self) -> None:
        decision = self.decide(tag_target=CURRENT_SHA)
        self.assertEqual("RELEASE_IDEMPOTENT_TAG", decision.status)
        self.assertTrue(decision.should_release)

    def test_unchanged_version_with_foreign_tag_fails_closed(self) -> None:
        with self.assertRaisesRegex(ReleasePlanError, "already targets"):
            self.decide(tag_target=OLD_SHA)

    def test_unchanged_version_with_missing_tag_recovers_release(self) -> None:
        decision = self.decide(tag_target=None)
        self.assertEqual("RELEASE_RECOVER_MISSING_TAG", decision.status)
        self.assertTrue(decision.should_release)

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
    def run_plan(
        self,
        *,
        previous_text: str,
        current_text: str,
        tag: str,
        apk: str,
        tag_target: str = "MISSING",
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            previous = root / "previous.gradle.kts"
            current = root / "current.gradle.kts"
            previous.write_text(previous_text, encoding="utf-8")
            current.write_text(current_text, encoding="utf-8")
            return subprocess.run(
                (
                    sys.executable,
                    str(SCRIPT),
                    "--previous",
                    str(previous),
                    "--current",
                    str(current),
                    "--release-tag",
                    tag,
                    "--release-apk",
                    apk,
                    "--current-sha",
                    CURRENT_SHA,
                    "--tag-target",
                    tag_target,
                ),
                check=False,
                capture_output=True,
                text=True,
            )

    def test_cli_prints_auditable_idempotent_decision(self) -> None:
        result = self.run_plan(
            previous_text=gradle_version(),
            current_text=gradle_version(),
            tag="v0.4.2",
            apk="Sense-v0.4.2.apk",
            tag_target=CURRENT_SHA,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        self.assertEqual(
            {
                "current_version": "0.4.2 (17)",
                "previous_version": "0.4.2 (17)",
                "release_tag": "v0.4.2",
                "should_release": True,
                "status": "RELEASE_IDEMPOTENT_TAG",
                "tag_target": CURRENT_SHA,
            },
            json.loads(result.stdout),
        )

    def test_cli_rejection_is_nonzero_and_machine_readable(self) -> None:
        result = self.run_plan(
            previous_text=gradle_version(),
            current_text=gradle_version(name="0.4.3", code=17),
            tag="v0.4.3",
            apk="Sense-v0.4.3.apk",
        )
        self.assertEqual(2, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn("RELEASE_PLAN_REJECTED", result.stderr)

    def test_cli_recovers_unchanged_version_when_tag_is_missing(self) -> None:
        result = self.run_plan(
            previous_text=gradle_version(),
            current_text=gradle_version(),
            tag="v0.4.2",
            apk="Sense-v0.4.2.apk",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        decision = json.loads(result.stdout)
        self.assertTrue(decision["should_release"])
        self.assertEqual("RELEASE_RECOVER_MISSING_TAG", decision["status"])


class LocalReleaseContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.script = LOCAL_RELEASE.read_text(encoding="utf-8")
        cls.script_lower = cls.script.lower()
        cls.command_text = re.sub(
            r"\s+",
            " ",
            re.sub(r"[`\"']", "", cls.script_lower),
        )

    def test_release_identity_matches_android_configuration(self) -> None:
        current = parse_android_version(
            APP_BUILD.read_text(encoding="utf-8"),
            str(APP_BUILD),
        )
        self.assertEqual(AndroidVersion(name="0.4.5.beta.8", code=29), current)
        self.assertIn(current.tag, self.script)
        self.assertIn(current.apk_name, self.script)
        self.assertIn(
            'Sense v0.4.5.beta.8 - Streaming ASR, undo/redo, and Skills UX',
            self.script,
        )
        self.assertRegex(
            self.script,
            re.compile(r"versionCode\s*(?:=|:)?\s*29", re.IGNORECASE),
        )

    def test_release_notes_are_pinned_and_used_for_prerelease(self) -> None:
        self.assertTrue(RELEASE_NOTES.is_file())
        self.assertIn("# Sense v0.4.5.beta.8", RELEASE_NOTES.read_text("utf-8"))
        self.assertIn("v0.4.5.beta.8.md", self.script)
        self.assertIn("--notes-file", self.script)
        self.assertIn("--prerelease", self.script)

    def test_latency_sensitive_gates_run_before_sustained_host_benchmarks(self) -> None:
        m3 = self.script.index(":core-input:m3SentenceBenchmark")
        m4 = self.script.index(":core-input:m4CoreBenchmark")
        m0 = self.script.index(":core-input:m0HostBenchmark")
        self.assertLess(m3, m0)
        self.assertLess(m4, m0)

    def test_release_signer_is_persistent_and_digest_is_pinned(self) -> None:
        digest = certificate_sha256(RELEASE_CERT)
        self.assertEqual(
            "76db888ff42b04d52d4d19a573fe8f8df2fa3af0ab36bd6a08c6f70a8aace984",
            digest,
        )
        self.assertIn(digest, self.script_lower)

        app_build = APP_BUILD.read_text(encoding="utf-8")
        for variable in (
            "SENSE_RELEASE_STORE_FILE",
            "SENSE_RELEASE_STORE_PASSWORD",
            "SENSE_RELEASE_KEY_ALIAS",
            "SENSE_RELEASE_KEY_PASSWORD",
        ):
            self.assertIn(variable, app_build)
            self.assertIn(variable, self.script)
        self.assertIn('storeType = "PKCS12"', app_build)
        self.assertIn(
            'signingConfig = signingConfigs.getByName("release")',
            app_build,
        )

    def test_local_build_and_android_sdk_checks_are_mandatory(self) -> None:
        for command in (
            ":app:assembleRelease",
            "apksigner",
            "zipalign",
            "aapt2",
        ):
            self.assertIn(command.lower(), self.script_lower)
        self.assertIn("app/build/outputs/apk/release", self.script_lower)
        self.assertIn("$ErrorActionPreference = \"Stop\"", self.script)
        self.assertIn("Set-StrictMode", self.script)

        apksigner_window = re.compile(
            r"(?:apksigner.{0,500}\bverify\b|\bverify\b.{0,500}apksigner)",
            re.DOTALL,
        )
        aapt2_window = re.compile(
            r"(?:aapt2.{0,500}\bdump\b|\bdump\b.{0,500}aapt2)",
            re.DOTALL,
        )
        self.assertRegex(self.script_lower, apksigner_window)
        self.assertRegex(self.script_lower, aapt2_window)

    def test_local_release_runs_repository_validation(self) -> None:
        self.assertIn("tools/test_release_plan.py", self.script_lower)
        self.assertIn(
            "tools/verify_manifest_permissions.py",
            self.script_lower,
        )
        self.assertIn(
            "tools/verify_aapt2_manifest_protection.py",
            self.script_lower,
        )
        self.assertIn(
            "tools/verify_runtime_boundaries.py",
            self.script_lower,
        )

    def test_release_plan_is_used_for_safe_tag_decision(self) -> None:
        self.assertIn("tools/release_plan.py", self.script_lower)
        for option in (
            "--previous",
            "--current",
            "--release-tag",
            "--release-apk",
            "--current-sha",
            "--tag-target",
        ):
            self.assertIn(option, self.script_lower)
        self.assertIn("git rev-parse", self.command_text)

    def test_gh_publish_contract_creates_uploads_then_downloads(self) -> None:
        create = self.script_lower.index('"release", "create"')
        upload = self.script_lower.index('"release", "upload"')
        download = self.script_lower.index('"release", "download"')
        self.assertLess(create, upload)
        self.assertLess(upload, download)
        self.assertIn("--verify-tag", self.script_lower)
        self.assertIn("--clobber", self.script_lower)
        self.assertIn("--draft=false", self.script_lower)
        self.assertIn("isdraft", self.script_lower)
        self.assertIn('"remote", "get-url", "origin"', self.script_lower)
        self.assertIn("$downloadedsha256 -ne $localsha256", self.script_lower)
        self.assertIn(
            "-apk $downloadedapk",
            self.command_text,
        )
        self.assertIn("downloaded sha256sums.txt", self.script_lower)

    def test_publish_requires_full_gate_and_clean_origin_main(self) -> None:
        self.assertRegex(
            self.script_lower,
            re.compile(
                r"\$publish\s+-and\s+\(\$skipbuild\s+-or\s+\$skiptests\)",
            ),
        )
        self.assertIn('"status", "--porcelain=v1"', self.script_lower)
        self.assertIn(
            '"ls-remote", "--heads", "origin", "refs/heads/main"',
            self.script_lower,
        )
        for result in (
            "benchmarks/results/m0-host.json",
            "benchmarks/results/m1-pinyin.json",
            "benchmarks/results/m2-adaptive.json",
            "benchmarks/results/m3-sentence.json",
            "benchmarks/results/m4-core.json",
            "benchmarks/results/m5-mixed-input.json",
            "benchmarks/results/m6-input-polish.json",
        ):
            self.assertIn(result, self.script_lower)


if __name__ == "__main__":
    unittest.main()
