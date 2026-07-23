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
INITIALS_NAMESPACE = "~"
HYBRID_NAMESPACE = "}"
HYBRID_SEPARATOR = "|"
MIN_INITIALS_LENGTH = 2
MAX_INITIALS_LENGTH = 12
MAX_INITIALS_CANDIDATES = 32
MAX_HYBRID_CANDIDATES = 64


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
    prefixed = add_statistical_prefixes(ranked, prefix_ranked)
    hybrid = add_hybrid_index(prefixed, ranked, syllables)
    return add_initials_index(hybrid, ranked), syllables


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


def add_initials_index(
    entries: dict[str, list[LexiconCandidate]],
    full_pinyin_source: dict[str, list[LexiconCandidate]],
) -> dict[str, list[LexiconCandidate]]:
    """Index exact syllable initials without changing full-pinyin records."""
    aggregate: defaultdict[str, dict[str, LexiconCandidate]] = defaultdict(dict)
    for candidates in full_pinyin_source.values():
        for candidate in candidates:
            initials = candidate.initials
            if len(initials) not in range(MIN_INITIALS_LENGTH, MAX_INITIALS_LENGTH + 1):
                continue
            previous = aggregate[initials].get(candidate.text)
            if previous is None or _candidate_is_better(candidate, previous):
                aggregate[initials][candidate.text] = candidate

    result = {code: values for code, values in entries.items() if not code.startswith(INITIALS_NAMESPACE)}
    for initials, values in aggregate.items():
        result[INITIALS_NAMESPACE + initials] = sorted(
            values.values(),
            key=lambda item: (item.source_tier, -item.weight, len(item.text), item.text, item.initials),
        )[:MAX_INITIALS_CANDIDATES]
    return result


def add_hybrid_index(
    entries: dict[str, list[LexiconCandidate]],
    full_pinyin_source: dict[str, list[LexiconCandidate]],
    syllables: set[str],
) -> dict[str, list[LexiconCandidate]]:
    """Index `full-pinyin prefix + remaining syllable initials` forms.

    The canonical full pinyin follows a separator in the private namespaced
    key. Runtime lookup scans `}typed|...`, retaining canonical metadata for
    adaptive learning without changing the v3 binary candidate record. Every
    proper syllable boundary is indexed, so both `zhongwsrf` and
    `zhongwensrf` resolve to the same canonical phrase.
    """
    aggregate: defaultdict[str, dict[str, LexiconCandidate]] = defaultdict(dict)
    for full_code, candidates in full_pinyin_source.items():
        for candidate in candidates:
            tokens = split_code_by_initials(full_code, candidate.initials, syllables)
            if not tokens or len(tokens) < 2:
                continue
            for full_prefix_count in range(1, len(tokens)):
                mixed = (
                    "".join(tokens[:full_prefix_count])
                    + "".join(token[0] for token in tokens[full_prefix_count:])
                )
                if mixed in (full_code, candidate.initials):
                    continue
                key = HYBRID_NAMESPACE + mixed + HYBRID_SEPARATOR + full_code
                if len(key.encode("ascii")) > 255:
                    continue
                previous = aggregate[key].get(candidate.text)
                if previous is None or _candidate_is_better(candidate, previous):
                    aggregate[key][candidate.text] = candidate

    result = {
        code: values
        for code, values in entries.items()
        if not code.startswith(HYBRID_NAMESPACE)
    }
    for code, values in aggregate.items():
        result[code] = sorted(
            values.values(),
            key=lambda item: (item.source_tier, -item.weight, len(item.text), item.text, item.initials),
        )[:MAX_HYBRID_CANDIDATES]
    return result


def split_code_by_initials(
    code: str,
    initials: str,
    syllables: set[str],
) -> tuple[str, ...] | None:
    """Recover the unique syllable path represented by a record's initials."""
    if not code or not initials:
        return None
    memo: dict[tuple[int, int], list[tuple[str, ...]]] = {}

    def visit(offset: int, initial_index: int) -> list[tuple[str, ...]]:
        state = (offset, initial_index)
        if state in memo:
            return memo[state]
        if initial_index == len(initials):
            return [()] if offset == len(code) else []
        if offset >= len(code) or code[offset] != initials[initial_index]:
            return []

        matches: list[tuple[str, ...]] = []
        for end in range(offset + 1, min(len(code), offset + 6) + 1):
            token = code[offset:end]
            if token not in syllables:
                continue
            for tail in visit(end, initial_index + 1):
                matches.append((token, *tail))
                if len(matches) > 1:
                    memo[state] = matches
                    return matches
        memo[state] = matches
        return matches

    paths = visit(0, 0)
    return paths[0] if len(paths) == 1 else None


def _candidate_is_better(candidate: LexiconCandidate, previous: LexiconCandidate) -> bool:
    return (
        candidate.source_tier < previous.source_tier
        or candidate.source_tier == previous.source_tier
        and (
            candidate.weight > previous.weight
            or candidate.weight == previous.weight and candidate.initials < previous.initials
        )
    )


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


def read_binary(path: Path) -> dict[str, list[LexiconCandidate]]:
    """Read a v3 SPLX asset so a pinned release asset can be enhanced deterministically."""
    data = path.read_bytes()
    if len(data) < 10 or data[:4] != MAGIC:
        raise ValueError("Pinyin lexicon header is invalid")
    version, record_count = struct.unpack_from(">HI", data, 4)
    if version != VERSION or record_count <= 0:
        raise ValueError(f"Unsupported pinyin lexicon version: {version}")
    offset = 10
    result: dict[str, list[LexiconCandidate]] = {}
    for _ in range(record_count):
        if offset >= len(data):
            raise ValueError("Pinyin lexicon record is truncated")
        code_length = data[offset]
        offset += 1
        code_end = offset + code_length
        if code_length == 0 or code_end >= len(data):
            raise ValueError("Pinyin code is truncated")
        code = data[offset:code_end].decode("ascii")
        offset = code_end
        candidate_count = data[offset]
        offset += 1
        candidates: list[LexiconCandidate] = []
        for _ in range(candidate_count):
            if offset >= len(data):
                raise ValueError("Pinyin candidate is truncated")
            text_length = data[offset]
            offset += 1
            text_end = offset + text_length
            if text_length == 0 or text_end + 6 > len(data):
                raise ValueError("Pinyin candidate text is truncated")
            text = data[offset:text_end].decode("utf-8")
            offset = text_end
            weight = struct.unpack_from(">I", data, offset)[0]
            offset += 4
            initials_length = data[offset]
            offset += 1
            initials_end = offset + initials_length
            if initials_length == 0 or initials_end >= len(data):
                raise ValueError("Pinyin candidate initials are truncated")
            initials = data[offset:initials_end].decode("ascii")
            offset = initials_end
            source_tier = data[offset]
            offset += 1
            if source_tier not in (0, 1):
                raise ValueError("Pinyin candidate source tier is invalid")
            candidates.append(LexiconCandidate(text, weight, initials, source_tier))
        result[code] = candidates
    if offset != len(data):
        raise ValueError("Pinyin lexicon has trailing bytes")
    return result


def augment_binary(
    base: Path,
    custom_paths: list[Path],
) -> tuple[dict[str, list[LexiconCandidate]], set[str]]:
    """Overlay project phrases and rebuild the exact-initials index on a pinned SPLX asset."""
    existing = read_binary(base)
    full: defaultdict[str, dict[str, LexiconCandidate]] = defaultdict(dict)
    for code, candidates in existing.items():
        if code.startswith((PREFIX_NAMESPACE, INITIALS_NAMESPACE, HYBRID_NAMESPACE)):
            continue
        for candidate in candidates:
            full[code][candidate.text] = candidate

    syllables: set[str] = {
        code
        for code, candidates in full.items()
        if len(code) <= 6 and any(len(candidate.initials) == 1 for candidate in candidates.values())
    }
    for path in custom_paths:
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line or line.startswith("#") or "\t" not in line:
                continue
            fields = line.split("\t")
            try:
                weight = max(0, int(fields[2])) if len(fields) > 2 else 0
            except ValueError:
                weight = 0
            add_entry(full, fields[0].strip(), fields[1], weight, syllables)

    ranked = {
        code: sorted(
            values.values(),
            key=lambda item: (item.source_tier, -item.weight, len(item.text), item.text, item.initials),
        )[:MAX_CANDIDATES]
        for code, values in full.items()
    }
    preserved = {
        code: values
        for code, values in existing.items()
        if not code.startswith((INITIALS_NAMESPACE, HYBRID_NAMESPACE))
    }
    preserved.update(ranked)
    hybrid = add_hybrid_index(preserved, ranked, syllables)
    return add_initials_index(hybrid, ranked), syllables


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--custom", action="append", type=Path, default=[])
    parser.add_argument("--cedict", action="append", type=Path, default=[])
    parser.add_argument("--syllables-output", type=Path)
    parser.add_argument(
        "--base-binary",
        action="store_true",
        help="Treat source as a pinned v3 SPLX asset, overlay --custom files, and rebuild initials.",
    )
    args = parser.parse_args()

    if args.base_binary:
        if args.cedict:
            parser.error("--cedict cannot be combined with --base-binary")
        entries, syllables = augment_binary(args.source, args.custom)
    else:
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
