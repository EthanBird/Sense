import copy
import gzip
import json
from pathlib import Path
import tempfile
import unittest

from candidate_student import candidate_evidence
from run_e55_aligned_context import add_provenance, coverage, evaluate


class E55PipelineTests(unittest.TestCase):
    def setUp(self):
        self.rows = {('a', 0): dict(id='a', cut=0, mode='empty', stratum='synthetic', query='jiayi',
            context='', expected='甲乙', rank=2, candidates=['甲已', '甲乙'],
            evidence={t: dict(kind='BASE_COMPOSED', baseline=2.-i, features=[0.]*17, words=['甲', t[1]],
                              personalEvidence=False) for i,t in enumerate(['甲已', '甲乙'])})}

    def test_metrics_derive_from_actual_stable_permutation(self):
        details, report = evaluate(self.rows, lambda *_: [1., 2.])
        self.assertEqual(report['before']['top1'], 0); self.assertEqual(report['after']['top1'], 1)
        self.assertEqual(report['before']['recall255'], report['after']['recall255'])
        self.assertEqual(details[0]['afterCandidates'], ['甲乙', '甲已'])
        self.assertTrue(report['nonregression']); self.assertEqual(len(report['firstGains']), 1)

    def test_full_order_retained_when_guard_fires(self):
        self.rows['a', 0]['evidence']['甲已']['features'][11] = -1.
        def fail(*_): raise AssertionError('Guarded path reached scorer')
        _, report = evaluate(self.rows, fail)
        self.assertEqual(report['before'], report['after'])
        self.assertEqual(report['reasons'], {'personalized-path': 1})

    def test_coverage_ignores_reference_and_rank(self):
        feature = lambda context, text, _: 1. if text.endswith('乙') else -1.
        expected = coverage(self.rows, feature)
        changed = copy.deepcopy(self.rows); changed['a', 0].update(expected='无关', rank=100)
        self.assertEqual(expected, coverage(changed, feature)); self.assertEqual(expected['discriminating'], 1)

    def test_provenance_bound_to_same_winner(self):
        raw = dict(kind='BASE_COMPOSED', total=2., prior=0., features=[0.]*16,
                   text='甲乙', edges=[['甲', 'jia', 10, 0], ['乙', 'yi', None, None]])
        row = dict(type='row', id='a', cut=0, pool=[raw], winnerIndices=[0])
        evidence = candidate_evidence(raw) | dict(words=['甲', '乙'])
        rows = {('a', 0): dict(texts=['甲乙'], evidence=[evidence])}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'export.gz'
            with gzip.open(path, 'wt', encoding='utf-8') as f: f.write(json.dumps(row) + '\n')
            report = add_provenance(rows, path)
            self.assertEqual(report['personalPaths'], 1); self.assertTrue(evidence['personalEvidence'])
            evidence['baseline'] = 999.
            with self.assertRaises(ValueError): add_provenance(rows, path)


if __name__ == '__main__': unittest.main()
