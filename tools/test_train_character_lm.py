import math
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from train_character_lm import train_model, write_model, read_model, BOS, EOS, UNK


class CharacterLanguageModelTest(unittest.TestCase):
    def test_fitting_ignores_test_partition_and_rejects_missing_attribution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = {"train": [{"id": "a", "group": "a", "segments": ["我们明天出去玩"] * 3, "domain": "fixture"}],
                    "dev": [{"id": "b", "group": "b", "segments": ["他们明天出去玩"], "domain": "fixture"}],
                    "test": [{"id": "c", "group": "c", "segments": ["朋友今天出去玩"], "domain": "fixture"}]}
            outputs = {}
            for split, values in rows.items():
                path = root / (split + ".jsonl")
                path.write_text("\n".join(json.dumps(v, ensure_ascii=False) for v in values), encoding="utf-8")
                outputs[split] = {"fileName": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "records": len(values)}
            attribution = root / "attribution.jsonl"
            attribution.write_text('{"author":"fixture"}\n', encoding="utf-8")
            corpus = {"schemaVersion": 1, "outputs": outputs, "source": {"license": "fixture"},
                      "attribution": {"fileName": attribution.name, "sha256": hashlib.sha256(attribution.read_bytes()).hexdigest()}}
            (root / "corpus.json").write_text(json.dumps(corpus), encoding="utf-8")
            command = [sys.executable, str(Path(__file__).with_name("train_character_lm.py")), "train", str(root), str(root / "model.scng"), str(root / "report.json")]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, first.returncode, first.stderr)
            expected = (root / "model.scng").read_bytes()
            (root / "test.jsonl").write_text("invalid deliberately unread test content", encoding="utf-8")
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual(expected, (root / "model.scng").read_bytes())
            attribution.unlink()
            missing = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, missing.returncode)

    def test_pruning_preserves_probability_mass_and_binary_roundtrip(self):
        model = train_model(["我喜欢吃饭"] * 8 + ["我喜欢喝茶", "他喜欢下棋", "𠀀在这里"], min_char_count=1, min_bigram_count=2, min_trigram_count=3)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.scng"
            write_model(model, path)
            loaded = read_model(path.read_bytes())
            for order in (1, 2, 3):
                for left in ((BOS, BOS), (ord("喜"), ord("欢")), (ord("𠀀"), ord("在")), (UNK, UNK)):
                    total = 0.0
                    for cp in model.unigrams:
                        self.assertAlmostEqual(model.log_probability(*left, cp, order=order), loaded.log_probability(*left, cp, order=order), places=5)
                        total += math.exp(loaded.log_probability(*left, cp, order=order))
                    self.assertAlmostEqual(1.0, total, places=6)
            data = path.read_bytes()
            for corrupted in (data[:20], data[:-1], b"BAD!" + data[4:], data + b"\0"):
                with self.assertRaises(ValueError):
                    read_model(corrupted)

    def test_seen_context_improves_prediction_and_every_distribution_is_normalized(self):
        model = train_model(["我喜欢吃饭"] * 8 + ["你喜欢喝茶"] * 4, min_char_count=1, min_trigram_count=1)
        self.assertGreater(model.log_probability(ord("喜"), ord("欢"), ord("吃")), model.log_probability(ord("喜"), ord("欢"), ord("茶")))
        for order in (1, 2, 3):
            for left in ((BOS, BOS), (ord("喜"), ord("欢")), (ord("我"), UNK), (UNK, UNK)):
                total = sum(math.exp(model.log_probability(*left, cp, order=order)) for cp in model.unigrams)
                self.assertAlmostEqual(1.0, total, places=6)
        self.assertTrue(math.isfinite(model.log_probability(UNK, UNK, EOS)))


if __name__ == "__main__":
    unittest.main()
