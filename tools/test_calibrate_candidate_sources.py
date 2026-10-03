import unittest

from calibrate_candidate_sources import f32, rank, summarize


class CandidateSourceCalibrationTest(unittest.TestCase):
    def row(self, exact=False, composed=True):
        return {"id": "fixture", "operation": "clean", "expected": "甲乙", "exact": exact,
                "composed": composed, "pool": [["甲已", 8., "BASE_COMPOSED", 0., "jiayi"],
                                                ["甲乙", 12., "CORRECTED", 0., "jiayi"]]}

    def test_adjustment_is_once_only_and_specific_to_composed_without_exact(self):
        row = self.row()
        self.assertEqual("甲已", rank(row, 0)[0][1])
        self.assertEqual("甲乙", rank(row, 12)[0][1])
        self.assertEqual(16., rank(row, 12)[0][3])  # 12 + 12 - 8, not 12 - 8 + 24.
        for row in (self.row(exact=True), self.row(composed=False)):
            self.assertEqual(rank(row, 0), rank(row, 12))

    def test_float_tie_source_order_and_text_dedup_match_ranker(self):
        row = self.row(composed=False)
        row["pool"] = [["乙", 1.25, "CORRECTED", 0, "yi"],
                       ["乙", .45, "BASE_COMPOSED", 0, "yi"],
                       ["甲", .45, "BASE_COMPOSED", 0, "jia"]]
        result = rank(row, 0)
        self.assertEqual(["乙", "甲"], [x[1] for x in result])
        self.assertEqual("BASE_COMPOSED", result[0][2])
        self.assertEqual(1., f32(f32(.45) + f32(.55)))

    def test_metrics_retain_wrong_first_choice_and_correct_repair(self):
        before, after = (summarize([self.row()], x)["groups"]["all"] for x in (0, 12))
        self.assertEqual((0, 1, 1, 1), tuple(before[k] for k in ("top1", "top3", "covered", "characterErrors")))
        self.assertEqual((1, 1, 1, 0), tuple(after[k] for k in ("top1", "top3", "covered", "characterErrors")))

    def test_supplementary_han_ties_use_jvm_utf16_length(self):
        row = self.row(composed=False)
        row["pool"] = [[text, 1.25, "CORRECTED", 0, "fixture"] for text in ("𠀀", "乙甲")]
        self.assertEqual(["乙甲", "𠀀"], [r[1] for r in rank(row, 0)])


if __name__ == "__main__":
    unittest.main()
