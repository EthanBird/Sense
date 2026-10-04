import math
import unittest

from context_only_ranker import CAP, contextual_features, objective
from word_context_ranker import (SCHEMA, WordContextScorer, arrays, features, fit,
                                 pair_key, valid_word_key, vocabulary)


def item(*words, baseline=1.25):
    return dict(words=list(words), baseline=baseline)


def model(keys=(), weights=(), include=True):
    return dict(schema=SCHEMA, cap=CAP, includeWords=include,
                vocabulary=list(keys), weights=list(weights))


class WordContextTests(unittest.TestCase):
    def test_actual_word_boundaries_not_gold_segmentation(self):
        a = features("", "今天有一点累", item("今天", "有一点", "累"))
        b = features("", "今天有一点累", item("今天", "有", "一点", "累"))
        self.assertIn(pair_key("W2", "有一点", "累"), a)
        self.assertNotIn(pair_key("W2", "有一点", "累"), b)
        self.assertEqual({k: v for k, v in a.items() if not k.startswith("[")},
                         {k: v for k, v in b.items() if not k.startswith("[")})

    def test_external_suffix_has_distinct_namespace(self):
        x = features("有点", "累了", item("累", "了"))
        self.assertIn(pair_key("CW", "有点", "累"), x)
        self.assertIn(pair_key("W2", "累", "了"), x)
        self.assertNotIn(pair_key("W2", "有点", "累"), x)
        self.assertFalse(any("EOS" in k for k in x))

    def test_single_word_empty_editor_adds_no_word_unigram_or_intercept(self):
        self.assertEqual(features("", "输入法", item("输入法")), contextual_features("", "输入法"))

    def test_repeated_pair_counts_and_supplementary_han(self):
        key = pair_key("W2", "𠀀", "好")
        self.assertEqual(features("", "𠀀好𠀀好", item("𠀀", "好", "𠀀", "好"))[key], 1.0)

    def test_reject_inconsistent_or_missing_actual_path(self):
        for value in ({}, item("你好", "啊"), item("你", ""), item("你", "x")):
            with self.assertRaises(ValueError):
                features("", "你好", value)

    def test_reject_unbounded_context_or_candidate(self):
        for context, text in (("今天有", "你好"), ("。", "你好"), ("", "好"), ("", "好" * 25)):
            with self.assertRaises(ValueError):
                features(context, text, item(text))

    def test_control_features_match_existing_context_model(self):
        self.assertEqual(features("输入", "体验很好", item("体验", "很好"), False),
                         contextual_features("输入", "体验很好"))

    def test_same_family_repetitions_do_not_establish_vocabulary(self):
        group = dict(id="one", context="", texts=["甲乙", "甲丙"],
                     evidence=[item("甲", "乙"), item("甲", "丙")], gold=0)
        self.assertEqual(vocabulary([group, {**group, "id": "two"}], {"one": "a", "two": "a"}), [])
        keys = vocabulary([group, {**group, "id": "two"}], {"one": "a", "two": "b"})
        self.assertIn(pair_key("W2", "甲", "乙"), keys)

    def test_word_key_canonical_encoding_and_limits(self):
        self.assertTrue(valid_word_key(pair_key("W2", "甲", "乙")))
        for key in ('["W2", "甲", "乙"]', pair_key("U1", "甲", "乙"),
                    pair_key("CW", "甲乙丙", "丁"), pair_key("W2", "", "乙"),
                    pair_key("W2", "^", "乙"), '["W2",1,"乙"]', 'null'):
            self.assertFalse(valid_word_key(key))

    def test_zero_and_unknown_features_preserve_exact_baselines(self):
        key = pair_key("W2", "甲", "乙")
        e = [item("甲", "乙", baseline=17.943204879760742)]
        for value in (model(), model([key], [0.0]), model([pair_key("W2", "丙", "丁")], [9.0])):
            self.assertEqual(WordContextScorer(value)("", ["甲乙"], e), [e[0]["baseline"]])

    def test_residual_bounded_and_not_reference_dependent(self):
        key = pair_key("W2", "甲", "乙")
        scorer = WordContextScorer(model([key], [1e6]))
        e = item("甲", "乙"); a = scorer("", ["甲乙"], [e])
        b = scorer("", ["甲乙"], [{**e, "expected": "丙丁", "gold": 900}])
        self.assertEqual(a, b)
        self.assertLessEqual(abs(a[0] - e['baseline']), CAP)

    def test_reject_model_errors_and_nonfinite_scores(self):
        key = pair_key("W2", "甲", "乙")
        for value in (model([key], [float('nan')]), model([key], [1], include=False),
                      model([key, key], [1, 1]), model([key], [])):
            with self.assertRaises(ValueError): WordContextScorer(value)
        with self.assertRaises(ValueError):
            WordContextScorer(model())("", ["甲乙"], [item("甲乙", baseline=float('inf'))])

    def test_arrays_match_inference_and_numerical_gradient(self):
        import numpy as np
        group = dict(id="a", context="今天", texts=["有点累", "有点类"],
                     evidence=[item("有点", "累"), item("有点", "类")], gold=0)
        keys = sorted(set().union(*(features(group['context'], t, e) for t, e in zip(group['texts'], group['evidence']))))
        data = arrays([group], keys); weights = np.linspace(-.1, .1, len(keys))
        expected = data[1] + CAP * np.tanh(data[0] @ weights / CAP)
        actual = WordContextScorer(model(keys, weights))(group['context'], group['texts'], group['evidence'])
        np.testing.assert_allclose(actual, expected, atol=1e-14)
        _, grad = objective(weights, *data, .001)
        for i in range(len(keys)):
            plus = weights.copy(); minus = weights.copy(); plus[i] += 1e-5; minus[i] -= 1e-5
            numeric = (objective(plus, *data, .001)[0] - objective(minus, *data, .001)[0]) / 2e-5
            self.assertAlmostEqual(grad[i], numeric, places=7)

    def test_fit_recovers_controlled_cross_family_pair(self):
        # Equal text-length paths; lexical context can overturn a small wrong prior.
        g = dict(id="one", context="", texts=["甲乙", "甲丙"],
                 evidence=[item("甲", "乙", baseline=.1), item("甲", "丙", baseline=0.)], gold=1)
        learned, report = fit([g, {**g, "id": "two"}], {"one": "a", "two": "b"}, True)
        self.assertTrue(report['converged'])
        scores = WordContextScorer(learned)("", g['texts'], g['evidence'])
        self.assertGreater(scores[1], scores[0])


if __name__ == "__main__":
    unittest.main()
