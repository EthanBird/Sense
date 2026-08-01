#!/usr/bin/env python3

import tempfile
import unittest
import zipfile
from pathlib import Path

import verify_wubi86_assets as verifier


class Wubi86AssetVerifierTest(unittest.TestCase):
    def test_zip_entries_must_be_unique_and_byte_identical(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            canonical = root / "canonical.bin"
            canonical.write_bytes(b"SWBX fixture")
            apk = root / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/wubi86_lexicon.bin", canonical.read_bytes())

            verifier.verify_zip_entries(
                apk,
                {"assets/wubi86_lexicon.bin": canonical},
            )

            canonical.write_bytes(b"changed")
            with self.assertRaisesRegex(verifier.VerificationError, "reviewed source"):
                verifier.verify_zip_entries(
                    apk,
                    {"assets/wubi86_lexicon.bin": canonical},
                )

    def test_zip_entries_reject_missing_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            canonical = root / "canonical.txt"
            canonical.write_text("license", encoding="utf-8")
            apk = root / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/NOTICE.txt", b"notice")

            with self.assertRaisesRegex(verifier.VerificationError, "exactly one"):
                verifier.verify_zip_entries(
                    apk,
                    {"assets/RIME-WUBI-LGPL-3.0.txt": canonical},
                )


if __name__ == "__main__":
    unittest.main()
