import unittest

from build_pinyin_lexicon import LexiconCandidate
from build_supplemental_pinyin import supplement


class SupplementalPinyinTest(unittest.TestCase):
    def test_fallback_preserves_all_existing_scores_and_indexes(self):
        base = {"zhineng": [LexiconCandidate("智能", 8850, "zn", 0)],
                "{z": [LexiconCandidate("智能", 13, "zn", 0)]}
        result, counts = supplement(base, [("new", ["智能\tzhi neng\t999999", "智能体\tzhi neng ti\t100"])],
                                    {"zhi", "neng", "ti"}, 16)
        for key, values in base.items():
            self.assertEqual(values, result[key])
        self.assertEqual(1, counts["retainedSupplement"])
        self.assertEqual(LexiconCandidate("智能体", 16, "znt", 1, "new", False, False, False), result["zhinengti"][0])
        self.assertNotIn("~znt", result)

    def test_noise_invalid_readings_and_duplicate_rows_do_not_enter_the_layer(self):
        lines = ["hello\the llo", "体\tti", "智能体\tzhi neng", "智能体\tzhi neng bad", "智能体\tzhi neng ti", "智能体\tzhi neng ti"]
        result, counts = supplement({}, [("new", lines)], {"zhi", "neng", "ti"}, 1)
        self.assertEqual(["zhinengti"], list(result))
        self.assertEqual(1, counts["duplicateSupplement"])
        self.assertEqual(2, counts["invalidSyllables"])

    def test_capacity_never_evicts_a_previous_candidate(self):
        old = [LexiconCandidate("词" + chr(0x4E00+i), i, "cc", 0) for i in range(128)]
        result, counts = supplement({"cici": old}, [("new", ["词词\tci ci\t100"])], {"ci"}, 16)
        self.assertEqual(old, result["cici"])
        self.assertEqual(1, counts["capacityRejected"])

    def test_order_does_not_depend_on_source_line_order_for_unique_readings(self):
        lines = ["智体\tzhi ti\t100", "知体\tzhi ti\t200", "只能\tzhi neng\t300"]
        forward = supplement({}, [("new", lines)], {"zhi", "ti", "neng"}, 16)
        backward = supplement({}, [("new", list(reversed(lines)))], {"zhi", "ti", "neng"}, 16)
        self.assertEqual(forward, backward)
        for value in (0, 129):
            with self.assertRaises(ValueError):
                supplement({}, [], set(), value)


if __name__ == "__main__":
    unittest.main()
