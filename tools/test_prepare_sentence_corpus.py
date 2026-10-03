import unittest

from prepare_sentence_corpus import prepare_records, build_partitions, near_duplicate_groups


class PrepareSentenceCorpusTest(unittest.TestCase):
    def test_protected_substring_also_blocks_near_neighbors_of_the_longer_sentence(self):
        rows = [{"id": 1, "text": "我知道他们正在学习中文", "author": "a", "modified": "0"},
                {"id": 2, "text": "我知道他们正在练习中文", "author": "b", "modified": "0"}]
        result = prepare_records(rows, lambda s: s, {"他们正在学习中文"})
        self.assertEqual([], result["records"])

    def test_eval_segments_repeated_in_training_or_dev_are_not_counted_as_held_out(self):
        records = [
            {"id": "a", "split": "train", "segments": ["你好", "今天出去玩"]},
            {"id": "b", "split": "dev", "segments": ["你好", "我准备出门了"]},
            {"id": "c", "split": "test", "segments": ["我准备出门了", "明天去爬山"]},
        ]
        splits, audit = build_partitions(records, {"今天出去玩"})
        self.assertEqual(["你好"], splits["train"][0]["segments"])
        self.assertEqual(["我准备出门了"], splits["dev"][0]["segments"])
        self.assertEqual(["明天去爬山"], splits["test"][0]["segments"])
        self.assertEqual(2, audit["heldoutDuplicateSegments"])
        self.assertEqual(1, audit["protectedSegments"])

    def test_equal_deletion_signatures_at_different_offsets_are_not_one_edit(self):
        groups = near_duplicate_groups(["甲乙丙丁戊己", "乙丙丁戊己甲"])
        self.assertNotEqual(groups["甲乙丙丁戊己"], groups["乙丙丁戊己甲"])

    def test_near_duplicates_share_a_group_and_protected_neighbor_removes_whole_family(self):
        texts = ["他们正在学习中文", "他们正在练习中文", "他们正在学习中文呢"]
        rows = [{"id": i + 1, "text": text, "author": "alice", "modified": "0"} for i, text in enumerate(texts)]
        result = prepare_records(rows, lambda s: s, set())
        self.assertEqual(1, len({r["group"] for r in result["records"]}))
        self.assertEqual(1, len({r["split"] for r in result["records"]}))
        protected = prepare_records(rows, lambda s: s, {"他们正在学习中文"})
        self.assertEqual([], protected["records"])

    def test_script_and_punctuation_variants_are_deduplicated_before_stable_split(self):
        rows = [
            {"id": 1, "text": "我們明天再聊。", "author": "alice", "modified": "0"},
            {"id": 2, "text": "我们明天再聊！", "author": "bob", "modified": "0"},
            {"id": 3, "text": "他们正在学习中文。", "author": "alice", "modified": "0"},
        ]
        convert = lambda s: s.replace("們", "们")
        result = prepare_records(rows, convert, set())
        reverse = prepare_records(list(reversed(rows)), convert, set())
        self.assertEqual(result, reverse)
        self.assertEqual(2, len(result["records"]))
        row = next(r for r in result["records"] if r["segments"] == ["我们明天再聊"])
        self.assertEqual([1, 2], [s["id"] for s in row["sources"]])
        self.assertEqual(1, result["audit"]["duplicateSkeletonRows"])


if __name__ == "__main__":
    unittest.main()
