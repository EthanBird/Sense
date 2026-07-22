#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path

import build_pinyin_lexicon as builder


class PinyinLexiconBuilderTest(unittest.TestCase):
    def test_tone_boundaries_umlaut_and_v3_spelling(self) -> None:
        self.assertEqual(["nv"], builder.normalized_syllables("nu:3"))
        self.assertEqual(["lv"], builder.normalized_syllables("LÜ4"))
        self.assertEqual(["ni", "hao"], builder.normalized_syllables("ni3hao3"))
        self.assertEqual(["shi", "sheng"], builder.normalized_syllables("shi1-sheng1"))
        self.assertEqual(["de"], builder.normalized_syllables("de5"))

    def test_primary_source_tier_precedes_low_weight_cedict(self) -> None:
        entries = self._read(
            "弱词\truo ci\t0\n",
            "若詞 若词 [ruo4 ci2] /test/\n",
        )
        values = entries["ruoci"]
        self.assertEqual(["弱词", "若词"], [candidate.text for candidate in values])
        self.assertEqual([0, 1], [candidate.source_tier for candidate in values])

    def test_statistical_prefix_is_isolated_and_excludes_cedict(self) -> None:
        entries = self._read(
            "先\txian\t100\n先思\txian si\t1000\n我\two\t10000\n",
            "窩囊 窝囊 [[wo1nang2]] /test/\n",
        )
        self.assertEqual(["先"], [candidate.text for candidate in entries["xian"]])
        self.assertIn("先思", [candidate.text for candidate in entries["{xian"]])
        self.assertEqual("我", entries["{w"][0].text)
        self.assertNotIn("窝囊", [candidate.text for candidate in entries["{w"]])

    def _read(self, primary: str, cedict: str):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            primary_path = root / "primary.tsv"
            cedict_path = root / "cedict.txt"
            primary_path.write_text(primary, encoding="utf-8")
            cedict_path.write_text(cedict, encoding="utf-8")
            entries, _ = builder.read_dictionary([primary_path], [cedict_path])
            return entries


if __name__ == "__main__":
    unittest.main()
