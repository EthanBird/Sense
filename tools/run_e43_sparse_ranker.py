"""Frozen E43 host trial, with current candidates and explicit E39 teacher reuse."""
import argparse
from collections import Counter
import datetime
import gzip
import json
from pathlib import Path
import subprocess
import time

from candidate_sparse_ranker import SparseScorer, train
from candidate_student import load_features, reorder_student, select_inputs
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from run_e37_rescoring import distribution, validate_baseline, write_new

read = lambda p: json.loads(p.read_text("utf-8"))


def check_reused_group(group, row):
    slots, evidence, reason = select_inputs(row["candidates"], row["evidence"])
    texts = [row["candidates"][i] for i in slots]
    if (reason or group["context"] != row["context"] or group["texts"] != texts
            or group["evidence"] != evidence or texts[group["gold"]] != row["expected"]):
        raise ValueError("Teacher reuse inputs differ from current production")


def run(root, out, old):
    policy_file = root / "benchmarks/corpus/e43-sparse-ranker-policy.json"
    policy = read(policy_file)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if subprocess.run(["git", "merge-base", "--is-ancestor", policy["sourceBaseCommit"], head], cwd=root).returncode:
        raise ValueError("Trial checkout is outside the declared source ancestry")
    old_lock = read(old / "pre-export-lock.json")
    teacher = read(old / "teacher-complete.json")
    for name, key in [("train.tsv", "trainSha256"), ("attribution.jsonl", "attributionSha256")]:
        if digest(old / name) != old_lock[key]:
            raise ValueError("Training source changed")
    for name, sha in old_lock["heldout"].items():
        if digest(root / name) != sha:
            raise ValueError("Held-out isolation inputs changed")
    if (not old_lock["corpusFamiliesDisjoint"] or teacher["qualification"]["passed"] is not True
            or digest(old / "training-groups.jsonl") != teacher["groupsSha256"]):
        raise ValueError("Unqualified or modified teacher groups")
    for name in ["qualified_masked_lm.py", "masked_lm_rescore.py", "fetch_e37_model.py"]:
        if digest(root / "tools" / name) != teacher["sourceFiles"]["tools/" + name]:
            raise ValueError("Teacher mathematics changed")
    rows, audit = load_features(root, old / "train.tsv", out / "train-features.jsonl.gz")
    groups = [json.loads(l) for l in (old / "training-groups.jsonl").read_text("utf-8").splitlines()]
    if len(groups) != teacher["groups"] or len({(g['id'], g['cut']) for g in groups}) != len(groups):
        raise ValueError("Teacher group count/identity mismatch")
    for g in groups:
        check_reused_group(g, rows[(g["id"], g["cut"])])
    # Independently compare all train states, not only the eligible teacher subset.
    with gzip.open(old / "train-features.jsonl.gz", "rt", encoding="utf-8") as f:
        old_fingerprints = {(r["id"], r["cut"]): r["resultSha256"]
                            for r in map(json.loads, f) if r["type"] == "row"}
    if {k: r["resultSha256"] for k, r in rows.items()} != old_fingerprints:
        raise ValueError("Full training candidate outputs changed")
    families = {r["id"]: r["group"] for r in read(old / "selected.json") if not r["exclusion"]}
    files = [policy_file, Path(__file__), root / "tools/candidate_sparse_ranker.py",
             root / "tools/test_candidate_sparse_ranker.py", root / "tools/candidate_student.py",
             root / "tools/evaluate_cross_domain.py"]
    lock = dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), checkout=head,
        sourceFiles={str(p.relative_to(root)): digest(p) for p in files},
        inputFiles={str(old / n): digest(old / n) for n in ["train.tsv", "attribution.jsonl", "selected.json",
            "pre-export-lock.json", "teacher-complete.json", "training-groups.jsonl", "train-features.jsonl.gz"]},
        freshExports={p.name: digest(p) for p in out.glob("*-features.jsonl.gz")},
        audit=audit, fullTrainStatesExactlyEqual=len(rows), currentTeacherGroupsExactlyEqual=len(groups),
        teacherRecomputed=False, teacherScope="Qualified E39 cached PLL; exact texts/context and all actual path inputs verified",
        noDevUsedInFit=True, policy=policy)
    write_new(out / "pre-fit-lock.json", lock)
    model, fit = train(groups, families)
    model_path = out / "model.json"
    write_new(model_path, model)
    write_new(out / "fit.json", fit)
    write_new(out / "pre-dev-freeze.json", dict(modelSha256=digest(model_path),
        fitSha256=digest(out / "fit.json"), lockSha256=digest(out / "pre-fit-lock.json"),
        createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), finalFitOnly=True))
    print(json.dumps({k: v for k, v in fit.items() if k != "lossHistory"}), flush=True)
    scorer = SparseScorer(read(model_path))
    zero = SparseScorer({**model, "weights": [0.0] * len(model["weights"])})
    correct_before = correct_after = 0
    for g in groups:
        assert zero(g["context"], g["texts"], g["evidence"]) == [e["baseline"] for e in g["evidence"]]
        scores = scorer(g["context"], g["texts"], g["evidence"])
        correct_before += max(range(len(scores)), key=lambda i: g["evidence"][i]["baseline"]) == g["gold"]
        correct_after += max(range(len(scores)), key=lambda i: scores[i]) == g["gold"]
    write_new(out / "train-control.json", dict(groups=len(groups), beforeTop1=correct_before,
        afterTop1=correct_after, zeroIncrementExact=True, scope="Training fit only, not quality validation"))
    evaluate(root, out)


def frozen_model(out):
    freeze = read(out / "pre-dev-freeze.json")
    for name, key in [("model.json", "modelSha256"), ("fit.json", "fitSha256"),
                      ("pre-fit-lock.json", "lockSha256")]:
        if digest(out / name) != freeze[key]:
            raise ValueError("Frozen trial artifact changed: " + name)
    return read(out / "model.json")


def evaluate(root, out):
    model = frozen_model(out)
    lock = read(out / "pre-fit-lock.json")
    for name, sha in lock["sourceFiles"].items():
        if digest(root / name) != sha:
            # The first driver stopped on a stale E42 CRLF source pin before
            # scoring any development row. Preserve that driver, then resume
            # only its frozen model on a freshly exported production baseline.
            archived = out / "run-before-fresh-baseline.py"
            if (Path(name).name != Path(__file__).name or not archived.is_file()
                    or digest(archived) != sha):
                raise ValueError("Frozen mathematical/test source changed: " + name)
    for name, sha in lock["freshExports"].items():
        if digest(out / name) != sha:
            raise ValueError("Frozen feature export changed: " + name)
    scorer = SparseScorer(model)
    zero = SparseScorer({**model, "weights": [0.0] * len(model["weights"])})
    model_path = out / "model.json"
    reports = {}
    sources = {"aishell": root / "benchmarks/corpus/p2c-aishell-v7/dev.tsv",
               "tatoeba": root / "benchmarks/corpus/p2c-supervised-v6/dev.tsv",
               "diagnostic": root / ".artifacts/input-quality/e28/diagnostic.tsv"}
    for domain, source in sources.items():
        rows, audit = load_features(root, source, out / (domain + "-features.jsonl.gz"))
        if domain != "diagnostic":
            _, baseline = validate_baseline(root, source, out / (domain + "-baseline.jsonl"))
            if rows.keys() != baseline.keys() or any(rows[k]["candidates"] != baseline[k]["candidates"]
                    or rows[k]["resultSha256"] != baseline[k]["resultSha256"] for k in rows):
                raise ValueError("Candidate export disagrees with actual production baseline")
        proposed, detail, timings = [], [], []
        reasons = Counter()
        for row in rows.values():
            base = row["candidates"]
            zero_order, _ = reorder_student(row["context"], base, row["evidence"], zero)
            if zero_order != base:
                raise ValueError("Zero residual reordered candidates")
            start = time.perf_counter_ns()
            actual, meta = reorder_student(row["context"], base, row["evidence"], scorer)
            elapsed = time.perf_counter_ns() - start
            again, again_meta = reorder_student(row["context"], base, row["evidence"], scorer)
            if again != actual or again_meta != meta or sorted(actual) != sorted(base):
                raise ValueError("Nondeterminism or candidate mutation")
            reasons[meta["reason"]] += 1
            if meta["reason"] == "scored":
                timings.append(elapsed)
            rank = actual.index(row["expected"]) + 1 if row["expected"] in actual else 0
            new = {**row, "candidates": actual, "rank": rank}
            proposed.append(new)
            detail.append({k: row[k] for k in ["id", "cut", "query", "context", "expected"]} |
                dict(beforeRank=row["rank"], afterRank=rank, beforeCandidates=base,
                     afterCandidates=actual, metadata=meta, hostNanos=elapsed))
        before, after = metrics(list(rows.values())), metrics(proposed)
        report = dict(before=before, after=after, nonregression=nonregression(before, after),
            firstGains=[r for r in detail if r["beforeRank"] != 1 and r["afterRank"] == 1],
            firstLosses=[r for r in detail if r["beforeRank"] == 1 and r["afterRank"] != 1],
            rankLosses=[r for r in detail if r["beforeRank"] and (not r["afterRank"] or r["afterRank"] > r["beforeRank"])],
            reasons=dict(reasons), timing=distribution(timings), exactZeroResidual=True,
            candidateSetsUnchanged=True, repeatedScoresEqual=True, audit=audit)
        write_new(out / (domain + "-details.json"), detail)
        write_new(out / (domain + "-comparison.json"), report)
        reports[domain] = report
        print(domain, json.dumps({k: report[k] for k in ["before", "after", "nonregression", "reasons", "timing"]}), flush=True)
    quality = (all(reports[d]["nonregression"] for d in ["aishell", "tatoeba"])
               and sum(reports[d]["after"]["top1"] - reports[d]["before"]["top1"] for d in ["aishell", "tatoeba"]) > 0)
    cost = all(reports[d]["timing"].get("p95Ms", float("inf")) <= 100 for d in ["aishell", "tatoeba"])
    write_new(out / "decision.json", dict(qualityGate=quality, hostCostGate=cost,
        eligibleForFurtherValidation=quality and cost, productionChanged=False,
        modelSha256=digest(model_path), modelBytes=model_path.stat().st_size,
        scope="Known development sets; host-only offline fixed-pool reordering; no Android or release adoption"))


if __name__ == "__main__":
    p = argparse.ArgumentParser(__doc__)
    for name in ["root", "output", "e39"]:
        p.add_argument(name, type=Path)
    p.add_argument("--evaluate-frozen", action="store_true",
                   help="Resume evaluation only; validate frozen model, fit, source and feature identities")
    a = p.parse_args()
    if a.evaluate_frozen:
        evaluate(a.root.resolve(), a.output.resolve())
    else:
        run(a.root.resolve(), a.output.resolve(), a.e39.resolve())
