import unittest

from compare_oov_replays import compare
import test_compare_lexicon_replays as fixtures


class OovReplayComparisonTest(unittest.TestCase):
    def pair(self):
        fixture = fixtures.LexiconReplayComparisonTest()
        before, after = fixture.data(), fixture.data()
        after["oovFeature"] = -4
        return before, after

    def test_only_the_oov_feature_may_differ(self):
        before, after = self.pair()
        self.assertEqual({"before": 0, "after": -4}, compare(before, after)["oovFeature"])
        after["assets"]["pinyin_lexicon.bin"] = "changed"
        with self.assertRaises(ValueError):
            compare(before, after)

    def test_changed_weights_sources_or_unbounded_values_fail(self):
        for field, value in (("lmWeight", 1.), ("oovFeature", float("nan")), ("sources", {})):
            before, after = self.pair()
            after[field] = value
            with self.assertRaises(ValueError):
                compare(before, after)


if __name__ == "__main__":
    unittest.main()
