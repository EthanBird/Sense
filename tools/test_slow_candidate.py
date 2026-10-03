"""Deterministic external-editor slow-worker acceptance on the dedicated debug AVD.

Only the idle sense-candidate-decoder thread is suspended. No app patch, remote
test endpoint or production fault hook. This is failure injection, NOT latency
measurement. JDWP protocol: docs.oracle.com/en/java/javase/17/docs/specs/jdwp/.
"""
import argparse
import contextlib
import hashlib
import json
from pathlib import Path
import re
import socket
import struct
import subprocess
import time

PACKAGE = "io.github.ethanbird.senseime.debug"
FIXTURE = "io.github.ethanbird.senseime.inputqualityfixture"
METHODS = ["delayedChineseConfirmationPreservesBothWordsAndOrder", "enterExplicitlyKeepsRawDuringSlowWait",
           "deleteCanCorrectTheCompositionBeforeWorkerResumes", "editorSwitchInvalidatesDelayedConfirmationAndQueuedInput"]


class Jdwp:
    def __init__(self, port):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=10)
        self.sequence = 0
        try:
            self.socket.sendall(b"JDWP-Handshake")
            if self.read(14) != b"JDWP-Handshake":
                raise ValueError("Bad JDWP handshake")
            sizes = struct.unpack(">5I", self.command(1, 7))
            self.object_size = sizes[2]
            if not 1 <= self.object_size <= 8:
                raise ValueError("Unsupported JDWP object size")
        except BaseException:
            self.socket.close()
            raise

    def read(self, count):
        result = b""
        while len(result) < count:
            part = self.socket.recv(count - len(result))
            if not part:
                raise EOFError("JDWP disconnected")
            result += part
        return result

    def command(self, command_set, command, data=b""):
        self.sequence += 1
        self.socket.sendall(struct.pack(">IIBBB", 11 + len(data), self.sequence, 0, command_set, command) + data)
        while True:
            length, identity, flags, tail = struct.unpack(">IIBH", self.read(11))
            if not 11 <= length <= 16_000_000:
                raise ValueError("Invalid JDWP packet length")
            body = self.read(length - 11)
            if flags == 0x80:
                if identity != self.sequence or tail:
                    raise ValueError(f"JDWP reply mismatch/error {identity}/{tail}")
                return body
            # An unsolicited VM-start event can arrive on debugger attach. We never
            # install breakpoints and accept only non-suspending composite events.
            if flags != 0 or tail != (64 << 8 | 100) or not body or body[0] != 0:
                raise ValueError("Unexpected or suspending JDWP event")

    def idle_candidate_thread(self):
        body = self.command(1, 4)
        count = struct.unpack(">I", body[:4])[0]
        if len(body) != 4 + count * self.object_size:
            raise ValueError("Malformed thread list")
        for offset in range(4, len(body), self.object_size):
            identity = body[offset:offset + self.object_size]
            name = self.command(11, 1, identity)
            size = struct.unpack(">I", name[:4])[0]
            if name[4:4 + size].decode("utf-8") == "sense-candidate-decoder":
                status, suspended = struct.unpack(">II", self.command(11, 4, identity))
                if status != 4 or suspended != 0:
                    raise ValueError(f"Candidate thread must be parked and unsuspended: {status}/{suspended}")
                return identity
        raise ValueError("Candidate worker not found")

    def close(self):
        try:
            self.command(1, 6)  # Dispose resumes our outstanding thread-level suspend too.
        finally:
            self.socket.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--adb", default="F:/Android/Sdk/platform-tools/adb.exe")
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--apk", type=Path, help="Optional baseline debug APK; its SHA is recorded")
    parser.add_argument("--method", choices=METHODS, help="Optional single regression scenario")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or not re.fullmatch(r"[a-zA-Z0-9_-]+", args.output.name):
        raise ValueError("Use a dedicated emulator and simple new evidence directory name")
    if args.output.exists():
        raise ValueError("Retain previous evidence; use a new directory")
    def adb(*parts):
        return subprocess.check_output([args.adb, "-s", args.serial, *map(str, parts)], timeout=40).decode("utf-8", errors="replace")
    if adb("emu", "avd", "name").splitlines()[0].strip() != "sense-input-quality":
        raise ValueError("Only the dedicated sense-input-quality AVD is supported")
    root = Path(__file__).resolve().parents[1]
    args.output.mkdir(parents=True)
    apks = {PACKAGE: args.apk or root / "app/build/outputs/apk/debug/app-debug.apk",
            FIXTURE: root / "input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk",
            FIXTURE + ".test": root / "input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk"}
    hashes = {}
    for package, apk in apks.items():
        hashes[package] = hashlib.sha256(apk.read_bytes()).hexdigest()
        if "Success" not in adb("install", "-r", apk):
            raise ValueError("APK install failed")
    original = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    hard_keyboard = adb("shell", "settings", "get", "secure", "show_ime_with_hard_keyboard").strip()
    result = {"schemaVersion": 1, "scope": "JDWP single-worker suspension + true external editor; not performance evidence",
              "serial": args.serial, "sdk": adb("shell", "getprop", "ro.build.version.sdk").strip(), "apkSha256": hashes, "tests": []}
    try:
        for index, method in enumerate([args.method] if args.method else METHODS):
            name = f"{args.output.name}-{index}"
            folder = args.output / name
            folder.mkdir()
            remote = f"/sdcard/Android/data/{FIXTURE}/files/input-quality/{name}"
            def exists(suffix):
                return adb("shell", f"test -f {remote}/{method}.{suffix} && echo yes || true").strip() == "yes"
            def marker(suffix):
                adb("shell", f"echo ok > {remote}/{method}.{suffix}")
            debugger = thread = port = None
            paused_at = None
            entry = {"method": method, "passed": False}
            with (folder / "instrumentation.log").open("wb") as log:
                process = subprocess.Popen([args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
                    "-e", "evidenceRun", name, "-e", "hostWorkerPause", "true", "-e", "noLearning", "true",
                    "-e", "class", f"{FIXTURE}.ExternalEditorSlowCandidateTest#{method}",
                    f"{FIXTURE}.test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 90
                    while process.poll() is None:
                        if time.monotonic() > deadline:
                            raise TimeoutError("External-editor test exceeded its deadline")
                        if debugger is None and exists("ready"):
                            pid = adb("shell", "pidof", f"{PACKAGE}:ime").strip()
                            if not pid.isdigit():
                                raise ValueError("Expected one Sense debug IME process")
                            port = int(adb("forward", "tcp:0", f"jdwp:{pid}").strip())
                            debugger = Jdwp(port)
                            thread = debugger.idle_candidate_thread()
                            debugger.command(11, 2, thread)
                            paused_at = time.monotonic()
                            entry["imePid"] = int(pid)
                            marker("paused")
                        if thread is not None and exists("resume"):
                            debugger.command(11, 3, thread)
                            thread = None
                            entry["workerPausedMs"] = round((time.monotonic() - paused_at) * 1000)
                            marker("resumed")
                        time.sleep(0.12)
                finally:
                    def stop_instrumentation():
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=10)
                    # Attempt every cleanup even if a process has died or Dispose fails.
                    with contextlib.ExitStack() as cleanup:
                        cleanup.callback(adb, "pull", remote, folder / "device")
                        cleanup.callback(stop_instrumentation)
                        if port is not None:
                            cleanup.callback(adb, "forward", "--remove", f"tcp:{port}")
                        if debugger is not None:
                            cleanup.callback(debugger.close)
            transcript = (folder / "instrumentation.log").read_text("utf-8", errors="replace")
            entry["passed"] = ("OK (1 test)" in transcript and "FAILURES!!!" not in transcript and
                               entry.get("workerPausedMs", 0) >= 1500)
            result["tests"].append(entry)
            (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(entry), flush=True)
    finally:
        if original not in ("", "null"):
            adb("shell", "ime", "set", original)
        if hard_keyboard in ("0", "1"):
            adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", hard_keyboard)
        else:
            adb("shell", "settings", "delete", "secure", "show_ime_with_hard_keyboard")
    if not all(test["passed"] for test in result["tests"]):
        raise SystemExit("Slow-worker acceptance failed; evidence retained")


if __name__ == "__main__":
    main()
