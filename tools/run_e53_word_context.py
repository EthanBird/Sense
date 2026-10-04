"""One fixed current-decoder lexical-context experiment; retain failures and old APK."""
import argparse
from collections import Counter
import datetime
import gzip
import json
from pathlib import Path
import subprocess
import time

from candidate_student import load_features, reorder_student
from compact_m19_training import extract
from context_ranker_cv import evaluate_groups
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from run_e37_rescoring import distribution, write_new
from word_context_ranker import WordContextScorer, fit


def read(path):
    return json.loads(path.read_text("utf-8"))


def attach_words(row, evidence):
    """Use ONLY the already audited winner, never another path favored by the reference."""
    from candidate_student import candidate_evidence
    winners = [row["pool"][i] for i in row["winnerIndices"]]
    by_text = {c["text"]: c for c in winners}
    if len(by_text) != len(winners):
        raise ValueError("Duplicate winner")
    for text, item in evidence.items():
        candidate = by_text[text]
        if candidate_evidence(candidate) != item:
            raise ValueError("Evidence no longer names the baseline winning path")
        words = [edge[0] for edge in candidate["edges"]]
        if not words or any(not word for word in words) or "".join(words) != text:
            raise ValueError("Inconsistent actual word edges")
        item["words"] = words
    return [c["text"] for c in winners]


def enrich_training(groups, export):
    by_key = {(g["id"], g["cut"]): g for g in groups}
    if len(by_key) != len(groups):
        raise ValueError("Duplicate training group")
    seen = set()
    with gzip.open(export, "rt", encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            if row["type"] != "row":
                continue
            key = (row["id"], row["cut"])
            if key not in by_key:
                continue
            if key in seen:
                raise ValueError("Duplicate enrichment row")
            seen.add(key); group = by_key[key]
            evidence = dict(zip(group["texts"], group["evidence"]))
            candidates = attach_words(row, evidence)
            group["originalTop8"] = candidates[:8]
            group["slots"] = [candidates.index(t) for t in group["texts"]]
            if group["slots"] != sorted(set(group["slots"])) or max(group["slots"]) >= 8:
                raise ValueError("Changed actual first-eight slots")
    if seen != by_key.keys():
        raise ValueError("Missing enrichment row")


def enrich_evaluation(rows, export):
    seen = set()
    with gzip.open(export, "rt", encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            if row["type"] != "row":
                continue
            key = (row["id"], row["cut"])
            if key in seen or key not in rows:
                raise ValueError("Unexpected evaluation row")
            seen.add(key)
            if attach_words(row, rows[key]["evidence"]) != rows[key]["candidates"]:
                raise ValueError("Changed candidate order")
    if seen != rows.keys():
        raise ValueError("Missing evaluation row")


def evaluate(rows, scorer):
    details, after, times = [], [], []
    reasons = Counter()
    for row in rows.values():
        original = row["candidates"]
        start = time.perf_counter_ns()
        proposed, meta = reorder_student(row["context"], original, row["evidence"], scorer)
        elapsed = time.perf_counter_ns() - start
        repeated, repeated_meta = reorder_student(row["context"], original, row["evidence"], scorer)
        if proposed != repeated or meta != repeated_meta or sorted(proposed) != sorted(original):
            raise ValueError("Unstable scores or changed membership")
        reasons[meta["reason"]] += 1
        if meta["reason"] == "scored":
            times.append(elapsed)
        rank = proposed.index(row["expected"]) + 1 if row["expected"] in proposed else 0
        after.append({**row, "candidates": proposed, "rank": rank})
        details.append({k: row[k] for k in ["id", "cut", "mode", "stratum", "query", "context", "expected"]} |
            dict(beforeRank=row["rank"], afterRank=rank, beforeCandidates=original,
                 afterCandidates=proposed, metadata=meta, hostNanos=elapsed))
    before = list(rows.values())
    b, a = metrics(before), metrics(after)
    return details, dict(before=b, after=a, nonregression=nonregression(b, a),
        modes={mode: dict(before=metrics([r for r in before if r["mode"] == mode]),
                         after=metrics([r for r in after if r["mode"] == mode]))
               for mode in sorted({r["mode"] for r in before})},
        reasons=dict(reasons), timing=distribution(times),
        firstGains=[d for d in details if d["beforeRank"] != 1 and d["afterRank"] == 1],
        firstLosses=[d for d in details if d["beforeRank"] == 1 and d["afterRank"] != 1],
        rankLosses=[d for d in details if d["beforeRank"] and (not d["afterRank"] or d["afterRank"] > d["beforeRank"])])


def run(root, out):
    policy = root / "benchmarks/corpus/e53-word-context-policy.json"
    export_lock = read(out / "export-lock.json")
    if digest(policy) != export_lock["policySha256"]:
        raise ValueError("Policy changed after export")
    if digest(out / "baseline-core.jar") != export_lock["jarSha256"]:
        raise ValueError("Decoder bytecode changed")
    old = out.parent / "e44/train"
    preparation = read(old / "pre-export-lock.json")
    if digest(old / "pre-export-lock.json") != export_lock["trainingPreparationSha256"]:
        raise ValueError("Training provenance changed")
    for name, sha in preparation["outputs"].items():
        if digest(old / name) != sha:
            raise ValueError("Training data changed")
    for name, sha in preparation["heldout"].items():
        if digest(root / name) != sha:
            raise ValueError("Heldout isolation target changed")
    selected = read(old / "selected.json")
    groups, audit = extract(root, old / "expanded.tsv", out / "train-features.jsonl.gz",
                            out / "base-training-groups.jsonl", selected)
    enrich_training(groups, out / "train-features.jsonl.gz")
    with (out / "word-training-groups.jsonl").open("x", encoding="utf-8", newline="\n") as f:
        for group in groups:
            f.write(json.dumps(group, ensure_ascii=False) + "\n")
    write_new(out / "training-audit.json", audit)
    families = {r["id"]: r["group"] for r in selected if not r["exclusion"]}
    source_names = ["word_context_ranker.py", "test_word_context_ranker.py", "run_e53_word_context.py",
                    "test_e53_word_evidence.py", "context_only_ranker.py", "candidate_sparse_ranker.py",
                    "compact_m19_training.py", "candidate_student.py", "audit_score_features.py",
                    "evaluate_cross_domain.py", "masked_lm_rescore.py", "context_ranker_cv.py"]
    source_pins = {"tools/" + n: digest(root / "tools" / n) for n in source_names}
    inputs = ["train-features.jsonl.gz", "aishell-features.jsonl.gz", "tatoeba-features.jsonl.gz",
              "word-training-groups.jsonl", "export-lock.json"]
    write_new(out / "pre-fit-lock.json", dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        head=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        sourceFiles=source_pins, policySha256=digest(policy), inputs={p: digest(out / p) for p in inputs},
        trainingPreparationSha256=digest(old / "pre-export-lock.json"), noDevFeaturesUsedForFit=True))
    frozen = {}; models = {}; converged = True
    for name, include_words in [("control", False), ("joint", True)]:
        print(f"Fitting {name}: {len(groups)} source-isolated groups", flush=True)
        model, report = fit(groups, families, include_words)
        scorer = WordContextScorer(model)
        zero = WordContextScorer({**model, "weights": [0.] * len(model["weights"])})
        for g in groups:
            if zero(g["context"], g["texts"], g["evidence"]) != [e["baseline"] for e in g["evidence"]]:
                raise ValueError("Zero residual changed original score")
        predictions, train_metrics = evaluate_groups(groups, scorer)
        write_new(out / (name + "-model.json"), model)
        write_new(out / (name + "-fit.json"), report | dict(trainingMetrics=train_metrics, zeroResidualExact=True))
        write_new(out / (name + "-training-predictions.json"), predictions)
        frozen[name] = dict(modelSha256=digest(out / (name + "-model.json")),
                            fitSha256=digest(out / (name + "-fit.json")))
        models[name] = scorer; converged &= report["converged"]
        print(name, json.dumps({k: v for k, v in report.items() if k != "lossHistory"}), train_metrics, flush=True)
    write_new(out / "pre-dev-freeze.json", dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        fits=frozen, soleCandidate="joint", lockSha256=digest(out / "pre-fit-lock.json")))
    if not converged:
        write_new(out / "decision.json", dict(eligibleForFurtherValidation=False, reason="fit-not-converged",
                                              productionChanged=False))
        return
    for path, sha in source_pins.items():
        if digest(root / path) != sha:
            raise ValueError("Frozen experiment code changed")
    reports = {name: {} for name in models}
    for domain, source in [("aishell", "p2c-aishell-v7"), ("tatoeba", "p2c-supervised-v6")]:
        export = out / (domain + "-features.jsonl.gz")
        rows, audit = load_features(root, root / "benchmarks/corpus" / source / "dev.tsv", export)
        enrich_evaluation(rows, export)
        write_new(out / (domain + "-audit.json"), audit)
        for name, scorer in models.items():
            details, report = evaluate(rows, scorer)
            write_new(out / (name + "-" + domain + "-details.json"), details)
            write_new(out / (name + "-" + domain + "-comparison.json"), report)
            reports[name][domain] = report
            print(name, domain, json.dumps({k: report[k] for k in ["before", "after", "nonregression", "timing"]}), flush=True)
    candidate = reports["joint"]
    quality = (all(r["nonregression"] for r in candidate.values())
               and sum(r["after"]["top1"] - r["before"]["top1"] for r in candidate.values()) > 0)
    cost = all(r["timing"].get("p95Ms", float("inf")) <= 100 for r in candidate.values())
    write_new(out / "decision.json", dict(qualityGate=quality, hostCostGate=cost,
        eligibleForFurtherValidation=quality and cost, soleCandidate="joint", controlSelectable=False,
        productionChanged=False, androidTested=False, modelSha256=digest(out / "joint-model.json"),
        modelBytes=(out / "joint-model.json").stat().st_size, goalComplete=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("root", type=Path); parser.add_argument("output", type=Path)
    args = parser.parse_args(); run(args.root.resolve(), args.output.resolve())
