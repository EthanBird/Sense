import json
import unittest
from evaluate_reference_engine import load_reference, compare


class ReferenceEngineTest(unittest.TestCase):
    def fixture(self):
        return [dict(type='header', schemaVersion=1, mode='whole-sentence-incremental', nbest=10,
            engine='libime-1.0.11-ubuntu-jammy', learning=False, fuzzy=['Inner']),
            dict(type='query', id='one', query='nihao', typingMicros=3, sentence='你好', candidates=[
                dict(text='你', score=0, consumed=2), dict(text='你好', score=-1, consumed=5),
                dict(text='你好', score=-2, consumed=5), dict(text='拟好', score=-3, consumed=5)]),
            dict(type='summary', rows=1)]

    def load(self, data):
        return load_reference([json.dumps(r) for r in data])

    def test_filters_prefixes_and_deduplicates_without_changing_engine_order(self):
        _, rows = self.load(self.fixture())
        self.assertEqual(rows[0]['fullTop10'], ['你好', '拟好'])
        self.assertEqual(rows[0]['displayFirst'], '你')

    def test_requires_complete_single_summary_and_frozen_configuration(self):
        for change in ['missing', 'extra', 'duplicate', 'count', 'config']:
            with self.subTest(change=change):
                f = self.fixture()
                if change == 'missing': f.pop()
                if change == 'extra': f.append(f[1])
                if change == 'duplicate': f.insert(2, f[1]); f[-1]['rows'] = 2
                if change == 'count': f[-1]['rows'] = 2
                if change == 'config': f[0]['learning'] = True
                with self.assertRaises(ValueError): self.load(f)

    def test_rejects_nonfinite_scores_and_invalid_coverage(self):
        for score, consumed in [(float('nan'), 5), (1, 6), (1, 0), (1, 5.0)]:
            f = self.fixture(); f[1]['candidates'][0].update(score=score, consumed=consumed)
            with self.assertRaises(ValueError): self.load(f)

    def test_pairs_by_identity_and_retains_improvements_and_display_distinctions(self):
        _, ref = self.load(self.fixture())
        old = [dict(id='one', mode='empty', cut=0, query='nihao', expected='你好', context='', rank=2, first='拟好')]
        text = 'one\tnihao\t你好\tshort\tni hao\n'
        result = compare(old, ref, text)
        self.assertEqual(result['sense']['top1'], 0); self.assertEqual(result['reference']['top1'], 1)
        self.assertEqual(len(result['referenceFirstGains']), 1)
        self.assertEqual(result['referenceDisplayDiffersFromFull'], 1)
        self.assertEqual(result['sense']['cer'], .5); self.assertEqual(result['reference']['cer'], 0)
        for modified, refs, gold in [(old * 2, ref, text), (old, [], text), (old, ref, text * 2),
                                     (old, ref, text.replace('nihao', 'niha'))]:
            with self.assertRaises(ValueError): compare(modified, refs, gold)

    def test_top10_does_not_turn_large_sense_pool_into_reference_advantage(self):
        _, ref = self.load(self.fixture())
        old = [dict(id='one', mode='empty', cut=0, query='nihao', expected='你好', context='', rank=11, first='拟好')]
        result = compare(old, ref, 'one\tnihao\t你好\tshort\tni hao')
        self.assertEqual(result['sense']['top10'], 0); self.assertEqual(result['sense']['mrrAt10'], 0)


if __name__ == '__main__': unittest.main()
