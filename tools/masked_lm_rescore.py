"""Offline, target-blind character PLL rescoring for E37. Not an Android runtime.

Only inference inputs reach the scorer; expected text is used by the caller after
ranking. Eligible slots are permuted, so even failed rescoring loses no candidate.
"""
from dataclasses import dataclass
import json
import math
from pathlib import Path
import time
import unicodedata

from fetch_e37_model import REVISION, WEIGHT_SHA, FILES, digest


def character_vocabulary(path):
    from transformers import BertTokenizer
    tokenizer = BertTokenizer(vocab_file=str(path), do_lower_case=True)
    # Use the model tokenizer's physical-line vocabulary, not str.splitlines():
    # a Unicode line-separator token in Chinese BERT shifts all following IDs.
    return tokenizer, tokenizer.get_vocab()


def han_only(text):
    return bool(text) and all(unicodedata.name(c, "").startswith(
        ("CJK UNIFIED IDEOGRAPH-", "CJK COMPATIBILITY IDEOGRAPH-")) for c in text)


def eligible_slots(candidates):
    if not candidates or not 2 <= len(candidates[0]) <= 24 or not han_only(candidates[0]):
        return []
    return [i for i, text in enumerate(candidates[:8])
            if len(text) == len(candidates[0]) and han_only(text)]


@dataclass(frozen=True)
class MaskCase:
    ids: tuple
    position: int
    target: int
    owner: int


def mask_cases(context, texts, vocab):
    if len(context) > 2 or (context and not han_only(context)):
        raise ValueError("Expected zero to two Han context characters")
    if not texts or any(not han_only(t) or not 2 <= len(t) <= 24 for t in texts):
        raise ValueError("Expected bounded Han candidates")
    if len({len(t) for t in texts}) != 1:
        raise ValueError("Length-incomparable candidates")
    for token in ["[CLS]", "[SEP]", "[MASK]", "[UNK]"]:
        if token not in vocab:
            raise ValueError("Missing special token")
    if any(c not in vocab or vocab[c] == vocab["[UNK]"] for c in context + "".join(texts)):
        return None
    cases = []
    for owner, text in enumerate(texts):
        original = [vocab["[CLS]"]] + [vocab[c] for c in context + text] + [vocab["[SEP]"]]
        for offset in range(len(text)):
            position = len(context) + 1 + offset
            masked = original.copy(); masked[position] = vocab["[MASK]"]
            cases.append(MaskCase(tuple(masked), position, original[position], owner))
    return cases


def reorder(context, candidates, scorer):
    """No expected text, source sentence, or reference rank is accepted here."""
    if len(candidates) != len(set(candidates)):
        raise ValueError("Duplicate production candidates")
    slots = eligible_slots(candidates)
    if len(slots) < 2:
        return list(candidates), dict(reason="fewer-than-two-eligible", slots=slots)
    selected = [candidates[i] for i in slots]
    scores = scorer(context, selected)
    if scores is None:
        return list(candidates), dict(reason="unknown-character", slots=slots)
    if len(scores) != len(slots) or not all(math.isfinite(s) for s in scores):
        raise ValueError("Invalid model score vector")
    order = sorted(range(len(slots)), key=lambda i: (-scores[i], i))
    result = list(candidates)
    for slot, source in zip(slots, order):
        result[slot] = selected[source]
    assert len(result) == len(candidates) and set(result) == set(candidates)
    return result, dict(reason="scored", slots=slots, originalTexts=selected,
                        scores=scores, order=order)


def masked_head_logits(model, ids, positions):
    """MLM head is token-local: avoid materializing B x T x 21128 logits.

    A real-model numerical equivalence check is required by the experiment runner.
    This is still one independently masked input per candidate character (PLL),
    never logits of the visible target or multiple simultaneously masked targets.
    """
    import torch
    hidden = model.bert(input_ids=ids, attention_mask=torch.ones_like(ids),
                        token_type_ids=torch.zeros_like(ids)).last_hidden_state
    return model.cls(hidden[torch.arange(ids.shape[0]), positions])


class CharacterPLL:
    def __init__(self, folder):
        import torch
        import transformers
        from transformers import BertConfig, BertForMaskedLM
        self.torch = torch
        folder = Path(folder)
        manifest = json.loads((folder / "download-manifest.json").read_text("utf-8"))
        if manifest["revision"] != REVISION or manifest["license"] != "apache-2.0":
            raise ValueError("Unpinned model manifest")
        for name in FILES:
            if digest(folder / name) != manifest["files"][name]["sha256"]:
                raise ValueError(f"Changed model input {name}")
        if manifest["files"]["pytorch_model.bin"]["sha256"] != WEIGHT_SHA:
            raise ValueError("Wrong weights")
        torch.set_num_threads(2); torch.set_num_interop_threads(1)
        torch.manual_seed(37)
        start = time.perf_counter_ns()
        config = BertConfig.from_json_file(str(folder / "config.json"))
        config._attn_implementation = "eager"
        model = BertForMaskedLM(config)
        state = torch.load(folder / "pytorch_model.bin", map_location="cpu", weights_only=True)
        # Upstream old pretraining file includes unused NSP/pooler tensors and omits
        # the duplicated decoder bias alias. Assert exact, explicit normalization.
        ignored = ["bert.pooler.dense.weight", "bert.pooler.dense.bias",
                   "cls.seq_relationship.weight", "cls.seq_relationship.bias"]
        for name in ignored:
            state.pop(name)
        if not torch.equal(state["bert.embeddings.word_embeddings.weight"],
                           state["cls.predictions.decoder.weight"]):
            raise ValueError("Expected tied pretrained embedding/head weights")
        state["cls.predictions.decoder.bias"] = state["cls.predictions.bias"]
        model.load_state_dict(state, strict=True)
        self.model = model.eval()
        self.tokenizer, self.vocab = character_vocabulary(folder / "vocab.txt")
        if len(self.vocab) != config.vocab_size or sorted(self.vocab.values()) != list(range(config.vocab_size)):
            raise ValueError("Vocabulary IDs disagree with model config")
        self.metadata = dict(modelRevision=REVISION, weightsSha256=WEIGHT_SHA,
                             torch=torch.__version__, transformers=transformers.__version__,
                             parameters=sum(p.numel() for p in model.parameters()),
                             parameterBytes=sum(p.numel() * p.element_size() for p in model.parameters()),
                             modelLoadNanos=time.perf_counter_ns() - start,
                             intraopThreads=2, interopThreads=1, device="cpu", dtype="float32",
                             microbatch=32, ignoredUnusedPretrainingKeys=ignored,
                             decoderBiasAlias="cls.predictions.bias", head="masked-positions-only")
        self.calls = 0; self.masks = 0; self.inference_nanos = 0

    def verify_numerics(self):
        """Structural fixture, not an accuracy selection sample."""
        torch = self.torch
        context = "这是"; texts = ["测试", "测式"]
        cases = mask_cases(context, texts, self.vocab)
        ids = torch.tensor([c.ids for c in cases], dtype=torch.long)
        positions = torch.tensor([c.position for c in cases], dtype=torch.long)
        with torch.inference_mode():
            compact = masked_head_logits(self.model, ids, positions)
            full = self.model(input_ids=ids, attention_mask=torch.ones_like(ids),
                              token_type_ids=torch.zeros_like(ids)).logits
            gathered = full[torch.arange(len(cases)), positions]
            max_error = (compact - gathered).abs().max().item()
            if not torch.allclose(compact, gathered, rtol=1e-5, atol=1e-5):
                raise ValueError("Masked-head optimization differs from full MLM")
        for text in texts:
            actual = self.tokenizer(context + text, add_special_tokens=True)["input_ids"]
            expected = [self.vocab["[CLS]"]] + [self.vocab[c] for c in context + text] + [self.vocab["[SEP]"]]
            if actual != expected:
                raise ValueError("Han character tokenizer mismatch")
        # Ensure current model is deterministic before quality replay.
        one = self(context, texts); two = self(context, texts)
        if one != two:
            raise ValueError("Non-repeatable CPU PLL")
        self.calls = 0; self.masks = 0; self.inference_nanos = 0
        return dict(maxAbsoluteLogitError=max_error, fullAndGatheredAgree=True,
                    tokenizerAgreement=True, repeatedScoresEqual=True)

    def __call__(self, context, texts):
        torch = self.torch
        cases = mask_cases(context, texts, self.vocab)
        if cases is None:
            return None
        # Verify every input, not only the structural smoke fixture.
        for text in texts:
            if self.tokenizer(context + text, add_special_tokens=False)["input_ids"] != [self.vocab[c] for c in context + text]:
                raise ValueError("Non-character tokenizer input")
        scores = [0.0] * len(texts)
        start = time.perf_counter_ns()
        with torch.inference_mode():
            for offset in range(0, len(cases), 32):
                batch = cases[offset:offset + 32]
                ids = torch.tensor([c.ids for c in batch], dtype=torch.long)
                positions = torch.tensor([c.position for c in batch], dtype=torch.long)
                targets = torch.tensor([c.target for c in batch], dtype=torch.long)
                logits = masked_head_logits(self.model, ids, positions)
                logps = torch.log_softmax(logits, dim=-1)[torch.arange(len(batch)), targets].tolist()
                for case, value in zip(batch, logps):
                    scores[case.owner] += value
        self.inference_nanos += time.perf_counter_ns() - start
        self.calls += 1; self.masks += len(cases)
        return scores
