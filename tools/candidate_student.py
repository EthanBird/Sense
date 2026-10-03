"""Offline one-forward residual candidate scorer. No Android runtime dependency.

Reference labels and teacher scores are deliberately absent from inference APIs.
Personal/corrected/unknown inputs retain the complete original candidate order.
"""
import gzip
import json
import math
from pathlib import Path
import time

from audit_score_features import FEATURES, f32
from fetch_e37_model import digest
from masked_lm_rescore import character_vocabulary, eligible_slots, han_only
from train_candidate_ranker import load_export

SUPPORTED = {'BASE_EXACT', 'BASE_COMPOSED'}
FEATURE_COUNT = len(FEATURES) + 1


def candidate_evidence(candidate):
    # Kotlin Float.toString is a round-trip *float32* representation, not the
    # exact corresponding Python float64. Reconstruct the actual decoder total.
    return dict(kind=candidate['kind'], baseline=f32(candidate['total']),
                features=candidate['features'] + [candidate['prior']])


def token_rows(context, texts, vocab):
    if len(context) > 2 or (context and not han_only(context)):
        raise ValueError('Expected zero to two Han context characters')
    if not texts or any(not han_only(t) or not 2 <= len(t) <= 24 for t in texts):
        raise ValueError('Expected bounded Han candidates')
    if len({len(t) for t in texts}) != 1:
        raise ValueError('Expected equal candidate lengths')
    for special in ['[CLS]', '[SEP]', '[PAD]', '[UNK]']:
        if special not in vocab:
            raise ValueError('Missing special token')
    if any(c not in vocab or vocab[c] == vocab['[UNK]'] for c in context + ''.join(texts)):
        return None
    return [[vocab['[CLS]']] + [vocab[c] for c in context + t] + [vocab['[SEP]']] for t in texts]


def select_inputs(candidates, evidence):
    if len(candidates) != len(set(candidates)):
        raise ValueError('Duplicate candidates')
    slots = eligible_slots(candidates)
    if len(slots) < 2:
        return slots, [], 'fewer-than-two-eligible'
    selected = []
    for slot in slots:
        item = evidence.get(candidates[slot])
        if item is None:
            return slots, [], 'missing-features'
        if item.get('kind') not in SUPPORTED:
            return slots, [], 'protected-source'
        vector = item.get('features', [])
        if (len(vector) != FEATURE_COUNT or not all(math.isfinite(v) for v in vector)
                or not math.isfinite(item.get('baseline', float('nan')))):
            return slots, [], 'invalid-features'
        selected.append(item)
    return slots, selected, None


def reorder_student(context, candidates, evidence, score):
    slots, selected, reason = select_inputs(candidates, evidence)
    if reason:
        return list(candidates), dict(reason=reason, slots=slots)
    texts = [candidates[i] for i in slots]
    scores = score(context, texts, selected)
    if scores is None:
        return list(candidates), dict(reason='unknown-character', slots=slots)
    if len(scores) != len(slots) or not all(math.isfinite(v) for v in scores):
        return list(candidates), dict(reason='invalid-scores', slots=slots)
    order = sorted(range(len(slots)), key=lambda i: (-scores[i], i))
    result = list(candidates)
    for slot, source in zip(slots, order):
        result[slot] = texts[source]
    return result, dict(reason='scored', slots=slots, originalTexts=texts, scores=scores, order=order)


def load_features(root, tsv, path):
    header, checked, audit = load_export(path, tsv)
    sources = {p.relative_to(root).as_posix(): digest(p)
               for p in (root / 'core-input/src/main/kotlin').rglob('*.kt')}
    if header['sources'] != sources:
        raise ValueError('Feature export is not current production')
    for name, expected in header['assets'].items():
        if digest(root / 'ime-service/src/main/assets' / name) != expected:
            raise ValueError('Feature export asset changed: ' + name)
    result = {}
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        next(f)
        for line in f:
            row = json.loads(line)
            if row['type'] != 'row':
                continue
            evidence = {}
            for index in row['winnerIndices']:
                c = row['pool'][index]
                evidence[c['text']] = candidate_evidence(c)
            row = {k: row[k] for k in ['id', 'cut', 'mode', 'stratum', 'query', 'context', 'expected', 'rank', 'resultSha256']}
            row['evidence'] = evidence
            row['candidates'] = list(evidence)
            # M20 baseline keys use id/cut; sampled mode is implied by cut and
            # was independently checked by load_export above.
            result[(row['id'], row['cut'])] = row
    if len(result) != len(checked):
        raise ValueError('Feature replay coverage changed')
    return result, audit


def make_model(config):
    import torch
    from transformers import BertModel

    class ResidualStudent(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.encoder = BertModel(config, add_pooling_layer=False)
            self.head = torch.nn.Linear(config.hidden_size + FEATURE_COUNT, 1)
            torch.nn.init.zeros_(self.head.weight)
            torch.nn.init.zeros_(self.head.bias)

        def forward(self, ids, attention, features, baseline):
            cls = self.encoder(input_ids=ids, attention_mask=attention,
                               token_type_ids=torch.zeros_like(ids)).last_hidden_state[:, 0]
            actual = (features / 32.0).clamp(-4.0, 4.0)
            delta = self.head(torch.cat([cls, actual], dim=-1)).squeeze(-1)
            return baseline + 8.0 * torch.tanh(delta / 8.0)

    return ResidualStudent()


def initialize_student(folder, spec):
    import torch
    from transformers import BertConfig
    folder = Path(folder)
    manifest = json.loads((folder / 'download-manifest.json').read_text('utf-8'))
    if (manifest['repo'] != spec['repo'] or manifest['revision'] != spec['revision']
            or manifest['license'] != 'apache-2.0'):
        raise ValueError('Student model identity/license mismatch')
    for name, entry in manifest['files'].items():
        if digest(folder / name) != entry['sha256']:
            raise ValueError('Student model file changed: ' + name)
    if digest(folder / 'pytorch_model.bin') != spec['weightsSha256']:
        raise ValueError('Student checkpoint changed')
    config = BertConfig.from_json_file(str(folder / 'config.json'))
    config._attn_implementation = 'eager'
    model = make_model(config)
    original = torch.load(folder / 'pytorch_model.bin', weights_only=True, map_location='cpu')
    state = {name.removeprefix('bert.'): value for name, value in original.items()
             if name.startswith('bert.') and not name.startswith('bert.pooler.')}
    # Older Transformers persisted this deterministic index buffer. The current
    # encoder regenerates it (persistent=False); never ignore a learned tensor.
    if 'embeddings.position_ids' in state:
        position_ids = state.pop('embeddings.position_ids')
        if not torch.equal(position_ids, torch.arange(config.max_position_embeddings).expand(1, -1)):
            raise ValueError('Unexpected legacy position buffer')
    model.encoder.load_state_dict(state, strict=True)
    _, vocab = character_vocabulary(folder / 'vocab.txt')
    if len(vocab) != config.vocab_size:
        raise ValueError('Student vocabulary size mismatch')
    return model, config, vocab


def tensors(rows, evidence, vocab):
    import torch
    width = max(map(len, rows))
    ids = torch.full((len(rows), width), vocab['[PAD]'], dtype=torch.long)
    attention = torch.zeros_like(ids)
    for i, row in enumerate(rows):
        ids[i, :len(row)] = torch.tensor(row)
        attention[i, :len(row)] = 1
    return (ids, attention, torch.tensor([x['features'] for x in evidence], dtype=torch.float32),
            torch.tensor([x['baseline'] for x in evidence], dtype=torch.float32))


def group_loss(scores, teacher, gold, temperature=2.0):
    import torch
    import torch.nn.functional as f
    hard = f.cross_entropy(scores.unsqueeze(0), torch.tensor([gold]))
    soft = f.kl_div(f.log_softmax(scores / temperature, dim=0),
                    f.softmax(teacher / temperature, dim=0), reduction='sum') * temperature ** 2
    return .75 * hard + .25 * soft


class StudentScorer:
    def __init__(self, model, vocab):
        self.model = model.eval(); self.vocab = vocab
        self.calls = 0; self.candidates = 0; self.inference_nanos = 0

    def __call__(self, context, texts, evidence):
        import torch
        encoded = token_rows(context, texts, self.vocab)
        if encoded is None:
            return None
        begin = time.perf_counter_ns()
        inputs = tensors(encoded, evidence, self.vocab)
        with torch.inference_mode():
            scores = self.model(*inputs).tolist()
        self.inference_nanos += time.perf_counter_ns() - begin
        self.calls += 1; self.candidates += len(texts)
        return scores
