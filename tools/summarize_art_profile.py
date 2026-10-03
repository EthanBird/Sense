"""Decode non-streaming ART v3 dual-clock diagnostics; not uninstrumented latency.

Record layout: AOSP platform/art/runtime/trace.cc, kTraceRecordSizeDualClock.
Only the requested thread inside the progressive decoder contributes CPU samples.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import struct


def summarize(data, thread_name="sense-candidate-decoder", include_all_methods=False,
              root_method="io.github.ethanbird.senseime.core.AdaptivePinyinDecoder.decodeProgressively"):
    end = data.index(b"*end\n") + 5
    header = data[:end].decode("utf-8")
    if "clock=dual\n" not in header or "data-file-overflow=false\n" not in header or "is_streaming=true" in header:
        raise ValueError("Expected a complete, non-streaming dual-clock trace")
    section = ""
    methods, threads = {}, {}
    for line in header.splitlines():
        if line.startswith("*"):
            section = line
        elif section == "*threads":
            identifier, name = line.split("\t", 1)
            threads[int(identifier)] = name
        elif section == "*methods":
            fields = line.split("\t")
            methods[int(fields[0], 16)] = fields[1] + "." + fields[2]
    magic, version, offset, _start = struct.unpack_from("<4sHHQ", data, end)
    size = struct.unpack_from("<H", data, end + 16)[0]
    if magic != b"SLOW" or version != 3 or size != 14 or offset < 18 or (len(data) - end - offset) % size:
        raise ValueError("Unsupported or truncated ART record layout")
    stacks, clocks = defaultdict(list), {}
    exclusive, inclusive = Counter(), Counter()
    root = root_method
    for position in range(end + offset, len(data), size):
        tid, encoded, cpu, _wall = struct.unpack_from("<HIII", data, position)
        method, action = encoded & ~3, encoded & 3
        if method not in methods or tid not in threads:
            raise ValueError("Unknown method or thread")
        stack = stacks[tid]
        if tid in clocks:
            duration = cpu - clocks[tid]
            if duration < 0:
                raise ValueError("CPU clock wrapped or moved backwards")
            names = [methods[item] for item in stack]
            if threads[tid] == thread_name and root in names:
                exclusive[names[-1]] += duration
                inclusive.update({name: duration for name in set(names)})
        clocks[tid] = cpu
        if action == 0:
            stack.append(method)
        elif action in (1, 2) and stack and stack[-1] == method:
            stack.pop()
        else:
            raise ValueError("Unbalanced method record")
    if not exclusive:
        raise ValueError("No requested root/thread CPU intervals observed")
    return {"schemaVersion": 1, "scope": "Intrusive ART method trace diagnostic, not normal latency; intervals assigned from recorded thread CPU clocks",
            "traceSha256": hashlib.sha256(data).hexdigest(), "thread": thread_name,
            "observedDecodeCpuUs": sum(exclusive.values()), "exclusiveCpuUs": exclusive.most_common(None if include_all_methods else 30),
            "inclusiveCpuUs": inclusive.most_common(None if include_all_methods else 50)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = summarize(args.trace.read_bytes())
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"decodeCpuUs": result["observedDecodeCpuUs"], "top5": result["exclusiveCpuUs"][:5]}))


if __name__ == "__main__":
    main()
