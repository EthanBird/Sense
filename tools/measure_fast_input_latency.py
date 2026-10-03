"""Run one frozen shell-touch ABBA workload on the dedicated disposable AVD.

Only the Sense DEBUG profile is reset. Keep all blocks, including preparation
failures. Restore the development APK and original IME even after a failed run.
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess

PACKAGE = "io.github.ethanbird.senseime.debug"
FIXTURE = "io.github.ethanbird.senseime.inputqualityfixture"
HELPER = "/data/local/tmp/sense-input-quality-touch/classes.dex"


def sha(path):
    return hashlib.file_digest(Path(path).open("rb"), "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("protocol", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--adb", default="F:/Android/Sdk/platform-tools/adb.exe")
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or not re.fullmatch(r"[a-zA-Z0-9_-]+", args.output.name):
        raise ValueError("Dedicated emulator and simple run name required")
    if args.output.exists():
        raise ValueError("Evidence already exists; never overwrite a run")
    root = Path(__file__).resolve().parents[1]
    lock = json.loads(args.protocol.read_text("utf-8"))
    if lock["order"] != ["baseline", "optimized", "optimized", "baseline"]:
        raise ValueError("Unexpected frozen order")
    for mode, item in lock["apks"].items():
        if sha(item["path"]) != item["sha256"]:
            raise ValueError(f"Frozen APK changed: {mode}")
    for name, digest in lock["productionSourcePins"].items():
        if sha(root / name) != digest:
            raise ValueError(f"Production source changed: {name}")

    def adb(*parts, instrument=False):
        # Instrumentation has bounded assertions. Keep its host process live for
        # inspection rather than restart an experiment after an observation timeout.
        result = subprocess.run([args.adb, "-s", args.serial, *map(str, parts)],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=None if instrument else 300)
        output = result.stdout.decode("utf-8", errors="replace")
        if result.returncode:
            raise RuntimeError(f"adb {parts[:3]} exited {result.returncode}: {output}")
        return output

    if adb("emu", "avd", "name").splitlines()[0].strip() != "sense-input-quality":
        raise ValueError("Only the dedicated input-quality AVD may be used")
    if adb("shell", "sha256sum", HELPER).split()[0] != lock["helperSha256"]:
        raise ValueError("Frozen event source changed")
    args.output.mkdir(parents=True)
    (args.output / "protocol-lock.json").write_bytes(args.protocol.read_bytes())
    fixture_paths = {
        FIXTURE: root / "input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk",
        FIXTURE + ".test": root / "input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk",
    }
    original = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    results = dict(schemaVersion=1, protocol="shell-touch-v1", serial=args.serial,
                   scope="Fixed known-query ABBA, genuine 32ms-target system touch; clean debug profile, runtime ready, no learning. Not phone certification.",
                   startedAt=datetime.now(timezone.utc).isoformat(), protocolSha256=sha(args.protocol),
                   sdk=adb("shell", "getprop", "ro.build.version.sdk").strip(),
                   originalIme=original, apkSha256={m: v["sha256"] for m, v in lock["apks"].items()},
                   helperSha256=lock["helperSha256"], installedFixtureApks={}, runs=[], restoration={})
    sources = [Path(__file__), root / "tools/android-fixture/TouchBurst.java",
               root / "input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorFastLatencyTest.kt",
               root / "input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorTestFixture.kt"]
    results["testSourceSha256"] = {str(p.relative_to(root)).replace("\\", "/"): sha(p) for p in sources}

    def save():
        (args.output / "measurements.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    save()
    try:
        for package, path in fixture_paths.items():
            if "Success" not in adb("install", "-r", path):
                raise RuntimeError("Fixture installation failed")
            remote = adb("shell", "pm", "path", package).strip().removeprefix("package:")
            digest = adb("shell", "sha256sum", remote).split()[0]
            if digest != sha(path):
                raise ValueError("Installed fixture identity mismatch")
            results["installedFixtureApks"][package] = digest
        save()
        for block, mode in enumerate(lock["order"], 1):
            name = f"{args.output.name}-{block}-{mode}"
            folder = args.output / name
            folder.mkdir()
            run = dict(block=block, mode=mode, artifactDirectory=name, passed=False, samples=[], errors=[])
            results["runs"].append(run)
            print(json.dumps(dict(block=block, mode=mode, state="starting")), flush=True)
            save()
            trace_started = False
            try:
                install = adb("install", "-r", lock["apks"][mode]["path"])
                (folder / "install.log").write_text(install, encoding="utf-8")
                if "Success" not in install or "Success" not in adb("shell", "pm", "clear", PACKAGE):
                    raise RuntimeError("Debug profile preparation failed")
                remote = adb("shell", "pm", "path", PACKAGE).strip().removeprefix("package:")
                run["installedApkSha256"] = adb("shell", "sha256sum", remote).split()[0]
                if run["installedApkSha256"] != lock["apks"][mode]["sha256"]:
                    raise ValueError("Installed product identity mismatch")
                adb("shell", "atrace", "--async_start", "-b", "4096", "-a", f"{PACKAGE},{PACKAGE}:ime")
                trace_started = True
                log = adb("shell", "am", "instrument", "-w", "-r", "-e", "evidenceRun", name,
                          "-e", "noLearning", "true", "-e", "touchInjectorSha256", lock["helperSha256"],
                          "-e", "class", f"{FIXTURE}.ExternalEditorFastLatencyTest",
                          f"{FIXTURE}.test/androidx.test.runner.AndroidJUnitRunner", instrument=True)
                (folder / "instrumentation.log").write_text(log, encoding="utf-8")
                run["instrumentationPassed"] = "OK (1 test)" in log and "FAILURES!!!" not in log
            except Exception as error:
                run["errors"].append(repr(error))
            finally:
                if trace_started:
                    try:
                        remote = f"/data/local/tmp/{name}.trace"
                        adb("shell", "atrace", "--async_stop", "-o", remote)
                        adb("pull", remote, folder / "system.trace")
                        adb("shell", "rm", remote)
                    except Exception as error:
                        run["errors"].append("trace: " + repr(error))
                try:
                    adb("pull", f"/sdcard/Android/data/{FIXTURE}/files/input-quality/{name}", folder / "device")
                    path = folder / "device/latency.tsv"
                    rows = list(csv.DictReader(path.read_text("utf-8").splitlines(), delimiter="\t")) if path.exists() else []
                    for row in rows:
                        row["round"] = int(row["round"])
                        row["spaceToEditorMs"] = int(row["spaceToEditorMs"])
                        row["passed"] = row["passed"] == "true"
                    run["samples"] = rows
                    followup = folder / "device/continued-input.jsonl"
                    continued = [json.loads(line) for line in followup.read_text("utf-8").splitlines()] if followup.exists() else []
                    run["continuedCases"] = len(continued)
                    run["continuedPassed"] = len(continued) == 2 and all(r["passed"] for r in continued)
                except Exception as error:
                    run["errors"].append("evidence: " + repr(error))
                run["passed"] = (not run["errors"] and run.get("instrumentationPassed", False)
                                 and len(run["samples"]) == 16 and all(r["passed"] for r in run["samples"])
                                 and run.get("continuedPassed", False))
                save()
                print(json.dumps(dict(block=block, mode=mode, passed=run["passed"], samples=len(run["samples"]),
                                      continuedPassed=run.get("continuedPassed"), errors=run["errors"])), flush=True)
    finally:
        # Do not leave the older baseline installed after the last ABBA block.
        try:
            output = adb("install", "-r", lock["apks"]["optimized"]["path"])
            remote = adb("shell", "pm", "path", PACKAGE).strip().removeprefix("package:")
            results["restoration"]["apkSha256"] = adb("shell", "sha256sum", remote).split()[0]
            results["restoration"]["apkRestored"] = ("Success" in output and results["restoration"]["apkSha256"] == lock["apks"]["optimized"]["sha256"])
        finally:
            if original not in ("", "null"):
                adb("shell", "ime", "set", original)
            results["restoration"]["ime"] = adb("shell", "settings", "get", "secure", "default_input_method").strip()
            results["finishedAt"] = datetime.now(timezone.utc).isoformat()
            save()
    if len(results["runs"]) != 4 or not all(r["passed"] for r in results["runs"]):
        raise SystemExit("Incomplete or failed workload retained; no successful-latency promotion")


if __name__ == "__main__":
    main()
