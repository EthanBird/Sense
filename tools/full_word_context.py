"""Train-only word association counts with distinct calibration families.

This host prototype adds one bounded feature, not a new sentence probability.
Inference uses a candidate's actual baseline lexical edges, never its reference.
"""
from collections import Counter, defaultdict
import math

from context_only_ranker import CAP
from masked_lm_rescore import han_only
from word_context_ranker import pair_key, path_words

SCHEMA = "full-word-association-v1"
DISCOUNT = .75
ALPHA = .1


def observations(text, units):
    if (not text or not han_only(text) or not units or "".join(units) != text
            or any(not unit or len(unit) > 8 or not han_only(unit) for unit in units)):
        raise ValueError("Invalid segmented background text")
    for unit in units:
        yield "U", "W2", "", unit
    for left, right in zip(units, units[1:]):
        yield "P", "W2", left, right
    ends, end = [], 0
    for unit in units:
        end += len(unit); ends.append(end)
    index = 0
    for cut in range(1, len(text)):
        while ends[index] <= cut:
            index += 1
        suffix = text[cut:ends[index]]
        yield "U", "CW", "", suffix
        for size in range(1, min(2, cut) + 1):
            yield "P", "CW", text[cut-size:cut], suffix


class Counts:
    def __init__(self):
        self.unigrams = {"W2": Counter(), "CW": Counter()}
        self.totals = {"W2": Counter(), "CW": Counter()}
        self.pairs = Counter()
        self.first_family = {}
        self.multiple_families = set()

    def add(self, family, text, units):
        if not family:
            raise ValueError("Missing background family")
        for event, kind, left, right in observations(text, units):
            if event == "U":
                self.unigrams[kind][right] += 1
                continue
            key = pair_key(kind, left, right)
            self.pairs[key] += 1; self.totals[kind][left] += 1
            first = self.first_family.setdefault(key, family)
            if first != family:
                self.multiple_families.add(key)

    def export(self):
        import json
        retained = {key: value for key, value in self.pairs.items() if key in self.multiple_families}
        if not retained:
            raise ValueError("No cross-family word associations")
        direct = defaultdict(float)
        for key, count in retained.items():
            kind, left, _ = json.loads(key)
            direct[kind, left] += count - DISCOUNT
        denominator = {kind: sum(values.values()) + ALPHA * len(values)
                       for kind, values in self.unigrams.items()}
        entries = []
        for key, count in sorted(retained.items()):
            kind, left, right = json.loads(key)
            total = self.totals[kind][left]
            prior = (self.unigrams[kind][right] + ALPHA) / denominator[kind]
            backoff = 1 - direct[kind, left] / total
            if not 0 < backoff <= 1:
                raise ValueError("Invalid discounted probability mass")
            conditional = (count - DISCOUNT) / total + backoff * prior
            lift = max(0., min(4., math.log(conditional / prior)))
            # Retain auditable count evidence, not a score-only opaque table.
            entries.append([key, count, total, self.unigrams[kind][right], prior, backoff, lift])
        return dict(schema=SCHEMA, discount=DISCOUNT, alpha=ALPHA, minimumFamilies=2,
                    namespaces={k: dict(unigramTotal=sum(v.values()), vocabulary=len(v))
                                for k, v in self.unigrams.items()}, entries=entries)


class AssociationFeature:
    def __init__(self, model):
        import json
        from word_context_ranker import valid_word_key
        if (model.get("schema") != SCHEMA or model.get("discount") != DISCOUNT
                or model.get("alpha") != ALPHA or model.get("minimumFamilies") != 2):
            raise ValueError("Unexpected full-word table")
        if (set(model['namespaces']) != {'W2', 'CW'}
                or not isinstance(model['entries'], list) or not 1 <= len(model['entries']) <= 500_000):
            raise ValueError('Invalid word association table limits')
        for namespace in model['namespaces'].values():
            if (set(namespace) != {'unigramTotal', 'vocabulary'}
                    or any(type(n) is not int or n <= 0 for n in namespace.values())
                    or namespace['vocabulary'] > namespace['unigramTotal']):
                raise ValueError('Invalid namespace counts')
        self.values = {}; previous = ""
        contexts = {}; direct_mass = defaultdict(float); word_counts = {}
        for row in model["entries"]:
            if len(row) != 7:
                raise ValueError("Malformed word association entry")
            key, count, total, unigram, prior, backoff, lift = row
            if (not valid_word_key(key) or key <= previous
                    or any(type(n) is not int or n <= 0 for n in [count, total, unigram])
                    or count < 2 or count > min(total, unigram)
                    or not all(math.isfinite(n) for n in [prior, backoff, lift])
                    or not 0 < prior <= 1 or not 0 < backoff <= 1 or not 0 <= lift <= 4):
                raise ValueError("Invalid word association entry")
            kind, left, right = json.loads(key)
            ns = model["namespaces"][kind]
            if max(total, unigram) > ns['unigramTotal']:
                raise ValueError('Count exceeds namespace total')
            if contexts.setdefault((kind, left), (total, backoff)) != (total, backoff):
                raise ValueError('Inconsistent context counts')
            if word_counts.setdefault((kind, right), unigram) != unigram:
                raise ValueError('Inconsistent unigram counts')
            direct_mass[kind, left] += count - DISCOUNT
            expected_prior = (unigram + ALPHA) / (ns["unigramTotal"] + ALPHA * ns["vocabulary"])
            expected_lift = max(0., min(4., math.log(((count-DISCOUNT)/total + backoff*prior)/prior)))
            if abs(prior-expected_prior) > 1e-12 or abs(lift-expected_lift) > 1e-12:
                raise ValueError("Word association disagrees with count evidence")
            self.values[key] = lift; previous = key
        for key, (total, backoff) in contexts.items():
            if abs(backoff - (1-direct_mass[key]/total)) > 1e-12:
                raise ValueError('Backoff disagrees with retained direct mass')

    def vector(self, context, text, evidence):
        if (len(context) > 2 or (context and not han_only(context))
                or not 2 <= len(text) <= 24 or not han_only(text)):
            raise ValueError("Expected bounded Han input")
        words = path_words(text, evidence)
        keys = Counter(pair_key("W2", a, b) for a, b in zip(words, words[1:]))
        if context:
            keys[pair_key("CW", context, words[0])] += 1
        scale = math.sqrt(len(text))
        return {k: n / scale for k, n in keys.items() if self.values.get(k, 0.) > 0}

    def __call__(self, context, text, evidence):
        return math.fsum(self.values[k] * v for k, v in self.vector(context, text, evidence).items())


class AssociationScorer:
    def __init__(self, feature, weight):
        if not math.isfinite(weight) or not 0 <= weight <= 4:
            raise ValueError("Invalid scalar calibration")
        self.feature, self.weight = feature, weight

    def __call__(self, context, texts, evidence):
        if not texts or len(texts) != len(evidence) or len({len(t) for t in texts}) != 1:
            raise ValueError("Unaligned candidates")
        scores = []
        for text, item in zip(texts, evidence):
            baseline = item["baseline"]
            if not math.isfinite(baseline):
                raise ValueError("Nonfinite baseline")
            x = self.feature(context, text, item)
            scores.append(baseline + CAP * math.tanh(self.weight * x / CAP))
        return scores


def fit_scalar(groups, feature):
    import numpy as np
    from scipy.optimize import minimize
    from scipy.sparse import csr_matrix
    from context_only_ranker import objective
    xs, baselines, boundaries, gold = [], [], [0], []
    for group in groups:
        if (len(group["texts"]) < 2 or len(group["texts"]) != len(group["evidence"])
                or len(group["texts"]) != len(set(group["texts"]))
                or len({len(t) for t in group["texts"]}) != 1
                or type(group["gold"]) is not int or not 0 <= group["gold"] < len(group["texts"])):
            raise ValueError("Invalid scalar training alignment")
        for text, evidence in zip(group["texts"], group["evidence"]):
            if not math.isfinite(evidence["baseline"]):
                raise ValueError("Nonfinite baseline")
            xs.append(feature(group["context"], text, evidence)); baselines.append(evidence["baseline"])
        boundaries.append(len(xs)); gold.append(group["gold"])
    if not groups:
        raise ValueError("Empty calibration groups")
    data = (csr_matrix(np.asarray(xs).reshape(-1, 1)), np.asarray(baselines),
            np.asarray(boundaries), np.asarray(gold))
    initial = np.zeros(1); history = []
    fit = minimize(objective, initial, args=(*data, .001), jac=True, method="L-BFGS-B",
        bounds=[(0., 4.)], callback=lambda w: history.append(objective(w, *data, .001)[0]),
        options={"maxiter": 200, "ftol": 1e-10, "gtol": 1e-7})
    return float(fit.x[0]), dict(groups=len(groups), rows=len(xs), nonzeroFeatures=int(np.count_nonzero(xs)),
        converged=bool(fit.success), iterations=int(fit.nit), message=str(fit.message),
        initialLoss=objective(initial, *data, .001)[0], finalLoss=float(fit.fun), lossHistory=history)
