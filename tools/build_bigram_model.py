#!/usr/bin/env python3
"""Derive a compact character-bigram boundary model from Sense's v3 lexicon."""

from __future__ import annotations

import argparse
import math
import struct
from collections import defaultdict
from pathlib import Path


LEXICON_MAGIC = b"SPLX"
LEXICON_VERSION = 3
MAGIC = b"SBGM"
VERSION = 1
DEFAULT_MAX_PAIRS = 65536
MAX_SCORE = 3.0
INDEX_NAMESPACES = ("{", "~")


def is_han(character: str) -> bool:
    return (
        "\u3400" <= character <= "\u4dbf"
        or "\u4e00" <= character <= "\u9fff"
        or "\uf900" <= character <= "\ufaff"
        or 0x20000 <= ord(character) <= 0x3134F
    )


def read_pair_mass(path: Path) -> dict[tuple[int, int], float]:
    data = path.read_bytes()
    if len(data) < 10 or data[:4] != LEXICON_MAGIC:
        raise ValueError("Invalid Sense pinyin lexicon")
    version, record_count = struct.unpack_from(">HI", data, 4)
    if version != LEXICON_VERSION:
        raise ValueError(f"Unsupported pinyin lexicon version: {version}")

    primary_text_weights: dict[str, int] = {}
    cursor = 10
    for _ in range(record_count):
        code_length = data[cursor]
        cursor += 1
        code = data[cursor : cursor + code_length].decode("ascii")
        cursor += code_length
        candidate_count = data[cursor]
        cursor += 1
        for _ in range(candidate_count):
            text_length = data[cursor]
            cursor += 1
            text = data[cursor : cursor + text_length].decode("utf-8")
            cursor += text_length
            (weight,) = struct.unpack_from(">I", data, cursor)
            cursor += 4
            initials_length = data[cursor]
            cursor += 1 + initials_length
            source_tier = data[cursor]
            cursor += 1
            if code.startswith(INDEX_NAMESPACES) or source_tier != 0 or len(text) < 2:
                continue
            primary_text_weights[text] = max(weight, primary_text_weights.get(text, 0))
    if cursor != len(data):
        raise ValueError("Pinyin lexicon has trailing or truncated bytes")

    pair_mass: defaultdict[tuple[int, int], float] = defaultdict(float)
    for text, weight in primary_text_weights.items():
        evidence = 1.0 + math.log1p(weight)
        for previous, next_character in zip(text, text[1:]):
            if is_han(previous) and is_han(next_character):
                pair_mass[(ord(previous), ord(next_character))] += evidence
    return dict(pair_mass)


def rank_pairs(pair_mass: dict[tuple[int, int], float], max_pairs: int) -> list[tuple[int, int, float]]:
    if max_pairs <= 0:
        raise ValueError("max_pairs must be positive")
    strongest_by_previous: defaultdict[int, float] = defaultdict(float)
    for (previous, _), mass in pair_mass.items():
        strongest_by_previous[previous] = max(strongest_by_previous[previous], mass)

    ranked = sorted(pair_mass.items(), key=lambda item: (-item[1], item[0]))[:max_pairs]
    result = []
    for (previous, next_character), mass in ranked:
        relative = mass / strongest_by_previous[previous]
        confidence = min(1.0, math.log1p(mass) / 5.0)
        score = MAX_SCORE * math.sqrt(relative) * confidence
        result.append((previous, next_character, max(0.05, score)))
    return sorted(result, key=lambda item: (item[0], item[1]))


def write_model(entries: list[tuple[int, int, float]], output: Path) -> None:
    if not entries:
        raise ValueError("No character bigrams were produced")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as stream:
        stream.write(MAGIC)
        stream.write(struct.pack(">HI", VERSION, len(entries)))
        for previous, next_character, score in entries:
            stream.write(struct.pack(">IIf", previous, next_character, score))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("lexicon", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--max-pairs", type=int, default=DEFAULT_MAX_PAIRS)
    args = parser.parse_args()

    pair_mass = read_pair_mass(args.lexicon)
    entries = rank_pairs(pair_mass, args.max_pairs)
    write_model(entries, args.output)
    print(
        f"Wrote {len(entries)} of {len(pair_mass)} observed Han bigrams "
        f"to {args.output} ({args.output.stat().st_size} bytes)"
    )


if __name__ == "__main__":
    main()
