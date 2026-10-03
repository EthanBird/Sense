#!/usr/bin/env python3
"""Diagnostic only: probe an LM on recorded M8 top-three candidates, never runtime routing.

Reports the entire fixed weight sweep, including losses. It cannot recover candidates outside
the saved top three and is not a held-out P2C benchmark or evidence of integrated beam search.
"""
import argparse
import json
import math
from pathlib import Path

from audit_sentence_corpus import han, sha256
from train_character_lm import BOS, read_model


def contextual_feature(model, text, context, mode):
    left = []
    for ch in context:
        if han(ch):
            left.append(ord(ch))
        else:
            left.clear()
    a, b = ([BOS, BOS] + left)[-2:]
    value = 0.0
    for c in map(ord, text):
        value += model.log_probability(a, b, c)
        if mode == "pmi":
            value -= model.log_probability(a, b, c, order=1)
        a, b = b, c
    return value / max(len(text), 1)


def probe(model, observations, mode, weight):
    correct = improvements = regressions = 0
    changes = []
    for row in observations:
        choices = row["top3"]
        if not choices or any(not t or not all(han(c) for c in t) for t in choices):
            raise ValueError("Probe expects nonempty Han-only candidates")
        accepted = {row["expected"], *row.get("aliases", [])}
        scores = [-math.log(i + 1) + weight * contextual_feature(model, text, row.get("context", ""), mode)
                  for i, text in enumerate(choices)]
        picked = max(range(len(choices)), key=lambda i: scores[i])
        before = choices[0] in accepted
        after = choices[picked] in accepted
        correct += after
        improvements += after and not before
        regressions += before and not after
        if picked:
            changes.append({"query": row["query"], "expected": row["expected"], "before": choices[0], "after": choices[picked], "afterCorrect": after})
    return {"mode": mode, "weight": weight, "top1": correct, "improvements": improvements, "regressions": regressions, "changes": changes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("m8_report", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    model = read_model(args.model.read_bytes())
    rows = json.loads(args.m8_report.read_text(encoding="utf-8"))["observations"]
    probes = [probe(model, rows, mode, weight) for mode in ("logp", "pmi") for weight in (0.0, 0.25, 0.5, 1.0, 2.0, 4.0)]
    report = {"schemaVersion": 1, "modelSha256": sha256(args.model), "m8ReportSha256": sha256(args.m8_report),
              "scriptSha256": sha256(Path(__file__)), "cases": len(rows),
              "scope": "recorded top3, rank prior -ln(rank), mean-character LM feature; developmental probe only",
              "top3Oracle": sum(any(c in {r["expected"], *r.get("aliases", [])} for c in r["top3"]) for r in rows),
              "sweep": probes, "productionEnabled": False,
              "next": "integrate state into candidate/word search, evaluate a new frozen P2C set, and retain individual losses"}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for p in probes:
        print(f"{p['mode']} weight={p['weight']}: top1={p['top1']}/{len(rows)}, improvements={p['improvements']}, regressions={p['regressions']}")


if __name__ == "__main__":
    main()
