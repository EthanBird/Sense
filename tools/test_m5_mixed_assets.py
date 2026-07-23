#!/usr/bin/env python3
"""Fresh-checkout gates for Sense M5 bilingual and hybrid-pinyin assets."""

from __future__ import annotations

import hashlib
import re
import tempfile
import unittest
from pathlib import Path

import build_bigram_model
import build_pinyin_lexicon


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "ime-service/src/main/assets"
LEXICON = ASSETS / "pinyin_lexicon.bin"
BIGRAMS = ASSETS / "pinyin_bigrams.bin"
ENGLISH = ASSETS / "english_lexicon.txt"
ENGLISH_LICENSE = ASSETS / "POPULAR-ENGLISH-WORDS-ISC.txt"
REPOSITORY_ENGLISH_LICENSE = ROOT / "licenses/popular-english-words-ISC.txt"
CUSTOM = ROOT / "ime-service/src/main/lexicon/sense_custom.dict.tsv"
IDIOMS = ROOT / "ime-service/src/main/lexicon/sense_idioms.dict.tsv"

LEXICON_RECORDS = 429_901
BIGRAM_RECORDS = 46_657
ENGLISH_WORDS = 20_000
LEXICON_SHA256 = "ef2fac5d3b62ba3d88674e63a9bfbdc907f0a814b1798fbba25f6ac3cadccce6"
BIGRAM_SHA256 = "db00a109dde6d1f471172a7abb53aae30509894d6064897a80a502aab690f18c"
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
            ["妇女", "👩🏻", "腐女", "父女"],
            [candidate.text for candidate in entries["}fun|funv"][:4]],
        )
        self.assertEqual(
            ["赋能"],
            [candidate.text for candidate in entries["}fun|funeng"][:1]],
        )
        self.assertEqual("服你", entries["funi"][0].text)
        self.assertEqual("好哦", entries["haoo"][0].text)
        self.assertEqual(LEXICON_SHA256, self._sha256(LEXICON))
        self.assertEqual(BIGRAM_SHA256, self._sha256(BIGRAMS))

    def test_fresh_checkout_rebuild_is_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rebuilt_lexicon = root / "pinyin_lexicon.bin"
            rebuilt_bigrams = root / "pinyin_bigrams.bin"

            entries, _ = build_pinyin_lexicon.augment_binary(LEXICON, [IDIOMS, CUSTOM])
            build_pinyin_lexicon.write_binary(entries, rebuilt_lexicon)
            self.assertEqual(LEXICON.read_bytes(), rebuilt_lexicon.read_bytes())

            pair_mass = build_bigram_model.read_pair_mass(rebuilt_lexicon)
            pairs = build_bigram_model.rank_pairs(
                pair_mass,
                build_bigram_model.DEFAULT_MAX_PAIRS,
            )
            self.assertEqual(BIGRAM_RECORDS, len(pairs))
            build_bigram_model.write_model(pairs, rebuilt_bigrams)
            self.assertEqual(BIGRAMS.read_bytes(), rebuilt_bigrams.read_bytes())

    @staticmethod
    def _sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()
