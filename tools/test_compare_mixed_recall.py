import unittest
from compare_mixed_recall import compare


def row(rank=1, fingerprint='a', expected='词'):
    return dict(id='1', stratum='fixture', mode='full', query='ci', expected=expected,
                rank=str(rank), top1='词', resultSha256=fingerprint)


class MixedRecallComparisonTest(unittest.TestCase):
    def test_prefix_only_changes_are_retained_without_faking_rank_changes(self):
        value = compare([row()], [row(fingerprint='b')])
        self.assertEqual(1, len(value['changedCompleteResults']))
        self.assertFalse(value['rankLosses'])
        self.assertTrue(value['passedNoFirstOrRecallLoss'])

    def test_missing_and_demoted_targets_are_distinct(self):
        lost = compare([row()], [row(0)])
        self.assertEqual(1, len(lost['targetRecallLosses']))
        self.assertFalse(lost['passedNoFirstOrRecallLoss'])
        demoted = compare([row(4)], [row(5)])
        self.assertEqual(1, len(demoted['rankLosses']))
        self.assertFalse(demoted['targetRecallLosses'])

    def test_changed_labels_cannot_be_compared(self):
        with self.assertRaises(AssertionError):
            compare([row()], [row(expected='另一词')])
