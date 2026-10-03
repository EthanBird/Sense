"""Summarize a complete ABBA system-input workload without hiding failed outputs."""
from collections import defaultdict
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics
from summarize_pinyin_cancellation import parse_trace


def metrics(values):
    ordered = sorted(values)
    return {"count": len(values), "medianMs": statistics.median(values),
            "observedP95Ms": ordered[math.ceil(len(values) * .95) - 1], "maxMs": max(values)}


def latency_regression_gate(summary):
    """Conservative workload guard, not a statistical or physical-device guarantee.

    Declare this rule before running a new variant: both observed aggregate median and
    P95 must be no worse than its paired reference. Still retain all per-query losses.
    A green functional suite alone must never silently approve a latency regression.
    """
    modes = summary["confirmationMs"]
    if any(modes[mode]["count"] != 32 for mode in ("baseline", "optimized")):
        raise ValueError("Expected 32 confirmations per mode")
    checks = {key: modes["optimized"][key] <= modes["baseline"][key]
              for key in ("medianMs", "observedP95Ms")}
    return {"scope": "Fixed ABBA workload only; not statistical significance or phone certification",
            "rule": "paired median and observed P95 each no worse than reference",
            "checks": checks, "passed": all(checks.values())}


def summarize(measurements, directory):
    runs = measurements["runs"]
    if [run["mode"] for run in runs] != ["baseline", "optimized", "optimized", "baseline"]:
        raise ValueError("Incomplete ABBA order")
    grouped = defaultdict(list)
    per_query = defaultdict(lambda: defaultdict(list))
    trace_reports = []
    queries = None
    for run in runs:
        rows = run["samples"]
        if not run["passed"] or len(rows) != 16 or not all(row["passed"] and row["actual"] == row["expectedBaselineOutput"] for row in rows):
            raise ValueError("Failed output or missing observations; do not report these as successful latency")
        identities = {(row["query"], row["round"]) for row in rows}
        if len(identities) != 16 or (queries is not None and queries != identities):
            raise ValueError("Workloads differ across blocks")
        queries = identities
        for row in rows:
            duration = row["spaceToEditorMs"]
            if duration < 0:
                raise ValueError("Negative latency")
            grouped[run["mode"]].append(duration)
            per_query[row["query"]][run["mode"]].append(duration)
        path = directory / run["artifactDirectory"] / "system.trace"
        trace = parse_trace(path.read_text("utf-8"))
        if not trace["completeTraceBuffers"] or trace["unfinishedDecodeSlices"] or not trace["decodeSlices"]:
            raise ValueError("Missing or incomplete application trace")
        canceled = [row for row in trace["decodeSlices"] if row["canceled"]]
        completed = [row for row in trace["decodeSlices"] if not row["canceled"]]
        trace_reports.append({"block": run["block"], "mode": run["mode"], "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                              "canceledDecodes": len(canceled), "completedDecodes": len(completed),
                              "completedDecodeMs": metrics([row["durationMs"] for row in completed])})
    return {"schemaVersion": 1, "scope": measurements["scope"], "apkSha256": measurements["apkSha256"],
            "limitations": ["Eight fixed known queries, four observations per query/mode, one API 37 x86_64 emulator and debug builds.",
                            "Observed percentiles describe this workload, not a population or real-phone tail-latency guarantee.",
                            "Includes input injection, system scheduling and polling; excludes initial asset loading. App atrace is enabled, not ART method profiling.",
                            "A known 类/累 semantic error is deliberately retained as baseline output; this is not an accuracy score.",
                            "Complete decode trace slices also include short prefixes completed while typing, not only final confirmations."],
            "confirmationMs": {mode: metrics(values) for mode, values in grouped.items()},
            "perQuery": [{"query": query, **{mode: {"samples": values, **metrics(values)} for mode, values in modes.items()}}
                         for query, modes in per_query.items()], "traces": trace_reports}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("measurements", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = summarize(json.loads(args.measurements.read_text("utf-8")), args.measurements.parent)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["confirmationMs"]))


if __name__ == "__main__":
    main()
