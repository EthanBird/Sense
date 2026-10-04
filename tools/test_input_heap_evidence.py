import unittest
from test_input_heap import assert_process_heaps


class HeapEvidenceTest(unittest.TestCase):
    def test_both_processes_must_report_the_exact_budget(self):
        assert_process_heaps("fixtureMaxHeapMiB=112\nimeRuntime=Sense candidate runtime: ready=true maxHeapMiB=112\n", 112)

    def test_large_fixture_or_ime_and_missing_evidence_fail(self):
        for fixture, ime in ((512, 112), (112, 512), (111, 112), (112, 111), (None, 112), (112, None)):
            with self.subTest(fixture=fixture, ime=ime):
                text = f"fixtureMaxHeapMiB={fixture}\nimeRuntime=Sense candidate runtime: maxHeapMiB={ime}\n"
                with self.assertRaises(ValueError):
                    assert_process_heaps(text, 112)

    def test_duplicate_or_suffix_values_do_not_hide_wrong_evidence(self):
        for text in (
            "fixtureMaxHeapMiB=112\nfixtureMaxHeapMiB=512\nimeRuntime=maxHeapMiB=112\n",
            "fixtureMaxHeapMiB=112\nimeRuntime=maxHeapMiB=1120\n",
            "fixtureMaxHeapMiB=112\nimeRuntime=maxHeapMiB=112garbage\n",
        ):
            with self.subTest(text=text), self.assertRaises(ValueError):
                assert_process_heaps(text, 112)


if __name__ == "__main__":
    unittest.main()
