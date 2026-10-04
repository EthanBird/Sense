"""One fixed complete-word-LM residual; raw scores come from the native probe."""
import hashlib
import json
import math

from aligned_word_context import select_guarded_inputs
from masked_lm_rescore import han_only

HEADER = dict(type='header', schemaVersion=1, modelOrder=3, reader='libime-1.0.11-static',
              maxWordLength=8, additionalUnknownPenalty=0, start='null', eos=False, beamPruning=False)


def identity(context, text):
    if len(context) > 2 or (context and not han_only(context)) or not 2 <= len(text) <= 24 or not han_only(text):
        raise ValueError('Expected bounded Han LM request')
    return hashlib.sha256((context + '\0' + text).encode('utf-8')).hexdigest()


def requests(groups):
    result = {}
    for g in groups:
        for text in g['texts']:
            result[identity(g['context'], text)] = (g['context'], text)
    return dict(sorted(result.items()))


def write_requests(path, workload):
    with path.open('x', encoding='utf-8', newline='\n') as f:
        for key, (context, text) in workload.items():
            if key != identity(context, text): raise ValueError('Invalid LM request identity')
            f.write(key + '\t' + context + '\t' + text + '\n')


def load_scores(path, workload):
    with path.open(encoding='utf-8') as f:
        rows = [json.loads(line) for line in f]
    if len(rows) < 3 or rows[0] != HEADER or rows[-1] != dict(type='summary', rows=len(rows)-2):
        raise ValueError('Incomplete or unexpected native run')
    records = {}; by_id = {}
    for r in rows[1:-1]:
        if (r.get('type') != 'row' or r['id'] not in workload or r['id'] in by_id
                or (r['context'], r['text']) != workload[r['id']] or r['id'] != identity(r['context'], r['text'])):
            raise ValueError('Native workload mismatch')
        text = r['context'] + r['text']; words = r['words']
        if (not words or ''.join(words) != text or any(not 1 <= len(w) <= 8 or not han_only(w) for w in words)
                or not math.isfinite(r['log10Score']) or r['directExact'] is not True
                or type(r['unknowns']) is not int or not 0 <= r['unknowns'] <= len(words)
                or type(r['nanos']) is not int or r['nanos'] < 0
                or type(r['states']) is not int or not 1 <= r['states'] <= 1+65*len(text)
                or type(r['transitions']) is not int or not 1 <= r['transitions'] <= 8*65*len(text)):
            raise ValueError('Invalid native path evidence')
        by_id[r['id']] = r; records[r['context'], r['text']] = r
    if by_id.keys() != workload.keys(): raise ValueError('Missing native requests')
    return records


def centered_values(context, texts, records):
    if not texts or len(texts) != len(set(texts)) or len({len(t) for t in texts}) != 1:
        raise ValueError('Unaligned word LM candidates')
    rows = [records[context, text] for text in texts]
    if any(r['unknowns'] for r in rows): return None
    raw = [r['log10Score'] for r in rows]
    if not all(math.isfinite(v) for v in raw): raise ValueError('Nonfinite native score')
    mean = math.fsum(raw) / len(raw)
    scale = math.log(10.) / math.sqrt(len(texts[0]))
    return [(value - mean) * scale for value in raw]


class StaticWordScorer:
    def __init__(self, records, weight):
        if not math.isfinite(weight) or not 0 <= weight <= 4: raise ValueError('Invalid LM scalar')
        self.records, self.weight = records, weight

    def __call__(self, context, texts, evidence):
        if len(texts) != len(evidence) or any(not math.isfinite(e['baseline']) for e in evidence):
            raise ValueError('Invalid LM evidence')
        values = centered_values(context, texts, self.records)
        if values is None: return None
        return [e['baseline'] + 8 * math.tanh(self.weight * x / 8) for e, x in zip(evidence, values)]


def fit(groups, records):
    import numpy as np
    from scipy.optimize import minimize
    from scipy.sparse import csr_matrix
    from context_only_ranker import objective
    eligible = []; xs = []; baselines = []; boundaries = [0]; gold = []; skipped = 0
    for g in groups:
        evidence = dict(zip(g['texts'], g['evidence']))
        slots, _, reason = select_guarded_inputs(g['originalTop8'], evidence)
        if reason or slots != g['slots']: raise ValueError('Changed personal/source eligibility')
        values = centered_values(g['context'], g['texts'], records)
        if values is None: skipped += 1; continue
        if type(g['gold']) is not int or not 0 <= g['gold'] < len(values): raise ValueError('Invalid training label')
        eligible.append(g); xs.extend(values); baselines.extend(e['baseline'] for e in g['evidence'])
        boundaries.append(len(xs)); gold.append(g['gold'])
    if not eligible or not all(math.isfinite(b) for b in baselines): raise ValueError('Invalid fitting data')
    data = (csr_matrix(np.asarray(xs).reshape(-1,1)), np.asarray(baselines), np.asarray(boundaries), np.asarray(gold))
    initial = np.zeros(1); history = []
    fitted = minimize(objective, initial, args=(*data,.001), jac=True, method='L-BFGS-B', bounds=[(0.,4.)],
        callback=lambda w: history.append(objective(w,*data,.001)[0]),
        options=dict(maxiter=200,ftol=1e-10,gtol=1e-7))
    return float(fitted.x[0]), eligible, dict(groups=len(eligible), skippedOovGroups=skipped, rows=len(xs),
        converged=bool(fitted.success), iterations=int(fitted.nit), message=str(fitted.message),
        initialLoss=objective(initial,*data,.001)[0], finalLoss=float(fitted.fun), lossHistory=history)
