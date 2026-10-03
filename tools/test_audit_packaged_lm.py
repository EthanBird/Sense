import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile
from audit_packaged_lm import audit


class PackagedLmAuditTest(unittest.TestCase):
    def package(self, root, renamed=False, corrupt=False):
        model = b"SCNG_fixture"
        authors = b"# sentenceId\tcontributor\n42\talice\n"
        sha = lambda data: hashlib.sha256(data).hexdigest()
        name = "authors.tsv.gz" if renamed else "authors.tsv"
        notice = {"model": {"file": "model.scng", "sha256": sha(model), "bytes": len(model)},
                  "attribution": {"file": name, "sha256": sha(authors), "sentences": 1, "contributors": 1},
                  "license": "CC-BY-2.0-FR", "decoderMode": "lm0.5"}
        apk = Path(root) / "fixture.apk"
        with ZipFile(apk, "w") as out:
            out.writestr("assets/pinyin_character_lm_notice.json", json.dumps(notice))
            out.writestr("assets/model.scng", model + (b"!" if corrupt else b""))
            out.writestr("assets/authors.tsv", authors)
            out.writestr("assets/PINYIN-LM-NOTICE.txt", f"{name}\nalice\n")
        return apk

    def test_all_packaged_paths_hashes_and_attribution_resolve(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertTrue(audit(self.package(root))["passed"])

    def test_aapt_style_rename_is_rejected(self):
        with tempfile.TemporaryDirectory() as root, self.assertRaises(KeyError):
            audit(self.package(root, renamed=True))

    def test_modified_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as root, self.assertRaises(AssertionError):
            audit(self.package(root, corrupt=True))


if __name__ == "__main__":
    unittest.main()
