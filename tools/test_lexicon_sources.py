#!/usr/bin/env python3

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from lexicon_sources import (
    load_source_manifest,
    normalized_syllables,
    write_canonical_ir,
)


class LexiconSourcesTest(unittest.TestCase):
    def test_manifest_verifies_sources_and_calibrates_weights(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "base.dict.yaml"
            source.write_text(
                "# Rime dictionary\n---\n我\two\t100\n词典\tci dian\t40\n低频\tdi pin\t0\nA\tA\t9\n",
                encoding="utf-8",
            )
            manifest = self._write_manifest(root, source)

            loaded = load_source_manifest(manifest)

            self.assertEqual(3, len(loaded.records))
            self.assertEqual([75, 30, 2], [record.weight for record in loaded.records])
            self.assertTrue(loaded.records[0].prefix_eligible)
            self.assertTrue(loaded.records[1].initials_eligible)
            self.assertFalse(loaded.records[2].initials_eligible)
            self.assertEqual(1, loaded.audits[0].rejected_non_han)

    def test_hash_mismatch_is_a_hard_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "base.dict.yaml"
            source.write_text("我\two\t100\n", encoding="utf-8")
            manifest = self._write_manifest(root, source)
            payload = json.loads(manifest.read_text(encoding="utf-8"))
            payload["sources"][0]["sha256"] = "0" * 64
            manifest.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                load_source_manifest(manifest)

    def test_source_path_must_stay_under_manifest_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outside = root.parent / "outside-sense-lexicon.tsv"
            outside.write_text("我\two\t100\n", encoding="utf-8")
            try:
                local = root / "base.dict.yaml"
                local.write_text("我\two\t100\n", encoding="utf-8")
                manifest = self._write_manifest(root, local)
                payload = json.loads(manifest.read_text(encoding="utf-8"))
                payload["sources"][0]["path"] = "../outside-sense-lexicon.tsv"
                payload["sources"][0]["sha256"] = hashlib.sha256(
                    outside.read_bytes()
                ).hexdigest()
                manifest.write_text(json.dumps(payload), encoding="utf-8")

                with self.assertRaisesRegex(ValueError, "escapes"):
                    load_source_manifest(manifest)
            finally:
                outside.unlink(missing_ok=True)

    def test_canonical_ir_is_stable_and_attributed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "base.dict.yaml"
            source.write_text("词典\tci dian\t40\n我\two\t100\n", encoding="utf-8")
            loaded = load_source_manifest(self._write_manifest(root, source))
            first = root / "first.tsv"
            second = root / "second.tsv"

            write_canonical_ir(first, loaded.manifest_sha256, loaded.records)
            write_canonical_ir(second, loaded.manifest_sha256, loaded.records)

            self.assertEqual(first.read_bytes(), second.read_bytes())
            text = first.read_text(encoding="utf-8")
            self.assertIn("manifest-sha256=", text)
            self.assertIn("词典\tci dian\t30\t0\tfixture\t40\tih", text)

    def test_normalization_handles_umlaut_and_numbered_tones(self) -> None:
        self.assertEqual(["nv", "er"], normalized_syllables("nü3 er2"))
        self.assertEqual(["lve"], normalized_syllables("lüe4"))

    @staticmethod
    def _write_manifest(root: Path, source: Path) -> Path:
        manifest = root / "sources.json"
        payload = {
            "schema_version": 1,
            "license": "GPL-3.0-only",
            "upstream": {"repository": "fixture", "commit": "fixture"},
            "sources": [
                {
                    "id": "fixture",
                    "path": source.name,
                    "format": "rime-dict-yaml",
                    "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                    "revision": "fixture-v1",
                    "license": "GPL-3.0-only",
                    "source_tier": 0,
                    "weight": {
                        "numerator": 3,
                        "denominator": 4,
                        "offset": 0,
                        "minimum": 2,
                        "maximum": 1000,
                        "max_text_length": 8,
                    },
                    "index": {
                        "prefix_min_weight": 50,
                        "initials_min_weight": 10,
                        "hybrid_min_weight": 25,
                    },
                }
            ],
        }
        manifest.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return manifest


if __name__ == "__main__":
    unittest.main()
