"""Collect E4's frozen quality, real-editor, latency and provenance evidence."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from summarize_typo_replay import compare

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / ".artifacts/input-quality"
OUT = ROOT / "benchmarks/results"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pin(path):
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": digest(path)}


def quality_checks(comparison, policy):
    before, after = comparison["before"], comparison["after"]
    clean = comparison["byOperation"]["clean"]
    return {
        "newAuditCleanTop1Nondecreasing": clean["after"]["top1"] >= clean["before"]["top1"],
        "newAuditCleanCerNonIncreasing": clean["after"]["characterErrors"] <= clean["before"]["characterErrors"],
        "newAuditAllTop1GainAtLeast": after["top1"] - before["top1"] >= policy["newAuditAllTop1GainAtLeast"],
        "newAuditAllTop10GainAtLeast": after["top10"] - before["top10"] >= policy["newAuditAllTop10GainAtLeast"],
        "newAuditAllCerNonIncreasing": after["characterErrors"] <= before["characterErrors"],
        "newAuditCoverageNondecreasing": after["covered"] >= before["covered"],
    }


def verify_pair(before, after, freeze, expected_rows):
    assert before["correctionCompositionBoost"] == 0
    assert after["correctionCompositionBoost"] == freeze["boost"] == 12
    assert before["sources"] == after["sources"]
    for path, sha in after["sources"].items():
        assert freeze["files"]["core-input/src/main/kotlin/" + path] == sha
    assert before["assets"] == after["assets"] == freeze["assets"]
    assert before["progressiveJoints"] == after["progressiveJoints"]
    for a, b in zip(before["observations"], after["observations"]):
        assert a["effectiveQuery"] == b["effectiveQuery"] == a["typed"].replace("'", "")
    return compare(before, after, expected_rows)


def passed_log(path, count):
    log = path.read_text(encoding="utf-8-sig")
    assert re.search(rf"OK \({count} tests?\)", log), path
    assert not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", log), path
    return {**pin(path), "tests": count}


def main():
    destination = OUT / "e4-final-acceptance-gate.json"
    if destination.exists():
        raise ValueError("Retain prior acceptance evidence")
    rule = read(OUT / "e4-development-freeze.json")
    freeze = read(OUT / "e4-candidate-freeze.json")
    assert rule["noRetuningFromAudit"] and not freeze["auditOutputsInspected"]
    assert rule["chosenCorrectionCompositionBoost"] == freeze["boost"]
    for path, sha in freeze["files"].items():
        assert digest(ROOT / path) == sha, path
    for path, sha in freeze["assets"].items():
        assert digest(ROOT / "ime-service/src/main/assets" / path) == sha

    audit = verify_pair(read(ART / "e4/audit-before.json"), read(ART / "e4/audit-after.json"), freeze, 929)
    checks = quality_checks(audit, rule["qualityGate"])
    assert all(checks.values()), checks
    known = verify_pair(read(ART / "e4/known-before.json"), read(ART / "e4/known-after.json"), freeze, 1874)
    development = verify_pair(read(ART / "e4/dev-production-before.json"), read(ART / "e4/dev-production-after.json"), freeze, 945)
    assert all(known["qualityGate"].values()) and all(development["qualityGate"].values())
    reference = read(OUT / "e4-android-binding-reference.json")
    assert reference["hostSourceReportSha256"] == digest(ART / "e4/known-after.json")
    short = read(OUT / "e4-short-word-diagnostic.json")
    issue = next(r for r in short["rows"] if r["query"] == "zhinengti")
    assert issue["beforeRank"] == 15 and issue["afterRank"] == 20
    # The old generator hardcoded its source path, but pinned the correct input bytes.
    # Preserve the historical frozen manifest and record an explicit resolution, not a rewrite.
    source = ROOT / "benchmarks/corpus/p2c-ranking-v2"
    mutations = read(ROOT / "benchmarks/corpus/typo-ranking-v2/frozen.json")
    assert mutations["sourceFrozenSha256"] == digest(source / "frozen.json")
    assert mutations["sourceAttributionSha256"] == digest(source / "attribution.jsonl")
    assert mutations["outputs"]["test"]["sha256"] == read(ART / "e4/audit-after.json")["inputSha256"]

    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    apk_hash = digest(apk)
    latency = read(OUT / "e4-android-latency.json")
    assert latency["apkSha256"]["optimized"] == apk_hash
    assert latency["apkSha256"]["baseline"] == digest(ART / "e4/d8-baseline.apk")
    timing = latency["confirmationMs"]
    ratios = {key: timing["optimized"][key] / timing["baseline"][key] for key in ("medianMs", "observedP95Ms")}
    assert ratios["medianMs"] <= rule["engineeringGate"]["androidMedianRatioAtMost"]
    assert ratios["observedP95Ms"] <= rule["engineeringGate"]["androidObservedP95RatioAtMost"]

    modules = {}
    for module, variant in (("core-input", "test"), ("ime-ui", "testDebugUnitTest"),
                            ("ime-service", "testDebugUnitTest"), ("app", "testDebugUnitTest")):
        files = sorted((ROOT / module / "build/test-results" / variant).glob("TEST*.xml"))
        assert files
        suites = [ET.parse(p).getroot() for p in files]
        modules[module] = {k: sum(int(r.get(k, 0)) for r in suites) for k in ("tests", "errors", "failures", "skipped")}
    assert sum(m["tests"] for m in modules.values()) == 823
    assert all(m[k] == 0 for m in modules.values() for k in ("errors", "failures", "skipped"))
    assert "BUILD SUCCESSFUL" in (ART / "e4/host-build-v2.log").read_text("utf-8-sig")
    pylog = (ART / "e4/python-tests.log").read_text("utf-8-sig")
    assert re.search(r"Ran 35 tests.*\bOK\b", pylog, re.S)
    assert re.search(r"Ran 4 tests.*\bOK\b", (ART / "e4/collector-tests-final.log").read_text("utf-8-sig"), re.S)

    systems = []
    for name, count in (("warm", 10), ("cold", 4), ("corrections", 5), ("associations", 9), ("learning", 2)):
        folder = ART / ("external-editor-e4-" + name)
        environment = read(folder / "environment.json")
        assert environment["apks"]["app/build/outputs/apk/debug/app-debug.apk"] == apk_hash
        systems.append({"name": name, "environment": environment, "log": passed_log(folder / "instrumentation.log", count),
                        "observations": {p.name: p.read_text("utf-8") for p in sorted((folder / "device").glob("*.txt"))}})
    configurations = []
    for name, count in (("e4-layout", 12), ("e4-timeout", 1)):
        folder = ART / name
        configuration = read(folder / "result.json")
        assert configuration["apks"]["io.github.ethanbird.senseime.debug"] == apk_hash
        assert configuration["settingsBefore"] == configuration["settingsRestored"]
        assert sum(r["expectedTests"] for r in configuration["runs"]) == count
        for run in configuration["runs"]:
            assert run["passed"] and not run["expectedFailure"]
            log = folder / run["name"] / "instrumentation.log"
            assert digest(log) == run["logSha256"]
            passed_log(log, run["expectedTests"])
        configurations.append(configuration)
    slow = read(ART / "e4-slow/result.json")
    assert len(slow["tests"]) == 4 and all(t["passed"] for t in slow["tests"])
    for t in slow["tests"]:
        assert t["workerPausedMs"] >= 1500
    assert slow["apkSha256"]["io.github.ethanbird.senseime.debug"] == apk_hash
    learning = read(OUT / "e4-learning-persistence.json")
    assert learning["forbiddenPrivatePhraseAbsent"]
    assert {r["text"] for r in learning["records"]} == {"程彻", "智能体"}
    assert all(r["recentPeakEvidence"] > 1 for r in learning["records"])
    packaged = read(OUT / "e4-apk-asset-audit.json")
    assert packaged["passed"] and packaged["apkSha256"] == apk_hash
    red = (ART / "e4/correction-red.log").read_text("utf-8")
    assert "Tests run: 2,  Failures: 2" in red
    red_environment = read(ART / "e4/correction-red-environment.json")
    for path, sha in red_environment["apks"].items():
        assert digest(ROOT / path) == sha

    raw = OUT / "e4-final-raw"
    raw.mkdir(exist_ok=True)
    # Never archive our own redirected stdout while it is still being written.
    paths = [p for p in (ART / "e4").rglob("*") if p.is_file() and p.suffix in (".json", ".jsonl", ".log", ".txt")
             and not p.name.startswith("acceptance-collector")]
    for pattern in ("external-editor-e4-*", "e4-layout", "e4-timeout", "e4-slow", "e4-latency"):
        for folder in ART.glob(pattern):
            paths.extend(p for p in folder.rglob("*") if p.is_file() and p.suffix in (".json", ".log", ".tsv", ".txt"))
    manifest = {}
    for path in sorted(set(paths)):
        name = path.relative_to(ART).as_posix().replace("/", "__") + ".gz"
        target = raw / name
        target.write_bytes(gzip.compress(path.read_bytes(), mtime=0))
        manifest[name] = {**pin(path), "compressedSha256": digest(target)}
    (OUT / "e4-final-evidence-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    result = {
        "schemaVersion": 1, "passed": True, "releaseReady": False,
        "scope": "Local frozen E4 long-sentence gates only; short-word regression and broad IME-quality goal remain open",
        "collector": pin(Path(__file__)),
        "harnessSources": [pin(p) for p in sorted((ROOT / "input-quality-device/src").rglob("*.kt"))],
        "evidenceFinalization": "Initial collector archived its own open log; retained first archive, final archive excludes collector stdout. Test and APK results unchanged.",
        "apk": pin(apk), "qualityChecks": checks, "frozenPolicy": pin(OUT / "e4-development-freeze.json"),
        "sourceFreeze": pin(OUT / "e4-candidate-freeze.json"), "audit": {"before": audit["before"], "after": audit["after"]},
        "modules": modules, "pythonTests": 39, "systemTests": 30, "configurationTests": 13, "slowWorkerTests": 4,
        "confirmationSamples": 64, "latencyRatios": ratios, "systems": systems, "configurations": configurations,
        "learning": learning, "slowWorker": slow, "packagedAssets": packaged,
        "sourcePathErratum": {"frozenLabel": mutations["source"], "resolvedSource": "../p2c-ranking-v2", "contentHashesMatch": True,
                              "reason": "Generator used a hardcoded v1 label; original manifest and all TSV bytes retained, generator fixed for future runs."},
        "knownProductRegressions": [
            {**issue, "condition": "No personal learning",
             "evidence": "benchmarks/results/e4-short-word-diagnostic.json", "status": "Open; prioritize short-word coverage/ranking before release"},
            {"metric": "Observed confirmation p95", "beforeMs": timing["baseline"]["observedP95Ms"],
             "afterMs": timing["optimized"]["observedP95Ms"], "status": "Within frozen 1.15 budget but higher; no speedup claim"},
        ],
        "limitations": freeze["limitations"] + ["Apostrophe controls are normalized duplicates, not forced-boundary support.",
            "Only one API 37 emulator; observed timings are not real-phone tail-latency guarantees.",
            "All audit labels and results are now known; future tuning needs a new audit.",
            "No release signing or publishing performed in this phase."],
    }
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": True, "qualityChecks": checks, "latencyRatios": ratios, "apkSha256": apk_hash}))


if __name__ == "__main__":
    main()
