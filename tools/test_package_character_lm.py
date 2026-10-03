import unittest
from package_character_lm import attributed_training_rows, attribution_bytes


class PackageCharacterLmTest(unittest.TestCase):
    def test_only_training_authors_are_exported_as_stable_utf8_tsv(self):
        source = [{"split": "train", "sentenceId": 5, "contributor": "alice", "license": "CC-BY-2.0-FR"},
                  {"split": "test", "sentenceId": 7, "contributor": "bob", "license": "CC-BY-2.0-FR"}]
        rows = attributed_training_rows(source)
        self.assertEqual([(5, "alice")], rows)
        self.assertEqual(b"# sentenceId\tcontributor\n5\talice\n", attribution_bytes(rows))
        self.assertNotIn("bob", attribution_bytes(rows).decode())
        with self.assertRaises(ValueError):
            attributed_training_rows([{**source[0], "contributor": ""}])


if __name__ == "__main__":
    unittest.main()
