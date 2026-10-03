"""Close E8's evidence without rewriting earlier stages or collecting APK/model copies."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / ".artifacts/input-quality/e8"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    return json.loads(path.read_text("utf-8-sig"))


def write_new(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write("\n")


def main():
    gate_path = ROOT / "benchmarks/results/e8-acceptance-gate.json"
    manifest_path = ROOT / "benchmarks/results/e8-evidence-manifest.json"
    assert not gate_path.exists() and not manifest_path.exists(), "Keep closed evidence"
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    apk_hash = sha(apk.read_bytes())
    units, collected = {}, set()
    for module, task in (("core-input", "test"), ("ime-ui", "testDebugUnitTest"),
                         ("ime-service", "testDebugUnitTest"), ("app", "testDebugUnitTest")):
        paths = list((ROOT / module / "build/test-results" / task).glob("TEST-*.xml"))
        assert paths
        rows = [ET.parse(p).getroot() for p in paths]
        units[module] = {k: sum(int(r.attrib.get(k, 0)) for r in rows)
                         for k in ("tests", "failures", "errors", "skipped")}
        assert units[module]["failures"] == units[module]["errors"] == units[module]["skipped"] == 0
        collected.update(paths)
    assert sum(v["tests"] for v in units.values()) == 853
    tool_log = (ART / "tools-tests.log").read_text("utf-8-sig")
    assert re.search(r"Ran 339 tests", tool_log) and "OK (skipped=1)" in tool_log
    baseline = ROOT / ".artifacts/input-quality/external-editor-e8-boundary-baseline"
    assert "Tests run: 6,  Failures: 5" in (baseline / "instrumentation.log").read_text("utf-8-sig")
    baseline_apk_hash = sha((ART / "baseline.apk").read_bytes())
    assert read(baseline / "environment.json")["apks"]["app/build/outputs/apk/debug/app-debug.apk"] == baseline_apk_hash
    groups = {"boundary": 6, "warm": 10, "correction": 8, "association": 9, "cold": 4, "learning": 2}
    device = []
    for name, count in groups.items():
        folder = ROOT / f".artifacts/input-quality/external-editor-e8-{name}-final"
        log = (folder / "instrumentation.log").read_text("utf-8-sig")
        assert f"OK ({count} tests)" in log and "FAILURES!!!" not in log
        env = read(folder / "environment.json")
        assert env["apks"]["app/build/outputs/apk/debug/app-debug.apk"] == apk_hash
        device.append({"group": name, "tests": count, "passed": True})
    ui_log = (ART / "ui-device.log").read_text("utf-8-sig")
    assert "OK (15 tests)" in ui_log
    equivalence = read(ROOT / "benchmarks/results/e8-progressive-equivalence.json")
    assert equivalence["passed"] and equivalence["identical"] == equivalence["observations"] == 1790
    assert set(equivalence["changedSourceFiles"]) == {
        "io/github/ethanbird/senseime/core/" + name for name in
        ("AdaptivePinyinDecoder.kt", "PinyinSyllableSegmenter.kt", "ProgressivePinyin.kt", "M14ProgressiveEquivalenceBenchmark.kt")}
    # The extracted baseline harness differs only in the path used to fingerprint
    # its own source tree. No old-engine edit or candidate query change is hidden.
    old_harness = ART / "e7-core/io/github/ethanbird/senseime/core/M14ProgressiveEquivalenceBenchmark.kt"
    live_harness = ROOT / "core-input/src/main/kotlin/io/github/ethanbird/senseime/core/M14ProgressiveEquivalenceBenchmark.kt"
    assert old_harness.read_text().replace(".artifacts/input-quality/e8/e7-core", "core-input/src/main/kotlin") == live_harness.read_text()
    audit = read(ART / "apk-audit.json")
    assert audit["passed"] and audit["apkSha256"] == apk_hash
    with ZipFile(ART / "baseline.apk") as before, ZipFile(apk) as after:
        assets = [name for name in before.namelist() if name.startswith("assets/")]
        assert set(assets) == {name for name in after.namelist() if name.startswith("assets/")}
        assert all(before.read(name) == after.read(name) for name in assets)
    signature = (ART / "apk-signature.log").read_text("utf-8-sig")
    assert "CN=Android Debug" in signature and "v2): true" in signature
    personal = read(ART / "personalization-audit.json")
    assert personal["forbiddenPrivatePhraseAbsent"]
    records = {row["text"]: row for row in personal["records"]}
    assert records["程彻"]["useCount"] >= 4 and records["智能体"]["useCount"] >= 3
    assert all(row["recentPeakEvidence"] > 1 for row in records.values())
    # Logs/results plus complete core, touched UI/service, and regression fixtures.
    collected.update(p for p in ART.iterdir() if p.is_file() and p.suffix != ".apk")
    for folder in (ROOT / ".artifacts/input-quality").glob("external-editor-e8-*"):
        collected.update(p for p in folder.rglob("*") if p.is_file())
    collected.update((ROOT / "core-input/src/main/kotlin").rglob("*.kt"))
    for relative in (
        "core-input/src/test/kotlin/io/github/ethanbird/senseime/core/PinyinExplicitBoundaryTest.kt",
        "ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/KeyboardLayoutContract.kt",
        "ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/KeyboardLetterLayout.kt",
        "ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/QwertyKeyboardLayout.kt",
        "ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/KeyboardSceneBuilder.kt",
        "ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/SenseKeyboardView.kt",
        "ime-ui/src/test/kotlin/io/github/ethanbird/senseime/ui/KeyboardLayoutContractTest.kt",
        "ime-ui/src/androidTest/kotlin/io/github/ethanbird/senseime/ui/SenseKeyboardViewLayoutDeviceTest.kt",
        "ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/SenseInputMethodService.kt",
        "ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/CandidateBarCompositionPresenter.kt",
        "ime-service/src/test/kotlin/io/github/ethanbird/senseime/service/PinyinSeparatorServiceTest.kt",
        "ime-service/src/test/kotlin/io/github/ethanbird/senseime/service/CandidateBarCompositionPresenterTest.kt",
        "input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorBoundaryTest.kt",
        "input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorTestFixture.kt",
        "tools/test_external_editor.ps1", "tools/collect_boundary_stage.py",
        "benchmarks/results/e8-progressive-equivalence.json",
        "docs/research/input-quality-stage-e8-2026-10-03.md",
    ):
        collected.add(ROOT / relative)
    target = ROOT / "benchmarks/results/e8-evidence"
    target.mkdir(exist_ok=False)
    files = {}
    for path in sorted(collected):
        relative = path.relative_to(ROOT).as_posix()
        data = path.read_bytes()
        packed = gzip.compress(data, mtime=0)
        archive = target / (sha(relative.encode())[:20] + ".gz")
        assert not archive.exists()
        archive.write_bytes(packed)
        assert gzip.decompress(archive.read_bytes()) == data
        files[relative] = {"archive": archive.relative_to(ROOT).as_posix(), "sha256": sha(data),
                           "bytes": len(data), "archiveSha256": sha(packed)}
    write_new(manifest_path, {"schemaVersion": 1, "files": files})
    write_new(gate_path, {"schemaVersion": 1, "stage": "E8", "passed": True,
        "goalComplete": False, "releaseReady": False,
        "scope": "Explicit 26-key pinyin boundary lifecycle; known/synthetic host and dedicated API37 x86_64 AVD",
        "host": units, "hostTests": 853, "python": {"run": 339, "passed": 338, "skipped": 1},
        "systemEditor": device, "systemEditorTests": 39, "keyboardViewTests": 15,
        "baselineSystemEditor": {"tests": 6, "failures": 5, "apkSha256": baseline_apk_hash},
        "ordinaryProgressiveEquivalence": {"observations": 1790, "identical": 1790,
            "scope": "895 states twice; complete result hashes, not fresh accuracy or Android latency"},
        "unchangedPackagedAssets": len(assets),
        "apk": {"bytes": apk.stat().st_size, "sha256": apk_hash, "signing": "Android Debug; v2 verified"},
        "evidenceManifestSha256": sha(manifest_path.read_bytes()), "archivedFiles": len(files)})
    print(f"E8 closed: host=853, system=39, UI=15, archives={len(files)}")


if __name__ == "__main__":
    main()
