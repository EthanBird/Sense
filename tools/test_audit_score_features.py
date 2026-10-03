import copy
import math
import unittest
from audit_score_features import FEATURES, check_row, f32, positive_rescaling_obstacle


class ScoreFeaturesTest(unittest.TestCase):
    def row(self):
        vector = [0.0] * len(FEATURES)
        vector[0] = f32(math.log(3))
        path = dict(text="词", kind="BASE_EXACT", score=vector[0], prior=8.0, total=f32(vector[0] + 8),
                    arithmeticError=0.0, normalizer=1.0, features=vector,
                    edges=[["词", "ci", 2, 0]], segments=1)
        return dict(pool=[path], winnerIndices=[0])

    def test_valid_and_same_text_duplicate_keep_first_path(self):
        row = self.row()
        self.assertEqual(0.0, check_row(row))
        row["pool"].append(copy.deepcopy(row["pool"][0]))
        self.assertEqual(0.0, check_row(row))

    def test_unexplained_score_and_nonfinite_features_are_rejected(self):
        for field, value in [("features", [0.0] * len(FEATURES)), ("arithmeticError", .1),
                             ("total", 99.0), ("score", float("nan"))]:
            with self.subTest(field=field):
                row = self.row(); row["pool"][0][field] = value
                with self.assertRaises(ValueError):
                    check_row(row)

    def test_wrong_edges_and_winning_path_are_rejected(self):
        for mutation in [lambda r: r.update(winnerIndices=[]),
                         lambda r: r["pool"][0].update(segments=2),
                         lambda r: r["pool"][0].update(edges=[["词", "ci", 1, 0]]),
                         lambda r: r["pool"][0].update(edges=[["词", "ci", 2, 1]]),
                         lambda r: r["pool"][0].update(edges=[["字", "ci", 2, 0]])]:
            row = self.row(); mutation(row)
            with self.assertRaises(ValueError):
                check_row(row)

    def test_known_arithmetic_error_is_not_a_feature(self):
        row = self.row(); path = row["pool"][0]
        path["features"][FEATURES.index("CHARACTER_LM")] = .000001
        path["score"] = f32(math.fsum(path["features"]))
        path["total"] = f32(path["score"] + path["prior"])
        path["arithmeticError"] = path["score"] - math.fsum(path["features"])
        self.assertAlmostEqual(abs(path["arithmeticError"]), check_row(row), places=10)

    def obstacle_row(self):
        row = self.row()
        row.update(id="fixture", cut=1, context="前", query="ci", expected="辞")
        target = copy.deepcopy(row["pool"][0]); target["text"] = "辞"
        target["features"][0] -= 1
        row["pool"].append(target)
        return row

    def test_dominance_checks_every_path_for_reference_not_only_its_baseline_winner(self):
        row = self.obstacle_row()
        self.assertEqual(1, positive_rescaling_obstacle(row)["referencePaths"])
        another = copy.deepcopy(row["pool"][1]); another["features"][1] = 1
        row["pool"].append(another)
        self.assertIsNone(positive_rescaling_obstacle(row))

    def test_missing_correct_and_tied_targets_are_not_rescaling_obstacles(self):
        row = self.obstacle_row()
        row["pool"][1]["features"] = row["pool"][0]["features"].copy()
        self.assertIsNone(positive_rescaling_obstacle(row))
        row["expected"] = "空"
        self.assertIsNone(positive_rescaling_obstacle(row))
        row["expected"] = "词"
        self.assertIsNone(positive_rescaling_obstacle(row))


if __name__ == "__main__":
    unittest.main()
