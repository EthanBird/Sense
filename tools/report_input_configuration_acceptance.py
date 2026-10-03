"""Collect D8 configuration evidence; never rewrite historical F1 acceptance."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / ".artifacts/input-quality"
OUT = ROOT / "benchmarks/results"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APP = "io.github.ethanbird.senseime.debug"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def pin(path):
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": digest(path)}


def passed_log(path, count):
    value = path.read_text(encoding="utf-8-sig")
    assert re.search(rf"OK \({count} tests?\)", value), path
    assert not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", value), path
    return {**pin(path), "passedTests": count}


def configuration(name, apk_hash, expected_count):
    folder = ART / name
    result = read(folder / "result.json")
    assert result["apks"][APP] == apk_hash
    assert result["settingsBefore"] == result["settingsRestored"]
    assert sum(r["expectedTests"] for r in result["runs"]) == expected_count
    if expected_count == 12:
        assert {(r["name"], r["fontScale"], r["rotation"]) for r in result["runs"]} == {
            ("portrait", 1.0, 0), ("portrait-large", 2.0, 0),
            ("landscape", 1.0, 1), ("landscape-large", 2.0, 1),
        }
    for row in result["runs"]:
        assert row["passed"] and not row["expectedFailure"]
        path = folder / row["name"] / "instrumentation.log"
        assert digest(path) == row["logSha256"]
        passed_log(path, row["expectedTests"])
        # Save actual observed configuration/geometry, not only requested runner flags.
        row["observations"] = {
            p.name: p.read_text(encoding="utf-8")
            for p in sorted((folder / row["name"] / "device").glob("*.txt"))
            if not p.name.endswith("-cadence.txt")
        }
    return result


def main():
    apk_hash = digest(APK)
    reference = read(OUT / "f1-implementation-snapshot.json")
    unchanged_core = {
        path: digest(ROOT / path) == value
        for path, value in reference["productionSources"].items()
        if path.startswith("core-input/")
    }
    assert unchanged_core and all(unchanged_core.values())
    assert all(digest(ROOT / "ime-service/src/main/assets" / p) == h
               for p, h in reference["existingDecoderAssets"].items())
    changed = {
        path: {"before": old, "after": digest(ROOT / path)}
        for path, old in reference["productionSources"].items()
        if digest(ROOT / path) != old
    }
    added = [
        pin(p) for module in ("core-input", "ime-ui", "ime-service")
        for p in sorted((ROOT / module / "src/main/kotlin").rglob("*.kt"))
        if p.relative_to(ROOT).as_posix() not in reference["productionSources"]
    ]
    save("d8-implementation-snapshot.json", {
        "schemaVersion": 1, "apkSha256": apk_hash,
        "changedSinceF1": changed, "addedSinceF1": added, "coreUnchangedFromF1": unchanged_core,
        "scope": "D8 changes presentation and timeout only; no new LM-quality claim",
        "harnessSources": [
            pin(p) for p in sorted((ROOT / "input-quality-device/src").rglob("*.kt"))
        ] + [
            pin(ROOT / "tools/test_input_configuration.py"),
            pin(ROOT / "tools/test_external_editor.ps1"),
        ],
    })

    modules = {}
    for module, variant in [("core-input", "test"), ("ime-ui", "testDebugUnitTest"),
                            ("ime-service", "testDebugUnitTest"), ("app", "testDebugUnitTest")]:
        files = list((ROOT / module / "build/test-results" / variant).glob("TEST*.xml"))
        assert files
        rows = [ET.parse(p).getroot() for p in files]
        modules[module] = {k: sum(int(r.get(k, 0)) for r in rows)
                           for k in ("tests", "errors", "failures", "skipped")}
    assert sum(m["tests"] for m in modules.values()) == 819
    assert all(m[k] == 0 for m in modules.values() for k in ("errors", "failures", "skipped"))
    pylog = ART / "d8-python-tests.log"
    assert re.search(r"Ran 30 tests.*\bOK\b", pylog.read_text(encoding="utf-8"), re.S)
    save("d8-host-validation.json", {
        "schemaVersion": 1, "modules": modules, "pythonTests": 30, "pythonLog": pin(pylog),
        "scope": "Full core/UI/service suite; targeted About tests; cached unchanged tasks permitted",
        "buildLogs": [pin(p) for p in sorted(ART.glob("d8-*build.log"))],
        "apkSha256": apk_hash,
    })

    matrix = configuration("d8-layout-accepted", apk_hash, 12)
    repeat = configuration("d8-layout-accepted-repeat", apk_hash, 12)
    baseline = configuration("d8-layout-corrected-baseline", reference["apkSha256"], 12)
    timeout = configuration("d8-timeout-accepted", apk_hash, 1)
    red_timeout = read(ART / "d8-timeout-red/result.json")
    red_timeout_log = ART / "d8-timeout-red/timeout/instrumentation.log"
    assert red_timeout["apks"][APP] == reference["apkSha256"]
    assert "User requested 10 seconds; suggestions must survive" in red_timeout_log.read_text(encoding="utf-8")
    assert not red_timeout["runs"][0]["passed"]
    rejected = []
    for name in ("d8-layout-baseline", "d8-layout-first", "d8-layout-final"):
        data = read(ART / name / "result.json")
        assert any(not row["passed"] for row in data["runs"])
        rejected.append({"result": data, "reason": "Rotation setup, before input assertions; not accepted as product results"})
    save("d8-configuration-system.json", {
        "schemaVersion": 1, "baseline": baseline, "final": matrix, "repeat": repeat,
        "rejectedHarnessRuns": rejected,
        "scope": "Real system IME, API 37 dedicated AVD, portrait/landscape x fontScale 1/2; no physical-phone claim",
    })
    save("d8-timeout-regression.json", {
        "schemaVersion": 1, "before": red_timeout, "beforeLog": pin(red_timeout_log), "after": timeout,
        "observation": "10-second platform preference: suggestion retained and selected after 5.5 seconds",
    })
    red_render = ART / "d8-render-red.log"
    assert "Tests run: 4,  Failures: 2" in red_render.read_text(encoding="utf-8")
    ui = passed_log(ART / "d8-render-accepted.log", 19)
    save("d8-render-regression.json", {
        "schemaVersion": 1, "beforeLog": pin(red_render),
        "beforeObservation": "Measured glyph ink 4725; collapsed clipped 3517; expanded wrong-font 3654",
        "beforeProvenance": "Pre-fix UI library sources; red test APK hash was not captured",
        "after": ui,
        "afterTestApk": pin(ROOT / "ime-ui/build/outputs/apk/androidTest/debug/ime-ui-debug-androidTest.apk"),
        "scope": "4 renderer tests, 1 fitted-text test, 14 real View tests including large-font scene reuse",
    })

    runs = []
    for name, count in [("associations", 9), ("warm", 10), ("cold", 4), ("corrections", 3), ("learning", 2)]:
        folder = ART / ("external-editor-d8-accepted-" + name)
        env = read(folder / "environment.json")
        assert env["apks"]["app/build/outputs/apk/debug/app-debug.apk"] == apk_hash
        runs.append({
            "name": name, "environment": env, "instrumentation": passed_log(folder / "instrumentation.log", count),
            "observations": {p.name: p.read_text(encoding="utf-8")
                             for p in sorted((folder / "device").glob("*.txt"))},
        })
    save("d8-system-ime.json", {"schemaVersion": 1, "passedTests": 28, "runs": runs})
    learning = read(OUT / "d8-learning-persistence.json")
    assert learning["forbiddenPrivatePhraseAbsent"]
    assert {r["text"] for r in learning["records"]} == {"程彻", "智能体"}
    asset = read(OUT / "d8-apk-asset-audit.json")
    assert asset["passed"] and asset["apkSha256"] == apk_hash
    assert asset["model"] == reference["model"]

    # Retain compact raw logs in the repository, including all rejected harness attempts.
    raw = OUT / "d8-raw"
    raw.mkdir(exist_ok=True)
    paths = list(ART.glob("d8-*.log"))
    for directory in ART.glob("d8-*"):
        if directory.is_dir():
            paths += list(directory.rglob("instrumentation.log"))
            paths += list(directory.rglob("result.json"))
    for directory in ART.glob("external-editor-d8-*"):
        paths += list(directory.glob("*.log")) + list(directory.glob("*.json"))
    manifest = {}
    for path in sorted(set(paths)):
        name = path.relative_to(ART).as_posix().replace("/", "__") + ".gz"
        data = path.read_bytes()
        target = raw / name
        target.write_bytes(gzip.compress(data, mtime=0))
        manifest[name] = {**pin(path), "compressedSha256": digest(target)}
    save("d8-raw-manifest.json", manifest)
    save("d8-acceptance-gate.json", {
        "schemaVersion": 1, "passed": True, "apkSha256": apk_hash,
        "scope": "Local D8 presentation/accessibility phase, broader quality goal remains active",
        "checks": {"host819": True, "python30": True, "rendererAndView19": True,
                   "configuration12RepeatedTwice": True, "timeoutRegression": True,
                   "systemIme28": True, "learnedWordsAndPrivacy": True,
                   "coreAndModelsUnchanged": True, "sameFinalApkAcrossSystemRuns": True},
        "limitations": ["Only API 37 emulator and 100/200-percent system UI matrix",
                        "No physical-device certification or frame-latency distribution",
                        "No new corpus/LM-quality evaluation in D8",
                        "Compact large-font rows hide secondary legends, not their gestures"],
    })
    save("d8-evidence-manifest.json", {
        "schemaVersion": 1, "collector": pin(Path(__file__)),
        "reports": {p.name: digest(p) for p in sorted(OUT.glob("d8-*.json"))
                    if p.name != "d8-evidence-manifest.json"},
        "images": [pin(p) for p in sorted((ROOT / "docs/research/images").glob("d8-*.png"))],
    })
    print(json.dumps({"passed": True, "host": 819, "python": 30, "androidExecutions": 72, "apkSha256": apk_hash}))


if __name__ == "__main__":
    main()
