import unittest
from evaluate_p2c import choose_mode, analyze


class P2cEvaluationTest(unittest.TestCase):
    def test_selection_uses_development_gates_and_lower_weight_ties(self):
        policy = {"developmentModes": ["legacy", "lm0.25", "lm0.5", "lm1.0", "lm2.0"],
                  "selectionGate": {"minimumTop1Gain": 3, "maximumTop10Loss": 1, "maximumCoverageLoss": 0}}
        base = {"top1": 20, "top3": 30, "top10": 35, "covered": 40, "characterErrors": 30}
        report = {"partition": "dev", "modes": [{**base, "mode": name} for name in policy["developmentModes"]]}
        self.assertEqual("legacy", choose_mode(report, policy))
        report["modes"][1]["top1"] = report["modes"][2]["top1"] = 23
        report["modes"][3].update(top1=30, characterErrors=31)  # More first choices but worse CER.
        report["modes"][4].update(top1=30, covered=39)
        self.assertEqual("lm0.25", choose_mode(report, policy))
        report["partition"] = "test"
        with self.assertRaises(ValueError):
            choose_mode(report, policy)

    def test_a_gain_in_top1_does_not_hide_character_error_regression(self):
        result = analyze({"cases": 20, "modes": [
            {"mode": "legacy", "top1": 10, "characterErrors": 10, "covered": 20},
            {"mode": "lm1.0", "top1": 11, "characterErrors": 12, "covered": 20, "gains": 3, "losses": 2}]}, "lm1.0")
        self.assertFalse(result["sourceReconstructionGatePassed"])
        self.assertFalse(result["productionEnabled"])
        self.assertEqual(1.0, result["pairedTop1"]["exactMcNemarTwoSidedP"])


if __name__ == "__main__":
    unittest.main()
