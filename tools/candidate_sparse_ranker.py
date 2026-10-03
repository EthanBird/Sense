"""Offline discriminative character n-gram residual; not enabled in the Android IME.

Inference sees only context, candidate text and actual decoder features. References,
teacher probabilities and corpus-family identifiers belong exclusively to training.
"""
from collections import Counter, defaultdict
import math

from candidate_student import FEATURE_COUNT
from masked_lm_rescore import han_only

SCHEMA = "sparse-character-residual-v1"
CAP = 8.0


def character_features(context, text):
    if len(context) > 2 or (context and not han_only(context)):
        raise ValueError("Expected at most two Han context characters")
    if not 2 <= len(text) <= 24 or not han_only(text):
        raise ValueError("Expected 2..24 Han candidate characters")
    # A context-only n-gram never contributes. No EOS: IME input may be unfinished.
    prefix = "^" * (2 - len(context)) + context
    full = prefix + text
    counts = Counter(f"{n}:{full[end-n:end]}"
                     for end in range(3, len(full) + 1) for n in (1, 2, 3))
    scale = math.sqrt(len(text))
    return {key: count / scale for key, count in counts.items()}


def vocabulary(groups, family_by_id, min_families=2):
    if min_families < 2:
        raise ValueError("Exclude features confined to one training source family")
    families = defaultdict(set)
    for group in groups:
        family = family_by_id[group["id"]]
        if not family:
            raise ValueError("Missing source family")
        for text in group["texts"]:
            for key in character_features(group["context"], text):
                families[key].add(family)
    return sorted(key for key, seen in families.items() if len(seen) >= min_families)


def feature_vector(context, text, evidence, index):
    values = evidence["features"]
    if (len(values) != FEATURE_COUNT or not all(math.isfinite(v) for v in values)
            or not math.isfinite(evidence["baseline"])):
        raise ValueError("Invalid actual-path features")
    result = {index[k]: v for k, v in character_features(context, text).items() if k in index}
    base = len(index)
    result.update({base + i: max(-4.0, min(4.0, value / 32.0))
                   for i, value in enumerate(values) if value})
    return result


class SparseScorer:
    def __init__(self, model):
        keys, weights = model["vocabulary"], model["weights"]
        if (model.get("schema") != SCHEMA or model.get("cap") != CAP
                or keys != sorted(set(keys)) or len(keys) > 100_000
                or len(weights) != len(keys) + FEATURE_COUNT
                or not all(math.isfinite(w) for w in weights)):
            raise ValueError("Malformed sparse residual model")
        self.index = {key: i for i, key in enumerate(keys)}
        self.weights = tuple(weights)

    def __call__(self, context, texts, evidence):
        if not texts or len(texts) != len(evidence) or len({len(t) for t in texts}) != 1:
            raise ValueError("Expected aligned equal-length candidates")
        scores = []
        for text, item in zip(texts, evidence):
            x = feature_vector(context, text, item, self.index)
            raw = math.fsum(self.weights[i] * value for i, value in x.items())
            scores.append(item["baseline"] + CAP * math.tanh(raw / CAP))
        return scores


def training_arrays(groups, keys):
    import numpy as np
    from scipy.sparse import csr_matrix
    from scipy.special import softmax
    index = {key: i for i, key in enumerate(keys)}
    data, indices, pointers = [], [], [0]
    baseline, teacher, gold, offsets = [], [], [], [0]
    for g in groups:
        n = len(g["texts"])
        if (n < 2 or len(g["evidence"]) != n or len(g["teacherScores"]) != n
                or type(g["gold"]) is not int or not 0 <= g["gold"] < n
                or not all(math.isfinite(v) for v in g["teacherScores"])
                or len(set(g["texts"])) != n or len({len(t) for t in g["texts"]}) != 1):
            raise ValueError("Invalid supervised group")
        for text, e in zip(g["texts"], g["evidence"]):
            values = feature_vector(g["context"], text, e, index)
            for i in sorted(values):
                indices.append(i); data.append(values[i])
            pointers.append(len(data)); baseline.append(e["baseline"])
        teacher.extend(softmax(np.asarray(g["teacherScores"], dtype=np.float64) / 2.0))
        gold.append(g["gold"]); offsets.append(offsets[-1] + n)
    if not groups:
        raise ValueError("Empty training set")
    matrix = csr_matrix((data, indices, pointers), shape=(len(baseline), len(keys) + FEATURE_COUNT))
    return matrix, np.asarray(baseline), np.asarray(offsets), np.asarray(gold), np.asarray(teacher)


def objective(weights, matrix, baseline, offsets, gold, teacher, ridge=0.01):
    """Same E39 gold/teacher loss; smaller representation, fixed ridge and optimizer."""
    import numpy as np
    from scipy.special import logsumexp, xlogy
    raw = matrix @ weights
    t = np.tanh(raw / CAP)
    scores = baseline + CAP * t
    ds = np.zeros_like(scores)
    value = 0.0
    for group, (start, end) in enumerate(zip(offsets[:-1], offsets[1:])):
        score = scores[start:end]
        log_p = score - logsumexp(score)
        log_pt = score / 2.0 - logsumexp(score / 2.0)
        q = teacher[start:end]
        value += .75 * -log_p[gold[group]] + np.sum(xlogy(q, q) - q * log_pt)
        gradient = .75 * np.exp(log_p) + .5 * (np.exp(log_pt) - q)
        gradient[gold[group]] -= .75
        ds[start:end] = gradient
    groups = len(gold)
    value = value / groups + .5 * ridge * (weights @ weights)
    gradient = np.asarray(matrix.T @ (ds * (1.0 - t * t) / groups)).ravel() + ridge * weights
    if not np.isfinite(value) or not np.all(np.isfinite(gradient)):
        raise ValueError("Nonfinite objective")
    return float(value), gradient


def train(groups, family_by_id, *, max_iterations=200, ridge=0.01):
    import numpy as np
    from scipy.optimize import minimize
    keys = vocabulary(groups, family_by_id)
    arrays = training_arrays(groups, keys)
    initial = np.zeros(len(keys) + FEATURE_COUNT, dtype=np.float64)
    before, _ = objective(initial, *arrays, ridge)
    history = []
    def callback(w):
        history.append(objective(w, *arrays, ridge)[0])
    fit = minimize(objective, initial, args=(*arrays, ridge), method="L-BFGS-B", jac=True,
                   callback=callback, options={"maxiter": max_iterations, "ftol": 1e-10, "gtol": 1e-7})
    model = dict(schema=SCHEMA, cap=CAP, vocabulary=keys, weights=fit.x.tolist())
    SparseScorer(model)
    return model, dict(initialLoss=before, finalLoss=float(fit.fun), iterations=int(fit.nit),
        evaluations=int(fit.nfev), converged=bool(fit.success), message=str(fit.message),
        lossHistory=history, parameters=len(initial), features=len(keys),
        trainingGroups=len(groups), candidateRows=len(arrays[1]), nonzeros=int(arrays[0].nnz))
