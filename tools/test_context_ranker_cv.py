import copy
import unittest

from context_ranker_cv import family_fold,split_groups,restored_order,select_ridge,fit_regularized
from context_only_ranker import feature_vocabulary
from test_candidate_sparse_ranker import group


class CalibrationContracts(unittest.TestCase):
    def fixture(self):
        rows=[];families={}
        for i in range(24):
            families[str(i)]='family'+str(i//2)
            g=group(str(i));rows.append(g);rows.append({**g,'cut':1})
        return rows,families

    def test_family_and_all_states_stay_in_one_fold(self):
        rows,families=self.fixture();seen=set()
        for f in range(3):
            a,b=split_groups(rows,families,'fixed',f)
            self.assertFalse({families[g['id']] for g in a}&{families[g['id']] for g in b})
            keys={(g['id'],g['cut']) for g in b};self.assertFalse(seen&keys);seen|=keys
        self.assertEqual(len(seen),len(rows))

    def test_assignment_deterministic_and_bad_folds_rejected(self):
        self.assertEqual(family_fold('f','seed'),family_fold('f','seed'))
        for args in [('', 's',3),('f','',3),('f','s',1)]:
            with self.assertRaises(ValueError):family_fold(*args)

    def test_validation_only_features_do_not_enter_fit_vocabulary(self):
        fitting=[group('a'),group('b')];validation=[group('c',texts=('天气','天汽')),group('d',texts=('天气','天汽'))]
        families={x['id']:x['id'] for x in fitting+validation}
        self.assertIn('2:天气',feature_vocabulary(fitting+validation,families))
        self.assertNotIn('2:天气',feature_vocabulary(fitting,families))

    def test_original_slots_preserved_for_unequal_length_candidates(self):
        g=dict(texts=['你好','你号','拟好'],slots=[0,3,5],originalTop8=['你好','更多文字','字','你号','占位符','拟好'])
        self.assertEqual(restored_order(g,[1,2,3]),['拟好','更多文字','字','你号','占位符','你好'])
        self.assertEqual(restored_order(g,[0,0,0]),g['originalTop8'])
        g['slots']=[0,0,5]
        with self.assertRaises(ValueError):restored_order(g,[1,2,3])

    def report(self,ridge=.01,top1=8,errors=2,top5=10,converged=True):
        return dict(ridge=ridge,allConverged=converged,metrics=dict(
            before=dict(states=10,top1=7,top5=10,characterErrors=3,characters=20),
            after=dict(states=10,top1=top1,top5=top5,characterErrors=errors,characters=20)))

    def test_strict_improvement_and_convergence_required(self):
        self.assertIsNone(select_ridge([self.report(top1=7),self.report(converged=False),self.report(errors=4),self.report(top5=9)]))

    def test_selection_ties_prefer_stronger_regularization(self):
        self.assertEqual(select_ridge([self.report(.001),self.report(.01)]),.01)
        self.assertEqual(select_ridge([self.report(.01,top1=8),self.report(.001,top1=9)]),.001)

    def test_unpaired_metrics_rejected(self):
        a=self.report();b=copy.deepcopy(a);b['metrics']['before']['states']=11
        with self.assertRaises(ValueError):select_ridge([a,b])

    def test_ridge_fit_uses_requested_strength(self):
        try:import scipy
        except ImportError:self.skipTest('SciPy required')
        rows=[group('a'),group('b')];families={'a':'a','b':'b'}
        a,ar=fit_regularized(rows,families,.01);b,br=fit_regularized(rows,families,.001)
        self.assertEqual(ar['ridge'],.01);self.assertEqual(br['ridge'],.001)
        self.assertGreater(sum(abs(w) for w in b['weights']),sum(abs(w) for w in a['weights']))


if __name__=='__main__':unittest.main()
