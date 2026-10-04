import itertools
import unittest

from prepare_p2c import one_edit
from word_context_background import HeldoutFilter


class HeldoutIsolationTests(unittest.TestCase):
    def test_exact_substring_containment_insert_delete_substitute(self):
        f = HeldoutFilter(['甲乙丙丁'])
        for text in ['甲乙丙丁', '乙丙', '甲乙丙丁戊', '甲戊乙丙丁', '甲丙丁', '甲戊丙丁']:
            self.assertTrue(f.reason(text), text)
        self.assertIsNone(f.reason('乙甲丙丁'))  # A transposition is two edits.

    def test_shared_deletion_at_different_offsets_is_not_one_edit(self):
        self.assertIsNone(HeldoutFilter(['甲乙丙']).reason('乙丙甲'))

    def test_matches_bruteforce_for_small_exhaustive_alphabet(self):
        values = ['甲乙丙', '乙乙', '丙甲乙甲']
        f = HeldoutFilter(values)
        for n in range(1, 6):
            for letters in itertools.product('甲乙丙', repeat=n):
                text = ''.join(letters)
                expected = any(text in other or other in text or one_edit(text, other) for other in values)
                self.assertEqual(bool(f.reason(text)), expected, text)

    def test_reject_empty_protection(self):
        for values in ([], ['']):
            with self.assertRaises(ValueError): HeldoutFilter(values)


if __name__ == '__main__': unittest.main()
