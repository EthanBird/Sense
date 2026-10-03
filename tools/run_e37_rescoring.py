"""Run the single predeclared E37 host-only experiment; preserve full evidence."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import platform
import subprocess
import sys
import time

from evaluate_cross_domain import load, metrics, nonregression
from fetch_e37_model import digest
from masked_lm_rescore import CharacterPLL, reorder
from fetch_e37_model import REVISION


def check_model_qualification(qualification, diagnostic_replay=False):
    if (qualification.get("schemaVersion") != 1 or qualification.get("model") != "hfl/rbt3"
            or qualification.get("revision") != REVISION):
        raise ValueError("Unpinned model qualification record")
    if qualification.get("trainedMaskedLmHeadVerified") is not True and not diagnostic_replay:
        raise ValueError("RBT3 MLM head is unqualified; --reproduce-rejected-checkpoint is diagnostic only")


def write_new(path, value):
    with path.open("x", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2); f.write("\n")


def distribution(values):
    values = sorted(values)
    if not values:
        return dict(count=0)
    middle = len(values) // 2
    median = values[middle] if len(values) % 2 else (values[middle - 1] + values[middle]) / 2
    return dict(count=len(values), medianMs=median / 1e6,
                p95Ms=values[math.ceil(.95 * len(values)) - 1] / 1e6,
                maxMs=values[-1] / 1e6, totalMs=sum(values) / 1e6)


def validate_baseline(root, source, baseline):
    header, rows = load(baseline.read_text("utf-8").splitlines(), source.read_text("utf-8"))
    if header["inputSha256"] != digest(source):
        raise ValueError("Baseline workload hash changed")
    current_sources = {p.relative_to(root).as_posix(): digest(p)
                       for p in (root / "core-input/src/main/kotlin").rglob("*.kt")}
    if header["sources"] != current_sources:
        raise ValueError("Baseline is not current production sources")
    for name, expected in header["assets"].items():
        if digest(root / "ime-service/src/main/assets" / name) != expected:
            raise ValueError(f"Baseline asset changed: {name}")
    if digest(root / "ime-service/src/main/assets/pinyin_character_lm.scng") != header["modelSha256"]:
        raise ValueError("Baseline language model changed")
    return header, rows


def run(root, model_folder, output, diagnostic_replay=False):
    qualification_path = root / "benchmarks/corpus/e37-model-qualification.json"
    qualification = json.loads(qualification_path.read_text("utf-8"))
    check_model_qualification(qualification, diagnostic_replay)
    output.mkdir(parents=True, exist_ok=True)
    policy_path = root / "benchmarks/corpus/e37-neural-rescoring-policy.json"
    policy = json.loads(policy_path.read_text("utf-8"))
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if subprocess.run(["git", "merge-base", "--is-ancestor", policy["sourceBaseCommit"], head], cwd=root).returncode:
        raise ValueError("Checkout does not descend from the declared source base")
    sources = {"aishell": root / "benchmarks/corpus/p2c-aishell-v7/dev.tsv",
               "tatoeba": root / "benchmarks/corpus/p2c-supervised-v6/dev.tsv",
               "diagnostic": root / ".artifacts/input-quality/e28/diagnostic.tsv"}
    baselines = {d: root / f".artifacts/input-quality/e36/{d}-baseline.jsonl" for d in sources}
    validated = {d: validate_baseline(root, sources[d], baselines[d]) for d in sources}
    files = [policy_path, qualification_path, Path(__file__), root / "tools/masked_lm_rescore.py",
             root / "tools/fetch_e37_model.py", root / "tools/evaluate_cross_domain.py",
             root / "tools/train_candidate_ranker.py", root / "tools/test_masked_lm_rescore.py"]
    lock = dict(sourceBaseCommit=policy["sourceBaseCommit"], checkoutCommit=head,
                sourceAndAssetsMatchCurrentProduction=True,
                modelQualification=qualification, diagnosticReplay=diagnostic_replay,
                sourceFiles={p.relative_to(root).as_posix(): digest(p) for p in files},
                inputs={d: dict(source=sources[d].relative_to(root).as_posix(), sourceSha256=digest(sources[d]),
                               baseline=baselines[d].relative_to(root).as_posix(), baselineSha256=digest(baselines[d])) for d in sources},
                modelManifest=json.loads((model_folder / "download-manifest.json").read_text("utf-8")),
                host=dict(python=sys.version, executable=sys.executable, platform=platform.platform(),
                          processor=platform.processor()),
                productionChanged=False, networkInference=False, timestampUtc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    write_new(output / "pre-replay-lock.json", lock)
    start = time.perf_counter_ns()
    scorer = CharacterPLL(model_folder)
    startup = time.perf_counter_ns() - start
    write_new(output / "model-runtime.json", {**scorer.metadata, "constructorIncludingHashesNanos": startup,
                                              "verification": scorer.verify_numerics()})
    print("Pinned model loaded; full-head equivalence/tokenizer/determinism checks passed", flush=True)
    reports = {}
    for domain in sources:
        header, old = validated[domain]
        new = {}; timings = []; traces = []
        with (output / f"{domain}-rescore.jsonl").open("x", encoding="utf-8") as f:
            f.write(json.dumps(dict(type="header", schemaVersion=1, scope="offline E37 reranking, not production replay",
                                    baselineSha256=lock["inputs"][domain]["baselineSha256"],
                                    policySha256=digest(policy_path), model=scorer.metadata), ensure_ascii=False) + "\n")
            for index, (key, row) in enumerate(old.items()):
                begin = time.perf_counter_ns()
                candidates, trace = reorder(row["context"], row["candidates"], scorer)
                elapsed = time.perf_counter_ns() - begin
                rank = candidates.index(row["expected"]) + 1 if row["expected"] in candidates else 0
                new[key] = {**row, "candidates": candidates, "rank": rank}
                result = dict(type="row", id=row["id"], cut=row["cut"], query=row["query"], context=row["context"],
                              expected=row["expected"], beforeRank=row["rank"], afterRank=rank,
                              beforeCandidates=row["candidates"], afterCandidates=candidates,
                              hostRerankNanos=elapsed, trace=trace)
                f.write(json.dumps(result, ensure_ascii=False) + "\n")
                traces.append(trace); timings.append(elapsed)
                if (index + 1) % 32 == 0:
                    print(f"{domain}: {index + 1}/{len(old)} states rescored", flush=True)
            f.write(json.dumps(dict(type="summary", rows=len(new), membershipUnchanged=True)) + "\n")
        before = metrics(list(old.values())); after = metrics(list(new.values()))
        changes = [dict(id=old[k]["id"], cut=old[k]["cut"], query=old[k]["query"],
                        context=old[k]["context"], expected=old[k]["expected"],
                        beforeRank=old[k]["rank"], afterRank=new[k]["rank"],
                        beforeTop5=old[k]["candidates"][:5], afterTop5=new[k]["candidates"][:5])
                   for k in old if old[k]["rank"] != new[k]["rank"]]
        report = dict(before=before, after=after, nonregressionPassed=nonregression(before, after),
                      eligibleRows=sum(t["reason"] == "scored" for t in traces),
                      firstChanged=sum(old[k]["candidates"][:1] != new[k]["candidates"][:1] for k in old),
                      skipReasons=dict(Counter(t["reason"] for t in traces)),
                      firstGains=[r for r in changes if r["afterRank"] == 1],
                      firstLosses=[r for r in changes if r["beforeRank"] == 1],
                      rankLosses=[r for r in changes if r["afterRank"] > r["beforeRank"]],
                      latencyAllRows=distribution(timings),
                      latencyScoredRows=distribution([n for n,t in zip(timings,traces) if t["reason"] == "scored"]),
                      slices={mode: dict(before=metrics([r for r in old.values() if r["mode"] == mode]),
                                         after=metrics([r for r in new.values() if r["mode"] == mode])) for mode in ["empty", "editor"]},
                      timingScope="Warm desktop CPU added rescoring only; excludes decoder and Android UI",
                      accuracyScope="Known development workload with empty personalization; not blind or natural mobile input")
        write_new(output / f"{domain}-comparison.json", report)
        reports[domain] = report
        print(json.dumps(dict(domain=domain, before=before, after=after, passed=report["nonregressionPassed"],
                              latency=report["latencyScoredRows"]), ensure_ascii=False), flush=True)
    gate = all(reports[d]["nonregressionPassed"] for d in ["aishell", "tatoeba"]) and sum(
        reports[d]["after"]["top1"] - reports[d]["before"]["top1"] for d in ["aishell", "tatoeba"]) > 0
    result = dict(stage="E37", developmentGatePassed=gate, domains=reports,
                  model=scorer.metadata, modelCalls=scorer.calls, independentlyMaskedInputs=scorer.masks,
                  totalInferenceNanos=scorer.inference_nanos, productionChanged=False,
                  decision="eligible-for-later-regression-and-device-feasibility" if gate else "reject-fixed-pure-PLL-reranker",
                  diagnosticExcludedFromGate=True, noParameterSweep=True, androidMeasured=False)
    write_new(output / "development-decision.json", result)
    print(f"E37 development gate: {gate}", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("root", type=Path); p.add_argument("model", type=Path); p.add_argument("output", type=Path)
    p.add_argument("--reproduce-rejected-checkpoint", action="store_true",
                   help="Replay disqualified RBT3 evidence only; not a production promotion")
    a = p.parse_args(); run(a.root.resolve(), a.model.resolve(), a.output.resolve(), a.reproduce_rejected_checkpoint)
