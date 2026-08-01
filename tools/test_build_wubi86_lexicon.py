#!/usr/bin/env python3

import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path

import build_wubi86_lexicon as builder


class Wubi86BuilderTest(unittest.TestCase):
    def test_exact_precedes_bounded_completion_and_z_is_reserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            vendor = root / "vendor"
            vendor.mkdir()
            dictionary = vendor / "wubi86.dict.yaml"
            dictionary.write_text(
                "---\nname: fixture\n...\n"
                "工\ta\t90\n"
                "式\taa\t100\n"
                "符号\tzabc\t999\n",
                encoding="utf-8",
            )
            license_file = vendor / "LICENSE"
            authors = vendor / "AUTHORS"
            license_file.write_text("license", encoding="utf-8")
            authors.write_text("authors", encoding="utf-8")
            sources = []
            for source_id, path in (
                ("dict", dictionary),
                ("license", license_file),
                ("authors", authors),
            ):
                sources.append(
                    {
                        "id": source_id,
                        "path": str(path.relative_to(root)),
                        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    }
                )
            manifest = root / "sources.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "upstream": {"license": "LGPL-3.0"},
                        "sources": sources,
                    }
                ),
                encoding="utf-8",
            )
            output = root / "wubi.bin"

            builder.build(manifest, output)

            data = output.read_bytes()
            self.assertEqual(builder.MAGIC, data[:4])
            version, exact_count, prefix_count, reverse_count = struct.unpack_from(">HIII", data, 4)
            self.assertEqual(builder.VERSION, version)
            self.assertEqual(2, exact_count)
            self.assertEqual(1, prefix_count)
            self.assertEqual(2, reverse_count)

    def test_production_source_keeps_expected_short_codes(self):
        root = Path(__file__).resolve().parent.parent
        manifest = root / "ime-service/src/main/lexicon/wubi_sources.json"
        document, sources = builder.load_manifest(manifest)
        dictionary = next(source.path for source in sources if source.path.name.endswith(".dict.yaml"))
        entries, stats = builder.read_dictionary(dictionary)
        exact = builder.group_exact(entries)

        self.assertEqual("工", exact["a"][0].text)
        self.assertEqual("了", exact["b"][0].text)
        self.assertEqual("我", exact["q"][0].text)
        self.assertEqual("的", exact["r"][0].text)
        self.assertGreater(stats["excluded_z"], 0)


if __name__ == "__main__":
    unittest.main()
