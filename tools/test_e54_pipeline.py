import copy
import unittest

from full_word_context import Counts, AssociationFeature
from run_e54_full_word_context import coverage
from word_context_ranker import WordContextScorer, SCHEMA, CAP, pair_key


class CoverageBoundaryTests(unittest.TestCase):
    def setUp(self):
        c = Counts(); c.add('one', '有点累', ['有点', '累']); c.add('two', '我有点累', ['我', '有点', '累'])
        self.model = c.export()
        self.feature = AssociationFeature(self.model)
        key = pair_key('W2', '有点', '类')
        self.old = WordContextScorer(dict(schema=SCHEMA, cap=CAP, includeWords=True,
                                         vocabulary=[key], weights=[-1.]))
        self.rows = {('id', 0): dict(id='id', cut=0, context='', candidates=['有点类', '有点累'],
            expected='有点累', rank=2, evidence={text: dict(kind='BASE_COMPOSED', baseline=2.-i,
                features=[0.] * 17, words=['有点', text[-1]]) for i, text in enumerate(['有点类', '有点累'])})}

    def test_coverage_ignores_reference_and_rank(self):
        before = coverage(self.rows, self.feature, self.old)
        changed = copy.deepcopy(self.rows)
        changed['id', 0].update(expected='无关参考', rank=999)
        self.assertEqual(before, coverage(changed, self.feature, self.old))
        self.assertEqual(before[0]['newDiscriminating'], 1)

    def test_protected_personal_source_not_exposed_to_word_feature(self):
        self.rows['id', 0]['evidence']['有点类']['kind'] = 'USER_FULL'
        report, detail = coverage(self.rows, self.feature, self.old)
        self.assertEqual(report['eligible'], 0); self.assertEqual(detail, [])

    def test_zero_positive_lift_is_not_counted_as_useful_coverage(self):
        self.feature.values = {k: 0. for k in self.feature.values}
        report, _ = coverage(self.rows, self.feature, self.old)
        self.assertEqual(report['newAny'], 0); self.assertEqual(report['newDiscriminating'], 0)

    def test_baseline_candidate_slots_are_not_modified(self):
        original = copy.deepcopy(self.rows)
        coverage(self.rows, self.feature, self.old)
        self.assertEqual(self.rows, original)

    def test_consistent_local_score_tampering_still_fails_global_backoff_mass(self):
        import math
        bad = copy.deepcopy(self.model)
        row = bad['entries'][0]
        row[5] = .9
        row[6] = max(0., min(4., math.log(((row[1]-.75)/row[2] + row[5]*row[4])/row[4])))
        with self.assertRaises(ValueError): AssociationFeature(bad)

    def test_namespace_population_bound_rejected(self):
        bad = copy.deepcopy(self.model)
        bad['namespaces']['W2']['vocabulary'] = bad['namespaces']['W2']['unigramTotal'] + 1
        with self.assertRaises(ValueError): AssociationFeature(bad)


if __name__ == '__main__': unittest.main()
