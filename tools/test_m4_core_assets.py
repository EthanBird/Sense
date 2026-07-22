#!/usr/bin/env python3
"""Fresh-checkout gates for Sense M4's generated lexicon and bigram assets."""

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
LEXICON_SHA256 = "d8d6ad58a462a0c00abd9300bd7f58930e0cff5a3bbf0089ff7128d805dab80c"
BIGRAM_SHA256 = "c8a7c4b9fe7b4b17b73fc29073c4c55943720e8203d42ca5f467c54f8aa12e28"


class M4CoreAssetsTest(unittest.TestCase):
    def test_production_initials_and_progressive_fixture(self) -> None:
        entries = build_pinyin_lexicon.read_binary(LEXICON)
        self.assertEqual(137_265, len(entries))
        self.assertEqual("我", entries["{w"][0].text)
        self.assertEqual("一个字", entries["~ygz"][0].text)
        self.assertEqual("匹配", entries["pipei"][0].text)
        self.assertIn("匹", [candidate.text for candidate in entries["pi"][:32]])
        self.assertIn("批", [candidate.text for candidate in entries["pi"][:32]])
        self.assertIn("配", [candidate.text for candidate in entries["pei"][:32]])
        self.assertEqual(LEXICON_SHA256, self._sha256(LEXICON))
        self.assertEqual(BIGRAM_SHA256, self._sha256(BIGRAMS))

    def test_fresh_checkout_rebuild_is_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rebuilt_lexicon = root / "pinyin_lexicon.bin"
            rebuilt_bigrams = root / "pinyin_bigrams.bin"

            entries, _ = build_pinyin_lexicon.augment_binary(LEXICON, [CUSTOM])
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
