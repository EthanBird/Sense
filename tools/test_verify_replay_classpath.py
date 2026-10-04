import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from verify_replay_classpath import DECODER_CLASS, run_checked_replay, verify_core_classpath


class ReplayClasspathTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.helpers = self.root / "helpers"
        self.helpers.mkdir()
        self.expected = self.jar("candidate.jar", b"candidate")
        self.baseline = self.jar("baseline.jar", b"baseline")
        self.digest = hashlib.sha256(self.expected.read_bytes()).hexdigest()

    def jar(self, name, payload):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr(DECODER_CLASS, payload)
        return path

    def command(self, *entries):
        return ["java", "-cp", os.pathsep.join(map(str, entries)), "AllocationReplay", "input.tsv", "output.tsv"]

    def verify(self, *entries):
        return verify_core_classpath(self.command(*entries), self.expected, self.digest)

    def test_expected_provider_and_hash(self):
        self.assertEqual(self.digest, self.verify(self.helpers, self.expected)["sha256"])

    def test_main_class_edit_does_not_change_the_selected_jar(self):
        command = self.command(self.helpers, self.baseline)
        command[3] = command[3].replace("baseline.jar", "candidate.jar")
        with self.assertRaises(ValueError):
            verify_core_classpath(command, self.expected, self.digest)

    def test_presence_of_expected_jar_does_not_hide_another_decoder(self):
        for entries in ((self.baseline, self.expected), (self.expected, self.baseline), (self.expected, self.expected)):
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                self.verify(*entries)

    def test_helper_directory_must_not_shadow_the_decoder(self):
        shadow = self.helpers / DECODER_CLASS
        shadow.parent.mkdir(parents=True)
        shadow.write_bytes(b"shadow")
        with self.assertRaises(ValueError):
            self.verify(self.helpers, self.expected)

    def test_mismatched_digest_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_core_classpath(self.command(self.expected), self.expected, "0" * 64)

    def test_implicit_missing_and_wildcard_classpaths_are_rejected(self):
        for command in (["java", "AllocationReplay"], self.command(self.root / "missing.jar"),
                        self.command(self.root / "*.jar"), ["java", "-cp"]):
            with self.subTest(command=command), self.assertRaises(ValueError):
                verify_core_classpath(command, self.expected, self.digest)

    def test_wrong_provider_stops_before_launch_or_log_creation(self):
        log = self.root / "not-started.log"
        with patch("verify_replay_classpath.subprocess.run") as launch:
            with self.assertRaises(ValueError):
                run_checked_replay(self.command(self.baseline), self.expected, self.digest, log)
            launch.assert_not_called()
        self.assertFalse(log.exists())

    def test_verified_command_launches_with_failure_propagation_enabled(self):
        command = self.command(self.helpers, self.expected)
        with patch("verify_replay_classpath.subprocess.run") as launch:
            result = run_checked_replay(command, self.expected, self.digest, self.root / "run.log")
        self.assertEqual(self.digest, result["sha256"])
        self.assertEqual(command, launch.call_args.args[0])
        self.assertTrue(launch.call_args.kwargs["check"])

    def test_existing_log_is_preserved_and_prevents_a_new_launch(self):
        log = self.root / "existing.log"
        log.write_bytes(b"previous evidence")
        with patch("verify_replay_classpath.subprocess.run") as launch:
            with self.assertRaises(FileExistsError):
                run_checked_replay(self.command(self.expected), self.expected, self.digest, log)
            launch.assert_not_called()
        self.assertEqual(b"previous evidence", log.read_bytes())


if __name__ == "__main__":
    unittest.main()
