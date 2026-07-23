#!/usr/bin/env python3
"""M4 semantic regression gates against Sense's current generated assets."""

from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

import build_bigram_model
import build_pinyin_lexicon


ROOT = Path(__file__).resolve().parent.parent
LEXICON = ROOT / "ime-service/src/main/assets/pinyin_lexicon.bin"
BIGRAMS = ROOT / "ime-service/src/main/assets/pinyin_bigrams.bin"
CUSTOM = ROOT / "ime-service/src/main/lexicon/sense_custom.dict.tsv"
IDIOMS = ROOT / "ime-service/src/main/lexicon/sense_idioms.dict.tsv"
LEXICON_SHA256 = "ef2fac5d3b62ba3d88674e63a9bfbdc907f0a814b1798fbba25f6ac3cadccce6"
BIGRAM_SHA256 = "db00a109dde6d1f471172a7abb53aae30509894d6064897a80a502aab690f18c"


class M4CoreAssetsTest(unittest.TestCase):
    def test_production_initials_and_progressive_fixture(self) -> None:
        entries = build_pinyin_lexicon.read_binary(LEXICON)
        self.assertEqual(429_901, len(entries))
        self.assertEqual("我", entries["{w"][0].text)
        self.assertEqual("一个字", entries["~ygz"][0].text)
        self.assertEqual("上窜下跳", entries["~scxt"][0].text)
        self.assertEqual("蛇鼠一窝", entries["~ssyw"][0].text)
        self.assertEqual("匹配", entries["pipei"][0].text)
        self.assertIn("匹", [candidate.text for candidate in entries["pi"][:32]])
        self.assertIn("批", [candidate.text for candidate in entries["pi"][:32]])
        self.assertIn("配", [candidate.text for candidate in entries["pei"][:32]])
        # M5 adds a private hybrid-key namespace. Keeping this assertion in
        # the M4 gate prevents a future generator from silently folding those
        # synthetic index records into the exact-pinyin or bigram corpus.
        self.assertTrue(any(code.startswith("}") for code in entries))
        self.assertIn("}", build_bigram_model.INDEX_NAMESPACES)
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
            pairs = build_bigram_model.rank_pairs(pair_mass, build_bigram_model.DEFAULT_MAX_PAIRS)
            build_bigram_model.write_model(pairs, rebuilt_bigrams)
            self.assertEqual(BIGRAMS.read_bytes(), rebuilt_bigrams.read_bytes())
            score = next(value for previous, following, value in pairs if (previous, following) == (ord("匹"), ord("配")))
            self.assertGreater(score, 1.0)

    @staticmethod
    def _sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()
