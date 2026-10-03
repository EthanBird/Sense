import hashlib
from pathlib import Path
import tempfile
import unittest

from summarize_emission_replay import FIELDS, compare


class EmissionReplayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.input = self.root / 'input.tsv'
        self.input.write_text('one\tdaily\tnihao\t你\n', encoding='utf-8')
        self.hash = hashlib.sha256(self.input.read_bytes()).hexdigest()
        self.row = 'one\tdaily\tnihao\t你\t' + 'a'*64 + '\t20\t30'

    def write(self, name, row=None, digest=None):
        path = self.root / name
        path.write_text('# inputSha256=' + (digest or self.hash) + '\n' + '\t'.join(FIELDS) + '\n' +
                        (self.row if row is None else row) + '\n', encoding='utf-8')
        return path

    def test_equal_result_and_changed_calls_are_separate_claims(self):
        before = self.write('a')
        after = self.write('b', self.row.replace('\t20\t30', '\t5\t8'))
        result = compare(before, after, self.input)
        self.assertTrue(result['allResultsEqual'])
        self.assertEqual(result['groups']['daily']['afterCalls'], 5)

    def test_changed_fingerprint_is_reported_not_hidden_by_lower_calls(self):
        result = compare(self.write('a'), self.write('b', self.row.replace('a'*64, 'b'*64)), self.input)
        self.assertFalse(result['allResultsEqual'])
        self.assertEqual(len(result['differences']), 1)

    def test_rejects_unpinned_or_reordered_input(self):
        before = self.write('a')
        for n, row, digest in [('hash', self.row, 'b'*64), ('query', self.row.replace('nihao', 'niha'), None),
                               ('context', self.row.replace('\t你\t', '\t好\t'), None)]:
            with self.subTest(n=n), self.assertRaises(ValueError):
                compare(before, self.write(n, row, digest), self.input)

    def test_rejects_duplicates_and_truncated_or_empty_rows(self):
        before = self.write('a')
        for n, row in [('duplicate', self.row+'\n'+self.row), ('empty', ''), ('short', 'one\tdaily'),
                       ('long', self.row+'\textra')]:
            with self.subTest(n=n), self.assertRaises(ValueError):
                compare(before, self.write(n, row), self.input)

    def test_rejects_invalid_counts_or_missing_result_digest(self):
        before = self.write('a')
        for n, row in [('negative', self.row.replace('\t20\t', '\t-20\t')),
                       ('fraction', self.row.replace('\t20\t', '\t20.5\t')),
                       ('nohash', self.row.replace('a'*64, ''))]:
            with self.subTest(n=n), self.assertRaises(ValueError):
                compare(before, self.write(n, row), self.input)


if __name__ == '__main__':
    unittest.main()
