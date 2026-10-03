"""Streaming full-path audit to bounded eligible groups; reference never enters features."""
from collections import Counter
import gzip
import json

from audit_score_features import FEATURES, check_row
from candidate_student import candidate_evidence, select_inputs
from evaluate_cross_domain import workload
from fetch_e37_model import digest


def extract(root, input_tsv, export, output, selected):
    expected = workload(input_tsv.read_text("utf-8"))
    metadata = {r["id"]: r for r in selected if not r["exclusion"]}
    seen, reasons, paths, summary = set(), Counter(), 0, None
    groups = []
    source_pins = {p.relative_to(root).as_posix(): digest(p)
                   for p in (root / "core-input/src/main/kotlin").rglob("*.kt")}
    with gzip.open(export, "rt", encoding="utf-8") as f:
        header = json.loads(next(f))
        if (header.get("type") != "header" or header.get("mode") != "sampled"
                or header.get("featureVersion") != "actual-path-v1"
                or header.get("inputSha256") != digest(input_tsv)
                or header.get("sources") != source_pins or header.get("features") != FEATURES
                or header.get("configuration") != dict(lmWeight=.5,oovFeature=-4,correctionBoost=12,limit=255)):
            raise ValueError("Training export identity changed")
        for name, sha in header["assets"].items():
            if digest(root / "ime-service/src/main/assets" / name) != sha:
                raise ValueError("Training asset changed")
        for line in f:
            row = json.loads(line)
            if summary is not None:
                raise ValueError("Data after summary")
            if row["type"] == "summary":
                summary = row; continue
            key = (row["id"], row["cut"])
            if (key in seen or key not in expected or row["id"] not in metadata
                    or any(row[k] != v for k,v in expected[key].items())):
                raise ValueError("Incomplete or changed workload")
            seen.add(key); paths += len(row["pool"]); check_row(row)
            winners = [row["pool"][i] for i in row["winnerIndices"]]
            candidates = [c["text"] for c in winners]
            rank = candidates.index(row["expected"]) + 1 if row["expected"] in candidates else 0
            if rank != row["rank"]:
                raise ValueError("Reference rank does not match whole candidate array")
            evidence = {c["text"]: candidate_evidence(c) for c in winners[:8]}
            slots, items, reason = select_inputs(candidates, evidence)
            texts = [candidates[i] for i in slots]
            if not reason and row["expected"] not in texts:
                reason = "target-not-recalled" if rank == 0 else "target-outside-top8-eligible"
            reasons[reason or "retained"] += 1
            if not reason:
                groups.append(dict(id=row["id"], cut=row["cut"], context=row["context"], texts=texts,
                    evidence=items, gold=texts.index(row["expected"]), cohort=metadata[row["id"]]["cohort"],
                    resultSha256=row["resultSha256"]))
            if len(seen) % 1000 == 0:
                print(f"Audited {len(seen)} states / {paths} actual paths", flush=True)
    if (seen != expected.keys() or not summary or summary["rows"] != len(seen)
            or summary["paths"] != paths or summary["observationEquivalent"] is not True):
        raise ValueError("Incomplete full-path evidence")
    with output.open("x", encoding="utf-8", newline="\n") as f:
        for group in groups:
            f.write(json.dumps(group, ensure_ascii=False, sort_keys=True) + "\n")
    return groups, dict(states=len(seen), actualPaths=paths, retainedGroups=len(groups),
        reasons=dict(reasons), sourceSha256=digest(input_tsv), exportSha256=digest(export),
        groupsSha256=digest(output), streamedOneFullPathRowAtATime=True,
        scope="Every actual path independently audited, only bounded candidate groups retained in memory")
