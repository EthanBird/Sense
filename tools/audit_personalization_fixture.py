"""Read-only persistence check after test_external_editor.ps1 -Learning on its disposable AVD."""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile


def audit(adb, serial):
    if not serial.startswith("emulator-"):
        raise ValueError("Only the disposable emulator fixture is supported")
    command = [str(adb), "-s", serial]
    def read(*args):
        return subprocess.check_output(command + list(args), timeout=30)
    if read("emu", "avd", "name").decode().splitlines()[0] != "sense-input-quality":
        raise ValueError("Expected the dedicated sense-input-quality AVD")
    package = "io.github.ethanbird.senseime.debug"
    data = read("exec-out", "run-as", package, "cat", "databases/sense_user_lexicon.db")
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "fixture.db"
        path.write_bytes(data)
        with closing(sqlite3.connect(path)) as connection:
            assert connection.execute("PRAGMA quick_check").fetchone()[0] == "ok"
            rows = connection.execute("SELECT full_pinyin,phrase,use_count,positive_evidence,negative_evidence,"
                                      "last_positive_evidence FROM user_phrase").fetchall()
    names = {row[1]: row for row in rows}
    assert "程澈" not in names, "The private fixture phrase was persisted"
    assert names["程彻"][2] >= 4 and names["程彻"][5] > 1
    assert names["智能体"][2] >= 3 and names["智能体"][5] > 1
    fields = ["pinyin", "text", "useCount", "positiveEvidence", "negativeEvidence", "recentPeakEvidence"]
    return {"scope": "Disposable debug AVD SQLite read-only observation after UI learning/privacy; no DB seeding",
            "databaseSha256": hashlib.sha256(data).hexdigest(), "forbiddenPrivatePhraseAbsent": True,
            "records": [dict(zip(fields, names[name])) for name in ["程彻", "智能体"]]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.adb, args.serial)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
