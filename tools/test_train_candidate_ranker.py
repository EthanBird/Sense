import gzip
import json
import math
from pathlib import Path
import tempfile
import unittest
from audit_score_features import FEATURES, f32
from train_candidate_ranker import ZERO, build_pairs, compare, evaluate, fit, load_export, objective, order, sha, vector


def candidate(text, score, feature=0):
    values = ZERO.copy(); values[0] = feature
    return dict(text=text, baseline=score, vector=values, kind='BASE_COMPOSED')


def row(pool, expected='你'):
    return dict(id='sample', cut=0, mode='empty', stratum='short', query='ni', context='', expected=expected, pool=pool)


class CandidateRankerTrainingTest(unittest.TestCase):
    def test_pairwise_gradient_matches_finite_difference_and_extremes_are_finite(self):
        pairs = [(2., [1., -2., 3., .1, 0., .5], .5), (-1., [-2., 1., 0., 1., .2, 0.], .5)]
        point = [.2, -.1, 0., .3, -.2, .1]
        loss, gradient = objective(point, pairs, .1)
        for i in range(6):
            a = point.copy(); b = point.copy(); a[i] += 1e-6; b[i] -= 1e-6
            numerical = (objective(a, pairs, .1)[0] - objective(b, pairs, .1)[0]) / 2e-6
            self.assertAlmostEqual(numerical, gradient[i], places=7)
        value, g = objective(ZERO, [(1000., [1.] * 6, .5), (-1000., [-1.] * 6, .5)], .1)
        self.assertTrue(math.isfinite(value) and all(math.isfinite(x) for x in g))

    def test_projection_regularization_and_deterministic_fitting(self):
        pairs = [(3., [1., 0., 0., 0., 0., 0.], 1.)]
        policy = dict(initialStep=.5, deltaBounds=[-.75, 1.], maxIterations=300, tolerance=1e-7)
        weak = fit(pairs, .001, policy); strong = fit(pairs, 10., policy)
        self.assertEqual(weak, fit(pairs, .001, policy))
        self.assertEqual(-.75, weak['delta'][0])
        self.assertLess(abs(strong['delta'][0]), abs(weak['delta'][0]))
        self.assertLessEqual(weak['history'][-1]['objective'], objective(ZERO, pairs, .001)[0])
        self.assertTrue(all(-.75 <= d <= 1 for d in weak['delta']))

    def test_pairs_exclude_unrecalled_targets_and_weight_queries_equally(self):
        rows = [row([candidate('你', 2), candidate('泥', 1)]),
                row([candidate('你', 3), candidate('泥', 2), candidate('拟', 1)]),
                row([candidate('泥', 1)])]
        pairs, count = build_pairs(rows, 8)
        self.assertEqual(2, count)
        self.assertEqual(3, len(pairs))
        self.assertAlmostEqual(1., sum(p[2] for p in pairs))
        self.assertEqual([.5, .25, .25], [p[2] for p in pairs])
        with self.assertRaises(ValueError): build_pairs([rows[-1]], 8)

    def test_zero_deltas_preserve_score_domain_and_nonzero_reranking_uses_no_gold(self):
        a = row([candidate('泥', 2, 0), candidate('你', 1, 3)])
        b = dict(a, expected='拟')
        self.assertEqual([0, 1], order(a, ZERO))
        self.assertEqual([1, 0], order(a, [1., 0, 0, 0, 0, 0]))
        self.assertEqual(order(a, [1.] * 6), order(b, [1.] * 6))

    def test_lexical_mass_and_anchor_are_bound_not_independent_features(self):
        values = [float(i) for i in range(len(FEATURES))]
        result = vector(dict(features=values))
        self.assertEqual(sum(values[:4]), result[0])
        self.assertEqual(values[FEATURES.index('SPELLING')], result[-1])

    def test_evaluation_reports_gains_losses_and_unrecalled_rows_in_denominator(self):
        rows = [row([candidate('你', 2, 0), candidate('泥', 1, 3)]),
                dict(row([candidate('泥', 2, 0), candidate('你', 1, 3)]), id='gain'),
                dict(row([candidate('泥', 1, 0)]), id='missing')]
        before = evaluate(rows, ZERO); after = evaluate(rows, [1., 0, 0, 0, 0, 0]); changes = compare(before, after)
        self.assertEqual(3, before['overall']['count'])
        self.assertEqual(2, before['overall']['oracleRecall'])
        self.assertEqual(1, len(changes['firstGains']))
        self.assertEqual(1, len(changes['firstLosses']))
        self.assertEqual(1, len(changes['rankLosses']))

    def fixture(self, root):
        source = root / 'input.tsv'; source.write_text('sample\tnihao\t你好\tshort\tni hao\n', encoding='utf-8')
        header = dict(type='header', featureVersion='actual-path-v1', mode='sampled', features=FEATURES,
            inputSha256=sha(source), configuration=dict(lmWeight=.5, oovFeature=-4, correctionBoost=12, limit=255))
        rows = []
        for cut, text, query, context in [(0, '你好', 'nihao', ''), (1, '好', 'hao', '你')]:
            features = [0.] * len(FEATURES); features[0] = len(text) * f32(math.log(2))
            score = f32(features[0])
            path = dict(text=text, kind='BASE_EXACT', features=features,
                edges=[[c, 'fixture', 1, 0] for c in text], segments=len(text), normalizer=1.,
                score=score, prior=8., total=f32(score + 8.), arithmeticError=score - features[0])
            rows.append(dict(type='row', id='sample', cut=cut, mode='editor' if cut else 'empty',
                query=query, context=context, expected=text, stratum='short', rank=1,
                pool=[path], winnerIndices=[0]))
        payload = [header, *rows, dict(type='summary', rows=2, paths=2, observationEquivalent=True)]
        export = root / 'pool.gz'
        with gzip.open(export, 'wt', encoding='utf-8') as stream:
            for r in payload: stream.write(json.dumps(r) + '\n')
        return source, export, payload

    def test_loader_checks_full_workload_and_labels_and_rejects_partial_export(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source, export, payload = self.fixture(root)
            _, rows, audit = load_export(export, source)
            self.assertEqual(2, len(rows)); self.assertTrue(audit['zeroDeltaEquivalent'])
            with gzip.open(export, 'wt', encoding='utf-8') as stream:
                for r in payload[:-1]: stream.write(json.dumps(r) + '\n')
            with self.assertRaises(ValueError): load_export(export, source)

    def test_loader_rejects_changed_labels_or_version(self):
        with tempfile.TemporaryDirectory() as directory:
            for mutation in [lambda x: x[1].update(expected='泥号'), lambda x: x[0].update(mode='suffixes')]:
                root = Path(directory); source, export, payload = self.fixture(root)
                mutation(payload)
                with gzip.open(export, 'wt', encoding='utf-8') as stream:
                    for r in payload: stream.write(json.dumps(r) + '\n')
                with self.assertRaises(ValueError): load_export(export, source)


if __name__ == '__main__': unittest.main()
