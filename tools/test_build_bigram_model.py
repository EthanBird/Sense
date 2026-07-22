#!/usr/bin/env python3

import math
import struct
import tempfile
import unittest
from pathlib import Path

import build_bigram_model as builder


class BigramModelBuilderTest(unittest.TestCase):
    def test_primary_weight_and_relative_frequency_control_scores(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lexicon = root / "lexicon.bin"
            model = root / "bigram.bin"
            self._write_lexicon(
                lexicon,
                [
                    ("woshi", [("我是", 1000, "ws", 0), ("我时", 10, "ws", 0)]),
                    ("zhongguo", [("中国", 500, "zg", 0)]),
                    ("{w", [("外围", 999999, "ww", 0)]),
                    ("ci", [("次级", 999999, "cj", 1)]),
                ],
            )

            mass = builder.read_pair_mass(lexicon)
            entries = builder.rank_pairs(mass, 10)
            builder.write_model(entries, model)
            scores = {(previous, next_character): score for previous, next_character, score in entries}

            self.assertGreater(scores[(ord("我"), ord("是"))], scores[(ord("我"), ord("时"))])
            self.assertIn((ord("中"), ord("国")), scores)
            self.assertNotIn((ord("外"), ord("围")), scores)
            self.assertNotIn((ord("次"), ord("级")), scores)
            self.assertEqual(b"SBGM", model.read_bytes()[:4])
            self.assertEqual(3, len(mass))

    def test_duplicate_primary_text_uses_the_strongest_weight_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lexicon = Path(directory) / "lexicon.bin"
            self._write_lexicon(
                lexicon,
                [
                    ("chongqing", [("重庆", 10, "cq", 0)]),
                    ("zhongqing", [("重庆", 1000, "zq", 0)]),
                ],
            )

            mass = builder.read_pair_mass(lexicon)

            self.assertEqual(1, len(mass))
            self.assertAlmostEqual(1.0 + math.log1p(1000), mass[(ord("重"), ord("庆"))])

    def test_output_is_deterministic(self) -> None:
        entries = [(ord("我"), ord("是"), 2.5), (ord("中"), ord("国"), 1.5)]
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.bin"
            second = Path(directory) / "second.bin"
            builder.write_model(sorted(entries), first)
            builder.write_model(sorted(entries), second)
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def _write_lexicon(self, path: Path, records) -> None:
        with path.open("wb") as stream:
            stream.write(b"SPLX")
            stream.write(struct.pack(">HI", 3, len(records)))
            for code, candidates in sorted(records):
                encoded_code = code.encode("ascii")
                stream.write(struct.pack("B", len(encoded_code)))
                stream.write(encoded_code)
                stream.write(struct.pack("B", len(candidates)))
                for text, weight, initials, tier in candidates:
                    encoded_text = text.encode("utf-8")
                    encoded_initials = initials.encode("ascii")
                    stream.write(struct.pack("B", len(encoded_text)))
                    stream.write(encoded_text)
                    stream.write(struct.pack(">I", weight))
                    stream.write(struct.pack("B", len(encoded_initials)))
                    stream.write(encoded_initials)
                    stream.write(struct.pack("B", tier))


if __name__ == "__main__":
    unittest.main()
