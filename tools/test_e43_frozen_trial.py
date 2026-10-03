import json
from pathlib import Path
import tempfile
import unittest

from fetch_e37_model import digest
from run_e43_sparse_ranker import check_reused_group, frozen_model
from test_candidate_sparse_ranker import group


class FrozenTrialTest(unittest.TestCase):
    def test_teacher_reuse_requires_exact_inference_and_actual_scores(self):
        g = group()
        row = dict(candidates=g["texts"], evidence=dict(zip(g["texts"], g["evidence"])),
                   context=g["context"], expected=g["texts"][0])
        check_reused_group(g, row)
        row["context"] = "今天"
        with self.assertRaises(ValueError):
            check_reused_group(g, row)

    def test_mutated_model_fit_or_lock_rejected(self):
        for name in ["model.json", "fit.json", "pre-fit-lock.json"]:
            with tempfile.TemporaryDirectory() as tmp:
                out = Path(tmp)
                for file in ["model.json", "fit.json", "pre-fit-lock.json"]:
                    (out / file).write_text("{}", encoding="utf-8")
                freeze = {key: digest(out / file) for file, key in [
                    ("model.json", "modelSha256"), ("fit.json", "fitSha256"),
                    ("pre-fit-lock.json", "lockSha256")]}
                (out / "pre-dev-freeze.json").write_text(json.dumps(freeze), encoding="utf-8")
                self.assertEqual(frozen_model(out), {})
                (out / name).write_text('{"modified": true}', encoding="utf-8")
                with self.assertRaises(ValueError):
                    frozen_model(out)


if __name__ == "__main__":
    unittest.main()
