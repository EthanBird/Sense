"""Compare dictionary-only experiments; retain every changed answer and rank loss."""
import argparse
import hashlib
import json
from pathlib import Path

from summarize_typo_replay import summary


def compare(before, after):
    if before.get("oovFeature", 0) != after.get("oovFeature", 0):
        raise ValueError("Dictionary-only replay changed OOV feature")
    for key in ("inputSha256", "candidateLimit", "graphDiagnosticLimit", "lmWeight",
                "correctionCompositionBoost", "learning", "sources", "progressiveJoints"):
        if before[key] != after[key]:
            raise ValueError("Dictionary-only replay changed " + key)
    for name, sha in before["assets"].items():
        if name != "pinyin_lexicon.bin" and after["assets"].get(name) != sha:
            raise ValueError("Other asset changed: " + name)
    a, b = before["observations"], after["observations"]
    if not a or len(a) != len(b) or len({r["id"] for r in a}) != len(a):
        raise ValueError("Missing or duplicated rows")
    changed = []
    def first(row):
        return row["top5"][0]["text"] if row["top5"] else ""
    for x, y in zip(a, b):
        for key in ("id", "sourceId", "typed", "effectiveQuery", "canonical", "expected", "operation", "stratum"):
            if x[key] != y[key]:
                raise ValueError("Unpaired observation: " + key)
        for row in (x, y):
            if not 0 <= row["rank"] <= row["candidateCount"] or len(row["top5"]) > row["candidateCount"]:
                raise ValueError("Invalid candidate rank/count")
        if x["rank"] != y["rank"] or first(x) != first(y):
            changed.append({"id": x["id"], "query": x["typed"], "expected": x["expected"],
                            "beforeRank": x["rank"], "afterRank": y["rank"],
                            "beforeTop1": first(x), "afterTop1": first(y),
                            "operation": x["operation"], "stratum": x["stratum"]})
    by_group = {}
    for key in ("operation", "stratum"):
        by_group[key] = {group: {"before": summary([r for r in a if r[key] == group]),
                                 "after": summary([r for r in b if r[key] == group])}
                         for group in sorted({r[key] for r in a})}
    return {"schemaVersion": 1, "scope": "Known isolated word / synthetic source reconstruction, not natural user accuracy",
            "before": summary(a), "after": summary(b), "byGroup": by_group, "changes": changed,
            "top1Gains": sum(r["afterRank"] == 1 and r["beforeRank"] != 1 for r in changed),
            "top1Losses": sum(r["beforeRank"] == 1 and r["afterRank"] != 1 for r in changed),
            "lexiconSha256": {"before": before["assets"]["pinyin_lexicon.bin"], "after": after["assets"]["pinyin_lexicon.bin"]}}


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("before", "after", "output"):
        p.add_argument(name, type=Path)
    args = p.parse_args()
    if args.output.exists():
        raise ValueError("Retain prior comparisons")
    result = compare(json.loads(args.before.read_text("utf-8")), json.loads(args.after.read_text("utf-8")))
    result["inputReports"] = {name: hashlib.sha256(getattr(args, name).read_bytes()).hexdigest() for name in ("before", "after")}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("before", "after", "top1Gains", "top1Losses")}))
