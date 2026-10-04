"""Check which artifact supplies the decoder before a host replay is launched."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


DECODER_CLASS = "io/github/ethanbird/senseime/core/PinyinDecoder.class"


def verify_core_classpath(command, expected_jar, expected_sha256):
    if command.count("-cp") != 1:
        raise ValueError("Replay must declare exactly one explicit classpath")
    index = command.index("-cp")
    if len(command) <= index + 2 or command[index + 2] not in ("AllocationReplay", "EmissionMemoReplay"):
        raise ValueError("Unexpected replay main class")
    expected = Path(expected_jar).resolve()
    providers = []
    for item in command[index + 1].split(os.pathsep):
        if not item or "*" in item:
            raise ValueError("Replay classpath entries must be explicit")
        path = Path(item).resolve()
        if path.is_dir():
            present = (path / DECODER_CLASS).is_file()
        elif path.is_file():
            with zipfile.ZipFile(path) as jar:
                present = DECODER_CLASS in jar.namelist()
        else:
            raise ValueError("Missing classpath entry")
        if present:
            providers.append(path)
    if providers != [expected]:
        raise ValueError("Decoder classpath does not have exactly the expected provider")
    with expected.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != expected_sha256:
        raise ValueError("Decoder artifact digest mismatch")
    return {"decoderArtifact": str(expected), "sha256": actual}


def run_checked_replay(command, expected_jar, expected_sha256, log_path):
    identity = verify_core_classpath(command, expected_jar, expected_sha256)
    with Path(log_path).open("xb") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    return identity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", type=Path)
    parser.add_argument("jar", type=Path)
    parser.add_argument("sha256")
    parser.add_argument("--run-log", type=Path, help="Launch only after verification; retain output in a new log")
    args = parser.parse_args()
    command = json.loads(args.command.read_text("utf-8"))["command"]
    if args.run_log is None:
        identity = verify_core_classpath(command, args.jar, args.sha256)
    else:
        identity = run_checked_replay(command, args.jar, args.sha256, args.run_log)
    print(json.dumps(identity))


if __name__ == "__main__":
    main()
