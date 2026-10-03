#!/usr/bin/env python3
"""Summarize observed confirmation times and classified Android trace slices.

This is a small-sample diagnostic, not a tail-latency or significance estimate.
Canceled work is never counted as a completed decode. Baseline APKs without
custom sections have missing decode measurements, not zero-duration work.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
import statistics


EVENT = re.compile(r"-(\d+)\s+\(\s*\d+\)\s+\[\d+\].*?\s(\d+\.\d+): tracing_mark_write: (.*)$")


def parse_trace(text):
    stacks = defaultdict(list)
    slices = []
    for line in text.splitlines():
        match = EVENT.search(line)
        if not match:
            continue
        tid, timestamp, event = match.groups()
        time = float(timestamp)
        stack = stacks[tid]
        if event.startswith("B|"):
            name = event.split("|", 2)[2]
            if name == "Sense.Pinyin.canceled":
                for item in reversed(stack):
                    if item["name"] == "Sense.Pinyin.decode":
                        item["canceled"] = True
                        break
            stack.append({"name": name, "start": time, "canceled": False})
        elif event == "E" or event.startswith("E|"):
            if not stack:
                continue  # tracing may start inside an unrelated section
            item = stack.pop()
            if item["name"] == "Sense.Pinyin.decode":
                slices.append({"threadId": int(tid), "canceled": item["canceled"],
                               "durationMs": round((time - item["start"]) * 1000, 3)})
    unfinished = sum(item["name"] == "Sense.Pinyin.decode" for stack in stacks.values() for item in stack)
    buffers = [(int(a), int(b)) for a, b in re.findall(r"entries-in-buffer/entries-written:\s*(\d+)/(\d+)", text)]
    return {"completeTraceBuffers": bool(buffers) and all(a == b for a, b in buffers),
            "unfinishedDecodeSlices": unfinished, "decodeSlices": slices}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("measurements", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Use a new report path; retained evidence is immutable")
    measurements = json.loads(args.measurements.read_text("utf-8"))
    by_mode = defaultdict(list)
    runs = []
    for row in measurements["samples"]:
        trace_path = args.measurements.parent / row["artifactDirectory"] / "system.trace"
        trace = parse_trace(trace_path.read_text("utf-8"))
        if not trace["completeTraceBuffers"] or trace["unfinishedDecodeSlices"]:
            raise ValueError(f"Incomplete trace: {trace_path}")
        if row["mode"] == "cancellable" and not trace["decodeSlices"]:
            raise ValueError("Instrumented APK has no observed decode slices")
        if not row["passed"] or row["spaceToEditorMs"] is None:
            raise ValueError("A sampled editor confirmation did not pass")
        by_mode[row["mode"]].append(row["spaceToEditorMs"])
        runs.append({**row, "traceSha256": hashlib.sha256(trace_path.read_bytes()).hexdigest(), **trace})
    output = {"schemaVersion": 1, "scope": measurements["scope"],
              "limitations": ["Small fixed-order sample per APK, one sentence, one emulator; not p95 or statistical evidence of a speedup.",
                              "Waited for production assets; no personal learning; does not measure cold-loading confirmation.",
                              "Baseline APK has no custom decode slices. Canceled durations are aborted work, not completed decode latency."],
              "confirmationMs": {mode: {"samples": values, "median": statistics.median(values)} for mode, values in by_mode.items()},
              "runs": runs}
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output["confirmationMs"]))
    for row in runs:
        print(row["artifactDirectory"], "canceled", sum(s["canceled"] for s in row["decodeSlices"]),
              "completed", [s["durationMs"] for s in row["decodeSlices"] if not s["canceled"]])


if __name__ == "__main__":
    main()
