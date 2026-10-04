"""Offline lexical-context residual over actual decoder paths; not an Android default.

No reference text, family identifier or decoder-prior coefficient enters inference.
Word boundaries are supplied by the baseline winning path, not retokenized from gold.
"""
from collections import Counter, defaultdict
import json
import math

from context_only_ranker import CAP, contextual_features, objective, valid_key
from masked_lm_rescore import han_only

SCHEMA = "word-context-residual-v1"


def pair_key(kind, left, right):
    return json.dumps([kind, left, right], ensure_ascii=False, separators=(",", ":"))


def path_words(text, evidence):
    words = evidence.get("words")
    if (not isinstance(words, list) or not 1 <= len(words) <= 24
            or any(not isinstance(w, str) or not w or not han_only(w) for w in words)
            or "".join(words) != text):
        raise ValueError("Missing or inconsistent actual lexical path")
    return words


def features(context, text, evidence, include_words=True):
    values = contextual_features(context, text)
    words = path_words(text, evidence)
    if include_words:
        counts = Counter(pair_key("W2", a, b) for a, b in zip(words, words[1:]))
        if context:
            # Editor suffix is not a dictionary word. Its namespace stays distinct.
            counts[pair_key("CW", context, words[0])] += 1
        scale = math.sqrt(len(text))
        values.update({key: count / scale for key, count in counts.items()})
    return values


def valid_word_key(key):
    if not isinstance(key, str):
        return False
    try:
        parts = json.loads(key)
    except (ValueError, TypeError):
        return False
    return (isinstance(parts, list) and len(parts) == 3 and parts[0] in ("W2", "CW")
            and all(isinstance(w, str) and 1 <= len(w) <= 24 and han_only(w) for w in parts[1:])
            and (parts[0] != "CW" or len(parts[1]) <= 2)
            and key == pair_key(*parts))


def vocabulary(groups, families, include_words=True):
    seen = defaultdict(set)
    for group in groups:
        family = families[group["id"]]
        if not family:
            raise ValueError("Missing training family")
        if len(group["texts"]) != len(group["evidence"]):
            raise ValueError("Unaligned vocabulary evidence")
        for text, evidence in zip(group["texts"], group["evidence"]):
            for key in features(group["context"], text, evidence, include_words):
                seen[key].add(family)
    return sorted(key for key, sources in seen.items() if len(sources) >= 2)


class WordContextScorer:
    def __init__(self, model):
        keys, weights = model["vocabulary"], model["weights"]
        include_words = model.get("includeWords")
        if (model.get("schema") != SCHEMA or model.get("cap") != CAP
                or type(include_words) is not bool
                or not isinstance(keys, list) or not all(isinstance(k, str) for k in keys)
                or keys != sorted(set(keys)) or len(keys) > 100_000
                or not all(valid_key(k) or (include_words and valid_word_key(k)) for k in keys)
                or len(keys) != len(weights) or not all(math.isfinite(w) for w in weights)):
            raise ValueError("Invalid word-context model")
        self.weights = dict(zip(keys, weights))
        self.include_words = include_words

    def __call__(self, context, texts, evidence):
        if not texts or len(texts) != len(evidence) or len({len(t) for t in texts}) != 1:
            raise ValueError("Unaligned candidate group")
        result = []
        for text, item in zip(texts, evidence):
            baseline = item["baseline"]
            if not math.isfinite(baseline):
                raise ValueError("Nonfinite original score")
            x = features(context, text, item, self.include_words)
            raw = math.fsum(self.weights.get(k, 0.0) * v for k, v in x.items())
            result.append(baseline + CAP * math.tanh(raw / CAP))
        return result


def arrays(groups, keys, include_words=True):
    import numpy as np
    from scipy.sparse import csr_matrix
    index = {k: i for i, k in enumerate(keys)}
    data, columns, pointers = [], [], [0]
    baseline, boundaries, gold = [], [0], []
    for group in groups:
        texts, evidence = group["texts"], group["evidence"]
        if (len(texts) < 2 or len(texts) != len(evidence) or len(set(texts)) != len(texts)
                or len({len(t) for t in texts}) != 1 or type(group["gold"]) is not int
                or not 0 <= group["gold"] < len(texts)):
            raise ValueError("Invalid supervised group")
        for text, item in zip(texts, evidence):
            if not math.isfinite(item["baseline"]):
                raise ValueError("Nonfinite original score")
            values = features(group["context"], text, item, include_words)
            for key in sorted(values):
                if key in index:
                    columns.append(index[key]); data.append(values[key])
            pointers.append(len(data)); baseline.append(item["baseline"])
        boundaries.append(len(baseline)); gold.append(group["gold"])
    if not groups:
        raise ValueError("Empty training input")
    return (csr_matrix((data, columns, pointers), shape=(len(baseline), len(keys))),
            np.asarray(baseline), np.asarray(boundaries), np.asarray(gold))


def fit(groups, families, include_words):
    import numpy as np
    from scipy.optimize import minimize
    keys = vocabulary(groups, families, include_words)
    if not keys:
        raise ValueError("No cross-family features")
    data = arrays(groups, keys, include_words)
    start = np.zeros(len(keys), dtype=np.float64)
    history = []
    ridge = .001  # Frozen E53 protocol, not selected from development output.
    result = minimize(objective, start, args=(*data, ridge), jac=True, method="L-BFGS-B",
        callback=lambda w: history.append(objective(w, *data, ridge)[0]),
        options={"maxiter": 200, "ftol": 1e-10, "gtol": 1e-7})
    model = dict(schema=SCHEMA, cap=CAP, includeWords=include_words,
                 vocabulary=keys, weights=result.x.tolist())
    WordContextScorer(model)
    return model, dict(parameters=len(keys), wordParameters=sum(valid_word_key(k) for k in keys),
        groups=len(groups), candidateRows=len(data[1]), nonzeros=int(data[0].nnz), ridge=ridge,
        iterations=int(result.nit), evaluations=int(result.nfev), converged=bool(result.success),
        message=str(result.message), initialLoss=objective(start, *data, ridge)[0],
        finalLoss=float(result.fun), lossHistory=history)
