"""Close E6's evidence after every benchmark/device process has finished; never publish a release."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / ".artifacts/input-quality"
OUT = ROOT / "benchmarks/results"


def read(path):
    return json.loads(path.read_text("utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    gate_path = OUT / "e6-acceptance-gate.json"
    if gate_path.exists():
        raise ValueError("Retain the completed stage; use a new version for further work")
    freeze = read(OUT / "e6-candidate-freeze.json")
    for path, digest in freeze["pins"].items():
        assert sha(ROOT / path) == digest, path
    quality = read(OUT / "e6-quality-gate.json")
    assert quality["qualityPassed"] and all(quality["gates"].values())
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    apk_sha = sha(apk)
    assert "BUILD SUCCESSFUL" in (ART / "e6/host-build-final.log").read_text("utf-8")
    evidence = [ROOT / p for p in freeze["pins"]]
    host = {}
    for module, task, expected in (("core-input", "test", 301), ("ime-ui", "testDebugUnitTest", 234),
                                    ("ime-service", "testDebugUnitTest", 295), ("app", "testDebugUnitTest", 3)):
        files = sorted((ROOT / module / "build/test-results" / task).glob("TEST-*.xml"))
        result = {k: sum(int(ET.parse(p).getroot().get(k, "0")) for p in files) for k in ("tests", "failures", "errors", "skipped")}
        assert result == {"tests": expected, "failures": 0, "errors": 0, "skipped": 0}, (module, result)
        host[module] = result
        evidence.extend(files)
    python_log = (ART / "e6/all-tool-tests.log").read_text("utf-8")
    assert "Ran 329 tests" in python_log and "OK (skipped=1)" in python_log
    device = []
    for name, count in (("correction", 7), ("warm", 10), ("cold", 4), ("association", 9), ("learning", 2)):
        folder = ART / f"external-editor-e6-{name}-final"
        log = (folder / "instrumentation.log").read_text("utf-8-sig")
        assert f"OK ({count} tests)" in log and not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", log), name
        environment = read(folder / "environment.json")
        assert environment["apks"]["app/build/outputs/apk/debug/app-debug.apk"] == apk_sha
        assert environment["serial"] == "emulator-5580"
        device.append({"group": name, "tests": count, "passed": True})
        evidence.extend(p for p in folder.rglob("*") if p.is_file())
    baseline = ART / "external-editor-e6-correction-baseline"
    assert "Tests run: 7,  Failures: 2" in (baseline / "instrumentation.log").read_text("utf-8-sig")
    baseline_env = read(baseline / "environment.json")
    new_env = read(ART / "external-editor-e6-correction-final/environment.json")
    for name, digest in baseline_env["apks"].items():
        if name.startswith("input-quality-device/"):
            assert new_env["apks"][name] == digest, "Baseline used a different test APK"
    evidence.extend(p for p in baseline.rglob("*") if p.is_file())
    latency = read(OUT / "e6-latency.json")
    measurements = read(ART / "e6-latency/measurements.json")
    assert len(measurements["runs"]) == 4
    assert all(r["passed"] and len(r["samples"]) == 16 and all(s["passed"] for s in r["samples"]) for r in measurements["runs"])
    assert latency["apkSha256"] == measurements["apkSha256"]
    assert latency["apkSha256"]["optimized"] == apk_sha
    assert latency["apkSha256"]["baseline"] == sha(ART / "e6/baseline.apk")
    assert baseline_env["apks"]["app/build/outputs/apk/debug/app-debug.apk"] == latency["apkSha256"]["baseline"]
    personal = read(ART / "e6/personalization-audit.json")
    assert personal["forbiddenPrivatePhraseAbsent"]
    rows = {r["text"]: r for r in personal["records"]}
    assert rows["程彻"]["useCount"] >= 4 and rows["智能体"]["useCount"] >= 3
    assert all(r["recentPeakEvidence"] > 1 for r in rows.values())
    for name in ("apk-association-audit.json", "apk-language-audit.json"):
        audit = read(ART / "e6" / name)
        assert audit["passed"] and audit["apkSha256"] == apk_sha
    evidence.extend(p for p in (ART / "e6").rglob("*") if p.is_file() and p.suffix != ".apk" and p.name != "collection.log")
    evidence.extend(p for p in (ART / "e6-latency").rglob("*") if p.is_file())
    evidence.extend(p for p in OUT.glob("e6-*.json") if p.name not in ("e6-evidence-manifest.json", "e6-acceptance-gate.json"))
    evidence.extend([Path(__file__).resolve(), ROOT / "tools/test_external_editor.ps1",
                     ROOT / "ime-service/src/test/kotlin/io/github/ethanbird/senseime/service/ProductionPinyinDecodersTest.kt",
                     ROOT / "input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorCorrectionTest.kt"])
    archive = OUT / "e6-evidence"
    archive.mkdir(exist_ok=True)
    manifest = {}
    for path in sorted(set(evidence)):
        raw = path.read_bytes()
        label = path.relative_to(ROOT).as_posix()
        target = archive / (hashlib.sha256(label.encode()).hexdigest()[:20] + ".gz")
        compressed = gzip.compress(raw, mtime=0)
        if target.exists():
            assert target.read_bytes() == compressed, target
        else:
            target.write_bytes(compressed)
        assert gzip.decompress(target.read_bytes()) == raw
        manifest[label] = {"archive": target.relative_to(ROOT).as_posix(), "sha256": hashlib.sha256(raw).hexdigest(),
                           "bytes": len(raw), "archiveSha256": sha(target)}
    manifest_path = OUT / "e6-evidence-manifest.json"
    manifest_path.write_text(json.dumps({"schemaVersion": 1, "files": manifest}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result = {"schemaVersion": 1, "stage": "E6", "passed": True, "goalComplete": False, "releaseReady": False,
              "scope": "Local debug stage: source reconstruction, unit suites and one dedicated API37 x86_64 AVD; no phone certification or release",
              "qualityGate": {"file": "benchmarks/results/e6-quality-gate.json", "sha256": sha(OUT / "e6-quality-gate.json")},
              "frozenCandidateUnchanged": True, "host": host, "hostTests": sum(x["tests"] for x in host.values()),
              "python": {"run": 329, "passed": 328, "skipped": 1, "skipReason": "Windows symbolic-link privilege"},
              "device": device, "deviceScenarioExecutions": sum(x["tests"] for x in device),
              "baselineFailedScenarios": 2, "controlledTestApkIdentical": True, "latencyConfirmations": 64,
              "latencyReport": {"file": "benchmarks/results/e6-latency.json", "sha256": sha(OUT / "e6-latency.json")},
              "personalization": personal, "apk": {"path": apk.relative_to(ROOT).as_posix(), "sha256": apk_sha, "bytes": apk.stat().st_size},
              "dictionarySupplementAdopted": False, "productionOovFeature": -4,
              "evidenceManifest": {"file": manifest_path.relative_to(ROOT).as_posix(), "sha256": sha(manifest_path), "files": len(manifest)},
              "openItems": ["Unlearned 智能体 and 跨会话 still weak", "Supplementary dictionary confidence and personal-only membership semantics",
                            "Natural typing and external-domain evaluation", "Frontend explicit syllable boundaries", "Association word-boundary accuracy",
                            "E5 generic lexicon InputStream load still reads fully before fromBytes byte limit; Android asset loader is separately pinned"]}
    gate_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("passed", "hostTests", "deviceScenarioExecutions", "latencyConfirmations", "evidenceManifest", "apk")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
