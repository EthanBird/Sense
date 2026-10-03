import copy
import tempfile
import unittest
from pathlib import Path

from summarize_burst_input import summarize_burst, describe_incomplete_attempt
import test_summarize_input_latency as legacy_fixture


class BurstInputSummaryTest(unittest.TestCase):
    def fixture(self, directory):
        report = legacy_fixture.InputLatencySummaryTest().fixture(directory)
        report['protocol'] = 'async-touch-no-animation-wait-v1'
        for run in report['runs']:
            folder = directory / run['artifactDirectory'] / 'device'
            folder.mkdir()
            text = 'round\tquery\ttargetIntervalMs\tactualStartIntervalsMs\tspaceInjectionStartMs\tfirstExpectedTextMs\n'
            for row in run['samples']:
                text += f"{row['round']}\t{row['query']}\t32\t" + ','.join(['32'] * (len(row['query']) - 1))
                text += f"\t1000\t{1000 + row['spaceToEditorMs']}\n"
            (folder / 'burst-cadence.tsv').write_text(text, encoding='utf-8')
        return report

    def test_reports_observed_cadence_without_promoting_production(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); report = summarize_burst(self.fixture(root), root)
            self.assertEqual(32, report['actualKeyIntervalsMs']['optimized']['medianMs'])
            self.assertEqual(160, report['actualKeyIntervalsMs']['optimized']['count'])
            self.assertIn('unchanged', report['promotionDecision'])

    def test_old_or_failed_protocol_does_not_count_as_burst_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); data = self.fixture(root)
            old = copy.deepcopy(data); old.pop('protocol')
            with self.assertRaises(ValueError): summarize_burst(old, root)
            data['runs'][0]['passed'] = False
            with self.assertRaises(ValueError): summarize_burst(data, root)

    def test_missing_duplicate_incomplete_and_different_timestamps_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); data = self.fixture(root)
            path = root / 'block1/device/burst-cadence.tsv'
            original = path.read_text(); lines = original.splitlines(keepends=True)
            for changed in [''.join(lines[:-1]), original + lines[-1],
                            original.replace('32,32,32,32,32', '32,32', 1),
                            original.replace('\t1000\t1100', '\t1000\t1101', 1)]:
                path.write_text(changed)
                with self.assertRaises(ValueError): summarize_burst(data, root)

    def test_failed_setup_retains_successes_but_prohibits_pooled_comparison(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); data = self.fixture(root)
            data['runs'][3]['passed'] = False; data['runs'][3]['samples'] = []
            (root/'block4/instrumentation.log').write_text('INSTRUMENTATION_STATUS: stack=runtime ready timeout\n')
            report = describe_incomplete_attempt(data, root)
            self.assertFalse(report['completePairedComparison'])
            self.assertIsNone(report['confirmationMs'])
            self.assertEqual([16,16,16,0], [b['recordedConfirmations'] for b in report['blocks']])
            self.assertIn('timeout', report['blocks'][3]['failure'])
            with self.assertRaises(ValueError): summarize_burst(data, root)


if __name__ == '__main__':
    unittest.main()
