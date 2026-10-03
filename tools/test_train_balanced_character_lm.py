import unittest
from train_balanced_character_lm import select_families


class BalancedSelectionTest(unittest.TestCase):
    def test_whole_family_stable_under_permutation(self):
        rows = [dict(id=str(i), group=str(i//2), segments=['中国你好']) for i in range(10)]
        selected = select_families(rows, 10, 'seed')
        self.assertEqual(selected, select_families(list(reversed(rows)), 10, 'seed'))
        self.assertEqual(len(selected), 4)
        self.assertEqual(len({r['group'] for r in selected}), 2)
        self.assertEqual(len({r['id'] for r in selected}), 4)

    def test_budget_and_exhaustion(self):
        row = dict(id='a', group='a', segments=['中国', '人民'])
        self.assertEqual(select_families([row], 4, 'seed'), [row])
        with self.assertRaises(ValueError): select_families([row], 5, 'seed')
        with self.assertRaises(ValueError): select_families([row], 0, 'seed')


if __name__ == '__main__': unittest.main()
