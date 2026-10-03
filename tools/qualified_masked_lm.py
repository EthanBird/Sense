"""Pinned task qualification before offline Sense neural scoring.

Model-name labels and tensor shape alone do not establish language-model ability.
No remote code, randomly initialized missing MLM head, or online inference.
"""
import json
from pathlib import Path
import time

from fetch_e37_model import digest
from masked_lm_rescore import CharacterPLL, character_vocabulary

REQUIRED_HEAD_KEYS = {
    'cls.predictions.bias', 'cls.predictions.transform.dense.weight',
    'cls.predictions.transform.dense.bias', 'cls.predictions.transform.LayerNorm.weight',
    'cls.predictions.transform.LayerNorm.bias',
}


def require_head_keys(keys):
    canonical = {}
    for key in keys:
        normalized = key.replace('.LayerNorm.gamma', '.LayerNorm.weight').replace('.LayerNorm.beta', '.LayerNorm.bias')
        if normalized in canonical:
            raise ValueError('Ambiguous legacy/canonical parameter aliases: ' + normalized)
        canonical[normalized] = key
    missing = sorted(REQUIRED_HEAD_KEYS - set(canonical))
    if missing:
        raise ValueError('Released checkpoint has no complete MLM task head: ' + ', '.join(missing))
    return {key: canonical[key] for key in sorted(REQUIRED_HEAD_KEYS)}


def qualification_passed(results):
    if len(results) != 8:
        raise ValueError('Incomplete qualification fixtures')
    return sum(r['hitTop5'] for r in results) >= 6


class QualifiedCharacterPLL(CharacterPLL):
    def __init__(self, folder, spec):
        import torch
        import transformers
        from transformers import BertForMaskedLM
        self.torch = torch
        folder = Path(folder)
        manifest = json.loads((folder / 'download-manifest.json').read_text('utf-8'))
        if (manifest['repo'] != spec['repo'] or manifest['revision'] != spec['revision']
                or manifest['license'] != 'apache-2.0'):
            raise ValueError('Model manifest identity/license mismatch')
        for name in spec['files']:
            if digest(folder / name) != manifest['files'][name]['sha256']:
                raise ValueError('Changed pinned input: ' + name)
        if manifest['files'][spec['weightFile']]['sha256'] != spec['weightSha256']:
            raise ValueError('Wrong checkpoint')
        weight_file = folder / spec['weightFile']
        if weight_file.suffix == '.safetensors':
            from safetensors import safe_open
            with safe_open(weight_file, framework='pt', device='cpu') as weights:
                head_keys = require_head_keys(weights.keys())
        else:
            state = torch.load(weight_file, weights_only=True, map_location='cpu')
            head_keys = require_head_keys(state.keys()); del state
        torch.set_num_threads(2)
        if torch.get_num_interop_threads() != 1:
            torch.set_num_interop_threads(1)
        torch.manual_seed(38)
        start = time.perf_counter_ns()
        model, loading = BertForMaskedLM.from_pretrained(
            folder, local_files_only=True, weights_only=True,
            use_safetensors=weight_file.suffix == '.safetensors',
            attn_implementation='eager', output_loading_info=True)
        if loading['missing_keys'] or loading['mismatched_keys'] or loading['error_msgs']:
            raise ValueError('Untrained/mismatched model task head: ' + json.dumps(loading))
        allowed = {'bert.pooler.dense.weight', 'bert.pooler.dense.bias',
                   'cls.seq_relationship.weight', 'cls.seq_relationship.bias'}
        if set(loading['unexpected_keys']) - allowed:
            raise ValueError('Unexpected checkpoint weights: ' + json.dumps(loading))
        self.model = model.eval()
        self.tokenizer, self.vocab = character_vocabulary(folder / 'vocab.txt')
        if len(self.vocab) != model.config.vocab_size or sorted(self.vocab.values()) != list(range(model.config.vocab_size)):
            raise ValueError('Vocabulary/model dimension mismatch')
        self.metadata = dict(model=spec['repo'], modelRevision=spec['revision'],
            weightsSha256=spec['weightSha256'], torch=torch.__version__, transformers=transformers.__version__,
            parameters=sum(p.numel() for p in model.parameters()),
            parameterBytes=sum(p.numel() * p.element_size() for p in model.parameters()),
            modelLoadNanos=time.perf_counter_ns() - start, intraopThreads=2, interopThreads=1,
            device='cpu', dtype='float32', microbatch=32, loadingInfo=loading, head='masked-positions-only',
            verifiedCheckpointHeadKeys=head_keys)
        self.calls = 0; self.masks = 0; self.inference_nanos = 0

    def qualify(self, fixtures):
        torch = self.torch
        numerics = self.verify_numerics()
        results = []
        for fixture in fixtures:
            tokens = self.tokenizer(fixture['text'], return_tensors='pt')
            mask = (tokens['input_ids'][0] == self.vocab['[MASK]']).nonzero()
            if mask.numel() != 1:
                raise ValueError('Expected exactly one mask in qualification fixture')
            with torch.inference_mode():
                logits = self.model(**tokens).logits[0, mask.item()]
                probabilities, indices = logits.softmax(-1).topk(5)
            predicted = self.tokenizer.convert_ids_to_tokens(indices.tolist())
            results.append(dict(**fixture, top5=predicted, probabilities=probabilities.tolist(),
                                hitTop5=bool(set(predicted) & set(fixture['targets']))))
        return dict(passed=qualification_passed(results), fixtures=results, numerics=numerics,
                    metadata=self.metadata, scope='Task-qualification smoke fixtures, not Sense accuracy')
