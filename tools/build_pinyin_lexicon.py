#!/usr/bin/env python3
"""Compile Sense's attributed pinyin sources into a compact binary index."""

from __future__ import annotations

import argparse
import math
import re
import struct
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


MAGIC = b"SPLX"
VERSION = 3
MAX_CANDIDATES = 128
MAX_PREFIX_LENGTH = 4
MAX_PREFIX_CANDIDATES = 16
PREFIX_COMPLETION_DECAY = 0.72
PREFIX_NAMESPACE = "{"


@dataclass(frozen=True)
class LexiconCandidate:
    text: str
    weight: int
    initials: str
    source_tier: int


def normalized_syllables(value: str) -> list[str]:
    normalized = value.lower().replace("u:", "v").replace("ü", "v")
    if re.search(r"[1-5]", normalized):
        result: list[str] = []
        current: list[str] = []
        for character in normalized:
            if "a" <= character <= "z":
                current.append(character)
            elif character in "12345":
                if current:
                    result.append("".join(current))
                    current.clear()
            elif current:
                result.append("".join(current))
                current.clear()
        if current:
            result.append("".join(current))
        return result
    return re.findall(r"[a-zv]+", normalized)


def is_han_text(value: str) -> bool:
    return bool(value) and all(
        "\u3400" <= character <= "\u4dbf"
        or "\u4e00" <= character <= "\u9fff"
        or "\uf900" <= character <= "\ufaff"
        for character in value
    )


def add_entry(
    entries: defaultdict[str, dict[str, LexiconCandidate]],
    text: str,
    raw_code: str,
    weight: int,
    syllables: set[str],
    source_tier: int = 0,
) -> None:
    tokens = normalized_syllables(raw_code)
    code = "".join(tokens)
    initials = "".join(token[0] for token in tokens)
    if not text or not code or not initials:
        return
    syllables.update(tokens)
    encoded_text = text.encode("utf-8")
    if len(code) > 255 or len(encoded_text) > 255 or len(initials) > 255:
        return
    candidate = LexiconCandidate(text, max(0, weight), initials, source_tier)
    previous = entries[code].get(text)
    if previous is None or candidate.source_tier < previous.source_tier or (
        candidate.source_tier == previous.source_tier
        and (
            candidate.weight > previous.weight
            or candidate.weight == previous.weight and candidate.initials < previous.initials
        )
    ):
        entries[code][text] = candidate


def read_dictionary(
    paths: list[Path],
    cedict_paths: list[Path],
) -> tuple[dict[str, list[LexiconCandidate]], set[str]]:
    entries: defaultdict[str, dict[str, LexiconCandidate]] = defaultdict(dict)
    syllables: set[str] = set()
    for path in paths:
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line or line.startswith("#") or "\t" not in line:
                continue
            fields = line.split("\t")
            if len(fields) < 2:
                continue
            text = fields[0].strip()
            raw_code = fields[1]
            try:
                weight = max(0, int(fields[2])) if len(fields) > 2 else 0
            except ValueError:
                weight = 0
            add_entry(entries, text, raw_code, weight, syllables)

    prefix_source = {code: dict(values) for code, values in entries.items()}
    cedict_pattern = re.compile(r"^\S+\s+(\S+)\s+\[{1,2}(.+?)\]{1,2}\s+/")
    cedict_total = 0
    cedict_parsed = 0
    cedict_added = 0
    cedict_duplicate = 0
    cedict_skipped = 0
    for path in cedict_paths:
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line or line.startswith("#"):
                continue
            cedict_total += 1
            match = cedict_pattern.match(line)
            if not match:
                cedict_skipped += 1
                continue
            text = match.group(1)
            tokens = normalized_syllables(match.group(2))
            if not is_han_text(text) or len(tokens) != len(text) or any(token not in syllables for token in tokens):
                cedict_skipped += 1
                continue
            cedict_parsed += 1
            code = "".join(tokens)
            if text in entries.get(code, {}):
                cedict_duplicate += 1
                continue
            add_entry(entries, text, match.group(2), 1, syllables, source_tier=1)
            cedict_added += 1

    ranked = {
        code: sorted(
            values.values(),
            key=lambda item: (item.source_tier, -item.weight, len(item.text), item.text, item.initials),
        )[:MAX_CANDIDATES]
        for code, values in entries.items()
    }
    prefix_ranked = {
        code: sorted(
            values.values(),
            key=lambda item: (item.source_tier, -item.weight, len(item.text), item.text, item.initials),
        )[:MAX_CANDIDATES]
        for code, values in prefix_source.items()
    }
    cedict_retained = sum(
        candidate.source_tier == 1
        for candidates in ranked.values()
        for candidate in candidates
    )
    if cedict_paths:
        print(
            "CC-CEDICT audit: "
            f"total={cedict_total}, parsed={cedict_parsed}, imported={cedict_added}, "
            f"retained={cedict_retained}, "
            f"duplicates={cedict_duplicate}, skipped={cedict_skipped}"
        )
    return add_statistical_prefixes(ranked, prefix_ranked), syllables


def add_statistical_prefixes(
    entries: dict[str, list[LexiconCandidate]],
    prefix_source: dict[str, list[LexiconCandidate]],
) -> dict[str, list[LexiconCandidate]]:
    """Build a namespaced prefix index without mutating real full-pinyin keys."""
    aggregate: defaultdict[str, dict[str, LexiconCandidate]] = defaultdict(dict)
    for code, candidates in prefix_source.items():
        for prefix_length in range(1, min(MAX_PREFIX_LENGTH, len(code) - 1) + 1):
            prefix = code[:prefix_length]
            completion = len(code) - prefix_length
            decay = math.pow(PREFIX_COMPLETION_DECAY, completion)
            for candidate in candidates:
                adjusted = max(1, round(candidate.weight * decay)) if candidate.weight > 0 else 0
                value = LexiconCandidate(candidate.text, adjusted, candidate.initials, candidate.source_tier)
                previous = aggregate[prefix].get(candidate.text)
                if previous is None or value.weight > previous.weight:
                    aggregate[prefix][candidate.text] = value

    result = dict(entries)
    for prefix, values in aggregate.items():
        result[PREFIX_NAMESPACE + prefix] = sorted(
            values.values(),
            key=lambda item: (item.source_tier, -item.weight, len(item.text), item.text, item.initials),
        )[:MAX_PREFIX_CANDIDATES]
    return result


def write_binary(entries: dict[str, list[LexiconCandidate]], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as stream:
        stream.write(MAGIC)
        stream.write(struct.pack(">HI", VERSION, len(entries)))
        for code in sorted(entries):
            code_bytes = code.encode("ascii")
            candidates = entries[code]
            stream.write(struct.pack("B", len(code_bytes)))
            stream.write(code_bytes)
            stream.write(struct.pack("B", len(candidates)))
            for candidate in candidates:
                text_bytes = candidate.text.encode("utf-8")
                initials_bytes = candidate.initials.encode("ascii")
                stream.write(struct.pack("B", len(text_bytes)))
                stream.write(text_bytes)
                stream.write(struct.pack(">I", min(candidate.weight, 0xFFFFFFFF)))
                stream.write(struct.pack("B", len(initials_bytes)))
                stream.write(initials_bytes)
                stream.write(struct.pack("B", candidate.source_tier))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--custom", action="append", type=Path, default=[])
    parser.add_argument("--cedict", action="append", type=Path, default=[])
    parser.add_argument("--syllables-output", type=Path)
    args = parser.parse_args()

    entries, syllables = read_dictionary([args.source, *args.custom], args.cedict)
    if not entries:
        raise SystemExit("No pinyin entries were read")
    write_binary(entries, args.output)
    if args.syllables_output:
        args.syllables_output.parent.mkdir(parents=True, exist_ok=True)
        args.syllables_output.write_text("\n".join(sorted(syllables)) + "\n", encoding="utf-8")
    print(f"Wrote {len(entries)} pinyin keys to {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
