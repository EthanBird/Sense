import copy
import unittest

from report_candidate_calibration import quality_checks, verify_pair


class CalibrationAcceptanceTest(unittest.TestCase):
    def data(self):
        return {"before": {"top1": 100, "top10": 150, "characterErrors": 60, "covered": 180},
                "after": {"top1": 120, "top10": 170, "characterErrors": 50, "covered": 180},
                "byOperation": {"clean": {"before": {"top1": 20, "characterErrors": 4},
                                           "after": {"top1": 20, "characterErrors": 4}}}}

    def check(self, data):
        return quality_checks(data, {"newAuditAllTop1GainAtLeast": 20, "newAuditAllTop10GainAtLeast": 20})

    def test_exact_threshold_passes_without_percent_rounding(self):
        self.assertTrue(all(self.check(self.data()).values()))

    def test_clean_regression_is_not_hidden_by_overall_gain(self):
        for metric, value in (("top1", 19), ("characterErrors", 5)):
            data = self.data()
            data["byOperation"]["clean"]["after"][metric] = value
            self.assertFalse(all(self.check(data).values()))

    def test_coverage_or_smaller_gain_is_rejected(self):
        for metric, value in (("covered", 179), ("top1", 119), ("top10", 169), ("characterErrors", 61)):
            data = self.data()
            data["after"][metric] = value
            self.assertFalse(all(self.check(data).values()))

    def test_source_change_after_freeze_is_rejected_before_scoring(self):
        before = {"correctionCompositionBoost": 0, "sources": {"Decoder.kt": "changed"}}
        after = copy.deepcopy(before)
        after["correctionCompositionBoost"] = 12
        freeze = {"boost": 12, "files": {"core-input/src/main/kotlin/Decoder.kt": "frozen"}}
        with self.assertRaises(AssertionError):
            verify_pair(before, after, freeze, 1)


if __name__ == "__main__":
    unittest.main()
