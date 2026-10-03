"""ABBA comparison of true system IME confirmations on the disposable input-quality AVD.

Resets only the verified AVD's Sense debug profile. Keeps failures, raw timings,
APK hashes and trace files; never calls decoder internals or seeds learned data.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess

PACKAGE = "io.github.ethanbird.senseime.debug"
FIXTURE = "io.github.ethanbird.senseime.inputqualityfixture"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("optimized", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--adb", default="F:/Android/Sdk/platform-tools/adb.exe")
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--burst", action="store_true", help="Diagnostic asynchronous touch cadence with main-thread editor timestamps")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or not re.fullmatch(r"[a-zA-Z0-9_-]+", args.output.name):
        raise ValueError("Use a dedicated emulator and simple evidence directory name")
    if args.output.exists():
        raise ValueError("Retain previous evidence; use a new directory")
    def adb(*parts):
        return subprocess.check_output([args.adb, "-s", args.serial, *map(str, parts)], timeout=300).decode("utf-8", errors="replace")
    if adb("emu", "avd", "name").splitlines()[0].strip() != "sense-input-quality":
        raise ValueError("This fixture resets only the sense-input-quality AVD's debug app")
    apks = {"baseline": args.baseline.resolve(), "optimized": args.optimized.resolve()}
    args.output.mkdir(parents=True)
    results = {"schemaVersion": 1, "scope": "True external-editor touch input; ready runtime, clean profile per block, no learning; fixed ABBA diagnostic, not phone certification",
               "serial": args.serial, "sdk": adb("shell", "getprop", "ro.build.version.sdk").strip(),
               "apkSha256": {mode: hashlib.sha256(path.read_bytes()).hexdigest() for mode, path in apks.items()},
               "runs": []}
    if args.burst:
        results["protocol"] = "async-touch-no-animation-wait-v1"
        results["scope"] = "Asynchronous system touch bursts without animation wait targeting 32ms key cadence; measured actual cadence and first expected text on external editor main thread. Clean profile per block, ready runtime, no learning. Not comparable to synchronous-injection timing or phone certification."
        results["installedFixtureApks"] = {}
        for package in [FIXTURE, FIXTURE + '.test']:
            paths = adb('shell', 'pm', 'path', package).strip().splitlines()
            if len(paths) != 1 or not paths[0].startswith('package:'):
                raise ValueError('Expected one installed fixture APK')
            results["installedFixtureApks"][package] = adb('shell', 'sha256sum', paths[0][8:]).split()[0]
    original = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    try:
        for block, mode in enumerate(["baseline", "optimized", "optimized", "baseline"]):
            name = f"{args.output.name}-{block + 1}-{mode}"
            folder = args.output / name
            folder.mkdir()
            assert "Success" in adb("install", "-r", apks[mode])
            assert "Success" in adb("shell", "pm", "clear", PACKAGE)
            adb("shell", "atrace", "--async_start", "-b", "4096", "-a", f"{PACKAGE},{PACKAGE}:ime")
            try:
                log = adb("shell", "am", "instrument", "-w", "-r", "-e", "evidenceRun", name,
                          "-e", "noLearning", "true", "-e", "class", f"{FIXTURE}." + ("ExternalEditorBurstInputTest" if args.burst else "ExternalEditorLatencyTest"),
                          f"{FIXTURE}.test/androidx.test.runner.AndroidJUnitRunner")
                (folder / "instrumentation.log").write_text(log, encoding="utf-8")
            finally:
                remote = f"/data/local/tmp/{name}.trace"
                adb("shell", "atrace", "--async_stop", "-o", remote)
                adb("pull", remote, folder / "system.trace")
                adb("shell", "rm", remote)
            adb("pull", f"/sdcard/Android/data/{FIXTURE}/files/input-quality/{name}", folder / "device")
            path = folder / "device/latency.tsv"
            rows = list(csv.DictReader(io.StringIO(path.read_text("utf-8")), delimiter="\t")) if path.exists() else []
            for row in rows:
                row["round"] = int(row["round"])
                row["spaceToEditorMs"] = int(row["spaceToEditorMs"])
                row["passed"] = row["passed"] == "true"
            passed = "OK (1 test)" in log and "FAILURES!!!" not in log and len(rows) == 16 and all(row["passed"] for row in rows)
            results["runs"].append({"block": block + 1, "mode": mode, "artifactDirectory": name, "passed": passed, "samples": rows})
            (args.output / "measurements.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps({"block": block + 1, "mode": mode, "passed": passed, "samples": len(rows),
                              "failedInputs": [row["query"] for row in rows if not row["passed"]]}), flush=True)
    finally:
        if original not in ("", "null"):
            adb("shell", "ime", "set", original)
    if not all(run["passed"] for run in results["runs"]):
        raise SystemExit("At least one workload failed; retained failures are not valid successful-latency samples")


if __name__ == "__main__":
    main()
