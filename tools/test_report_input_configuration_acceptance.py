import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import report_input_configuration_acceptance as report


class ConfigurationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.root_patch = patch.object(report, "ROOT", self.root)
        self.art_patch = patch.object(report, "ART", self.root)
        self.root_patch.start()
        self.art_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.addCleanup(self.art_patch.stop)

    def test_transcript_requires_exact_count_and_no_failure_marker(self):
        log = self.root / "instrumentation.log"
        log.write_text("OK (3 tests)\n", encoding="utf-8")
        self.assertEqual(3, report.passed_log(log, 3)["passedTests"])
        for value in ["OK (13 tests)", "OK (3 tests)\nFAILURES!!!", "INSTRUMENTATION_FAILED", ""]:
            log.write_text(value, encoding="utf-8")
            with self.assertRaises(AssertionError):
                report.passed_log(log, 3)

    def write_result(self, value):
        folder = self.root / "run"
        folder.mkdir(exist_ok=True)
        (folder / "result.json").write_text(json.dumps(value), encoding="utf-8")
        return folder

    def base(self):
        return {"apks": {report.APP: "apk"}, "settingsBefore": {"font": "1.0"},
                "settingsRestored": {"font": "1.0"}, "runs": []}

    def test_configuration_rejects_wrong_apk_and_unrestored_settings(self):
        for key, value in [("apks", {report.APP: "old"}), ("settingsRestored", {"font": "2.0"})]:
            data = self.base()
            data[key] = value
            self.write_result(data)
            with self.assertRaises(AssertionError):
                report.configuration("run", "apk", 0)

    def test_four_duplicate_portrait_results_do_not_count_as_a_matrix(self):
        data = self.base()
        data["runs"] = [{"name": "portrait", "fontScale": 1.0, "rotation": 0,
                         "expectedTests": 3} for _ in range(4)]
        self.write_result(data)
        with self.assertRaises(AssertionError):
            report.configuration("run", "apk", 12)

    def test_configuration_pins_logs_and_preserves_observed_geometry(self):
        data = self.base()
        folder = self.write_result(data)
        case = folder / "timeout"
        device = case / "device"
        device.mkdir(parents=True)
        log = case / "instrumentation.log"
        log.write_text("OK (1 test)\n", encoding="utf-8")
        (device / "actual.txt").write_text("fontScale=1.0\norientation=1\n", encoding="utf-8")
        data["runs"] = [{"name": "timeout", "expectedTests": 1, "passed": True,
                         "expectedFailure": False, "logSha256": report.digest(log)}]
        self.write_result(data)
        result = report.configuration("run", "apk", 1)
        self.assertIn("orientation=1", result["runs"][0]["observations"]["actual.txt"])
        log.write_text("changed\nOK (1 test)\n", encoding="utf-8")
        with self.assertRaises(AssertionError):
            report.configuration("run", "apk", 1)


if __name__ == "__main__":
    unittest.main()
