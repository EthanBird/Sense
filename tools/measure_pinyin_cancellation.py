#!/usr/bin/env python3
"""Compare confirmation latency on a dedicated, disposable Sense emulator profile.

Resets ONLY io.github.ethanbird.senseime.debug in the verified sense-input-quality AVD.
Use existing built editor/test APKs. InputConnection requests no personalized learning.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


PACKAGE = "io.github.ethanbird.senseime.debug"
FIXTURE = "io.github.ethanbird.senseime.inputqualityfixture"
METHOD = "rapidTypingAndImmediateSpaceCommitTheLatestSentenceOnce"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("current", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--adb", default="F:/Android/Sdk/platform-tools/adb.exe")
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--samples", type=int, default=3)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise ValueError("A dedicated emulator is required")
    if args.output.exists() or not 1 <= args.samples <= 30:
        raise ValueError("Use a new evidence directory and 1..30 samples")
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.output.name):
        raise ValueError("Use a simple evidence directory name")
    digests = {apk: hashlib.sha256(apk.read_bytes()).hexdigest() for apk in (args.baseline, args.current)}
    def adb(*parts):
        return subprocess.run([args.adb, "-s", args.serial, *map(str, parts)], check=True, capture_output=True).stdout.decode("utf-8", errors="replace")
    if adb("emu", "avd", "name").splitlines()[0].strip() != "sense-input-quality":
        raise ValueError("This runner resets only the sense-input-quality AVD's debug app")
    original_ime = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    args.output.mkdir(parents=True)
    result = {"schemaVersion": 1, "serial": args.serial,
              "sdk": adb("shell", "getprop", "ro.build.version.sdk").strip(),
              "scope": "dedicated emulator, wait for production runtime; fresh debug data per APK; no-learning editor; not cold-loading input or phone certification", "samples": []}
    try:
        for mode, apk in [("baseline", args.baseline), ("cancellable", args.current)]:
            digest = digests[apk]
            adb("install", "-r", apk)
            assert "Success" in adb("shell", "pm", "clear", PACKAGE)
            for index in range(args.samples):
                name = f"{args.output.name}-{mode}-{index + 1}"
                folder = args.output / name
                folder.mkdir()
                adb("shell", "am", "force-stop", PACKAGE)
                adb("shell", "atrace", "--async_start", "-b", "2048", "-a", f"{PACKAGE},{PACKAGE}:ime")
                try:
                    log = adb("shell", "am", "instrument", "-w", "-r", "-e", "evidenceRun", name,
                              "-e", "noLearning", "true", "-e", "class", f"{FIXTURE}.ExternalEditorInputTest#{METHOD}",
                              f"{FIXTURE}.test/androidx.test.runner.AndroidJUnitRunner")
                    (folder / "instrumentation.log").write_text(log, encoding="utf-8")
                finally:
                    remote = f"/data/local/tmp/{name}.trace"
                    adb("shell", "atrace", "--async_stop", "-o", remote)
                    adb("pull", remote, folder / "system.trace")
                    adb("shell", "rm", remote)
                adb("pull", f"/sdcard/Android/data/{FIXTURE}/files/input-quality/{name}", folder / "device")
                timing = (folder / "device" / f"{METHOD}.txt").read_text("utf-8")
                match = re.search(r"space_to_committed_editor_ms=(\d+)", timing)
                row = {"mode": mode, "sample": index + 1, "apkSha256": digest,
                       "passed": "OK (1 test)" in log, "spaceToEditorMs": int(match.group(1)) if match else None,
                       "artifactDirectory": name}
                result["samples"].append(row)
                (args.output / "measurements.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
                print(json.dumps(row), flush=True)
    finally:
        if original_ime not in ("", "null"):
            adb("shell", "ime", "set", original_ime)
    if not all(row["passed"] for row in result["samples"]):
        raise SystemExit("A measured input failed; inspect the retained evidence")


if __name__ == "__main__":
    main()
