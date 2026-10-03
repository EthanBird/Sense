import unittest
from prepare_p2c import select_rows, overlaps, validate_annotation


class P2cPreparationTest(unittest.TestCase):
    def test_new_audit_excludes_every_member_of_previously_used_families(self):
        rows = [{"id": str(i), "group": str(i // 2), "segments": ["今天我们出去玩" + "吧" * i]} for i in range(8)]
        policy = {"seed": "fixed", "strata": [{"name": "all", "minCharacters": 1, "maxCharacters": 30}],
                  "excludedFamilies": ["0", "1", "2"]}
        result = select_rows(rows, "test", 1, policy)
        self.assertEqual(["3"], [row["group"] for row in result])
        with self.assertRaises(ValueError):
            select_rows(rows, "test", 2, policy)

    def test_sampling_is_order_invariant_stratified_and_family_unique(self):
        rows = [{"id": str(i), "group": str(i // 2), "segments": ["今天我们出去玩" + "吧" * i]} for i in range(12)]
        policy = {"seed": "fixed", "strata": [{"name": "short", "minCharacters": 6, "maxCharacters": 9},
                                                {"name": "long", "minCharacters": 10, "maxCharacters": 20}]}
        result = select_rows(rows, "dev", 4, policy)
        self.assertEqual(result, select_rows(list(reversed(rows)), "dev", 4, policy))
        self.assertEqual(4, len({r["group"] for r in result}))
        self.assertEqual(["short", "short", "long", "long"], [r["stratum"] for r in result])

    def test_leakage_checks_substrings_and_one_edit_but_not_rotations(self):
        self.assertEqual("substring", overlaps("他们正在学习中文", ["我知道他们正在学习中文呢"]))
        self.assertEqual("near", overlaps("他们正在练习中文", ["他们正在学习中文"]))
        self.assertEqual("near", overlaps("他们正在学习中文", ["他们正学习中文"]))
        self.assertIsNone(overlaps("甲乙丙丁戊己", ["乙丙丁戊己甲"]))

    def test_freeze_requires_explicit_review_complete_syllables_and_no_leaky_acceptance(self):
        row = {"text": "你好世界", "leakage": None}
        allowed = {"ni", "hao", "shi", "jie"}
        validate_annotation(row, ["accept", "ni hao shi jie", "reviewed before decode"], allowed)
        for annotation in (["pending", "ni hao shi jie", ""], ["accept", "ni hao", "reviewed"],
                           ["accept", "ni hao shi wrong", "reviewed"]):
            with self.assertRaises(ValueError):
                validate_annotation(row, annotation, allowed)
        with self.assertRaises(ValueError):
            validate_annotation({**row, "leakage": "near"}, ["accept", "ni hao shi jie", "reviewed"], allowed)


if __name__ == "__main__":
    unittest.main()
