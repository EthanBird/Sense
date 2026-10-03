"""Fixed-pool diagnosis; production replay is required before adopting a source calibration."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import struct
from datetime import datetime, timezone

TIE = {k: v for v, k in enumerate(["BASE_EXACT", "BASE_COMPOSED", "USER_FULL", "USER_INITIALS",
       "BASE_HYBRID", "BASE_INITIALS", "CORRECTED", "BASE_PREFIX", "ENGLISH_EXACT", "ENGLISH_PREFIX"])}


def f32(value):
    return struct.unpack("f", struct.pack("f", value))[0]


def prior(kind, exact, composed):
    return {"BASE_EXACT": 8 if exact else 1.2,
            "BASE_COMPOSED": 6 if composed and not exact else .55,
            "BASE_HYBRID": .55, "BASE_INITIALS": .1, "BASE_PREFIX": -.75,
            "CORRECTED": -13 if exact else -8 if composed else -.25}[kind]


def rank(row, boost):
    best = {}
    for text, score, kind, language, canonical in row["pool"]:
        adjustment = boost if kind == "CORRECTED" and row["composed"] and not row["exact"] else 0
        # Production applies the calibrated residual once to Candidate.score, then the
        # shared source prior. Keep JVM float rounding and UTF-16 lexical tie order.
        total = f32(f32(f32(score) + f32(adjustment)) + f32(prior(kind, row["exact"], row["composed"])))
        utf16 = text.encode("utf-16-be")
        key = (-total, TIE[kind], len(utf16) // 2, utf16)
        if text not in best or key < best[text][0]:
            best[text] = (key, text, kind, total)
    return sorted(best.values())[:255]


def edit_distance(a, b):
    previous = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        current = [i]
        for j, y in enumerate(b, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j-1] + (x != y)))
        previous = current
    return previous[-1]


def summarize(rows, boost):
    groups = defaultdict(Counter)
    observations = []
    for row in rows:
        ordered = rank(row, boost)
        texts = [r[1] for r in ordered]
        place = texts.index(row["expected"]) + 1 if row["expected"] in texts else 0
        errors = edit_distance(texts[0] if texts else "", row["expected"])
        for group in ["all", row["operation"]]:
            bucket = groups[group]
            bucket.update({"rows": 1, "top1": place == 1, "top3": 0 < place <= 3,
                           "top10": 0 < place <= 10, "covered": place > 0,
                           "characterErrors": errors, "characters": len(row["expected"])})
        observations.append({"id": row["id"], "operation": row["operation"], "rank": place,
                             "top1": texts[0] if texts else "", "topKind": ordered[0][2] if ordered else "",
                             "characterErrors": errors})
    return {"boost": boost, "groups": dict(groups), "observations": observations}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("input", type=Path)
    p.add_argument("output", type=Path)
    args = p.parse_args()
    if args.output.exists():
        raise ValueError("Keep earlier sweep evidence")
    header, *rows = [json.loads(line) for line in args.input.read_text(encoding="utf-8").splitlines()]
    assert len(rows) == header["rows"]
    assert all([r[1] for r in rank(row, 0)][:10] == row["actual"] for row in rows)
    trials = [summarize(rows, boost) for boost in (0, 4, 8, 10, 12, 13, 14, 16)]
    baseline = trials[0]
    baseline_observations = baseline["observations"]
    for trial in trials:
        changes = [(a, b, source) for a, b, source in zip(baseline_observations, trial["observations"], rows)
                   if a["top1"] != b["top1"] or a["rank"] != b["rank"]]
        trial["changes"] = [{"typed": source["typed"], "expected": source["expected"], "before": a, "after": b}
                            for a, b, source in changes]
        trial["cleanTopTextChanges"] = sum(a["top1"] != b["top1"] and source["operation"] == "clean"
                                         for a, b, source in changes)
        trial.pop("observations")
    # The sweep/criterion were designed after exploratory development inspection.
    # They will be frozen before the new sentence audit, not described as pre-registered.
    eligible = [r for r in trials if r["cleanTopTextChanges"] == 0 and
                all(r["groups"]["clean"][k] >= baseline["groups"]["clean"][k] for k in ("top1", "top3", "top10")) and
                r["groups"]["all"]["characterErrors"] <= baseline["groups"]["all"]["characterErrors"]]
    selected = min(eligible, key=lambda r: (-r["groups"]["all"]["top1"], -r["groups"]["all"]["top10"], r["boost"]))
    result = {"schemaVersion": 1, "inputSha256": hashlib.sha256(args.input.read_bytes()).hexdigest(),
              "sourceMetadata": header, "createdAt": datetime.now(timezone.utc).isoformat(),
              "scope": "Known development fixed-pool analysis; joint controls were normalized to letters by progressive decoder",
              "selectionTiming": "Rule formulated after exploratory dev inspection, before new P2C audit output",
              "rule": "Preserve every clean top text, clean Top1/3/10 counts and overall CER; maximize overall Top1, Top10, then prefer smaller boost",
              "selectedBoost": selected["boost"], "trials": trials}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for trial in trials:
        print(trial["boost"], trial["groups"]["all"], "cleanTopTextChanges", trial["cleanTopTextChanges"])
    print("Selected", selected["boost"])


if __name__ == "__main__":
    main()
