import copy
import math
import unittest

from aligned_word_context import (FrozenSegmenter, compact_segmenter, AlignedAssociationFeature,
    personal_evidence, select_guarded_inputs, reorder_guarded)
from candidate_student import reorder_student
from full_word_context import Counts, AssociationScorer
from train_association_model import Segmenter
from word_context_ranker import pair_key


def table():
    counts = Counts()
    for family in ['a', 'b']:
        counts.add(family, '他们的电脑', ['他们的', '电脑'])
        counts.add(family, '他们的电表', ['他们的', '电表'])
        counts.add(family, '有点累', ['有点', '累'])
    return counts.export()


def tokenizer():
    return FrozenSegmenter(dict(schema='fixed-word-tokenizer-v1', maxWordLength=8, unknown=-30.,
        entries=sorted([['他们的', -2.], ['电脑', -2.], ['电表', -2.], ['有点', -2.], ['累', -2.]])))


class AlignedWordContextTests(unittest.TestCase):
    def test_compact_preserves_training_paths_and_original_scores(self):
        original = Segmenter({'他们的': 100, '他们': 90, '的': 80, '电脑': 100, '电': 10, '脑': 10, '别的词': 3})
        rows = [dict(text=t, words=original.units(t)) for t in ['他们的电脑', '他们的𠀀']]
        model, report = compact_segmenter(original, rows)
        compact = FrozenSegmenter(model)
        self.assertEqual(compact.unknown, original.unknown)
        self.assertEqual(report['verifiedPaths'], 2)
        self.assertNotIn('别的词', compact.scores)
        for row in rows:
            self.assertEqual(compact.units(row['text']), row['words'])
        for word, score in compact.scores.items():
            self.assertEqual(score, original.scores.get(word, original.unknown))

    def test_inconsistent_background_path_rejected(self):
        with self.assertRaises(ValueError):
            compact_segmenter(Segmenter({'他们的': 100}), [dict(text='他们的', words=['他们', '的'])])

    def test_longest_first_tie_and_supplementary_fallback(self):
        seg = FrozenSegmenter(dict(schema='fixed-word-tokenizer-v1', maxWordLength=8, unknown=-9.,
            entries=sorted([['甲', -1.], ['甲乙', -2.], ['乙', -1.]])))
        self.assertEqual(seg.units('甲乙𠀀'), ['甲乙', '𠀀'])

    def test_model_validation(self):
        good = tokenizer().model()
        for key, bad in [('unknown', float('nan')), ('unknown', 0.), ('maxWordLength', 9), ('schema', 'other')]:
            model = copy.deepcopy(good); model[key] = bad
            with self.assertRaises(ValueError): FrozenSegmenter(model)
        for entries in [[['a', -1.]], [['甲', 1.]], [['甲', -1.], ['甲', -2.]], []]:
            model = copy.deepcopy(good); model['entries'] = entries
            with self.assertRaises(ValueError): FrozenSegmenter(model)

    def test_aligned_feature_does_not_use_decoder_word_boundaries(self):
        feature = AlignedAssociationFeature(table(), tokenizer(), aligned=True, signed=True)
        a = feature('', '他们的电脑', dict(words=['他们', '的', '电', '脑']))
        b = feature('', '他们的电脑', dict(words=['他们的', '电脑']))
        self.assertEqual(a, b)
        self.assertGreater(a, 0.)
        path = AlignedAssociationFeature(table(), tokenizer(), aligned=False, signed=True)
        self.assertNotEqual(path('', '他们的电脑', dict(words=['他们', '的', '电', '脑'])), a)

    def test_signed_retained_probability_and_unseen_backoff(self):
        model = table(); f = AlignedAssociationFeature(model, tokenizer(), aligned=False, signed=True)
        row = next(r for r in model['entries'] if r[0] == pair_key('W2', '有点', '累'))
        expected = max(-4., min(4., math.log(((row[1]-.75)/row[2] + row[5]*row[4])/row[4])))
        self.assertAlmostEqual(f.pair_score('W2', '有点', '累'), expected)
        self.assertAlmostEqual(f.pair_score('W2', '有点', '类'), max(-4., math.log(row[5])))
        self.assertLess(f.pair_score('W2', '有点', '类'), 0.)
        self.assertEqual(f.pair_score('W2', '不存在', '类'), 0.)

    def test_stored_negative_lift_is_not_lost(self):
        counts = Counts()
        for family in ['a', 'b']:
            counts.add(family, '甲乙', ['甲', '乙'])
            for _ in range(20): counts.add(family, '甲丙', ['甲', '丙'])
            for _ in range(100): counts.add(family, '丁乙', ['丁', '乙'])
        model = counts.export()
        neg = AlignedAssociationFeature(model, tokenizer(), aligned=False, signed=True)
        pos = AlignedAssociationFeature(model, tokenizer(), aligned=False, signed=False)
        self.assertLess(neg.pair_score('W2', '甲', '乙'), 0.)
        self.assertEqual(pos.pair_score('W2', '甲', '乙'), 0.)

    def test_positive_path_exactly_matches_e54(self):
        from full_word_context import AssociationFeature
        old = AssociationFeature(table())
        control = AlignedAssociationFeature(table(), tokenizer(), aligned=False, signed=False)
        for context in ['', '的', '们的', '未知']:
            for text, words in [('他们的电脑', ['他们的', '电脑']), ('有点类', ['有点', '类'])]:
                self.assertEqual(old(context, text, dict(words=words)), control(context, text, dict(words=words)))

    def test_zero_weight_identity_signed_cap_and_no_mutation(self):
        feature = AlignedAssociationFeature(table(), tokenizer(), aligned=True, signed=True)
        ev = [dict(baseline=2., words=['有点', '类']), dict(baseline=1., words=['有点', '累'])]
        previous = copy.deepcopy(ev)
        self.assertEqual(AssociationScorer(feature, 0.)('', ['有点类', '有点累'], ev), [2., 1.])
        scores = AssociationScorer(feature, 4.)('', ['有点类', '有点累'], ev)
        self.assertTrue(all(abs(s-e['baseline']) <= 8. for s, e in zip(scores, ev)))
        self.assertEqual(ev, previous)

    def test_context_bounds(self):
        f = AlignedAssociationFeature(table(), tokenizer(), aligned=True, signed=True)
        for context, text in [('三个字', '有点累'), ('。', '有点累'), ('', '累'), ('', 'hello')]:
            with self.assertRaises(ValueError): f(context, text, {})


class PersonalGuardTests(unittest.TestCase):
    def setUp(self):
        self.candidates = ['有点类', '有点累']
        self.evidence = {t: dict(kind='BASE_COMPOSED', baseline=2.-i,
            features=[0.]*17, words=['有点', t[-1]], personalEvidence=False) for i, t in enumerate(self.candidates)}
        self.calls = 0

    def scorer(self, *args):
        self.calls += 1
        return [0., 9.]

    def test_reproduce_old_guard_gap_on_composed_personal_adjustment(self):
        self.evidence['有点类']['features'][11] = 1.
        old, _ = reorder_student('', self.candidates, self.evidence, self.scorer)
        self.assertNotEqual(old, self.candidates)
        self.calls = 0
        new, meta = reorder_guarded('', self.candidates, self.evidence, self.scorer)
        self.assertEqual(new, self.candidates)
        self.assertEqual(meta['reason'], 'personalized-path')
        self.assertEqual(self.calls, 0)

    def test_positive_negative_and_cross_component_cancellation_protected(self):
        for index in [9, 10, 11]:
            for value in [1., -1.]:
                with self.subTest(index=index, value=value):
                    e = copy.deepcopy(self.evidence); e['有点类']['features'][index] = value
                    self.assertEqual(select_guarded_inputs(self.candidates, e)[2], 'personalized-path')
        e = copy.deepcopy(self.evidence); e['有点类']['features'][9:12] = [1., 2., -3.]
        self.assertEqual(select_guarded_inputs(self.candidates, e)[2], 'personalized-path')

    def test_null_weight_edge_protected_even_when_vector_zero(self):
        raw = dict(features=[0.]*16, edges=[['程彻', 'chengche', None, None]])
        self.assertTrue(personal_evidence(raw))
        self.evidence['有点类']['personalEvidence'] = personal_evidence(raw)
        result, _ = reorder_guarded('', self.candidates, self.evidence, self.scorer)
        self.assertEqual(result, self.candidates); self.assertEqual(self.calls, 0)

    def test_missing_provenance_preserves_order(self):
        del self.evidence['有点类']['personalEvidence']
        result, meta = reorder_guarded('', self.candidates, self.evidence, self.scorer)
        self.assertEqual(result, self.candidates); self.assertEqual(self.calls, 0)
        self.assertEqual(meta['reason'], 'missing-personal-provenance')

    def test_normal_and_source_protection(self):
        result, meta = reorder_guarded('', self.candidates, self.evidence, self.scorer)
        self.assertEqual(result, list(reversed(self.candidates))); self.assertEqual(meta['reason'], 'scored')
        self.calls = 0; self.evidence['有点类']['kind'] = 'USER_FULL'
        result, meta = reorder_guarded('', self.candidates, self.evidence, self.scorer)
        self.assertEqual(result, self.candidates); self.assertEqual(self.calls, 0)
        self.assertEqual(meta['reason'], 'protected-source')


if __name__ == '__main__': unittest.main()
