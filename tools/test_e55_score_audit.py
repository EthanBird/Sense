import itertools
import math
import unittest

from audit_e55_scores import edit_distance, metrics, tokenize
from train_association_model import Segmenter


class IndependentScoreAuditTests(unittest.TestCase):
    def test_independent_tokenizer_matches_exhaustive_small_strings(self):
        original = Segmenter({'甲': 4, '乙': 9, '丙': 1, '甲乙': 99, '乙丙': 0, '甲乙丙': 300})
        for length in range(1, 6):
            for text in map(''.join, itertools.product('甲乙丙', repeat=length)):
                self.assertEqual(tokenize(text, original.scores, original.unknown), original.units(text))

    def test_equal_score_prefers_longest_first(self):
        scores = {'甲': -1., '乙': -1., '甲乙': -2.}
        self.assertEqual(tokenize('甲乙𠀀', scores, -10.), ['甲乙', '𠀀'])

    def test_rank_zero_excluded_from_topk(self):
        result = metrics([(['甲乙'], '甲乙'), (['甲乙'], '乙丙'), ([], '丙甲')])
        self.assertEqual(result['top1'], 1); self.assertEqual(result['top5'], 1)
        self.assertEqual(result['recall255'], 1); self.assertEqual(result['characterErrors'], 4)

    def test_edit_distance_empty_substitution_insertion_and_codepoints(self):
        self.assertEqual(edit_distance('', '甲'), 1)
        self.assertEqual(edit_distance('甲乙', '甲丙'), 1)
        self.assertEqual(edit_distance('甲乙', '甲丙乙'), 1)
        self.assertEqual(edit_distance('𠀀甲', '甲'), 1)


if __name__ == '__main__': unittest.main()
