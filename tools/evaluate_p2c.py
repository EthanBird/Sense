#!/usr/bin/env python3
"""Run development P2C modes; pin the selected mode and inputs before first test evaluation."""
from __future__ import annotations

import argparse
import json
import math
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from audit_sentence_corpus import sha256


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "benchmarks/corpus/p2c-v1"
RESULTS = ROOT / "benchmarks/results"
MODEL = ROOT / ".artifacts/input-quality/character-v1.scng"
ASSETS = ROOT / "ime-service/src/main/assets"
CORE = ROOT / "core-input/src/main/kotlin"
PIN = RESULTS / "m12-p2c-development-freeze.json"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def source_hashes():
    return {p.relative_to(CORE).as_posix(): sha256(p) for p in sorted(CORE.rglob("*.kt"))}


def inputs():
    frozen = read(DATA / "frozen.json")
    selection = read(DATA / "selection.json")
    for filename, digest in (("selection.json", frozen["selectionSha256"]), ("annotations.tsv", frozen["annotationsSha256"]),
                             ("attribution.jsonl", frozen["attributionSha256"]), ("proposals.jsonl", selection["proposalsSha256"]),
                             ("dev.tsv", frozen["outputs"]["dev"]["sha256"]), ("test.tsv", frozen["outputs"]["test"]["sha256"])):
        if sha256(DATA / filename) != digest:
            raise ValueError(f"Dataset changed: {filename}")
    if sha256(MODEL) != frozen["modelFrozenBeforeCandidateEvaluation"]:
        raise ValueError("Model changed after annotation freeze")
    if sha256(ASSETS / "pinyin_syllables.txt") != frozen["syllablesSha256"]:
        raise ValueError("Syllable inventory changed")
    return {"frozenDatasetSha256": sha256(DATA / "frozen.json"),
            "assets": {p.name: sha256(p) for p in [ASSETS / x for x in ("pinyin_lexicon.bin", "pinyin_bigrams.bin", "pinyin_syllables.txt", "english_lexicon.txt")] + [MODEL]},
            "sources": source_hashes(), "runnerSha256": sha256(Path(__file__))}


def choose_mode(report, policy):
    if report["partition"] != "dev" or [m["mode"] for m in report["modes"]] != policy["developmentModes"]:
        raise ValueError("Expected complete development sweep only")
    baseline = report["modes"][0]
    gate = policy["selectionGate"]
    eligible = [m for m in report["modes"][1:] if
                m["top1"] >= baseline["top1"] + gate["minimumTop1Gain"] and
                m["top10"] >= baseline["top10"] - gate["maximumTop10Loss"] and
                m["covered"] >= baseline["covered"] - gate["maximumCoverageLoss"] and
                m["characterErrors"] <= baseline["characterErrors"]]
    eligible.sort(key=lambda m: (-m["top1"], m["characterErrors"], -m["top3"], float(m["mode"][2:])))
    return eligible[0]["mode"] if eligible else "legacy"


def check_report(report, partition, modes, pins):
    expected = {**pins["assets"], f"{partition}.tsv": sha256(DATA / f"{partition}.tsv")}
    if report["partition"] != partition or [r["mode"] for r in report["modes"]] != modes or report["assets"] != expected or report["sources"] != pins["sources"]:
        raise ValueError("Benchmark report does not match pinned execution inputs")
    if report["cases"] != read(DATA / "frozen.json")["outputs"][partition]["rows"]:
        raise ValueError("Benchmark silently omitted reviewed cases")


def run(partition, modes, pins):
    report = RESULTS / f"m12-p2c-{partition}.json"
    command = [str(ROOT / "gradlew.bat"), "--offline", "--no-parallel", "--console=plain", ":core-input:m12P2cBenchmark",
               f"-Pp2cPartition={partition}", "-Pp2cModes=" + ",".join(modes), f"-Pp2cReport={report}"]
    subprocess.run(command, cwd=ROOT, check=True)
    result = read(report)
    if inputs() != pins:
        raise ValueError("Inputs changed while benchmark was running")
    check_report(result, partition, modes, pins)
    return result


def analyze(report, selected):
    baseline = report["modes"][0]
    chosen = next(mode for mode in report["modes"] if mode["mode"] == selected)
    gains, losses = chosen["gains"], chosen["losses"]
    discordant = gains + losses
    p = min(1.0, 2 * sum(math.comb(discordant, k) for k in range(min(gains, losses) + 1)) / 2 ** discordant) if discordant else 1.0
    no_regression = chosen["top1"] >= baseline["top1"] and chosen["characterErrors"] <= baseline["characterErrors"] and chosen["covered"] >= baseline["covered"]
    return {"selectedMode": selected, "testCases": report["cases"], "pairedTop1": {"gains": gains, "losses": losses,
        "exactMcNemarTwoSidedP": p}, "sourceReconstructionGatePassed": no_regression,
        "productionEnabled": False, "androidLifecycleAcceptance": "pending",
        "interpretation": "Source reconstruction only; agent-reviewed pinyin, not human gold. Test has now been inspected and is a regression set for subsequent changes."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("dev", "freeze-dev", "test"))
    args = parser.parse_args()
    pins = inputs()
    policy = read(DATA / "selection.json")["policy"]
    if args.command == "dev":
        if PIN.exists():
            raise ValueError("Development selection already frozen; do not retune from held-out results")
        run("dev", policy["developmentModes"], pins)
    elif args.command == "freeze-dev":
        if PIN.exists():
            raise ValueError("Existing development freeze must not be overwritten")
        file = RESULTS / "m12-p2c-dev.json"
        report = read(file)
        check_report(report, "dev", policy["developmentModes"], pins)
        selected = choose_mode(report, policy)
        write(PIN, {"schemaVersion": 1, "frozenAt": datetime.now(timezone.utc).isoformat(), "selectedMode": selected,
                    "developmentReportSha256": sha256(file), "policy": policy, "pins": pins,
                    "testViewedForSelection": False, "selectionRule": policy["selectionTieBreak"]})
        print("Frozen development-selected mode:", selected)
    else:
        frozen = read(PIN)
        if frozen["pins"] != pins or sha256(RESULTS / "m12-p2c-dev.json") != frozen["developmentReportSha256"]:
            raise ValueError("Changed inputs/development report since freeze")
        start = RESULTS / "m12-p2c-test-start.json"
        if start.exists():
            raise ValueError("First-use test already started; later reruns are regression evidence, not fresh held-out selection")
        selected = frozen["selectedMode"]
        modes = ["legacy"] + ([] if selected == "legacy" else [selected])
        write(start, {"startedAt": datetime.now(timezone.utc).isoformat(), "developmentFreezeSha256": sha256(PIN), "modes": modes})
        result = run("test", modes, pins)
        analysis = analyze(result, selected)
        analysis["reportSha256"] = sha256(RESULTS / "m12-p2c-test.json")
        analysis["developmentFreezeSha256"] = sha256(PIN)
        write(RESULTS / "m12-p2c-conclusion.json", analysis)
        print(json.dumps(analysis, indent=2))


if __name__ == "__main__":
    main()
