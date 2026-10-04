import copy
import math
import unittest

from full_word_context import AssociationFeature, AssociationScorer, Counts, fit_scalar, observations
from word_context_ranker import pair_key


def example():
    counts = Counts()
    counts.add('a', '我有点累', ['我', '有点', '累'])
    counts.add('b', '他有点累', ['他', '有点', '累'])
    counts.add('c', '这个类型', ['这个', '类型'])
    counts.add('d', '这种类型', ['这种', '类型'])
    return counts.export()


class FullWordContextTests(unittest.TestCase):
    def test_midword_suffix_and_boundary_are_observations_not_sentence_ends(self):
        events = list(observations('智能体很好', ['智能体', '很好']))
        self.assertIn(('P', 'CW', '智', '能体'), events)
        self.assertIn(('P', 'CW', '能体', '很好'), events)
        self.assertIn(('P', 'W2', '智能体', '很好'), events)
        self.assertTrue(all(e[3] for e in events))
        self.assertEqual(sum(e[0] == 'U' and e[1] == 'CW' for e in events), 4)

    def test_supplementary_han_is_single_character(self):
        events = list(observations('𠀀甲乙', ['𠀀甲', '乙']))
        self.assertIn(('P', 'CW', '𠀀甲', '乙'), events)

    def test_repeated_single_family_is_not_general_evidence(self):
        counts = Counts()
        for _ in range(10): counts.add('a', '有点累', ['有点', '累'])
        with self.assertRaises(ValueError): counts.export()
        counts.add('b', '有点累', ['有点', '累'])
        rows = {r[0]: r for r in counts.export()['entries']}
        self.assertEqual(rows[pair_key('W2', '有点', '累')][1], 11)

    def test_discount_mass_and_rare_removed_edges(self):
        counts = Counts()
        counts.add('a', '甲乙', ['甲', '乙']); counts.add('b', '甲乙', ['甲', '乙'])
        counts.add('a', '甲丙', ['甲', '丙'])
        model = counts.export(); row = next(r for r in model['entries'] if r[0] == pair_key('W2', '甲', '乙'))
        self.assertEqual(row[1:3], [2, 3])
        self.assertAlmostEqual(row[5], 1 - (2-.75)/3)
        self.assertNotIn(pair_key('W2', '甲', '丙'), AssociationFeature(model).values)

    def test_model_has_separate_word_and_suffix_unigram_distributions(self):
        model = example()
        self.assertNotEqual(model['namespaces']['W2']['unigramTotal'], model['namespaces']['CW']['unigramTotal'])
        AssociationFeature(model)

    def test_unknown_context_and_word_are_neutral_not_negative(self):
        feature = AssociationFeature(example())
        self.assertEqual(feature('未知', '甲乙', dict(words=['甲', '乙'])), 0.)
        self.assertGreater(feature('', '有点累', dict(words=['有点', '累'])), 0.)

    def test_actual_path_stays_intact_and_no_single_word_feature(self):
        feature = AssociationFeature(example())
        self.assertEqual(feature('', '有点累', dict(words=['有点累'])), 0.)
        with self.assertRaises(ValueError): feature('', '有点累', dict(words=['有点', '类']))

    def test_bounded_feature_and_zero_weight_identity(self):
        feature = AssociationFeature(example()); e = dict(words=['有点', '累'], baseline=17.89525604248047)
        self.assertEqual(AssociationScorer(feature, 0.)('', ['有点累'], [e]), [e['baseline']])
        result = AssociationScorer(feature, 4.)('', ['有点累'], [e])[0]
        self.assertTrue(e['baseline'] < result <= e['baseline'] + 8.)

    def test_reject_unbounded_and_nonfinite_inputs(self):
        feature = AssociationFeature(example())
        for context, text in [('三个字', '有点累'), ('', '累'), ('。', '有点累')]:
            with self.assertRaises(ValueError): feature(context, text, dict(words=[text]))
        for weight in [-1., 4.1, float('nan')]:
            with self.assertRaises(ValueError): AssociationScorer(feature, weight)
        with self.assertRaises(ValueError):
            AssociationScorer(feature, 1.)('', ['有点累'], [dict(words=['有点', '累'], baseline=float('inf'))])

    def test_tampered_score_and_count_evidence_rejected(self):
        for column, value in [(1, 1), (4, .99), (5, 0.), (6, 9.)]:
            model = example(); model['entries'][0][column] = value
            with self.assertRaises(ValueError): AssociationFeature(model)

    def test_duplicate_unsorted_keys_rejected(self):
        model = example(); model['entries'].append(model['entries'][0])
        with self.assertRaises(ValueError): AssociationFeature(model)

    def test_single_scalar_learns_helpful_context_without_changing_priors(self):
        feature = AssociationFeature(example())
        g = dict(context='', texts=['有点类', '有点累'], gold=1,
                 evidence=[dict(words=['有点', '类'], baseline=.1), dict(words=['有点', '累'], baseline=0.)])
        weight, report = fit_scalar([g], feature)
        self.assertTrue(report['converged']); self.assertTrue(0 < weight <= 4)
        scores = AssociationScorer(feature, weight)(g['context'], g['texts'], g['evidence'])
        self.assertGreater(scores[1], scores[0]); self.assertEqual(g['evidence'][0]['baseline'], .1)

    def test_no_signal_leaves_zero_weight_and_scores(self):
        feature = AssociationFeature(example())
        g = dict(context='', texts=['甲乙', '甲丙'], gold=1,
                 evidence=[dict(words=['甲', '乙'], baseline=.1), dict(words=['甲', '丙'], baseline=0.)])
        weight, report = fit_scalar([g], feature)
        self.assertTrue(report['converged']); self.assertEqual(weight, 0.)


if __name__ == '__main__': unittest.main()
