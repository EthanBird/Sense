"""Independent E55 rescoring check from raw winners and count evidence (no fit)."""
import argparse
from collections import Counter, defaultdict
import gzip
import hashlib
import json
import math
from pathlib import Path
import struct
import unicodedata


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text('utf-8'))


def han(text):
    return bool(text) and all(unicodedata.name(c, '').startswith(
        ('CJK UNIFIED IDEOGRAPH-', 'CJK COMPATIBILITY IDEOGRAPH-')) for c in text)


def tokenize(text, weights, unknown):
    # Ascending endpoint traversal with >= is equivalent to longest-first ties.
    suffix = [(0., []) for _ in range(len(text) + 1)]
    for start in reversed(range(len(text))):
        best, path = -math.inf, None
        for end in range(start + 1, min(len(text), start + 8) + 1):
            word = text[start:end]
            if word not in weights and end != start + 1: continue
            score = weights.get(word, unknown) + suffix[end][0]
            if score >= best: best, path = score, [word] + suffix[end][1]
        suffix[start] = best, path
    return suffix[0][1]


def edit_distance(left, right):
    previous = list(range(len(right) + 1))
    for i, char in enumerate(left, 1):
        current = [i]
        for j, other in enumerate(right, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j-1] + (char != other)))
        previous = current
    return previous[-1]


def metrics(rows):
    ranks = [c.index(gold) + 1 if gold in c else 0 for c, gold in rows]
    return dict(states=len(rows), top1=sum(r == 1 for r in ranks), top5=sum(0 < r <= 5 for r in ranks),
        top10=sum(0 < r <= 10 for r in ranks), recall255=sum(r > 0 for r in ranks),
        characterErrors=sum(edit_distance(c[0] if c else '', gold) for c, gold in rows),
        characters=sum(len(gold) for _, gold in rows))


def run(root, out):
    own_sha = sha(Path(__file__)); e53, e54 = out.parent / 'e53', out.parent / 'e54'
    lock = read(out / 'pre-run-lock.json'); freeze = read(out / 'pre-dev-freeze.json')
    for name, digest in lock['sourceFiles'].items():
        assert sha(root / name) == digest, name
    for name, digest in lock['inputs'].items(): assert sha(Path(name)) == digest, name
    assert sha(root / 'benchmarks/corpus/e55-aligned-word-context-policy.json') == lock['policySha256'] == freeze['policySha256']
    assert sha(out / 'tokenizer.json') == freeze['tokenizerSha256']
    assert sha(e54 / 'association-table.json') == freeze['tableSha256']
    model = read(out / 'tokenizer.json'); weights = dict(model['entries'])
    path_count = 0
    with (e54 / 'background-segments.jsonl').open(encoding='utf-8') as f:
        for line in f:
            row = json.loads(line)
            assert tokenize(row['text'], weights, model['unknown']) == row['words']
            path_count += 1
    assert path_count == 85502
    counts = read(e54 / 'association-table.json')
    direct, totals = defaultdict(float), {}
    for encoded, count, total, *_ in counts['entries']:
        kind, left, _ = json.loads(encoded)
        direct[kind, left] += count - .75; totals[kind, left] = total
    backoffs = {key: 1. - value / totals[key] for key, value in direct.items()}
    ratios = {}
    for encoded, count, total, unigram, _, backoff, _ in counts['entries']:
        key = tuple(json.loads(encoded)); ns = counts['namespaces'][key[0]]
        prior = (unigram + .1) / (ns['unigramTotal'] + .1 * ns['vocabulary'])
        assert abs(backoff - backoffs[key[:2]]) < 1e-12
        ratios[key] = math.log(((count - .75) / total + backoffs[key[:2]] * prior) / prior)
    score_count = state_count = 0; max_error = 0.; reports = {}
    for domain in ['aishell', 'tatoeba']:
        with gzip.open(e53 / (domain + '-features.jsonl.gz'), 'rt', encoding='utf-8') as f:
            raw_rows = {(r['id'], r['cut']): r for line in f if (r := json.loads(line))['type'] == 'row'}
        for variant, fit in freeze['fits'].items():
            assert sha(out / (variant + '-fit.json')) == fit['fitSha256']
            aligned, signed = variant.startswith('aligned'), variant.endswith('-signed')
            details = read(out / (variant + '-' + domain + '-details.json'))
            assert {(r['id'], r['cut']) for r in details} == raw_rows.keys() and len(details) == len(raw_rows)
            before, after = [], []
            for row in details:
                raw = raw_rows[row['id'], row['cut']]
                winners = [raw['pool'][i] for i in raw['winnerIndices']]
                original = [c['text'] for c in winners]; proposed = original.copy()
                assert original == row['beforeCandidates'] and raw['expected'] == row['expected']
                slots = ([i for i, t in enumerate(original[:8]) if len(t) == len(original[0]) and han(t)]
                         if original and 2 <= len(original[0]) <= 24 and han(original[0]) else [])
                reason = ('fewer-than-two-eligible' if len(slots) < 2 else
                          'protected-source' if any(winners[i]['kind'] not in ['BASE_EXACT', 'BASE_COMPOSED'] for i in slots) else
                          'personalized-path' if any(any(winners[i]['features'][j] != 0 for j in [9, 10, 11]) or
                              any(e[2] is None for e in winners[i]['edges']) for i in slots) else 'scored')
                assert row['metadata']['reason'] == reason
                if reason == 'scored':
                    scores = []
                    for i in slots:
                        text, candidate = original[i], winners[i]
                        words = (tokenize(text, weights, model['unknown']) if aligned else [e[0] for e in candidate['edges']])
                        pairs = Counter(('W2', a, b) for a, b in zip(words, words[1:]))
                        if raw['context']: pairs['CW', raw['context'], words[0]] += 1
                        terms = []
                        for key, count in pairs.items():
                            ratio = ratios.get(key, math.log(backoffs.get(key[:2], 1.)) if signed else 0.)
                            terms.append(max(-4. if signed else 0., min(4., ratio)) * (count / math.sqrt(len(text))))
                        x = math.fsum(terms)
                        baseline = struct.unpack('!f', struct.pack('!f', candidate['total']))[0]
                        scores.append(baseline + 8 * math.tanh(fit['weight'] * x / 8))
                    error = max(abs(a-b) for a,b in zip(scores, row['metadata']['scores']))
                    max_error = max(max_error, error); assert error < 1e-12
                    for slot, source in zip(slots, sorted(range(len(scores)), key=lambda j: (-scores[j], j))):
                        proposed[slot] = original[slots[source]]
                    score_count += len(scores)
                assert proposed == row['afterCandidates']
                assert (proposed.index(raw['expected']) + 1 if raw['expected'] in proposed else 0) == row['afterRank']
                before.append((original, raw['expected'])); after.append((proposed, raw['expected'])); state_count += 1
            comparison = read(out / (variant + '-' + domain + '-comparison.json'))
            assert metrics(before) == comparison['before']; assert metrics(after) == comparison['after']
            reports[variant + '-' + domain] = dict(before=metrics(before), after=metrics(after))
    result = dict(passed=True, independentlyTokenizedPaths=path_count, checkedStates=state_count,
        recomputedScores=score_count, maxScoreError=max_error, metrics=reports, auditScriptSha256=own_sha,
        scope='Independent fixed-output/tokenizer verification; no optimizer re-fit or Android claim.')
    with (out / 'independent-score-audit.json').open('x', encoding='utf-8', newline='\n') as f:
        json.dump(result, f, ensure_ascii=False, indent=2); f.write('\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'metrics'}))


if __name__ == '__main__':
    p = argparse.ArgumentParser(__doc__); p.add_argument('root', type=Path); p.add_argument('output', type=Path)
    a = p.parse_args(); run(a.root.resolve(), a.output.resolve())
