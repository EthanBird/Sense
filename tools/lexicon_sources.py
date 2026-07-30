#!/usr/bin/env python3
"""Deterministic source-manifest loader for Sense's production pinyin lexicon.

The checked-in manifest is the attribution and transformation boundary.  This
module deliberately uses only Python's standard library so the production
dictionary can be rebuilt by the offline gate without PyYAML or network access.
Rime dictionary bodies are tab-separated even though their metadata header is
YAML, so a small line parser is both sufficient and stricter than accepting
arbitrary YAML.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


MANIFEST_SCHEMA_VERSION = 1
CANONICAL_IR_VERSION = 1
SUPPORTED_FORMATS = frozenset({"rime-dict-yaml", "sense-tsv"})
PINNED_LICENSE = "GPL-3.0-only"
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class LexiconSourceRecord:
    text: str
    syllables: tuple[str, ...]
    weight: int
    source_tier: int
    source_id: str
    raw_weight: int
    prefix_eligible: bool
    initials_eligible: bool
    hybrid_eligible: bool


@dataclass(frozen=True)
class SourceAudit:
    source_id: str
    path: str
    sha256: str
    accepted: int
    malformed: int
    rejected_non_han: int
    rejected_length: int


@dataclass(frozen=True)
class ManifestLoadResult:
    records: tuple[LexiconSourceRecord, ...]
    audits: tuple[SourceAudit, ...]
    manifest_sha256: str
    manifest_path: Path


def normalized_syllables(value: str) -> list[str]:
    """Normalize numbered or plain pinyin to lowercase ASCII syllables."""
    normalized = (
        value.lower()
        .replace("u:", "v")
        .replace("ü", "v")
        .replace("u\u0308", "v")
    )
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
        or "\U00020000" <= character <= "\U0002fa1f"
        for character in value
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_source_manifest(path: Path) -> ManifestLoadResult:
    """Load, verify and normalize every source declared by *path*.

    Paths must stay beneath the manifest directory, every byte source has a
    pinned SHA-256, and all numeric transforms use integer rational arithmetic.
    These rules make the same checkout produce byte-identical canonical IR on
    Windows and Linux.
    """
    manifest_path = path.resolve()
    raw_manifest = manifest_path.read_bytes()
    try:
        manifest = json.loads(raw_manifest.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Invalid lexicon source manifest: {error}") from error
    _require_exact_keys(
        manifest,
        {"schema_version", "license", "upstream", "sources"},
        "manifest",
    )
    if manifest["schema_version"] != MANIFEST_SCHEMA_VERSION:
        raise ValueError(
            f"Unsupported lexicon manifest schema: {manifest['schema_version']}"
        )
    if manifest["license"] != PINNED_LICENSE:
        raise ValueError(
            f"Production lexicon manifest license must be {PINNED_LICENSE}"
        )
    if not isinstance(manifest["upstream"], dict):
        raise ValueError("manifest.upstream must be an object")
    sources = manifest["sources"]
    if not isinstance(sources, list) or not sources:
        raise ValueError("manifest.sources must be a non-empty list")

    root = manifest_path.parent
    records: list[LexiconSourceRecord] = []
    audits: list[SourceAudit] = []
    source_ids: set[str] = set()
    for source in sources:
        _require_exact_keys(
            source,
            {
                "id",
                "path",
                "format",
                "sha256",
                "revision",
                "license",
                "source_tier",
                "weight",
                "index",
            },
            "source",
        )
        source_id = _required_identifier(source["id"], "source.id")
        if source_id in source_ids:
            raise ValueError(f"Duplicate lexicon source id: {source_id}")
        source_ids.add(source_id)
        source_format = source["format"]
        if source_format not in SUPPORTED_FORMATS:
            raise ValueError(f"Unsupported lexicon source format: {source_format}")
        if source["license"] != PINNED_LICENSE:
            raise ValueError(
                f"Source {source_id} must declare the production license boundary"
            )
        _required_identifier(source["revision"], f"{source_id}.revision")
        expected_sha256 = source["sha256"]
        if not isinstance(expected_sha256, str) or not SHA256_PATTERN.fullmatch(
            expected_sha256
        ):
            raise ValueError(f"Source {source_id} has an invalid SHA-256")
        source_path = _resolve_beneath(root, source["path"], source_id)
        observed_sha256 = sha256_file(source_path)
        if observed_sha256 != expected_sha256:
            raise ValueError(
                f"Source {source_id} SHA-256 mismatch: "
                f"expected {expected_sha256}, observed {observed_sha256}"
            )
        source_tier = source["source_tier"]
        if source_tier not in (0, 1):
            raise ValueError(f"Source {source_id} has an invalid source_tier")
        weight_policy = _weight_policy(source["weight"], source_id)
        index_policy = _index_policy(source["index"], source_id)

        accepted = malformed = rejected_non_han = rejected_length = 0
        source_records: list[LexiconSourceRecord] = []
        with source_path.open("r", encoding="utf-8", newline="") as stream:
            for line_number, raw_line in enumerate(stream, start=1):
                line = raw_line.rstrip("\r\n")
                if not line or line.startswith("#") or "\t" not in line:
                    continue
                fields = line.split("\t")
                if len(fields) < 2:
                    malformed += 1
                    continue
                text = fields[0].strip()
                syllables = normalized_syllables(fields[1])
                if not text or not syllables:
                    malformed += 1
                    continue
                if not is_han_text(text):
                    rejected_non_han += 1
                    continue
                if len(text) > weight_policy["max_text_length"]:
                    rejected_length += 1
                    continue
                raw_weight_text = (
                    fields[2].strip()
                    if len(fields) > 2 and fields[2].strip()
                    else "0"
                )
                if not re.fullmatch(r"[0-9]+", raw_weight_text):
                    raise ValueError(
                        f"Source {source_id}:{line_number} has an invalid weight"
                    )
                raw_weight = int(raw_weight_text)
                weight = _calibrated_weight(raw_weight, weight_policy)
                source_records.append(
                    LexiconSourceRecord(
                        text=text,
                        syllables=tuple(syllables),
                        weight=weight,
                        source_tier=source_tier,
                        source_id=source_id,
                        raw_weight=raw_weight,
                        prefix_eligible=weight >= index_policy["prefix_min_weight"],
                        initials_eligible=weight
                        >= index_policy["initials_min_weight"],
                        hybrid_eligible=weight >= index_policy["hybrid_min_weight"],
                    )
                )
                accepted += 1
        records.extend(source_records)
        audits.append(
            SourceAudit(
                source_id=source_id,
                path=source_path.relative_to(root).as_posix(),
                sha256=observed_sha256,
                accepted=accepted,
                malformed=malformed,
                rejected_non_han=rejected_non_han,
                rejected_length=rejected_length,
            )
        )

    return ManifestLoadResult(
        records=tuple(records),
        audits=tuple(audits),
        manifest_sha256=hashlib.sha256(raw_manifest).hexdigest(),
        manifest_path=manifest_path,
    )


def write_canonical_ir(
    path: Path,
    manifest_sha256: str,
    records: list[LexiconSourceRecord] | tuple[LexiconSourceRecord, ...],
) -> None:
    """Write a stable, inspectable intermediate representation.

    The IR is not packaged in the APK.  It exists for source review, diffing and
    reproducibility audits and intentionally retains source id and raw weight.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    ordered = sorted(
        records,
        key=lambda item: (
            "".join(item.syllables),
            item.source_tier,
            -item.weight,
            item.text,
            item.source_id,
            item.raw_weight,
        ),
    )
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(f"# Sense canonical pinyin lexicon IR v{CANONICAL_IR_VERSION}\n")
        stream.write(f"# manifest-sha256={manifest_sha256}\n")
        stream.write(
            "# text<TAB>pinyin<TAB>weight<TAB>tier<TAB>source"
            "<TAB>raw-weight<TAB>indexes\n"
        )
        for record in ordered:
            indexes = (
                ("p" if record.prefix_eligible else "")
                + ("i" if record.initials_eligible else "")
                + ("h" if record.hybrid_eligible else "")
            )
            stream.write(
                f"{record.text}\t{' '.join(record.syllables)}\t{record.weight}\t"
                f"{record.source_tier}\t{record.source_id}\t"
                f"{record.raw_weight}\t{indexes or '-'}\n"
            )


def _calibrated_weight(raw_weight: int, policy: dict[str, int]) -> int:
    scaled = (
        raw_weight * policy["numerator"] + policy["denominator"] // 2
    ) // policy["denominator"]
    return min(
        policy["maximum"],
        max(policy["minimum"], scaled + policy["offset"]),
    )


def _weight_policy(value: Any, source_id: str) -> dict[str, int]:
    _require_exact_keys(
        value,
        {
            "numerator",
            "denominator",
            "offset",
            "minimum",
            "maximum",
            "max_text_length",
        },
        f"{source_id}.weight",
    )
    result: dict[str, int] = {}
    for key in (
        "numerator",
        "denominator",
        "offset",
        "minimum",
        "maximum",
        "max_text_length",
    ):
        number = value[key]
        if not isinstance(number, int):
            raise ValueError(f"{source_id}.weight.{key} must be an integer")
        result[key] = number
    if result["numerator"] < 0 or result["denominator"] <= 0:
        raise ValueError(f"{source_id} has an invalid weight ratio")
    if result["minimum"] < 0 or result["maximum"] < result["minimum"]:
        raise ValueError(f"{source_id} has an invalid weight range")
    if result["maximum"] > 0xFFFFFFFF:
        raise ValueError(f"{source_id} weight exceeds SPLX v3 capacity")
    if not 1 <= result["max_text_length"] <= 64:
        raise ValueError(f"{source_id} has an invalid max_text_length")
    return result


def _index_policy(value: Any, source_id: str) -> dict[str, int]:
    _require_exact_keys(
        value,
        {"prefix_min_weight", "initials_min_weight", "hybrid_min_weight"},
        f"{source_id}.index",
    )
    result: dict[str, int] = {}
    for key in ("prefix_min_weight", "initials_min_weight", "hybrid_min_weight"):
        number = value[key]
        if not isinstance(number, int) or number < 0:
            raise ValueError(f"{source_id}.index.{key} must be a non-negative integer")
        result[key] = number
    return result


def _resolve_beneath(root: Path, raw_path: Any, source_id: str) -> Path:
    if not isinstance(raw_path, str) or not raw_path:
        raise ValueError(f"Source {source_id} has an invalid path")
    relative = Path(raw_path)
    if relative.is_absolute():
        raise ValueError(f"Source {source_id} path must be relative")
    resolved = (root / relative).resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ValueError(f"Source {source_id} escapes the manifest directory") from error
    if not resolved.is_file():
        raise ValueError(f"Source {source_id} does not exist: {relative.as_posix()}")
    return resolved


def _required_identifier(value: Any, field: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", value):
        raise ValueError(f"{field} must be a stable identifier")
    return value


def _require_exact_keys(value: Any, expected: set[str], field: str) -> None:
    if not isinstance(value, dict):
        raise ValueError(f"{field} must be an object")
    observed = set(value)
    if observed != expected:
        missing = sorted(expected - observed)
        unknown = sorted(observed - expected)
        raise ValueError(
            f"{field} keys differ; missing={missing}, unknown={unknown}"
        )
