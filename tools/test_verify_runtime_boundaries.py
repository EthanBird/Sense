#!/usr/bin/env python3

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from verify_runtime_boundaries import (
    NetworkDependencyLeak,
    RuntimeBoundaryError,
    find_network_dependency_leaks,
    verify_no_network_dependency_leaks,
    verify_runtime_manifest,
)


ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"


def valid_runtime_manifest() -> str:
    return f"""\
<manifest xmlns:android="{ANDROID_NAMESPACE}">
    <application>
        <activity
            android:name="io.github.ethanbird.senseime.SettingsActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name="io.github.ethanbird.senseime.brain.runtime.SenseAiBrainService"
            android:exported="false"
            android:process=":brain" />
        <service
            android:name="io.github.ethanbird.senseime.service.SenseInputMethodService"
            android:permission="android.permission.BIND_INPUT_METHOD">
            <intent-filter>
                <action android:name="android.view.InputMethod" />
            </intent-filter>
        </service>
    </application>
</manifest>
"""


class RuntimeManifestVerifierTest(unittest.TestCase):
    def verify(self, xml: str) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "AndroidManifest.xml"
            manifest.write_text(xml, encoding="utf-8")
            verify_runtime_manifest(manifest)

    def test_accepts_the_merged_runtime_component_contract(self) -> None:
        self.verify(valid_runtime_manifest())

    def assertRejected(self, xml: str, pattern: str) -> None:
        with self.assertRaisesRegex(RuntimeBoundaryError, pattern):
            self.verify(xml)

    def test_rejects_missing_duplicate_or_malformed_components(self) -> None:
        valid = valid_runtime_manifest()
        settings = """\
        <activity
            android:name="io.github.ethanbird.senseime.SettingsActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
"""
        brain = """\
        <service
            android:name="io.github.ethanbird.senseime.brain.runtime.SenseAiBrainService"
            android:exported="false"
            android:process=":brain" />
"""
        ime = """\
        <service
            android:name="io.github.ethanbird.senseime.service.SenseInputMethodService"
            android:permission="android.permission.BIND_INPUT_METHOD">
            <intent-filter>
                <action android:name="android.view.InputMethod" />
            </intent-filter>
        </service>
"""
        cases = (
            (valid.replace(settings, ""), "expected one .*SettingsActivity"),
            (
                valid.replace(settings, settings + settings),
                "expected one .*SettingsActivity",
            ),
            (valid.replace(brain, ""), "expected one .*SenseAiBrainService"),
            (
                valid.replace(brain, brain + brain),
                "expected one .*SenseAiBrainService",
            ),
            (valid.replace(ime, ""), "expected one .*SenseInputMethodService"),
            (
                valid.replace(ime, ime + ime),
                "expected one .*SenseInputMethodService",
            ),
            (
                valid.replace("    <application>\n", "").replace(
                    "    </application>\n",
                    "",
                ),
                "expected one application",
            ),
            ("<manifest>", "cannot parse manifest"),
        )
        for xml, pattern in cases:
            with self.subTest(pattern=pattern):
                self.assertRejected(xml, pattern)

    def test_rejects_runtime_attribute_and_intent_regressions(self) -> None:
        valid = valid_runtime_manifest()
        launcher_filter = """\
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
"""
        cases = (
            (
                valid.replace('android:exported="true"', 'android:exported="false"'),
                "SettingsActivity must be exported=true",
            ),
            (
                valid.replace(
                    launcher_filter,
                    launcher_filter + launcher_filter,
                ),
                "exactly one MAIN\\+LAUNCHER",
            ),
            (
                valid.replace("android.intent.action.MAIN", "fixture.MAIN"),
                "exactly one MAIN\\+LAUNCHER",
            ),
            (
                valid.replace("android.intent.category.LAUNCHER", "fixture.LAUNCHER"),
                "exactly one MAIN\\+LAUNCHER",
            ),
            (
                valid.replace('android:exported="false"', 'android:exported="true"'),
                "Brain service must be exported=false",
            ),
            (
                valid.replace('android:process=":brain"', 'android:process=":main"'),
                "Brain service must run in :brain",
            ),
            (
                valid.replace(
                    "android.permission.BIND_INPUT_METHOD",
                    "fixture.permission.BIND_INPUT_METHOD",
                ),
                "IME service must require BIND_INPUT_METHOD",
            ),
            (
                valid.replace("android.view.InputMethod", "fixture.InputMethod"),
                "IME service is missing InputMethod action",
            ),
        )
        for xml, pattern in cases:
            with self.subTest(pattern=pattern):
                self.assertRejected(xml, pattern)


class NetworkDependencyVerifierTest(unittest.TestCase):
    def test_reports_forbidden_kotlin_dependencies_with_file_and_line(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "ime-service"
            source = root / "src" / "main" / "Transport.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                "package fixture\n"
                "import java.net.URI\n"
                "val client = okhttp3.OkHttpClient()\n",
                encoding="utf-8",
            )
            (source.parent / "Ignored.java").write_text(
                "import javax.net.SocketFactory;\n",
                encoding="utf-8",
            )

            leaks = find_network_dependency_leaks((root,))

        self.assertEqual(
            leaks,
            (
                NetworkDependencyLeak(
                    path=source,
                    line_number=2,
                    line="import java.net.URI",
                    dependency="java.net.",
                ),
                NetworkDependencyLeak(
                    path=source,
                    line_number=3,
                    line="val client = okhttp3.OkHttpClient()",
                    dependency="okhttp",
                ),
            ),
        )

    def test_covers_all_forbidden_patterns_and_orders_results(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            ime_ui = repository / "ime-ui"
            ime_service = repository / "ime-service"
            ui_source = ime_ui / "src" / "B.kt"
            service_source = ime_service / "src" / "A.kt"
            ui_source.parent.mkdir(parents=True)
            service_source.parent.mkdir(parents=True)
            ui_source.write_text(
                "import retrofit2.Retrofit\n"
                "import javax.net.SocketFactory\n",
                encoding="utf-8",
            )
            service_source.write_text(
                "import java.net.URL\n"
                "import okhttp3.Request\n",
                encoding="utf-8",
            )

            leaks = find_network_dependency_leaks(
                (ime_service, ime_ui, ime_service),
            )

        self.assertEqual(
            [(leak.path.name, leak.line_number, leak.dependency) for leak in leaks],
            [
                ("A.kt", 1, "java.net."),
                ("A.kt", 2, "okhttp"),
                ("B.kt", 1, "retrofit"),
                ("B.kt", 2, "javax.net."),
            ],
        )

    def test_clean_kotlin_sources_pass_the_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Clean.kt").write_text(
                "package fixture\nclass Clean\n",
                encoding="utf-8",
            )
            verify_no_network_dependency_leaks((root,))

    def test_leaks_are_reported_together(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Leaky.kt").write_text(
                "import java.net.URL\nimport retrofit2.Retrofit\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                RuntimeBoundaryError,
                r"Leaky\.kt:1:import java\.net\.URL[\s\S]*"
                r"Leaky\.kt:2:import retrofit2\.Retrofit",
            ):
                verify_no_network_dependency_leaks((root,))

    def test_missing_roots_and_unreadable_kotlin_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(RuntimeBoundaryError, "does not exist"):
                find_network_dependency_leaks((root / "missing",))

            invalid = root / "Invalid.kt"
            invalid.write_bytes(b"\xff\xfe\x00")
            with self.assertRaisesRegex(
                RuntimeBoundaryError,
                "cannot read Kotlin source",
            ):
                find_network_dependency_leaks((invalid,))


if __name__ == "__main__":
    unittest.main()
