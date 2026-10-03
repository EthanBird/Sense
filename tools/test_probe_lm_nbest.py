import unittest

from probe_lm_nbest import probe
from train_character_lm import train_model


class NbestProbeTest(unittest.TestCase):
    def test_zero_weight_preserves_rank_and_sweep_counts_losses_as_well_as_wins(self):
        model = train_model(["我喜欢吃饭"] * 10, min_char_count=1, min_trigram_count=1)
        choices = ["我喜欢茶", "我喜欢吃饭"]
        rows = [{"query": "fixture-a", "expected": choices[1], "top3": choices},
                {"query": "fixture-b", "expected": choices[0], "top3": choices}]
        before = probe(model, rows, "logp", 0.0)
        after = probe(model, rows, "logp", 10.0)
        self.assertEqual(1, before["top1"])
        self.assertEqual([], before["changes"])
        self.assertEqual(1, after["improvements"])
        self.assertEqual(1, after["regressions"])
        self.assertEqual(1, after["top1"])


if __name__ == "__main__":
    unittest.main()
