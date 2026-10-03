import unittest
from prepare_weak_ranker_train import valid_readings


class WeakRankerTrainTest(unittest.TestCase):
    def test_annotation_checks_alignment_inventory_and_input_budget(self):
        self.assertIsNone(valid_readings('你好', ['ni', 'hao'], {'ni', 'hao'}))
        self.assertEqual('alignment', valid_readings('你好', ['nihao'], {'nihao'}))
        self.assertEqual('unknown-syllable', valid_readings('你𠀀', ['ni', '𠀀'], {'ni'}))
        self.assertEqual('query-length', valid_readings('长' * 20, ['chang'] * 20, {'chang'}))
        self.assertIsNone(valid_readings('女', ['nv'], {'nv'}))


if __name__ == '__main__':
    unittest.main()
