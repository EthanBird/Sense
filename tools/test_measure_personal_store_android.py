import unittest
from measure_personal_store_android import DATASETS, compare_datasets


class PersonalStoreComparisonTest(unittest.TestCase):
    def datasets(self):
        def rows(value):
            return [dict.fromkeys(['readWallNs', 'readCpuNs', 'restoreWallNs', 'restoreCpuNs'], value) for _ in range(10)]
        return {f'{size}-shared-{shared}': dict(fingerprints={'a'*64}, baseline=rows(100_000_000),
                                             candidate=rows(80_000_000)) for size, shared, _ in DATASETS}

    def test_equal_state_and_faster_restoration_pass(self):
        results, passed = compare_datasets(self.datasets())
        self.assertTrue(passed)
        self.assertEqual(.8, results['10000-shared-True']['medianWallRatio'])
        self.assertEqual(80, results['10000-shared-True']['timings']['candidate']['restoreWallNs']['medianMs'])

    def test_p95_and_state_regressions_fail_even_when_median_is_faster(self):
        for state in [False, True]:
            data = self.datasets()
            if state: data['1000-shared-True']['fingerprints'].add('b'*64)
            else: data['1000-shared-True']['candidate'][-1]['restoreWallNs'] = 110_000_000
            self.assertFalse(compare_datasets(data)[1])

    def test_insufficient_large_bucket_improvement_fails(self):
        data = self.datasets()
        for row in data['10000-shared-True']['candidate']: row['restoreWallNs'] = 90_000_000
        self.assertFalse(compare_datasets(data)[1])

    def test_missing_dataset_or_observation_fails(self):
        data = self.datasets(); data.pop('1000-shared-True')
        with self.assertRaises(ValueError): compare_datasets(data)
        data = self.datasets(); data['1000-shared-True']['candidate'].pop()
        with self.assertRaises(ValueError): compare_datasets(data)


if __name__ == '__main__': unittest.main()
