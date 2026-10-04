"""Bounded-heap real-editor acceptance on the dedicated API29 userdebug AVD.

Does not change ART properties. A fresh zygote must already use the declared
budget; both the fixture and the actual IME report and assert Runtime.maxMemory.
Only the disposable Sense Debug profile is cleared. No physical device target.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from test_input_configuration import validate_target


CASES = (
    ("preflight", "ExternalEditorInputTest#touchscreenPinyinCandidateCommitsThroughExternalInputConnection", 1),
    ("ordinary", "ExternalEditorInputTest", 10),
    ("learning", "ExternalEditorLearningTest", 6),
    ("context", "ExternalEditorContextTest", 8),
    ("correction", "ExternalEditorCorrectionTest", 10),
    ("boundary", "ExternalEditorBoundaryTest", 6),
    ("association", "ExternalEditorAssociationTest", 9),
)


def assert_process_heaps(text, heap_mib):
    fixture = re.findall(r"^fixtureMaxHeapMiB=(\d+)$", text, re.M)
    ime = re.findall(r"^imeRuntime=.*?maxHeapMiB=(\d+)(?:\s|$)", text, re.M)
    if fixture != [str(heap_mib)] or ime != [str(heap_mib)]:
        raise ValueError("Missing or mismatched actual process heap evidence")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("output", type=Path)
    p.add_argument("--adb", default="F:/Android/Sdk/platform-tools/adb.exe")
    p.add_argument("--serial", default="emulator-5584")
    p.add_argument("--heap-mib", type=int, choices=(112,), default=112)
    a = p.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", a.serial):
        raise ValueError("Dedicated emulator serial required")
    if a.output.exists() or not re.fullmatch(r"[a-zA-Z0-9_-]+", a.output.name):
        raise ValueError("Fresh, simply named evidence folder required")

    def adb(*args):
        return subprocess.check_output([a.adb, "-s", a.serial, *map(str, args)],
                                       text=True, encoding="utf-8", timeout=90).strip()

    avd = adb("emu", "avd", "name").splitlines()[0]
    sdk = adb("shell", "getprop", "ro.build.version.sdk")
    validate_target(a.serial, avd, sdk, "sense-input-quality-api29-google")
    if adb("shell", "getprop", "dalvik.vm.heapgrowthlimit") != f"{a.heap_mib}m":
        raise ValueError("Requested ART growth limit is not configured")
    root = Path(__file__).resolve().parents[1]
    a.output.mkdir(parents=True)
    fixture = "io.github.ethanbird.senseime.inputqualityfixture"
    app = "io.github.ethanbird.senseime.debug"
    apks = (
        (app, "app/build/outputs/apk/debug/app-debug.apk"),
        (fixture, "input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk"),
        (fixture + ".test", "input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk"),
    )
    result = {"schemaVersion": 1, "serial": a.serial, "avd": avd, "sdk": sdk,
              "heapMiB": a.heap_mib, "apks": {}, "runs": [],
              "scope": "50 real-editor executions at a fixed application heap; not latency or physical-device certification"}
    settings = ("default_input_method", "show_ime_with_hard_keyboard")
    before = {key: adb("shell", "settings", "get", "secure", key) for key in settings}
    result["settingsBefore"] = before

    def save():
        (a.output / "result.json").write_bytes((json.dumps(result, indent=2) + "\n").encode())

    try:
        for package, path in apks:
            with (root / path).open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            result["apks"][package] = digest
            print(adb("install", "-r", root / path), flush=True)
            remote = adb("shell", "pm", "path", package)
            if not remote.startswith("package:") or "\n" in remote:
                raise ValueError("Expected one installed base APK")
            if adb("shell", "sha256sum", remote.removeprefix("package:")).split()[0] != digest:
                raise ValueError("Installed APK identity mismatch")
        save()
        for name, clazz, count in CASES:
            folder = a.output / name
            folder.mkdir()
            run = a.output.name + "-" + name
            if name in ("preflight", "learning"):
                if adb("shell", "pm", "clear", app) != "Success":
                    raise ValueError("Disposable Debug profile was not cleared")
                barrier = adb("shell", "am", "wait-for-broadcast-idle")
                if "All broadcast queues are idle" not in barrier:
                    raise ValueError("Package-stop broadcast barrier failed")
            args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", fixture + "." + clazz,
                    "-e", "evidenceRun", run, "-e", "minimumHeapMiB", str(a.heap_mib),
                    "-e", "maximumHeapMiB", str(a.heap_mib)]
            if name in ("correction", "boundary", "association"):
                args += ["-e", "noLearning", "true"]
            args += [fixture + ".test/androidx.test.runner.AndroidJUnitRunner"]
            with (folder / "instrumentation.log").open("wb") as log:
                subprocess.run([a.adb, "-s", a.serial, *args], stdout=log,
                               stderr=subprocess.STDOUT, check=True, timeout=360)
            adb("pull", f"/sdcard/Android/data/{fixture}/files/input-quality/{run}/", folder / "device")
            text = (folder / "instrumentation.log").read_text("utf-8", errors="replace")
            passed = bool(re.search(rf"OK \({count} tests?\)", text)) and not re.search(
                "FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED", text)
            environments = list((folder / "device").glob("**/*-environment.txt"))
            heap_error = None
            try:
                if len(environments) != count:
                    raise ValueError("Missing per-test environment evidence")
                for path in environments:
                    assert_process_heaps(path.read_text("utf-8"), a.heap_mib)
            except ValueError as error:
                heap_error = str(error)
            row = {"name": name, "expectedTests": count, "passed": passed and heap_error is None,
                   "processHeapError": heap_error}
            result["runs"].append(row)
            save()
            print(json.dumps(row), flush=True)
            if not row["passed"]:
                raise ValueError("Fixed-budget acceptance failed; retain evidence instead of retrying")
    finally:
        for key, value in before.items():
            if key == "default_input_method" and value not in ("", "null"):
                adb("shell", "ime", "set", value)
            elif value in ("", "null"):
                adb("shell", "settings", "delete", "secure", key)
            else:
                adb("shell", "settings", "put", "secure", key, value)
        result["settingsRestored"] = {key: adb("shell", "settings", "get", "secure", key) for key in settings}
        (a.output / "logcat.txt").write_text(adb("logcat", "-d", "-t", "2000"), encoding="utf-8")
        save()
        if result["settingsRestored"] != before:
            raise ValueError("Input settings were not restored exactly")


if __name__ == "__main__":
    main()
