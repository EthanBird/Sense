#!/usr/bin/env python3
"""Compile the Apache-2.0 Rime pinyin-simp dictionary into Sense's binary index."""

from __future__ import annotations

import argparse
import struct
from collections import defaultdict
from pathlib import Path


MAGIC = b"SPLX"
VERSION = 1
MAX_CANDIDATES = 12


def normalized_code(value: str) -> str:
    return "".join(character for character in value.lower() if "a" <= character <= "z")


def read_dictionary(paths: list[Path]) -> dict[str, list[tuple[str, int]]]:
    entries: defaultdict[str, dict[str, int]] = defaultdict(dict)
    for path in paths:
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line or line.startswith("#") or "\t" not in line:
                continue
            fields = line.split("\t")
            if len(fields) < 2:
                continue
            text = fields[0].strip()
            code = normalized_code(fields[1])
            if not text or not code:
                continue
            try:
                weight = max(0, int(fields[2])) if len(fields) > 2 else 0
            except ValueError:
                weight = 0
            encoded_text = text.encode("utf-8")
            if len(code) > 255 or len(encoded_text) > 255:
                continue
            entries[code][text] = max(weight, entries[code].get(text, 0))

    return {
        code: sorted(values.items(), key=lambda item: (-item[1], len(item[0]), item[0]))[:MAX_CANDIDATES]
        for code, values in entries.items()
    }


def write_binary(entries: dict[str, list[tuple[str, int]]], output: Path) -> None:
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
            for text, weight in candidates:
                text_bytes = text.encode("utf-8")
                stream.write(struct.pack("B", len(text_bytes)))
                stream.write(text_bytes)
                stream.write(struct.pack(">I", min(weight, 0xFFFFFFFF)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--custom", action="append", type=Path, default=[])
    args = parser.parse_args()

    entries = read_dictionary([args.source, *args.custom])
    if not entries:
        raise SystemExit("No pinyin entries were read")
    write_binary(entries, args.output)
    print(f"Wrote {len(entries)} pinyin keys to {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
