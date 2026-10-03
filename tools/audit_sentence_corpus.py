#!/usr/bin/env python3
"""Audit pinned, locally downloaded Tatoeba exports before choosing LM training data.

No network, extraction to disk, model training or automatic promotion to production.
Only Python stdlib is needed. Chinese script variants are deliberately NOT folded yet.
"""
from __future__ import annotations

import argparse
import bz2
import hashlib
import io
import json
import tarfile
import unicodedata
from collections import Counter
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def han(ch: str) -> bool:
    # Unicode scalar iteration; supplementary CJK counts as one character.
    return unicodedata.name(ch, "").startswith(("CJK UNIFIED IDEOGRAPH-", "CJK COMPATIBILITY IDEOGRAPH-"))


def han_key(text: str) -> str:
    return "".join(ch for ch in unicodedata.normalize("NFKC", text) if han(ch))


@contextmanager
def source_lines(path: Path, format_name: str):
    if format_name == "tatoeba-cc0-tar":
        with tarfile.open(path, "r:bz2") as archive:
            members = archive.getmembers()
            if len(members) != 1 or members[0].name != "sentences_CC0.csv" or not members[0].isfile():
                raise ValueError("Unexpected CC0 archive member")
            if members[0].size > 256 * 1024 * 1024:
                raise ValueError("Corpus exceeds audit expansion budget")
            with io.TextIOWrapper(archive.extractfile(members[0]), encoding="utf-8") as stream:
                yield stream
    elif format_name == "tatoeba-detailed-bz2":
        with bz2.open(path, "rt", encoding="utf-8") as stream:
            yield stream
    else:
        raise ValueError(f"Unsupported source format: {format_name}")


def audit_source(source: dict, raw_dir: Path, excluded_texts: set[str]) -> dict:
    name = source["fileName"]
    if Path(name).name != name or "/" in name or "\\" in name:
        raise ValueError("Source filename must be local basename")
    expected_license = {"tatoeba-cc0-tar": "CC0-1.0", "tatoeba-detailed-bz2": "CC-BY-2.0-FR"}
    if source["license"] != expected_license.get(source["format"]):
        raise ValueError("Source license/format mismatch")
    path = raw_dir / name
    digest = sha256(path)
    if digest != source["sha256"]:
        raise ValueError(f"Snapshot hash mismatch: {name}; freeze and audit a new manifest instead")
    languages: Counter = Counter()
    authors: Counter = Counter()
    lengths: Counter = Counter()
    missing_authors = zero_dates = 0
    text_hashes: set[str] = set()
    han_keys: set[str] = set()
    overlaps: set[str] = set()
    seen_ids: set[int] = set()
    pure_han_with_punctuation = han_characters = 0
    read_chars = 0
    with source_lines(path, source["format"]) as stream:
        for number, line in enumerate(stream, 1):
            read_chars += len(line)
            if len(line) > 65536 or read_chars > 256 * 1024 * 1024:
                raise ValueError("Corpus exceeds audit text budget")
            fields = line.rstrip("\r\n").split("\t")
            width = 4 if source["format"] == "tatoeba-cc0-tar" else 6
            if len(fields) != width or not fields[0].isdigit() or not fields[1] or not fields[2]:
                raise ValueError(f"Malformed corpus row {number}")
            sid = int(fields[0])
            if sid <= 0 or sid in seen_ids:
                raise ValueError(f"Invalid or duplicate sentence id {sid}")
            seen_ids.add(sid)
            languages[fields[1]] += 1
            if fields[1] != "cmn":
                continue
            text = fields[2]
            normalized = unicodedata.normalize("NFKC", text)
            key = han_key(text)
            text_hashes.add(hashlib.sha256(text.encode("utf-8")).hexdigest())
            if key:
                han_keys.add(key)
                if key in excluded_texts:
                    overlaps.add(key)
            han_characters += len(key)
            lengths[len(key)] += 1
            if key and all(han(ch) or ch.isspace() or unicodedata.category(ch).startswith("P") for ch in normalized):
                pure_han_with_punctuation += 1
            if width == 6:
                if fields[3] in ("", "\\N"):
                    missing_authors += 1
                else:
                    authors[fields[3]] += 1
            if fields[-1] in ("", "\\N", "0000-00-00 00:00:00"):
                zero_dates += 1
    cmn = languages["cmn"]
    return {
        "sourceId": source["id"], "url": source["url"], "sha256": digest,
        "compressedBytes": path.stat().st_size, "license": source["license"],
        "totalRows": sum(languages.values()), "languageCount": len(languages),
        "mandarinRows": cmn, "uniqueMandarinTexts": len(text_hashes),
        "uniqueHanSkeletonsBeforeScriptConversion": len(han_keys),
        "hanCharacters": han_characters, "hanAndPunctuationOnlyRows": pure_han_with_punctuation,
        "hanLengthHistogram": {str(k): v for k, v in sorted(lengths.items())},
        "contributors": len(authors), "missingContributorRows": missing_authors,
        "topContributorShare": max(authors.values(), default=0) / max(cmn, 1),
        "missingModifiedDates": zero_dates,
        "existingReplayExactHanOverlapCount": len(overlaps),
        "sufficientRowsForPilot": cmn >= 10_000,
        "productionReady": False,
        "remaining": ["simplified/traditional normalization", "near-duplicate grouping before split",
                      "exclude replay answers before training", "training/dev/frozen-test split",
                      "attribution sidecar for selected CC BY sentences", "domain and translation-style audit"],
    }


def replay_texts(root: Path) -> set[str]:
    values = set()
    # Exact overlap audit only. This is not a near-duplicate or script-normalized leakage gate.
    for file in sorted(root.glob("*.tsv")):
        for line in file.read_text(encoding="utf-8").splitlines():
            if line and not line.startswith("#"):
                for field in line.split("\t"):
                    for variant in field.split("|"):
                        if key := han_key(variant):
                            values.add(key)
    return values


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("raw_dir", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--replays", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported manifest schema")
    excluded = replay_texts(args.replays)
    audits = [audit_source(s, args.raw_dir, excluded) for s in manifest["sources"]]
    report = {"schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
              "manifestSha256": sha256(args.manifest), "auditorSha256": sha256(Path(__file__)),
              "replayHanSkeletons": len(excluded), "sources": audits,
              "pilotSizeEligibleSources": [a["sourceId"] for a in audits if a["sufficientRowsForPilot"]],
              "decision": "Size eligibility is not model readiness; corpus preparation and evaluation remain pending; no model promoted"}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for audit in audits:
        print(f"{audit['sourceId']}: Mandarin={audit['mandarinRows']}, pilot-size={audit['sufficientRowsForPilot']}")


if __name__ == "__main__":
    main()
