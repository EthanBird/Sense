import copy
import unittest
from compare_progressive_results import compare


class ProgressiveEquivalenceTest(unittest.TestCase):
    def test_timing_changes_are_allowed_but_order_scores_and_metadata_are_not(self):
        before = {"sources": {}, "observations": [{"pass": 0, "group": "daily", "resultSha256": "full-result",
                  "query": "nihao", "keyCount": 5, "wholeCount": 50, "prefixCount": 60, "decodeNs": 10_000_000}]}
        after = copy.deepcopy(before)
        after["observations"][0]["decodeNs"] = 1_000_000
        self.assertTrue(compare(before, after)["passed"])
        for field in ["resultSha256", "keyCount", "wholeCount", "prefixCount", "query"]:
            changed = copy.deepcopy(after)
            changed["observations"][0][field] = "different"
            self.assertFalse(compare(before, changed)["passed"])

    def test_missing_observations_are_not_vacuous_success(self):
        with self.assertRaises(ValueError): compare({"observations": []}, {"observations": []})

    def test_changed_corpus_assets_or_search_settings_are_rejected(self):
        before = {"sources": {}, "candidateLimit": 255, "weight": 0.5, "learning": False,
                  "inputs": {"test.tsv": "corpus"}, "assets": {"model": "weights"},
                  "observations": [{"pass": 0, "group": "daily", "decodeNs": 1}]}
        for field in ("candidateLimit", "weight", "learning", "inputs", "assets"):
            after = copy.deepcopy(before)
            after.pop(field)
            with self.assertRaises(ValueError): compare(before, after)


if __name__ == "__main__": unittest.main()
