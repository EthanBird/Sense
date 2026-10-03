"""Paired OOV-only host ablation; dictionary and all other decoder inputs stay fixed."""
import argparse
import hashlib
import json
from pathlib import Path

from compare_lexicon_replays import compare as compare_dictionary


def compare(before, after):
    if before["assets"] != after["assets"]:
        raise ValueError("OOV ablation changed assets")
    values = {key: report.get("oovFeature", 0) for key, report in (("before", before), ("after", after))}
    if any(not isinstance(v, (float, int)) or not -6 <= v <= 0 for v in values.values()):
        raise ValueError("Invalid bounded OOV feature")
    # Explicitly allow just this field; delegate all row/configuration pairing checks.
    result = compare_dictionary({**before, "oovFeature": 0}, {**after, "oovFeature": 0})
    result["scope"] = "OOV-only host diagnostic on known reconstruction sets, not a production adoption or independent audit"
    result["oovFeature"] = values
    return result


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
