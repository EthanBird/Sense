import unittest
from compare_context_replay import compare
from test_summarize_context_replay import fixture


class ContextVersionComparisonTest(unittest.TestCase):
    def test_unchanged_control_passes(self):
        v=compare(fixture(), fixture())
        self.assertTrue(v['passedAggregateGate'] and v['passedInvariants'])

    def test_short_score_only_change_is_still_an_invariant_failure(self):
        a,b=fixture(),fixture()
        for r in b:
            if r['mode'] in ('editor','accepted'): r['resultSha256']='changed'
        v=compare(a,b)
        self.assertTrue(v['passedAggregateGate'])
        self.assertFalse(v['passedInvariants'])

    def test_long_contextual_rank_losses_are_not_hidden_by_equivalence(self):
        a,b=fixture(),fixture()
        for r in a+b:r['query']='longquery'
        for r in b:
            if r['mode'] in ('editor','accepted'):r.update(rank='2',top1='号',resultSha256='changed')
        v=compare(a,b)
        self.assertFalse(v['passedAggregateGate'])
        self.assertTrue(v['passedInvariants'])
        self.assertEqual(1,len(v['firstLosses']))
        self.assertEqual(1,len(v['rankLosses']))

    def test_reordered_rows_are_compared_by_identity(self):
        self.assertTrue(compare(fixture(), list(reversed(fixture())))['passedInvariants'])

    def test_changed_labels_are_rejected(self):
        a,b=fixture(),fixture()
        for r in b:r['query']='changed'
        with self.assertRaises(ValueError):compare(a,b)
