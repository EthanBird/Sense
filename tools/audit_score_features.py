"""Independently check actual-path exports; no fitting and no label-derived feature weights."""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import math
from pathlib import Path
import struct

FEATURES = ["LOG_FREQUENCY", "FALLBACK_TIER", "UNIGRAM_MASS", "NORMALIZATION_ANCHOR",
            "INTERNAL_BIGRAM", "WORD_BOUNDARY", "INITIALS_LENGTH", "MIXED_TYPED",
            "COMPLETION", "PERSONAL_BASE", "PERSONAL_FLOOR", "PERSONAL_ADJUSTMENT",
            "SPELLING", "CHARACTER_LM", "CORRECTION_CALIBRATION", "EXTERNAL_BIGRAM"]
PRIORITY = dict(BASE_EXACT=0, BASE_COMPOSED=1, USER_FULL=2, USER_INITIALS=3,
                BASE_HYBRID=4, BASE_INITIALS=5, CORRECTED=6, BASE_PREFIX=7,
                ENGLISH_EXACT=8, ENGLISH_PREFIX=9, WUBI_EXACT=0, WUBI_COMPLETION=7)


def f32(value):
    return struct.unpack("!f", struct.pack("!f", value))[0]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_row(row):
    require(row["pool"], "empty feature pool")
    winners = {}
    max_error = 0.0
    for index, path in enumerate(row["pool"]):
        vector = path["features"]
        require(len(vector) == len(FEATURES) and all(math.isfinite(x) for x in vector), "bad feature vector")
        require(all(math.isfinite(path[k]) for k in ("score", "prior", "total", "arithmeticError", "normalizer")), "nonfinite score")
        require(path["normalizer"] > 0, "bad normalizer")
        error = f32(path["score"]) - math.fsum(vector)
        require(abs(error) <= .0002, "feature sum differs from candidate score")
        require(abs(error - path["arithmeticError"]) <= 1e-10, "incorrect arithmetic error")
        require(f32(f32(path["score"]) + f32(path["prior"])) == f32(path["total"]), "incorrect total")
        require(path["segments"] == len(path["edges"]), "incorrect path segments")
        require("".join(edge[0] for edge in path["edges"]) == path["text"], "incorrect lexical edges")
        require(all(len(edge) == 4 and edge[1] for edge in path["edges"]), "missing lexical provenance")
        divisor = f32(path["normalizer"])
        lexical = math.fsum(f32(math.log(edge[2] + 1)) for edge in path["edges"] if edge[2] is not None) / divisor
        tier = sum(-1.0 for edge in path["edges"] if edge[3] is not None and edge[3] >= 1) / divisor
        require(abs(vector[FEATURES.index("LOG_FREQUENCY")] - lexical) < 1e-9, "frequency differs from actual edge weights")
        require(abs(vector[FEATURES.index("FALLBACK_TIER")] - tier) < 1e-9, "tier score differs from actual edge tiers")
        key = (-f32(path["total"]), PRIORITY[path["kind"]], len(path["text"]), path["text"].encode("utf-16-be"))
        previous = winners.get(path["text"])
        if previous is None or key < previous[0]:
            winners[path["text"]] = (key, index)
        max_error = max(max_error, abs(error))
    indices = [index for _, index in sorted(winners.values())[:255]]
    require(indices == row["winnerIndices"], "exported winning paths disagree with independent ranking")
    return max_error


def positive_rescaling_obstacle(row):
    """A diagnostic, not an accuracy label. Check EVERY retained path for the reference text.

    If one wrong path dominates all reference paths in all exported additive components,
    strictly positive rescaling of these components alone will still prefer that wrong path
    (ignoring Float rounding). Richer/new features or a new pool would be required.
    """
    top = row["pool"][row["winnerIndices"][0]]
    if top["text"] == row["expected"]:
        return None
    targets = [path for path in row["pool"] if path["text"] == row["expected"]]
    if not targets:
        return None
    for target in targets:
        delta = [a - b for a, b in zip(top["features"] + [top["prior"]], target["features"] + [target["prior"]])]
        if not all(d >= 0 for d in delta) or not any(d > .0002 for d in delta):
            return None
    return dict(id=row["id"], cut=row["cut"], context=row["context"], query=row["query"],
                first=top["text"], expected=row["expected"], referencePaths=len(targets),
                firstMinusReference={name: top["features"][i] - targets[0]["features"][i]
                                    for i, name in enumerate(FEATURES) if top["features"][i] != targets[0]["features"][i]},
                sourcePriorDifference=top["prior"] - targets[0]["prior"])


def audit(export: Path, baseline: Path | None = None):
    before = {}
    if baseline:
        with baseline.open(encoding="utf-8") as handle:
            for row in csv.DictReader((line for line in handle if not line.startswith("#")), delimiter="\t"):
                key = (row["id"], int(row["cut"]), row["mode"])
                require(key not in before, "duplicate baseline key")
                before[key] = row
    keys = set(); paths = 0; max_error = 0.0; summary = None; header = None
    first = top5 = recall = pool_recall = 0
    feature_nonzero = dict.fromkeys(FEATURES, 0)
    obstacles = []
    with gzip.open(export, "rt", encoding="utf-8") as handle:
        for ordinal, line in enumerate(handle):
            row = json.loads(line)
            if ordinal == 0:
                require(row["type"] == "header" and row["schemaVersion"] == 1 and row["features"] == FEATURES, "bad header")
                header = row
                continue
            require(summary is None, "data follows summary")
            if row["type"] == "summary":
                summary = row
                continue
            require(row["type"] == "row", "unknown row")
            key = (row["id"], row["cut"], row["mode"])
            require(key not in keys, "duplicate export key")
            keys.add(key)
            max_error = max(max_error, check_row(row))
            paths += len(row["pool"])
            for path in row["pool"]:
                for name, value in zip(FEATURES, path["features"]):
                    feature_nonzero[name] += value != 0
            first += row["rank"] == 1
            top5 += 0 < row["rank"] <= 5
            recall += row["rank"] > 0
            pool_recall += any(path["text"] == row["expected"] for path in row["pool"])
            if row["mode"] == "editor":
                obstacle = positive_rescaling_obstacle(row)
                if obstacle:
                    obstacles.append(obstacle)
            if baseline:
                require(key in before, "export row absent from baseline")
                old = before[key]
                require(all(row[k] == old[k] for k in ("context", "query", "expected", "stratum")), "changed replay labels")
                require(row["resultSha256"] == old["resultSha256"], "complete progressive result differs from pre-change bytecode")
                require(row["rank"] == int(old["rank"]), "changed target rank")
    require(header is not None and summary is not None and keys, "incomplete export")
    require(summary["rows"] == len(keys) and summary["paths"] == paths and summary["observationEquivalent"] is True, "summary mismatch")
    require(abs(max_error - summary["maxArithmeticError"]) < 1e-10, "summary arithmetic mismatch")
    if baseline:
        require(keys == set(before), "baseline row coverage mismatch")
    return dict(schemaVersion=1, decision="instrumentation-checked", rows=len(keys), paths=paths,
                maxArithmeticError=max_error, independentRankerAgreement=True,
                observationEquivalent=True, preChangeCompleteResultsCompared=len(keys) if baseline else 0,
                first=first, top5=top5, recall255=recall, preRankerPoolRecall=pool_recall,
                featureNonzeroPaths=feature_nonzero, exportSha256=hashlib.sha256(export.read_bytes()).hexdigest(),
                strictlyPositiveRescalingObstacles=obstacles,
                obstacleScope="Known editor-context rows only; all reference paths checked, exported additive components plus source prior; ignores Float rounding. Not a verdict on reference acceptability, unconstrained weights, richer features or changed search.",
                baselineSha256=hashlib.sha256(baseline.read_bytes()).hexdigest() if baseline else None,
                rankingWeightsTrained=False, qualityImprovementClaimed=False, goalComplete=False)


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("export", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    require(not args.output.exists(), "retain previous evidence")
    result = audit(args.export, args.baseline)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
