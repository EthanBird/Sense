"""Host-only aligned signed word-context trial, deliberately separate from E53/E54.

No reference labels enter inference. Personal diagnostics are a conservative
offline guard, not a replacement for runtime per-edge personalization provenance.
"""
from collections import Counter
import math

from audit_score_features import FEATURES
from candidate_student import select_inputs, reorder_student
from full_word_context import AssociationFeature, DISCOUNT
from masked_lm_rescore import han_only
from train_association_model import Segmenter
from word_context_ranker import pair_key, path_words

PERSONAL = tuple(FEATURES.index(k) for k in ['PERSONAL_BASE', 'PERSONAL_FLOOR', 'PERSONAL_ADJUSTMENT'])
TOKENIZER_SCHEMA = 'fixed-word-tokenizer-v1'


class FrozenSegmenter(Segmenter):
    def __init__(self, model):
        if (model.get('schema') != TOKENIZER_SCHEMA or model.get('maxWordLength') != 8
                or not math.isfinite(model.get('unknown', float('nan'))) or model['unknown'] >= 0
                or not isinstance(model.get('entries'), list) or not 1 <= len(model['entries']) <= 200_000):
            raise ValueError('Invalid frozen tokenizer')
        self.scores = {}; previous = ''
        self.unknown = model['unknown']
        for row in model['entries']:
            if not isinstance(row, list) or len(row) != 2:
                raise ValueError('Invalid frozen word')
            word, score = row
            if (not isinstance(word, str) or not 1 <= len(word) <= 8 or not han_only(word)
                    or word <= previous or not math.isfinite(score) or score > 0):
                raise ValueError('Invalid frozen word score')
            self.scores[word] = score; previous = word

    def model(self):
        return dict(schema=TOKENIZER_SCHEMA, maxWordLength=8, unknown=self.unknown,
                    entries=[[w, s] for w, s in sorted(self.scores.items())])


def compact_segmenter(original, rows):
    kept = set()
    for row in rows:
        text, words = row['text'], row['words']
        if not text or not han_only(text) or not words or ''.join(words) != text:
            raise ValueError('Invalid background segmentation')
        if any(w not in original.scores and len(w) > 1 for w in words):
            raise ValueError('Background contains non-dictionary multiword')
        kept.update(words); kept.update(text)
    model = dict(schema=TOKENIZER_SCHEMA, maxWordLength=8, unknown=original.unknown,
                 entries=[[w, original.scores.get(w, original.unknown)] for w in sorted(kept)])
    # Do not renormalize: removing non-winning paths must not alter path scores.
    frozen = FrozenSegmenter(model)
    for row in rows:
        if frozen.units(row['text']) != row['words']:
            raise ValueError('Compact tokenizer changed a background training path')
    return model, dict(originalVocabulary=len(original.scores), compactVocabulary=len(kept),
                       unknownScore=original.unknown, verifiedPaths=len(rows), exact=True)


class AlignedAssociationFeature:
    def __init__(self, model, tokenizer, *, aligned, signed):
        import json
        AssociationFeature(model)  # Validate all counts, priors and backoff masses.
        if type(aligned) is not bool or type(signed) is not bool:
            raise ValueError('Expected explicit ablation flags')
        self.tokenizer, self.aligned, self.signed = tokenizer, aligned, signed
        self.values = {}; self.backoffs = {}
        for key, count, total, _, prior, backoff, positive in model['entries']:
            kind, left, right = json.loads(key)
            signed_score = math.log(((count - DISCOUNT) / total + backoff * prior) / prior)
            self.values[kind, left, right] = max(-4., min(4., signed_score)) if signed else positive
            self.backoffs[kind, left] = max(-4., min(4., math.log(backoff))) if signed else 0.

    def pair_score(self, kind, left, right):
        return self.values.get((kind, left, right), self.backoffs.get((kind, left), 0.))

    def vector(self, context, text, evidence):
        if (len(context) > 2 or (context and not han_only(context))
                or not 2 <= len(text) <= 24 or not han_only(text)):
            raise ValueError('Expected bounded Han input')
        words = self.tokenizer.units(text) if self.aligned else path_words(text, evidence)
        pairs = Counter(('W2', a, b) for a, b in zip(words, words[1:]))
        if context:
            pairs['CW', context, words[0]] += 1
        scale = math.sqrt(len(text))
        return {pair_key(*key): self.pair_score(*key) * (count / scale)
                for key, count in pairs.items() if self.pair_score(*key) != 0.}

    def __call__(self, context, text, evidence):
        return math.fsum(self.vector(context, text, evidence).values())


def personal_evidence(candidate):
    vector = candidate['features']
    edges = candidate['edges']
    if len(vector) != len(FEATURES) or not all(math.isfinite(v) for v in vector) or not edges:
        raise ValueError('Missing raw path evidence')
    if any(len(e) != 4 or not e[0] or not e[1] for e in edges):
        raise ValueError('Invalid raw lexical edges')
    return any(vector[i] != 0. for i in PERSONAL) or any(e[2] is None for e in edges)


def select_guarded_inputs(candidates, evidence):
    slots, selected, reason = select_inputs(candidates, evidence)
    if reason:
        return slots, selected, reason
    for item in selected:
        if type(item.get('personalEvidence')) is not bool:
            return slots, [], 'missing-personal-provenance'
        if item['personalEvidence'] or any(item['features'][i] != 0. for i in PERSONAL):
            return slots, [], 'personalized-path'
    return slots, selected, None


def reorder_guarded(context, candidates, evidence, scorer):
    slots, _, reason = select_guarded_inputs(candidates, evidence)
    if reason:
        return list(candidates), dict(reason=reason, slots=slots)
    return reorder_student(context, candidates, evidence, scorer)
