import unittest
from summarize_context_replay import summarize, MODES


def fixture():
    return [dict(id='a', cut='1', stratum='short', mode=m, context='我', query='hao',
                 expected='好', rank='1', top1='好', resultSha256='same') for m in MODES]


class ContextReplayTest(unittest.TestCase):
    def test_equivalence_is_not_accuracy(self):
        rows = fixture()
        for r in rows:
            r.update(rank='0', top1='号')
        value = summarize(rows)
        self.assertTrue(value['passedContextEquivalence'])
        self.assertEqual(0, value['metrics']['editor']['first'])

    def test_records_regression_even_when_recall_remains(self):
        rows = fixture()
        for r in rows:
            if r['mode'] in ('editor', 'accepted'):
                r.update(rank='2', top1='号', resultSha256='new')
        value = summarize(rows)
        self.assertEqual(1, len(value['contextFirstLosses']))
        self.assertEqual(1, len(value['contextRankLosses']))
        self.assertTrue(value['passedContextEquivalence'])

    def test_each_equivalence_failure_is_reported_separately(self):
        rows = fixture()
        rows[2]['resultSha256'] = 'boundary-failure'
        rows[3]['resultSha256'] = 'accepted-failure'
        value = summarize(rows)
        self.assertFalse(value['passedContextEquivalence'])
        self.assertEqual(1, len(value['acceptedContextMismatches']))
        self.assertEqual(1, len(value['punctuationBoundaryMismatches']))

    def test_changed_labels_missing_and_duplicate_modes_fail(self):
        changed = fixture()
        changed[1]['query'] = 'new'
        for rows in ([], fixture()[:-1], fixture() + fixture()[:1], changed):
            with self.assertRaises(ValueError): summarize(rows)

    def test_inconsistent_rank_fails_instead_of_counting_success(self):
        rows = fixture()
        rows[1]['rank'] = '3'
        with self.assertRaises(ValueError): summarize(rows)
