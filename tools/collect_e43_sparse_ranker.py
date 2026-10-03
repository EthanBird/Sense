"""Audit and archive E43 including its rejected fit and pre-evaluation driver failure."""
import argparse
import datetime
import gzip
import hashlib
import json
import math
from pathlib import Path

from audit_score_features import FEATURES
from candidate_sparse_ranker import SparseScorer, feature_vector
from candidate_student import load_features
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from run_e37_rescoring import write_new
from run_e43_sparse_ranker import frozen_model

read = lambda p: json.loads(p.read_text("utf-8"))


def collect(root, out):
    model = frozen_model(out)
    scorer = SparseScorer(model)
    decision = read(out / "decision.json")
    if digest(out / "model.json") != decision["modelSha256"]:
        raise ValueError("Decision model identity differs")
    sources = {"aishell": root / "benchmarks/corpus/p2c-aishell-v7/dev.tsv",
               "tatoeba": root / "benchmarks/corpus/p2c-supervised-v6/dev.tsv",
               "diagnostic": root / ".artifacts/input-quality/e28/diagnostic.tsv"}
    reports, diagnostic_terms = {}, {}
    for domain, source in sources.items():
        rows, _ = load_features(root, source, out / (domain + "-features.jsonl.gz"))
        detail = read(out / (domain + "-details.json"))
        if [(d["id"], d["cut"]) for d in detail] != list(rows):
            raise ValueError("Missing/reordered evidence states")
        before, after = [], []
        explanations = []
        for d in detail:
            row = rows[(d["id"], d["cut"])]
            old, new = d["beforeCandidates"], d["afterCandidates"]
            if old != row["candidates"] or sorted(old) != sorted(new):
                raise ValueError("Baseline/candidate identity changed")
            rank = lambda c: c.index(row["expected"]) + 1 if row["expected"] in c else 0
            if d["beforeRank"] != rank(old) or d["afterRank"] != rank(new):
                raise ValueError("Incorrect recorded rank")
            before.append({**row, "candidates": old, "rank": rank(old)})
            after.append({**row, "candidates": new, "rank": rank(new)})
            meta = d["metadata"]
            if meta["reason"] == "scored":
                texts = [old[i] for i in meta["slots"]]
                evidence = [row["evidence"][t] for t in texts]
                scores = scorer(row["context"], texts, evidence)
                if scores != meta["scores"]:
                    raise ValueError("Frozen-model score replay differs")
                order = sorted(range(len(scores)), key=lambda i: (-scores[i], i))
                expected_order = old.copy()
                for dest, src in zip(meta["slots"], order):
                    expected_order[dest] = texts[src]
                if expected_order != new:
                    raise ValueError("Ranked array differs from recorded scores")
            elif old != new:
                raise ValueError("Protected row reordered")
            if (d["beforeRank"] == 1) == (d["afterRank"] == 1):
                continue
            x = feature_vector(row["context"], old[0], row["evidence"][old[0]], scorer.index)
            y = feature_vector(row["context"], new[0], row["evidence"][new[0]], scorer.index)
            terms = []
            for i in sorted(x.keys() | y.keys()):
                delta = (y.get(i, 0) - x.get(i, 0)) * scorer.weights[i]
                name = (model["vocabulary"][i] if i < len(model["vocabulary"]) else
                        "actual:" + (FEATURES + ["SOURCE_PRIOR"])[i - len(model["vocabulary"])])
                if delta:
                    terms.append(dict(feature=name, newMinusOldRawResidual=delta))
            a = sum(scorer.weights[i] * v for i, v in x.items())
            b = sum(scorer.weights[i] * v for i, v in y.items())
            if abs(sum(t["newMinusOldRawResidual"] for t in terms) - (b - a)) >= 1e-10:
                raise ValueError("Residual explanation lost a component")
            explanations.append(dict(expected=row["expected"], context=row["context"], before=old[0], after=new[0],
                rawResidualBefore=a, rawResidualAfter=b, boundedDeltaBefore=8*math.tanh(a/8),
                boundedDeltaAfter=8*math.tanh(b/8), terms=sorted(terms, key=lambda t: -abs(t["newMinusOldRawResidual"]))))
        report = read(out / (domain + "-comparison.json"))
        if metrics(before) != report["before"] or metrics(after) != report["after"]:
            raise ValueError("Stored quality metrics differ")
        if nonregression(report["before"], report["after"]) != report["nonregression"]:
            raise ValueError("Nonregression decision differs")
        reports[domain] = report
        diagnostic_terms[domain] = explanations
        if domain != "diagnostic":
            current = [json.loads(l) for l in (out / (domain + "-baseline.jsonl")).read_text("utf-8").splitlines()]
            previous = [json.loads(l) for l in (out.parent / "e42" / ("candidate-" + domain + ".jsonl")).read_text("utf-8").splitlines()]
            strip = lambda stream: [{k: v for k, v in row.items() if k != "hostNanos"}
                                    for row in stream if row["type"] == "row"]
            if strip(current) != strip(previous):
                raise ValueError("Fresh production baseline differs from E42")
    if diagnostic_terms != read(out / "score-change-diagnostics.json"):
        raise ValueError("Independent residual explanation differs")
    quality = (all(reports[d]["nonregression"] for d in ["aishell", "tatoeba"])
        and sum(reports[d]["after"]["top1"] - reports[d]["before"]["top1"] for d in ["aishell", "tatoeba"]) > 0)
    if quality != decision["qualityGate"]:
        raise ValueError("Quality gate differs")
    pins = read(out.parent / "release-0.4.16-rc.5/input-source-pins.json")
    for name, sha in pins.items():
        if digest(root / name) != sha:
            raise ValueError("Production source changed: " + name)
    release = read(root / "benchmarks/results/release-v0.4.16-rc.5.json")
    apk = root / "build/releases/v0.4.16-rc.5/Sense-v0.4.16-rc.5.apk"
    if digest(apk) != release["apkSha256"]:
        raise ValueError("Published local APK changed")
    e39 = read(root / "benchmarks/results/e39-evidence-manifest.json")
    lock = read(out / "pre-fit-lock.json")
    for path, sha in lock["inputFiles"].items():
        entry = e39["files"]["external/e39/final/" + Path(path).name]
        if entry["sha256"] != sha or digest(Path(path)) != sha:
            raise ValueError("Teacher/training dependency differs from archived E39")
    log = (out / "final-tests.log").read_text("utf-8")
    if "Ran 61 tests" not in log or not log.rstrip().endswith("OK"):
        raise ValueError("Final tool suite did not pass")
    write_new(out / "closure-check.json", dict(checkedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        productionSourceFilesUnchanged=len(pins), releaseApkSha256=digest(apk),
        e39InputDependenciesVerified=len(lock["inputFiles"]), finalTests=61,
        freshE42BaselinesExactlyEqual=True, reloadedModelScoresExactlyEqual=True,
        independentMetricsAndResidualTermsEqual=True, oldTestAndAndroidNotExecuted=True))
    files = {"raw/" + p.name: p for p in out.iterdir() if p.is_file()}
    for name in ["tools/candidate_sparse_ranker.py", "tools/run_e43_sparse_ranker.py",
                 "tools/collect_e43_sparse_ranker.py", "tools/test_candidate_sparse_ranker.py",
                 "tools/test_e43_frozen_trial.py", "benchmarks/corpus/e43-sparse-ranker-policy.json",
                 "docs/research/input-quality-stage-e43-2026-10-04.md",
                 "docs/development/input-quality-program-v2.md"]:
        files["workspace/" + name] = root / name
    folder = root / "benchmarks/results/e43-evidence"
    folder.mkdir(exist_ok=False)
    manifest = dict(schemaVersion=1, rawDirectory=str(out), files={},
        dependencies={name: digest(root / f"benchmarks/results/{name}-evidence-manifest.json") for name in ["e39", "e42"]},
        scope="Rejected host-only model. Training attribution/teacher groups inherited via the pinned E39 dependency; rc.5 production unchanged.")
    for name, path in sorted(files.items()):
        data = path.read_bytes(); packed = gzip.compress(data, mtime=0)
        target = folder / (hashlib.sha256(name.encode()).hexdigest()[:20] + ".gz")
        target.write_bytes(packed)
        manifest["files"][name] = dict(archive=target.relative_to(root).as_posix(),
            sha256=hashlib.sha256(data).hexdigest(), bytes=len(data), archiveSha256=digest(target))
    mp = root / "benchmarks/results/e43-evidence-manifest.json"
    write_new(mp, manifest)
    gate = {**decision, "stage": "E43", "modelParameters": read(out / "fit.json")["parameters"],
            "reports": {d: {k: r[k] for k in ["before", "after", "nonregression", "reasons", "timing"]}
                        for d, r in reports.items()}, "finalTests": 61,
            "evidenceManifestSha256": digest(mp), "archives": len(manifest["files"]),
            "goalComplete": False, "publishedApkUnchanged": True}
    write_new(root / "benchmarks/results/e43-acceptance-gate.json", gate)
    print(json.dumps({k: gate[k] for k in ["qualityGate", "hostCostGate", "modelParameters", "archives", "finalTests"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("root", type=Path); parser.add_argument("output", type=Path)
    args = parser.parse_args()
    collect(args.root.resolve(), args.output.resolve())
