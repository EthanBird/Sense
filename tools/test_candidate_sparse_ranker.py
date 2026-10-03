import copy
import math
import unittest

from candidate_sparse_ranker import (CAP, SCHEMA, SparseScorer, character_features,
    feature_vector, objective, train, training_arrays, vocabulary)
from candidate_student import FEATURE_COUNT, reorder_student

try:
    import numpy as np
    import scipy
except ImportError:
    np = None


def evidence(score=0.0, kind="BASE_COMPOSED"):
    return dict(baseline=score, features=[0.0] * FEATURE_COUNT, kind=kind)


def group(identity="a", context="", texts=("你好", "你号")):
    return dict(id=identity, cut=0, context=context, texts=list(texts),
                evidence=[evidence() for _ in texts], gold=0, teacherScores=[0.0, -2.0])


class SparseFeatureTest(unittest.TestCase):
    def test_only_candidate_endpoints_no_eos(self):
        x = character_features("今天", "你好")
        self.assertIn("3:今天你", x)
        self.assertIn("3:天你好", x)
        self.assertNotIn("2:今天", x)
        self.assertNotIn("1:今", x)
        self.assertTrue(all("$" not in k for k in x))
        self.assertEqual(len(x), 6)

    def test_bos_and_supplementary_codepoints(self):
        x = character_features("", "𠀀好")
        self.assertIn("3:^^𠀀", x)
        self.assertIn("2:𠀀好", x)
        self.assertAlmostEqual(x["1:𠀀"], 1 / math.sqrt(2))

    def test_repeated_ngram_counts_and_bounds(self):
        self.assertAlmostEqual(character_features("", "哈哈")["1:哈"], math.sqrt(2))
        for context, text in [("前三字", "你好"), ("a", "你好"), ("", "你"),
                              ("", "你好!"), ("", "好" * 25)]:
            with self.assertRaises(ValueError):
                character_features(context, text)

    def test_distinct_family_not_state_counts(self):
        a = group(); b = group("b"); b["cut"] = 1
        self.assertEqual(vocabulary([a, b], {"a": "same", "b": "same"}), [])
        self.assertIn("2:你好", vocabulary([a, b], {"a": "one", "b": "two"}))
        with self.assertRaises(KeyError):
            vocabulary([a], {})

    def test_zero_model_and_unknown_features_exact_baseline(self):
        model = dict(schema=SCHEMA, cap=CAP, vocabulary=[], weights=[0.0] * FEATURE_COUNT)
        self.assertEqual(SparseScorer(model)("", ["你好", "你号"], [evidence(1.25), evidence(-2.75)]), [1.25, -2.75])

    def test_bounded_delta_and_known_weights(self):
        model = dict(schema=SCHEMA, cap=CAP, vocabulary=["1:好"], weights=[1e9] + [0.0] * FEATURE_COUNT)
        self.assertEqual(SparseScorer(model)("", ["你好", "你号"], [evidence(), evidence()]), [8.0, 0.0])

    def test_serialize_and_reject_corrupt_model(self):
        import json
        model = dict(schema=SCHEMA, cap=CAP, vocabulary=["1:好"], weights=[1.0] + [0.0] * FEATURE_COUNT)
        expected = SparseScorer(model)("", ["你好", "你号"], [evidence(), evidence()])
        self.assertEqual(SparseScorer(json.loads(json.dumps(model)))("", ["你好", "你号"], [evidence(), evidence()]), expected)
        for key, value in [("cap", 2), ("weights", [float("nan")] * (FEATURE_COUNT + 1)), ("vocabulary", ["1:好", "1:好"])]:
            changed = copy.deepcopy(model); changed[key] = value
            with self.assertRaises(ValueError):
                SparseScorer(changed)

    def test_actual_feature_validation(self):
        x = evidence(); x["features"][0] = 256
        self.assertEqual(feature_vector("", "你好", x, {})[0], 4)
        x["features"][0] = float("nan")
        with self.assertRaises(ValueError):
            feature_vector("", "你好", x, {})

    def test_personal_and_correction_whole_row_protection(self):
        for kind in ["USER_FULL", "USER_INITIALS", "CORRECTED"]:
            def fail(*args):
                raise AssertionError("Protected row reached scoring")
            original = ["你好", "你号"]
            actual, meta = reorder_student("", original, {"你好": evidence(), "你号": evidence(kind=kind)}, fail)
            self.assertEqual(actual, original)
            self.assertEqual(meta["reason"], "protected-source")


@unittest.skipIf(np is None, "NumPy/SciPy required for optimizer contract")
class SparseTrainingTest(unittest.TestCase):
    def setUp(self):
        self.groups = [group("a"), group("b", "今天")]
        self.families = {"a": "family1", "b": "family2"}

    def test_analytic_gradient_matches_central_difference(self):
        keys = vocabulary(self.groups, self.families)
        arrays = training_arrays(self.groups, keys)
        w = np.linspace(-.1, .2, len(keys) + FEATURE_COUNT)
        _, actual = objective(w, *arrays)
        for i in range(len(w)):
            plus, minus = w.copy(), w.copy(); plus[i] += 1e-6; minus[i] -= 1e-6
            numeric = (objective(plus, *arrays)[0] - objective(minus, *arrays)[0]) / 2e-6
            self.assertAlmostEqual(actual[i], numeric, places=7)

    def test_extreme_teacher_softmax_is_finite(self):
        groups = copy.deepcopy(self.groups)
        for g in groups:
            g["teacherScores"] = [0.0, -10000.0]
        keys = vocabulary(groups, self.families)
        arrays = training_arrays(groups, keys)
        loss, grad = objective(np.zeros(len(keys) + FEATURE_COUNT), *arrays)
        self.assertTrue(math.isfinite(loss) and np.isfinite(grad).all())

    def test_fit_reduces_loss_and_is_deterministic(self):
        a, report = train(self.groups, self.families, max_iterations=50)
        b, _ = train(self.groups, self.families, max_iterations=50)
        self.assertEqual(a, b)
        self.assertLess(report["finalLoss"], report["initialLoss"])
        scores = SparseScorer(a)("", ["你好", "你号"], [evidence(), evidence()])
        self.assertGreater(scores[0], scores[1])

    def test_bad_training_alignment_rejected(self):
        broken = group(); broken["gold"] = 5
        with self.assertRaises(ValueError):
            training_arrays([broken], [])
        with self.assertRaises(ValueError):
            training_arrays([], [])


if __name__ == "__main__":
    unittest.main()
