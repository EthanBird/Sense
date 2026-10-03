import inspect
import math
from pathlib import Path
import tempfile
import unittest
from masked_lm_rescore import eligible_slots, mask_cases, reorder, masked_head_logits, character_vocabulary
from run_e37_rescoring import check_model_qualification
from fetch_e37_model import REVISION


class MaskedRescoreTest(unittest.TestCase):
    vocab = {t: i for i, t in enumerate(["[CLS]", "[SEP]", "[MASK]", "[UNK]", "今", "天", "有", "点", "累", "类"])}

    def test_one_mask_per_candidate_character_never_context(self):
        cases = mask_cases("今天", ["有点累", "有点类"], self.vocab)
        self.assertEqual(len(cases), 6)
        for i, c in enumerate(cases):
            self.assertEqual(c.owner, i // 3)
            self.assertEqual(c.position, 3 + i % 3)
            self.assertEqual(c.ids.count(self.vocab["[MASK]"]), 1)
            self.assertEqual(c.ids[1:3], (self.vocab["今"], self.vocab["天"]))
            self.assertNotEqual(c.ids[c.position], c.target)
            self.assertEqual(c.ids[-1], self.vocab["[SEP]"])

    def test_zero_context_masks_from_first_character(self):
        cases = mask_cases("", ["有点"], self.vocab)
        self.assertEqual([c.position for c in cases], [1, 2])

    def test_unknown_character_does_not_use_unk_probability(self):
        self.assertIsNone(mask_cases("", ["有点好"], self.vocab))
        self.assertIsNone(mask_cases("前", ["有点"], self.vocab))

    def test_invalid_context_mixed_length_and_non_han_rejected(self):
        for context, texts in [("今天有", ["有点"]), ("a", ["有点"]),
                               ("", ["有点", "有点累"]), ("", ["ha"]), ("", ["有"]), ("", [])]:
            with self.subTest(context=context, texts=texts), self.assertRaises(ValueError):
                mask_cases(context, texts, self.vocab)

    def test_slots_top_eight_same_length_han_only(self):
        candidates = ["有点", "hi", "有点累", "今天", "有", "类别", "今天累", "点累", "点类"]
        self.assertEqual(eligible_slots(candidates), [0, 3, 5, 7])
        for first in ["hi", "有", "今" * 25]:
            self.assertEqual(eligible_slots([first, "有点"]), [])

    def test_permutation_keeps_unselected_slots_and_entire_recall(self):
        original = ["有点累", "hello", "有点", "有点类", "今天有", "末尾"]
        actual, trace = reorder("今天", original, lambda context, texts: [-3., -1., -2.])
        self.assertEqual(actual, ["有点类", "hello", "有点", "今天有", "有点累", "末尾"])
        self.assertEqual(set(actual), set(original))
        self.assertEqual(original[0], "有点累")
        self.assertEqual(trace["order"], [1, 2, 0])

    def test_ties_keep_existing_order(self):
        original = ["有点累", "有点类"]
        actual, _ = reorder("", original, lambda c, t: [-2., -2.])
        self.assertEqual(actual, original)

    def test_unknown_entire_row_passthrough(self):
        original = ["有点累", "有点类"]
        actual, trace = reorder("", original, lambda c, t: None)
        self.assertEqual(actual, original)
        self.assertEqual(trace["reason"], "unknown-character")

    def test_no_inference_for_single_eligible(self):
        def fail(*args): self.fail("unexpected inference")
        self.assertEqual(reorder("", ["有点累", "ok"], fail)[0], ["有点累", "ok"])

    def test_invalid_score_vectors_and_duplicate_candidates_rejected(self):
        for scores in [[1.], [1., math.nan], [1., math.inf]]:
            with self.subTest(scores=scores), self.assertRaises(ValueError):
                reorder("", ["有点累", "有点类"], lambda c, t: scores)
        with self.assertRaises(ValueError): reorder("", ["有点累", "有点累"], lambda c, t: [1., 2.])

    def test_scorer_api_has_no_expected_reference_input(self):
        self.assertEqual(list(inspect.signature(reorder).parameters), ["context", "candidates", "scorer"])

    def test_unqualified_head_stops_before_inference(self):
        qualification = dict(schemaVersion=1, model="hfl/rbt3", revision=REVISION,
                             trainedMaskedLmHeadVerified=False)
        with self.assertRaises(ValueError): check_model_qualification(qualification)

    def test_diagnostic_replay_requires_explicit_opt_in(self):
        qualification = dict(schemaVersion=1, model="hfl/rbt3", revision=REVISION,
                             trainedMaskedLmHeadVerified=False)
        check_model_qualification(qualification, diagnostic_replay=True)

    def test_diagnostic_override_does_not_relax_identity(self):
        qualification = dict(schemaVersion=1, model="hfl/rbt3", revision="changed",
                             trainedMaskedLmHeadVerified=False)
        with self.assertRaises(ValueError): check_model_qualification(qualification, diagnostic_replay=True)

    def test_unicode_line_separator_token_does_not_shift_ids(self):
        try:
            import transformers
        except ImportError:
            self.skipTest("Optional transformers host research dependency")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vocab.txt"
            path.write_text("[PAD]\n[UNK]\n[CLS]\n[SEP]\n[MASK]\n\u2028\n今\n", encoding="utf-8")
            tokenizer, vocab = character_vocabulary(path)
            self.assertEqual(vocab["今"], 6)
            self.assertEqual(tokenizer("今", add_special_tokens=False)["input_ids"], [6])

    def test_masked_head_matches_actual_full_bert(self):
        try:
            import torch
            from transformers import BertConfig, BertForMaskedLM
        except ImportError:
            self.skipTest("Optional torch/transformers host research dependencies")
        torch.manual_seed(37); torch.set_num_threads(2)
        config = BertConfig(vocab_size=16, hidden_size=8, num_hidden_layers=1,
                            num_attention_heads=2, intermediate_size=16)
        config._attn_implementation = "eager"
        model = BertForMaskedLM(config).eval()
        ids = torch.tensor([[1, 2, 3, 4], [1, 3, 2, 4]])
        positions = torch.tensor([1, 2])
        with torch.inference_mode():
            actual = masked_head_logits(model, ids, positions)
            expected = model(ids).logits[torch.arange(2), positions]
        self.assertTrue(torch.allclose(actual, expected, rtol=1e-5, atol=1e-5))


if __name__ == "__main__": unittest.main()
