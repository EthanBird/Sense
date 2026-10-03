import bz2
import io
import tarfile
import tempfile
import unittest
from pathlib import Path

from audit_sentence_corpus import audit_source, han_key, replay_texts, sha256


class SentenceCorpusAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def detailed(self, text):
        path = self.root / "cmn.tsv.bz2"
        path.write_bytes(bz2.compress(text.encode("utf-8")))
        return {"id": "fixture", "fileName": path.name, "format": "tatoeba-detailed-bz2",
                "license": "CC-BY-2.0-FR", "sha256": sha256(path), "url": "https://example.test/fixture"}

    def test_detailed_counts_and_overlap_keep_script_boundary_explicit(self):
        source = self.detailed("1\tcmn\t我来了。\talice\t0\t0\n2\tcmn\t我來了！\tbob\t0\t\\N\n3\teng\tHello.\tcarol\t0\t0\n4\tcmn\t我来了！\t\\N\t0\t0\n")
        result = audit_source(source, self.root, {"我来了"})
        self.assertEqual(3, result["mandarinRows"])
        self.assertEqual(3, result["uniqueMandarinTexts"])
        self.assertEqual(2, result["uniqueHanSkeletonsBeforeScriptConversion"])
        self.assertEqual(1, result["existingReplayExactHanOverlapCount"])
        self.assertEqual(1, result["missingContributorRows"])
        self.assertEqual(1, result["missingModifiedDates"])
        self.assertFalse(result["sufficientRowsForPilot"])
        self.assertFalse(result["productionReady"])

    def test_hash_license_and_path_fail_closed(self):
        source = self.detailed("1\tcmn\t你好\talice\t0\t0\n")
        for change in ({"sha256": "0" * 64}, {"license": "CC0-1.0"}, {"fileName": "../cmn.tsv.bz2"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                audit_source({**source, **change}, self.root, set())

    def test_duplicate_and_malformed_ids_rejected(self):
        for text in ("1\tcmn\t你好\ta\t0\t0\n1\tcmn\t再见\tb\t0\t0\n", "oops\tcmn\t你好\ta\t0\t0\n", "0\tcmn\t你好\ta\t0\t0\n", "1\tcmn\t你好\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                audit_source(self.detailed(text), self.root, set())

    def test_cc0_archive_is_read_without_extracting_members(self):
        path = self.root / "cc0.tar.bz2"
        for name, allowed in (("sentences_CC0.csv", True), ("../sentences_CC0.csv", False)):
            with tarfile.open(path, "w:bz2") as archive:
                data = "1\teng\tHi\t0\n2\tcmn\t你好\t0\n".encode()
                member = tarfile.TarInfo(name); member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            source = {"id": "cc0", "fileName": path.name, "format": "tatoeba-cc0-tar",
                      "license": "CC0-1.0", "sha256": sha256(path), "url": "https://example.test/fixture"}
            if allowed:
                result = audit_source(source, self.root, set())
                self.assertEqual(2, result["totalRows"])
                self.assertEqual(1, result["mandarinRows"])
            else:
                with self.assertRaises(ValueError):
                    audit_source(source, self.root, set())
            self.assertFalse((self.root / "sentences_CC0.csv").exists())

    def test_supplementary_han_and_replay_aliases(self):
        self.assertEqual("你好𠀀", han_key("Ｎ你好，𠀀！"))
        (self.root / "m.tsv").write_text("# ignored\nnihao\t你好|您好\n", encoding="utf-8")
        self.assertEqual({"你好", "您好"}, replay_texts(self.root))


if __name__ == "__main__":
    unittest.main()
