import copy
from dataclasses import replace
from pathlib import Path
import tempfile
import unittest

from audit_association_asset import verify_dictionary_dependency, digest
from build_pinyin_lexicon import LexiconCandidate, write_binary
from package_association_model import dictionary_dependency


class LayeredDictionaryDependencyTest(unittest.TestCase):
    def fixture(self, root, changed=False):
        root = Path(root)
        word = LexiconCandidate("智能", 100, "zn", 0)
        write_binary({"zhineng": [word]}, root/'base')
        base = (root/'base').read_bytes()
        write_binary({"zhineng": [replace(word, weight=101) if changed else word],
                      "zhinengti": [LexiconCandidate("智能体", 1, "znt", 2)]}, root/'layered', version=4)
        data = (root/'layered').read_bytes()
        return data, {"sha256": digest(base), "projection": {"format": "SPLX/3", "operation": "remove-tier-2"},
                      "runtimeAsset": {"format": "SPLX/4", "sha256": digest(data), "bytes": len(data)}}

    def test_runtime_extension_preserves_the_exact_training_base(self):
        with tempfile.TemporaryDirectory() as root:
            data, dependency = self.fixture(root)
            result = verify_dictionary_dependency(data, dependency)
            self.assertTrue(result['baseProjectionVerified'])
            self.assertNotEqual(result['runtimeSha256'], result['trainingBaseSha256'])
            self.assertEqual(dependency['sha256'], result['trainingBaseSha256'])

    def test_self_consistent_runtime_hash_never_hides_a_changed_training_word(self):
        with tempfile.TemporaryDirectory() as root:
            data, dependency = self.fixture(root, changed=True)
            with self.assertRaises(ValueError): verify_dictionary_dependency(data, dependency)

    def test_missing_or_changed_projection_pins_fail(self):
        with tempfile.TemporaryDirectory() as root:
            data, dependency = self.fixture(root)
            for kind in ('algorithm', 'missing', 'hash', 'size', 'format'):
                dep = copy.deepcopy(dependency)
                if kind == 'algorithm': dep['projection']['operation'] = 'ignore-differences'
                elif kind == 'missing': del dep['runtimeAsset']
                else: dep['runtimeAsset'][{'hash':'sha256', 'size':'bytes', 'format':'format'}[kind]] = 'changed'
                with self.assertRaises(ValueError): verify_dictionary_dependency(data, dep)

    def test_packager_retains_training_hash_and_proves_runtime_projection(self):
        with tempfile.TemporaryDirectory() as root:
            data, dependency = self.fixture(root)
            actual = dictionary_dependency(data, dependency['sha256'])
            self.assertEqual(dependency['sha256'], actual['sha256'])
            self.assertEqual(dependency['runtimeAsset'], actual['runtimeAsset'])
            changed, _ = self.fixture(root, changed=True)
            with self.assertRaises(ValueError): dictionary_dependency(changed, dependency['sha256'])


if __name__ == '__main__': unittest.main()
