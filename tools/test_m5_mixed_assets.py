#!/usr/bin/env python3
"""Fresh-checkout gates for Sense M5 bilingual and hybrid-pinyin assets."""

from __future__ import annotations

import hashlib
import json
import re
import unittest
from pathlib import Path

import build_pinyin_lexicon
from lexicon_sources import is_han_text, load_source_manifest


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "ime-service/src/main/assets"
LEXICON = ASSETS / "pinyin_lexicon.bin"
BIGRAMS = ASSETS / "pinyin_bigrams.bin"
ENGLISH = ASSETS / "english_lexicon.txt"
ENGLISH_LICENSE = ASSETS / "POPULAR-ENGLISH-WORDS-ISC.txt"
REPOSITORY_ENGLISH_LICENSE = ROOT / "licenses/popular-english-words-ISC.txt"
MANIFEST = ROOT / "ime-service/src/main/lexicon/sources.json"
BUILD_STATS = ROOT / "ime-service/src/main/lexicon/pinyin_lexicon.stats.json"

LEXICON_RECORDS = 823_782
ENGLISH_WORDS = 20_000
LEXICON_SHA256 = "71258c3d1b4cade8693a13564ead0217a7e92068bbe554ecc806ae0f3a08e800"
BIGRAM_SHA256 = "9f37c162783e1ea1cfb59a321cc310d32d693ef8d88b332ca28b29933760fe5d"
MANIFEST_SHA256 = "4275a03b9130bc06bcaa46dddac2db3ed2c75cc04a314dda4317bb6d06e62829"
ENGLISH_SHA256 = "1a182354bc9c944dc28a384c21dbb9a2338e93bd963c4ee33f40b033a8f55624"
ENGLISH_LICENSE_SHA256 = "f432301e16a48011db30f6fd74d5ec906745d5c9bfcacb91c924e4738d7e4fa7"
ENGLISH_HEADER = [
    "# popular-english-words 1.0.2, first 20,000 eligible popularity-ranked words",
    "# https://github.com/tkoop/popular-english-words",
    "# ISC license; see assets/POPULAR-ENGLISH-WORDS-ISC.txt",
]


class M5MixedAssetsTest(unittest.TestCase):
    def test_popularity_ranked_english_asset(self) -> None:
        lines = ENGLISH.read_text(encoding="utf-8").splitlines()
        self.assertEqual(ENGLISH_HEADER, lines[: len(ENGLISH_HEADER)])
        words = [line for line in lines if line and not line.startswith("#")]
        self.assertEqual(ENGLISH_WORDS, len(words))
        self.assertEqual(ENGLISH_WORDS, len(set(words)))
        self.assertTrue(all(re.fullmatch(r"[a-z]{1,32}", word) for word in words))
        self.assertEqual({"a", "i"}, {word for word in words if len(word) == 1})
        self.assertLess(words.index("host"), words.index("hosts"))
        self.assertLess(words.index("hosts"), words.index("hostile"))
        self.assertEqual(1_374, words.index("host") + 1)
        self.assertEqual(3_095, words.index("hosts") + 1)
        self.assertEqual(3_344, words.index("fun") + 1)
        self.assertEqual(6_288, words.index("hostile") + 1)
        self.assertEqual(ENGLISH_SHA256, self._sha256(ENGLISH))

    def test_english_license_is_packaged_verbatim(self) -> None:
        self.assertEqual(
            REPOSITORY_ENGLISH_LICENSE.read_bytes(),
            ENGLISH_LICENSE.read_bytes(),
        )
        self.assertEqual(ENGLISH_LICENSE_SHA256, self._sha256(ENGLISH_LICENSE))

    def test_hybrid_and_mixed_chinese_fixtures(self) -> None:
        entries = build_pinyin_lexicon.read_binary(LEXICON)
        self.assertEqual(LEXICON_RECORDS, len(entries))
        self.assertEqual(
            ["中文输入法"],
            [candidate.text for candidate in entries["}zhongwsrf|zhongwenshurufa"][:1]],
        )
        self.assertEqual(
            ["中文输入法"],
            [candidate.text for candidate in entries["}zhongwensrf|zhongwenshurufa"][:1]],
        )
        self.assertEqual(
            ["妇女", "父女", "腐女"],
            [candidate.text for candidate in entries["}fun|funv"]],
        )
        self.assertEqual(
            ["赋能"],
            [candidate.text for candidate in entries["}fun|funeng"]],
        )
        self.assertEqual("好哦", entries["haoo"][0].text)
        self.assertTrue(
            all(is_han_text(candidate.text) for candidate in entries["}fun|funv"])
        )
        self.assertEqual(LEXICON_SHA256, self._sha256(LEXICON))
        self.assertEqual(BIGRAM_SHA256, self._sha256(BIGRAMS))

    def test_sources_are_hash_pinned_and_product_weights_are_calibrated(self) -> None:
        loaded = load_source_manifest(MANIFEST)
        self.assertEqual(MANIFEST_SHA256, loaded.manifest_sha256)
        self.assertEqual(610_421, len(loaded.records))
        self.assertEqual(
            {
                "rime-frost-8105",
                "rime-frost-base",
                "rime-frost-ext",
                "rime-frost-others",
                "sense-product",
            },
            {audit.source_id for audit in loaded.audits},
        )
        product_records = [
            record for record in loaded.records if record.source_id == "sense-product"
        ]
        self.assertTrue(product_records)
        self.assertTrue(all(is_han_text(record.text) for record in product_records))
        self.assertLess(max(record.raw_weight for record in product_records), 100_000)
        self.assertTrue(
            all("src/test/fixtures" not in audit.path for audit in loaded.audits)
        )

    def test_committed_build_stats_match_packaged_asset(self) -> None:
        stats = json.loads(BUILD_STATS.read_text(encoding="utf-8"))
        self.assertEqual(LEXICON_SHA256, stats["asset"]["sha256"])
        self.assertEqual(35_069_585, stats["asset"]["bytes"])
        self.assertEqual(610_298, stats["counts"]["exact"]["candidates"])
        self.assertEqual(608_314, stats["exact_unique_texts"])
        self.assertEqual(MANIFEST_SHA256, stats["manifest"]["sha256"])

    @staticmethod
    def _sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()
