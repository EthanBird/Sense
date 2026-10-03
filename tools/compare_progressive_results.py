"""Compare every full result hash and metadata, keeping timing separate from equivalence."""
import argparse
from collections import defaultdict
import hashlib
import gzip
import json
from pathlib import Path
from summarize_input_latency import metrics


def compare(before, after):
    for field in ("schemaVersion", "candidateLimit", "weight", "learning", "inputs", "assets"):
        if before.get(field) != after.get(field):
            raise ValueError(f"Changed benchmark configuration: {field}")
    old, new = before["observations"], after["observations"]
    if not old or len(old) != len(new):
        raise ValueError("Missing or mismatched observation counts")
    mismatches = []
    for index, (a, b) in enumerate(zip(old, new)):
        if {k: v for k, v in a.items() if k != "decodeNs"} != {k: v for k, v in b.items() if k != "decodeNs"}:
            mismatches.append({"index": index, "before": a, "after": b})
    timings = {}
    for label, rows in [("before", old), ("after", new)]:
        groups = defaultdict(list)
        for row in rows:
            groups[f"pass{row['pass']}-{row['group']}"].append(row["decodeNs"] / 1e6)
        timings[label] = {group: metrics(values) for group, values in groups.items()}
    return {"schemaVersion": 1, "scope": "LM 0.5 full progressive result equality on known corpora; host-JVM timing is diagnostic, not Android latency",
            "observations": len(old), "identical": len(old) - len(mismatches), "mismatches": mismatches,
            "passed": not mismatches, "hostDecodeMs": timings,
            "changedSourceFiles": sorted(name for name, digest in after["sources"].items() if before["sources"].get(name) != digest)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    def read(path):
        payload = gzip.decompress(path.read_bytes()) if path.suffix == ".gz" else path.read_bytes()
        return json.loads(payload.decode("utf-8"))
    result = compare(read(args.before), read(args.after))
    result["inputSha256"] = {label: hashlib.sha256(path.read_bytes()).hexdigest() for label, path in [("before", args.before), ("after", args.after)]}
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"identical={result['identical']}/{result['observations']}")
    if not result["passed"]:
        raise SystemExit("Candidate/provenance/order/score changed; inspect all mismatches")


if __name__ == "__main__": main()
