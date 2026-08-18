#!/usr/bin/env python3
"""Build Sense's compact, deterministic Wubi86 lexicon from a pinned Rime source."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


MAGIC = b"SWBX"
VERSION = 1
MAX_EXACT_CANDIDATES = 128
MAX_COMPLETION_CANDIDATES = 16
MAX_REVERSE_CODES = 8
VALID_CODE = re.compile(r"^[a-y]{1,4}$")


@dataclass(frozen=True)
class Source:
    source_id: str
    path: Path
    sha256: str


@dataclass(frozen=True)
class Entry:
    text: str
    code: str
    weight: int


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> tuple[dict[str, object], list[Source]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schema_version") != 1:
        raise ValueError("Unsupported Wubi source manifest schema")
    upstream = document.get("upstream")
    if not isinstance(upstream, dict) or upstream.get("license") != "LGPL-3.0":
        raise ValueError("Wubi manifest must retain the LGPL-3.0 source license")
    root = path.parent.resolve()
    sources: list[Source] = []
    for item in document.get("sources", []):
        source_path = (root / item["path"]).resolve()
        if root not in source_path.parents:
            raise ValueError(f"Wubi source leaves manifest root: {source_path}")
        expected = str(item["sha256"]).lower()
        actual = sha256(source_path)
        if actual != expected:
            raise ValueError(f"Wubi source hash mismatch: {source_path}")
        sources.append(Source(str(item["id"]), source_path, actual))
    if not sources:
        raise ValueError("Wubi source manifest is empty")
    return document, sources


def parse_weight(raw: str) -> int:
    value = raw.strip()
    if not value:
        return 0
    if value.endswith("%"):
        value = value[:-1]
    try:
        return min(0xFFFFFFFF, max(0, round(float(value))))
    except ValueError:
        return 0


def read_dictionary(path: Path) -> tuple[list[Entry], dict[str, int]]:
    entries: dict[tuple[str, str], Entry] = {}
    stats = defaultdict(int)
    in_body = False
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if raw_line == "...":
            in_body = True
            continue
        if not in_body or not raw_line or raw_line.startswith("#"):
            continue
        fields = raw_line.split("\t")
        if len(fields) < 2:
            stats["malformed"] += 1
            continue
        text = fields[0].strip()
        code = fields[1].strip().lower()
        if code.startswith("z"):
            stats["excluded_z"] += 1
            continue
        if not text or not VALID_CODE.fullmatch(code):
            stats["invalid"] += 1
            continue
        encoded = text.encode("utf-8")
        if len(encoded) > 0xFFFF:
            stats["oversize"] += 1
            continue
        entry = Entry(text, code, parse_weight(fields[2] if len(fields) > 2 else ""))
        key = (code, text)
        previous = entries.get(key)
        if previous is None or entry.weight > previous.weight:
            entries[key] = entry
        stats["accepted"] += 1
    return list(entries.values()), dict(stats)


def rank(values: list[Entry]) -> list[Entry]:
    return sorted(values, key=lambda item: (-item.weight, len(item.text), item.text, item.code))


def group_exact(entries: list[Entry]) -> dict[str, list[Entry]]:
    groups: defaultdict[str, list[Entry]] = defaultdict(list)
    for entry in entries:
        groups[entry.code].append(entry)
    return {code: rank(values)[:MAX_EXACT_CANDIDATES] for code, values in groups.items()}


def group_completions(exact: dict[str, list[Entry]]) -> dict[str, list[Entry]]:
    groups: defaultdict[str, dict[str, Entry]] = defaultdict(dict)
    for code, values in exact.items():
        for length in range(1, min(3, len(code) - 1) + 1):
            prefix = code[:length]
            for entry in values:
                previous = groups[prefix].get(entry.text)
                if previous is None or (
                    entry.weight,
                    -len(entry.code),
                    entry.code,
                ) > (
                    previous.weight,
                    -len(previous.code),
                    previous.code,
                ):
                    groups[prefix][entry.text] = entry
    return {
        prefix: rank(list(values.values()))[:MAX_COMPLETION_CANDIDATES]
        for prefix, values in groups.items()
    }


def is_han_code_point(code_point: int) -> bool:
    return (
        0x3400 <= code_point <= 0x4DBF
        or 0x4E00 <= code_point <= 0x9FFF
        or 0xF900 <= code_point <= 0xFAFF
        or 0x20000 <= code_point <= 0x323AF
    )


def build_reverse(exact: dict[str, list[Entry]]) -> dict[int, list[str]]:
    by_code_point: defaultdict[int, dict[str, int]] = defaultdict(dict)
    for values in exact.values():
        for entry in values:
            if len(entry.text) != 1:
                continue
            code_point = ord(entry.text)
            if not is_han_code_point(code_point):
                continue
            by_code_point[code_point][entry.code] = max(
                entry.weight,
                by_code_point[code_point].get(entry.code, 0),
            )
    return {
        code_point: [
            code
            for code, _ in sorted(
                codes.items(),
                key=lambda item: (len(item[0]), -item[1], item[0]),
            )[:MAX_REVERSE_CODES]
        ]
        for code_point, codes in by_code_point.items()
    }


def write_candidate(stream, entry: Entry) -> None:
    code = entry.code.encode("ascii")
    text = entry.text.encode("utf-8")
    stream.write(struct.pack("B", len(code)))
    stream.write(code)
    stream.write(struct.pack(">H", len(text)))
    stream.write(text)
    stream.write(struct.pack(">I", entry.weight))


def write_group(stream, code: str, values: list[Entry]) -> None:
    encoded_code = code.encode("ascii")
    stream.write(struct.pack("B", len(encoded_code)))
    stream.write(encoded_code)
    stream.write(struct.pack(">H", len(values)))
    for value in values:
        write_candidate(stream, value)


def write_binary(
    path: Path,
    exact: dict[str, list[Entry]],
    completions: dict[str, list[Entry]],
    reverse: dict[int, list[str]],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as stream:
        stream.write(MAGIC)
        stream.write(struct.pack(">HIII", VERSION, len(exact), len(completions), len(reverse)))
        for code in sorted(exact):
            write_group(stream, code, exact[code])
        for prefix in sorted(completions):
            write_group(stream, prefix, completions[prefix])
        for code_point in sorted(reverse):
            codes = reverse[code_point]
            stream.write(struct.pack(">IB", code_point, len(codes)))
            for code in codes:
                encoded = code.encode("ascii")
                stream.write(struct.pack("B", len(encoded)))
                stream.write(encoded)


def write_stats(
    path: Path,
    asset: Path,
    manifest_path: Path,
    manifest: dict[str, object],
    sources: list[Source],
    source_stats: dict[str, int],
    exact: dict[str, list[Entry]],
    completions: dict[str, list[Entry]],
    reverse: dict[int, list[str]],
) -> None:
    payload = {
        "schema_version": 1,
        "format": f"SWBX/{VERSION}",
        "asset": {"bytes": asset.stat().st_size, "sha256": sha256(asset)},
        "upstream": manifest["upstream"],
        "manifest": {
            "path": manifest_path.name,
            "sha256": sha256(manifest_path),
            "sources": [
                {"id": source.source_id, "path": source.path.name, "sha256": source.sha256}
                for source in sources
            ],
        },
        "counts": {
            "exact_codes": len(exact),
            "exact_candidates": sum(map(len, exact.values())),
            "completion_prefixes": len(completions),
            "completion_candidates": sum(map(len, completions.values())),
            "reverse_characters": len(reverse),
        },
        "source_audit": source_stats,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def build(manifest_path: Path, output: Path, stats_output: Path | None = None) -> None:
    manifest, sources = load_manifest(manifest_path)
    dictionary = next((source for source in sources if source.path.name.endswith(".dict.yaml")), None)
    if dictionary is None:
        raise ValueError("Wubi manifest has no dictionary source")
    entries, source_stats = read_dictionary(dictionary.path)
    if not entries:
        raise ValueError("Wubi source produced no entries")
    exact = group_exact(entries)
    completions = group_completions(exact)
    reverse = build_reverse(exact)
    write_binary(output, exact, completions, reverse)
    if stats_output is not None:
        write_stats(
            stats_output,
            output,
            manifest_path,
            manifest,
            sources,
            source_stats,
            exact,
            completions,
            reverse,
        )
    print(
        f"Wrote {len(exact)} exact codes, {len(completions)} prefixes and "
        f"{len(reverse)} reverse entries to {output} ({output.stat().st_size} bytes)"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--stats-output", type=Path)
    args = parser.parse_args()
    build(args.manifest, args.output, args.stats_output)


if __name__ == "__main__":
    main()
