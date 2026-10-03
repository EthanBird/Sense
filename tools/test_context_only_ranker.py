import copy
import math
import unittest

from context_only_ranker import ContextScorer, SCHEMA, CAP, arrays, contextual_features, feature_vocabulary, fit, objective
from prepare_e44_training import exclusion
from test_candidate_sparse_ranker import group, evidence

try:
    import numpy as np
    import scipy
except ImportError:
    np = None


class ContextContractTest(unittest.TestCase):
    def test_features_contain_genuine_context_not_unigram_proxies(self):
        values = contextual_features("", "你好")
        self.assertEqual(set(values), {"2:你好", "3:^你好"})
        values = contextual_features("今天", "你好")
        self.assertEqual(set(values), {"2:天你", "3:今天你", "2:你好", "3:天你好"})

    def test_supplementary_han_and_repeated_counts(self):
        values = contextual_features("", "𠀀哈哈")
        self.assertIn("3:𠀀哈哈", values)
        self.assertIn("2:哈哈", values)

    def test_source_family_threshold(self):
        rows = [group("a"), group("b")]
        self.assertEqual(feature_vocabulary(rows, {"a":"f", "b":"f"}), [])
        self.assertIn("2:你好", feature_vocabulary(rows, {"a":"f", "b":"g"}))

    def test_schema_rejects_unigram_bias_or_extra_score_weights(self):
        for keys, weights in [(["1:你"],[1]), (["3:^^你"],[1]), (["2:^你"],[1]),
                              (["2:你好"],[1,2]), (["score:LM"],[1])]:
            with self.assertRaises(ValueError):
                ContextScorer(dict(schema=SCHEMA,cap=CAP,vocabulary=keys,weights=weights))

    def test_original_components_do_not_affect_delta(self):
        scorer = ContextScorer(dict(schema=SCHEMA,cap=CAP,vocabulary=["2:你好"],weights=[.5]))
        a = [evidence(), evidence()]; b = copy.deepcopy(a)
        for e in b:
            e["features"] = [10000] * len(e["features"])
        self.assertEqual(scorer("",["你好","你号"],a), scorer("",["你好","你号"],b))

    def test_zero_unknown_and_stable_baseline(self):
        scorer = ContextScorer(dict(schema=SCHEMA,cap=CAP,vocabulary=[],weights=[]))
        self.assertEqual(scorer("",["你好","你号"],[evidence(2.5),evidence(-3.25)]),[2.5,-3.25])

    def test_residual_is_bounded_and_bad_baseline_rejected(self):
        scorer = ContextScorer(dict(schema=SCHEMA,cap=CAP,vocabulary=["2:你好"],weights=[1e8]))
        self.assertEqual(scorer("",["你好","你号"],[evidence(),evidence()]),[8.,0.])
        with self.assertRaises(ValueError):
            scorer("",["你好","你号"],[evidence(float("inf")),evidence()])

    def test_overlap_is_symmetric_and_one_edit_sensitive(self):
        self.assertEqual(exclusion("你好",["今天你好"]),"substring")
        self.assertEqual(exclusion("今天你好",["你好"]),"contains-heldout")
        self.assertEqual(exclusion("我爱北京",["我在北京"]),"near")
        self.assertIsNone(exclusion("今天晴天",["明天下雨"]))


@unittest.skipIf(np is None,"NumPy/SciPy required")
class ContextOptimizerTest(unittest.TestCase):
    def setUp(self):
        self.groups=[group("a"),group("b")]; self.families={"a":"f","b":"g"}

    def test_gradient_matches_finite_difference(self):
        keys=feature_vocabulary(self.groups,self.families); data=arrays(self.groups,keys)
        w=np.linspace(-.1,.2,len(keys)); _,grad=objective(w,*data)
        for i in range(len(w)):
            a=w.copy();b=w.copy();a[i]+=1e-6;b[i]-=1e-6
            self.assertAlmostEqual(grad[i],(objective(a,*data)[0]-objective(b,*data)[0])/2e-6,places=7)

    def test_deterministic_fit_improves_fixed_toy_loss(self):
        a, report=fit(self.groups,self.families); b,_=fit(self.groups,self.families)
        self.assertEqual(a,b);self.assertLess(report["finalLoss"],report["initialLoss"])
        self.assertEqual(len(a["weights"]),len(a["vocabulary"]))

    def test_no_context_features_and_bad_alignment_rejected(self):
        with self.assertRaises(ValueError):
            fit([group("a")], {"a":"f"})
        bad=group();bad["gold"]=3
        with self.assertRaises(ValueError):
            arrays([bad],[])


if __name__ == "__main__":
    unittest.main()
