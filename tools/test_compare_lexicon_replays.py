import copy
import unittest

from compare_lexicon_replays import compare


class LexiconReplayComparisonTest(unittest.TestCase):
    def data(self, empty=False):
        row = {"id": "fixture", "sourceId": "source", "typed": "nihao", "effectiveQuery": "nihao",
               "canonical": "nihao", "expected": "你好", "operation": "clean", "stratum": "short",
               "rank": 0 if empty else 1, "candidateCount": 0 if empty else 1,
               "top5": [] if empty else [{"text": "你好"}], "characterErrors": 2 if empty else 0,
               "decodeNs": 1, "graphAlignedRank": 1, "graphCanonicalRank": 1}
        return {"inputSha256": "fixed", "candidateLimit": 255, "graphDiagnosticLimit": 48,
                "lmWeight": .5, "correctionCompositionBoost": 12, "learning": False,
                "sources": {"decoder": "same"}, "progressiveJoints": "letters",
                "assets": {"pinyin_lexicon.bin": "lexicon", "lm": "unchanged"}, "observations": [row]}

    def test_empty_pools_are_reported_and_not_discarded(self):
        result = compare(self.data(empty=True), self.data())
        self.assertEqual(1, result["before"]["rows"])
        self.assertEqual(0, result["before"]["covered"])
        self.assertEqual(1, result["top1Gains"])
        self.assertEqual("", result["changes"][0]["beforeTop1"])
        result = compare(self.data(), self.data(empty=True))
        self.assertEqual(1, result["top1Losses"])
        self.assertEqual(2, result["after"]["characterErrors"])

    def test_only_dictionary_bytes_may_differ(self):
        before = self.data()
        after = copy.deepcopy(before)
        after["assets"]["pinyin_lexicon.bin"] = "new"
        compare(before, after)
        for field, value in (("sources", {"decoder": "different"}), ("lmWeight", 1.)):
            invalid = copy.deepcopy(after)
            invalid[field] = value
            with self.assertRaises(ValueError):
                compare(before, invalid)
        after["assets"]["lm"] = "new"
        with self.assertRaises(ValueError):
            compare(before, after)

    def test_missing_or_unpaired_or_impossible_rows_fail(self):
        before = self.data()
        for kind in ("missing", "unpaired", "invalid"):
            after = self.data()
            if kind == "missing": after["observations"] = []
            elif kind == "unpaired": after["observations"][0]["typed"] = "wrong"
            else: after["observations"][0]["rank"] = 2
            with self.assertRaises(ValueError):
                compare(before, after)

    def test_new_model_ablation_is_not_a_dictionary_only_comparison(self):
        before, after = self.data(), self.data()
        after["oovFeature"] = -4
        with self.assertRaises(ValueError):
            compare(before, after)


if __name__ == "__main__":
    unittest.main()
