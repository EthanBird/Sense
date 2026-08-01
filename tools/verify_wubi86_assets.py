#!/usr/bin/env python3
"""Verify the pinned Wubi86 source, reproducible asset, and packaged attribution.

This gate deliberately duplicates the release-critical hashes from the source
manifest and generated statistics.  Changing the upstream revision or the
SWBX/1 compiler output therefore requires an explicit review of this file too.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
import zipfile
from pathlib import Path
from typing import Mapping

import build_wubi86_lexicon as builder


ROOT = Path(__file__).resolve().parent.parent
LEXICON_DIR = ROOT / "ime-service/src/main/lexicon"
ASSET_DIR = ROOT / "ime-service/src/main/assets"
LICENSE_DIR = ROOT / "licenses"

MANIFEST = LEXICON_DIR / "wubi_sources.json"
STATS = LEXICON_DIR / "wubi86_lexicon.stats.json"
ASSET = ASSET_DIR / "wubi86_lexicon.bin"
SOURCE_LICENSE = LEXICON_DIR / "vendor/rime-wubi/LICENSE"
SOURCE_NOTICE = LICENSE_DIR / "RIME-WUBI-NOTICE.md"
LICENSE_COPY = LICENSE_DIR / "rime-wubi-LGPL-3.0.txt"

EXPECTED_REPOSITORY = "https://github.com/rime/rime-wubi"
EXPECTED_COMMIT = "152a0d3f3efe40cae216d1e3b338242446848d07"
EXPECTED_MANIFEST_SHA256 = "d37379890b59db10db7d49e6390c487ed5207c27ccf8e7f325a3d8c42bf80da3"
EXPECTED_STATS_SHA256 = "4dcb347eb4285a5912bf2be6b7b3084fce7bf7731eab7008b39c2294272030aa"
EXPECTED_ASSET_SHA256 = "e2d47d43ab702862c349cd7f9ad36b2d4cbd72963c95cdb6f7911bf849937207"
EXPECTED_ASSET_BYTES = 5_611_947
EXPECTED_LICENSE_SHA256 = "da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768"
EXPECTED_NOTICE_SHA256 = "c1ccba91f1ba2ebe970ef62a80e69feef1d1a61b568d078a20e9b904782035a8"

EXPECTED_SOURCE_RECORDS = {
    "rime-wubi86": (
        "vendor/rime-wubi/wubi86.dict.yaml",
        "f833d86b72341fe82e069a425b6625f29ef85f1bc0f34f6fb7975fe514888b5a",
    ),
    "rime-wubi-license": (
        "vendor/rime-wubi/LICENSE",
        EXPECTED_LICENSE_SHA256,
    ),
    "rime-wubi-authors": (
        "vendor/rime-wubi/AUTHORS",
        "4e4ca0e3ae6763a7cd5913f92c3b28091a7ec97d5cf4fc96c8f1a294e43f2839",
    ),
}


class VerificationError(RuntimeError):
    """A release-critical Wubi invariant did not hold."""


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def require_hash(path: Path, expected: str) -> None:
    require(path.is_file(), f"Missing required file: {path}")
    actual = sha256_file(path)
    require(actual == expected, f"SHA-256 mismatch for {path}: expected {expected}, got {actual}")


def require_equal(left: Path, right: Path) -> None:
    require(left.is_file(), f"Missing required file: {left}")
    require(right.is_file(), f"Missing required file: {right}")
    require(
        left.read_bytes() == right.read_bytes(),
        f"Files differ byte-for-byte: {left} != {right}",
    )


def verify_manifest() -> dict[str, object]:
    require_hash(MANIFEST, EXPECTED_MANIFEST_SHA256)
    document, sources = builder.load_manifest(MANIFEST)
    upstream = document.get("upstream")
    require(isinstance(upstream, dict), "Wubi manifest upstream block is missing")
    require(upstream.get("repository") == EXPECTED_REPOSITORY, "Unexpected Wubi repository")
    require(upstream.get("commit") == EXPECTED_COMMIT, "Unexpected Wubi revision")
    require(upstream.get("license") == "LGPL-3.0", "Unexpected Wubi source license")

    records = document.get("sources")
    require(isinstance(records, list), "Wubi source records are missing")
    actual_records = {
        str(record.get("id")): (str(record.get("path")), str(record.get("sha256")))
        for record in records
        if isinstance(record, dict)
    }
    require(actual_records == EXPECTED_SOURCE_RECORDS, "Pinned Wubi source records changed")
    require(len(sources) == len(EXPECTED_SOURCE_RECORDS), "Unexpected Wubi source count")
    return document


def verify_repository() -> None:
    document = verify_manifest()
    require_hash(STATS, EXPECTED_STATS_SHA256)
    require_hash(ASSET, EXPECTED_ASSET_SHA256)
    require(ASSET.stat().st_size == EXPECTED_ASSET_BYTES, "Unexpected SWBX/1 asset size")
    require_hash(SOURCE_LICENSE, EXPECTED_LICENSE_SHA256)
    require_hash(LICENSE_COPY, EXPECTED_LICENSE_SHA256)
    require_hash(SOURCE_NOTICE, EXPECTED_NOTICE_SHA256)

    require_equal(SOURCE_LICENSE, LICENSE_COPY)
    require_equal(LICENSE_COPY, ASSET_DIR / "RIME-WUBI-LGPL-3.0.txt")
    require_equal(SOURCE_NOTICE, ASSET_DIR / "RIME-WUBI-NOTICE.txt")
    require_equal(ROOT / "NOTICE", ASSET_DIR / "NOTICE.txt")

    root_notice = (ROOT / "NOTICE").read_text(encoding="utf-8")
    for marker in (EXPECTED_COMMIT, "SWBX/1", "licenses/RIME-WUBI-NOTICE.md"):
        require(marker in root_notice, f"Root NOTICE is missing Wubi marker: {marker}")

    stats = json.loads(STATS.read_text(encoding="utf-8"))
    require(stats.get("format") == "SWBX/1", "Generated statistics do not identify SWBX/1")
    require(stats.get("upstream") == document.get("upstream"), "Statistics upstream block drifted")
    stats_manifest = stats.get("manifest")
    require(isinstance(stats_manifest, dict), "Statistics manifest block is missing")
    require(stats_manifest.get("sha256") == EXPECTED_MANIFEST_SHA256, "Statistics manifest hash drifted")
    require(
        stats.get("asset")
        == {"bytes": EXPECTED_ASSET_BYTES, "sha256": EXPECTED_ASSET_SHA256},
        "Statistics asset identity drifted",
    )

    with tempfile.TemporaryDirectory(prefix="sense-wubi86-") as directory:
        rebuilt_asset = Path(directory) / "wubi86_lexicon.bin"
        rebuilt_stats = Path(directory) / "wubi86_lexicon.stats.json"
        builder.build(MANIFEST, rebuilt_asset, rebuilt_stats)
        require_equal(ASSET, rebuilt_asset)
        require_equal(STATS, rebuilt_stats)


def verify_zip_entries(apk: Path, expected: Mapping[str, Path]) -> None:
    require(apk.is_file(), f"APK is missing: {apk}")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        for entry, source in expected.items():
            require(names.count(entry) == 1, f"APK must contain exactly one {entry}")
            packaged = archive.read(entry)
            canonical = source.read_bytes()
            require(
                packaged == canonical,
                f"APK entry differs from its reviewed source: {entry}",
            )


def verify_apk(apk: Path) -> None:
    verify_zip_entries(
        apk,
        {
            "assets/wubi86_lexicon.bin": ASSET,
            "assets/RIME-WUBI-LGPL-3.0.txt": LICENSE_COPY,
            "assets/RIME-WUBI-NOTICE.txt": SOURCE_NOTICE,
            "assets/NOTICE.txt": ROOT / "NOTICE",
        },
    )
    with zipfile.ZipFile(apk) as archive:
        packaged_asset = archive.read("assets/wubi86_lexicon.bin")
    require(len(packaged_asset) == EXPECTED_ASSET_BYTES, "Packaged SWBX/1 size drifted")
    require(
        sha256_bytes(packaged_asset) == EXPECTED_ASSET_SHA256,
        "Packaged SWBX/1 SHA-256 drifted",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, help="also verify Wubi asset and license entries in an APK")
    args = parser.parse_args()

    verify_repository()
    if args.apk is not None:
        verify_apk(args.apk.resolve())
    suffix = f" and {args.apk}" if args.apk is not None else ""
    print(f"Verified pinned Rime Wubi sources, deterministic SWBX/1, attribution{suffix}.")


if __name__ == "__main__":
    main()
