"""Train-only contextual residual with structurally frozen global priors.

There is no character-unigram column, intercept, or decoder-feature coefficient.
The original candidate total is an immutable offset, not a fitted input feature.
"""
from collections import defaultdict
import math

from candidate_sparse_ranker import character_features
from masked_lm_rescore import han_only

SCHEMA = "context-only-residual-v1"
CAP = 8.0


def contextual_features(context, text):
    return {k: v for k, v in character_features(context, text).items()
            if k.startswith(("2:", "3:")) and len(k[2:].lstrip("^")) >= 2}


def valid_key(key):
    if not isinstance(key, str) or not key.startswith(("2:", "3:")):
        return False
    n, gram = int(key[0]), key[2:]
    plain = gram.lstrip("^")
    return len(gram) == n and len(plain) >= 2 and han_only(plain)


def feature_vocabulary(groups, families):
    seen = defaultdict(set)
    for group in groups:
        family = families[group["id"]]
        if not family:
            raise ValueError("Missing training family")
        for text in group["texts"]:
            for key in contextual_features(group["context"], text):
                seen[key].add(family)
    return sorted(k for k, f in seen.items() if len(f) >= 2)


class ContextScorer:
    def __init__(self, model):
        keys, weights = model["vocabulary"], model["weights"]
        if (model.get("schema") != SCHEMA or model.get("cap") != CAP
                or keys != sorted(set(keys)) or not all(valid_key(k) for k in keys)
                or len(keys) != len(weights) or len(keys) > 100_000
                or not all(math.isfinite(w) for w in weights)):
            raise ValueError("Invalid context-only model")
        self.weights = dict(zip(keys, weights))

    def __call__(self, context, texts, evidence):
        if len(texts) != len(evidence) or not texts or len({len(t) for t in texts}) != 1:
            raise ValueError("Unaligned candidate group")
        result = []
        for text, e in zip(texts, evidence):
            baseline = e["baseline"]
            if not math.isfinite(baseline):
                raise ValueError("Nonfinite original score")
            raw = math.fsum(self.weights.get(k, 0.0) * v for k, v in contextual_features(context, text).items())
            result.append(baseline + CAP * math.tanh(raw / CAP))
        return result


def arrays(groups, keys):
    import numpy as np
    from scipy.sparse import csr_matrix
    index = {k: i for i, k in enumerate(keys)}
    values, columns, pointers = [], [], [0]
    baselines, boundaries, gold = [], [0], []
    for g in groups:
        n = len(g["texts"])
        if (n < 2 or len(g["evidence"]) != n or len(set(g["texts"])) != n
                or len({len(t) for t in g["texts"]}) != 1
                or type(g["gold"]) is not int or not 0 <= g["gold"] < n):
            raise ValueError("Invalid training alignment")
        for text, e in zip(g["texts"], g["evidence"]):
            if not math.isfinite(e["baseline"]):
                raise ValueError("Nonfinite original score")
            x = contextual_features(g["context"], text)
            for key in sorted(x):
                if key in index:
                    columns.append(index[key]); values.append(x[key])
            pointers.append(len(values)); baselines.append(e["baseline"])
        boundaries.append(len(baselines)); gold.append(g["gold"])
    if not groups:
        raise ValueError("Empty training input")
    return (csr_matrix((values, columns, pointers), shape=(len(baselines), len(keys))),
            np.asarray(baselines), np.asarray(boundaries), np.asarray(gold))


def objective(weights, matrix, baseline, boundaries, gold, ridge=0.01):
    import numpy as np
    from scipy.special import logsumexp
    tangent = np.tanh((matrix @ weights) / CAP)
    scores = baseline + CAP * tangent
    grad = np.zeros_like(scores)
    loss = 0.0
    for i, (a, b) in enumerate(zip(boundaries[:-1], boundaries[1:])):
        log_p = scores[a:b] - logsumexp(scores[a:b])
        loss -= log_p[gold[i]]
        grad[a:b] = np.exp(log_p); grad[a + gold[i]] -= 1.0
    count = len(gold)
    result = loss / count + .5 * ridge * (weights @ weights)
    gradient = np.asarray(matrix.T @ (grad * (1 - tangent*tangent) / count)).ravel() + ridge * weights
    if not np.isfinite(result) or not np.isfinite(gradient).all():
        raise ValueError("Nonfinite training objective")
    return float(result), gradient


def fit(groups, families):
    import numpy as np
    from scipy.optimize import minimize
    keys = feature_vocabulary(groups, families)
    data = arrays(groups, keys)
    initial = np.zeros(len(keys), dtype=np.float64)
    if not keys:
        raise ValueError("No cross-family context features")
    before, _ = objective(initial, *data)
    loss_history = []
    result = minimize(objective, initial, args=data, jac=True, method="L-BFGS-B",
        callback=lambda w: loss_history.append(objective(w, *data)[0]),
        options={"maxiter":200, "ftol":1e-10, "gtol":1e-7})
    model = dict(schema=SCHEMA, cap=CAP, vocabulary=keys, weights=result.x.tolist())
    ContextScorer(model)
    return model, dict(parameters=len(keys), trainingGroups=len(groups), candidateRows=len(data[1]),
        nonzeros=int(data[0].nnz), initialLoss=before, finalLoss=float(result.fun),
        iterations=int(result.nit), evaluations=int(result.nfev), converged=bool(result.success),
        message=str(result.message), lossHistory=loss_history,
        decoderCoefficientsLearned=False, characterUnigramsLearned=False)
