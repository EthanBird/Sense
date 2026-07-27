#!/usr/bin/env python3

from __future__ import annotations

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
WORKFLOW = ROOT / ".github" / "workflows" / "android.yml"
APP_BUILD = ROOT / "app" / "build.gradle.kts"
RELEASE_NOTES = ROOT / "docs" / "releases" / "v0.4.4.md"
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
        self.assertEqual(AndroidVersion(name="0.4.4", code=19), current)

        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("RELEASE_TAG: v0.4.4", workflow)
        self.assertIn("RELEASE_APK: Sense-v0.4.4.apk", workflow)
        self.assertEqual(
            2,
            workflow.count(
                "versionCode='19' versionName='0.4.4'",
            ),
        )
        self.assertIn("--notes-file docs/releases/v0.4.4.md", workflow)
        self.assertTrue(RELEASE_NOTES.is_file())

    def test_workflow_uses_push_before_and_release_job_output(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("release_plan:", workflow)
        self.assertIn("python3 tools/test_release_plan.py", workflow)
        self.assertIn("${{ github.event.before }}", workflow)
        self.assertIn("fetch-depth: 0", workflow)
        self.assertIn("needs: [verify, package_release]", workflow)
        self.assertIn(
            "needs: [verify, package_release, release_plan]",
            workflow,
        )
        self.assertIn(
            "needs.release_plan.outputs.should_release == 'true'",
            workflow,
        )
        self.assertEqual(
            2,
            workflow.count("name: sense-v0.4.4-clean-apks"),
        )
        self.assertIn(
            "apk=$(find artifacts -type f "
            "-path '*/benchmark/*.apk' -print -quit)",
            workflow,
        )
        self.assertNotIn('git show "HEAD^:', workflow)

    def test_publish_path_peels_and_rechecks_the_remote_tag(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        release_step = workflow.split(
            "      - name: Create Sense 0.4.4 release",
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
