#!/usr/bin/env python3
"""M4 semantic regression gates against Sense's current generated assets."""

from __future__ import annotations

import hashlib
import unittest
from pathlib import Path

import build_bigram_model
import build_pinyin_lexicon


ROOT = Path(__file__).resolve().parent.parent
LEXICON = ROOT / "ime-service/src/main/assets/pinyin_lexicon.bin"
BIGRAMS = ROOT / "ime-service/src/main/assets/pinyin_bigrams.bin"
LEXICON_SHA256 = "71258c3d1b4cade8693a13564ead0217a7e92068bbe554ecc806ae0f3a08e800"
BIGRAM_SHA256 = "9f37c162783e1ea1cfb59a321cc310d32d693ef8d88b332ca28b29933760fe5d"


class M4CoreAssetsTest(unittest.TestCase):
    def test_production_initials_and_progressive_fixture(self) -> None:
        entries = build_pinyin_lexicon.read_binary(LEXICON)
        self.assertEqual(823_782, len(entries))
        self.assertEqual("我", entries["{w"][0].text)
        self.assertEqual("一个字", entries["~ygz"][0].text)
        self.assertEqual("上蹿下跳", entries["~scxt"][0].text)
        self.assertIn("上窜下跳", [candidate.text for candidate in entries["~scxt"]])
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

    def test_bigram_contains_ranked_phrase_evidence(self) -> None:
        pair_mass = build_bigram_model.read_pair_mass(LEXICON)
        pairs = build_bigram_model.rank_pairs(
            pair_mass,
            build_bigram_model.DEFAULT_MAX_PAIRS,
        )
        self.assertEqual(65_536, len(pairs))
        score = next(
            value
            for previous, following, value in pairs
            if (previous, following) == (ord("匹"), ord("配"))
        )
        self.assertGreater(score, 1.0)

    @staticmethod
    def _sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()
