import copy
import tempfile
from pathlib import Path
import unittest
from summarize_input_latency import summarize, latency_regression_gate


class InputLatencySummaryTest(unittest.TestCase):
    def test_latency_guard_rejects_median_or_tail_regression_independently(self):
        for median, tail, passed in [(99, 199, True), (100, 200, True), (101, 190, False), (90, 201, False)]:
            result = latency_regression_gate({"confirmationMs": {
                "baseline": {"count": 32, "medianMs": 100, "observedP95Ms": 200},
                "optimized": {"count": 32, "medianMs": median, "observedP95Ms": tail}}})
            self.assertEqual(passed, result["passed"])

    def test_latency_guard_rejects_unbalanced_confirmation_counts(self):
        with self.assertRaises(ValueError):
            latency_regression_gate({"confirmationMs": {
                "baseline": {"count": 32}, "optimized": {"count": 31}}})

    def fixture(self, directory):
        runs = []
        for block, mode in enumerate(["baseline", "optimized", "optimized", "baseline"], 1):
            folder = directory / f"block{block}"
            folder.mkdir()
            (folder / "system.trace").write_text(
                "# entries-in-buffer/entries-written: 2/2\n"
                "worker-1 (123) [000] ..... 1.000: tracing_mark_write: B|123|Sense.Pinyin.decode\n"
                "worker-1 (123) [000] ..... 1.040: tracing_mark_write: E|123\n", encoding="utf-8")
            rows = [{"query": f"query{query}", "round": round, "actual": "test", "expectedBaselineOutput": "test",
                     "passed": True, "spaceToEditorMs": 50 if mode == "optimized" else 100}
                    for round in range(2) for query in range(8)]
            runs.append({"block": block, "mode": mode, "samples": rows, "passed": True, "artifactDirectory": folder.name})
        return {"runs": runs, "scope": "fixture", "apkSha256": {"baseline": "a", "optimized": "b"}}

    def test_balanced_sample_and_decode_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = summarize(self.fixture(root), root)
            self.assertEqual(32, result["confirmationMs"]["baseline"]["count"])
            self.assertEqual(50, result["confirmationMs"]["optimized"]["medianMs"])
            self.assertEqual(8, len(result["perQuery"]))
            self.assertEqual(1, result["traces"][0]["completedDecodes"])

    def test_missing_failed_or_different_workloads_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.fixture(root)
            broken = copy.deepcopy(original)
            broken["runs"][1]["samples"][0]["passed"] = False
            with self.assertRaises(ValueError): summarize(broken, root)
            broken = copy.deepcopy(original)
            broken["runs"][1]["samples"][0]["actual"] = "wrong"
            with self.assertRaises(ValueError): summarize(broken, root)
            broken = copy.deepcopy(original)
            broken["runs"][1]["samples"][0]["query"] = "different"
            with self.assertRaises(ValueError): summarize(broken, root)
            broken = copy.deepcopy(original)
            broken["runs"].pop()
            with self.assertRaises(ValueError): summarize(broken, root)


if __name__ == "__main__": unittest.main()
