import copy
import unittest
from summarize_ranker_uncertainty import percentile, summarize


class RankerUncertaintyTest(unittest.TestCase):
    def report(self):
        a = dict(id='a', cut=0, mode='empty', expected='你好', rank=2, characterErrors=1)
        b = dict(a, cut=1, mode='editor', expected='好')
        return dict(before=dict(rows=[a, b]), after=dict(rows=[dict(a, rank=1, characterErrors=0), dict(b, rank=1, characterErrors=0)]))

    def test_related_states_are_one_cluster_and_deterministic(self):
        r = summarize(self.report(), replicates=1000)
        self.assertEqual(r, summarize(self.report(), replicates=1000))
        self.assertEqual(1, r['sourceSentences']); self.assertEqual(2, r['rows'])
        self.assertEqual([1., 1.], r['metrics']['top1']['percentile95'])
        self.assertEqual(1., r['metrics']['top1']['observedDelta'])
        self.assertAlmostEqual(-2/3, r['metrics']['cer']['observedDelta'])

    def test_pairing_and_duplicate_checks(self):
        r = self.report(); r['after']['rows'][1]['id'] = 'other'
        with self.assertRaises(ValueError): summarize(r)
        r = self.report(); r['before']['rows'] += copy.deepcopy(r['before']['rows']); r['after']['rows'] += copy.deepcopy(r['after']['rows'])
        with self.assertRaises(ValueError): summarize(r)

    def test_percentile_interpolates_without_assuming_independent_states(self):
        self.assertEqual(1.5, percentile([3., 0., 1., 2.], .5))
        with self.assertRaises(ValueError): summarize(self.report(), replicates=1)


if __name__ == '__main__': unittest.main()
