import math
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from train_mkn_character_lm import adjusted_counts, estimate_discounts, train_model
from train_character_lm import BOS, EOS, UNK, write_model, read_model, train_model as original_model


class ModifiedKneserNeyTest(unittest.TestCase):
    def test_cli_uses_only_hashed_train_and_preserves_attribution_and_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            segments = ['我们明天出去玩'] * 3
            train = root / 'train.jsonl'
            train.write_text(json.dumps(dict(id='train-1', segments=segments, group='train-family', domain='fixture')) + '\n', encoding='utf-8')
            # Deliberately unusable held-out files: train must not inspect them.
            for split in ('dev', 'test'):
                (root / (split + '.jsonl')).write_text('not valid json', encoding='utf-8')
            attribution = root / 'attribution.jsonl'
            attribution.write_text('{"author":"fixture"}\n', encoding='utf-8')
            digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
            manifest = dict(schemaVersion=1, source=dict(license='fixture'),
                            outputs=dict(train=dict(fileName=train.name, sha256=digest(train), records=1)),
                            attribution=dict(fileName=attribution.name, sha256=digest(attribution)))
            (root / 'corpus.json').write_text(json.dumps(manifest), encoding='utf-8')
            baseline = root / 'baseline.scng'
            write_model(original_model(segments), baseline)
            def run(name):
                return subprocess.run([sys.executable, str(Path(__file__).with_name('train_mkn_character_lm.py')),
                                       str(root), str(baseline), str(root / (name + '.scng')),
                                       str(root / (name + '.json'))], capture_output=True, text=True)
            first = run('first')
            self.assertEqual(0, first.returncode, first.stderr)
            report = json.loads((root / 'first.json').read_text('utf-8'))
            self.assertEqual(manifest['attribution'], report['attribution'])
            self.assertFalse(report['testEvaluated'])
            self.assertNotEqual(0, run('first').returncode)
            train.write_text('changed train', encoding='utf-8')
            self.assertNotEqual(0, run('changed').returncode)
            self.assertFalse((root / 'changed.scng').exists())
            attribution.unlink()
            self.assertNotEqual(0, run('missing').returncode)

    def test_distinct_predecessors_not_raw_frequency_and_raw_bos_counts(self):
        segments = ['甲乙'] * 20 + ['丙丁', '戊丁', '己丁']
        vocabulary = {ord(ch) for text in segments for ch in text} | {EOS, UNK}
        uni, bi, tri = adjusted_counts(segments, vocabulary)
        self.assertEqual((1, 3), (uni[ord('乙')], uni[ord('丁')]))
        self.assertEqual(20, bi[BOS, ord('甲')])
        self.assertEqual(1, bi[ord('甲'), ord('乙')])
        self.assertEqual(20, tri[BOS, ord('甲'), ord('乙')])
        model, _ = train_model(segments, min_char_count=1)
        old = original_model(segments, min_char_count=1)
        self.assertLess(model.unigrams[ord('乙')], model.unigrams[ord('丁')])
        self.assertGreater(old.unigrams[ord('乙')], old.unigrams[ord('丁')])

    def test_closed_form_discounts_and_sparse_fallback(self):
        counts = {str(i): c for i, c in enumerate([1] * 10 + [2] * 5 + [3] * 3 + [4] * 2)}
        discounts, report = estimate_discounts(counts)
        for expected, actual in zip([0.5, 1.1, 5 / 3], discounts):
            self.assertAlmostEqual(expected, actual)
        self.assertEqual([], report['fallbackBuckets'])
        self.assertEqual([0.75] * 3, estimate_discounts({('甲',): 100})[0])

    def test_all_contexts_normalize_with_pruning_unknowns_and_float32(self):
        segments = ['我喜欢吃饭'] * 8 + ['你喜欢喝茶', '他今天喝茶', '𠀀在这里']
        for cutoff in (1, 2, 100):
            model, _ = train_model(segments, min_char_count=2, min_bigram_count=cutoff, min_trigram_count=cutoff)
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'm.scng'
                write_model(model, path)
                loaded = read_model(path.read_bytes())
                tokens = list(model.unigrams) + [BOS, ord('𠀀')]
                for a in tokens:
                    for b in tokens:
                        for order in (1, 2, 3):
                            mass = sum(math.exp(loaded.log_probability(a, b, w, order)) for w in model.unigrams)
                            self.assertAlmostEqual(1.0, mass, places=6)
                self.assertNotIn(ord('𠀀'), model.unigrams)
                self.assertTrue(math.isfinite(loaded.log_probability(UNK, UNK, EOS)))

    def test_order_independence_and_same_vocabulary(self):
        segments = ['我是一个人'] * 4 + ['你是一个人', '𠀀来了', '𠀀去了']
        first, _ = train_model(segments)
        second, _ = train_model(reversed(segments))
        self.assertEqual(first, second)
        self.assertEqual(first.unigrams.keys(), original_model(segments).unigrams.keys())
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / 'a', Path(directory) / 'b'
            write_model(first, a); write_model(second, b)
            self.assertEqual(a.read_bytes(), b.read_bytes())

    def test_invalid_inputs(self):
        for segments in ([], [''], ['ASCII'], ['我 喜欢']):
            with self.assertRaises(ValueError):
                train_model(segments)
        for options in ({'min_char_count': 0}, {'min_bigram_count': 0}, {'min_trigram_count': 0},
                        {'alpha': 0}, {'alpha': float('nan')}):
            with self.assertRaises(ValueError):
                train_model(['我是一个人'], **options)


if __name__ == '__main__':
    unittest.main()
