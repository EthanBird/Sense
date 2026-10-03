"""Descriptive paired source-sentence bootstrap; related whole/midpoint rows stay together."""
import argparse
import hashlib
import json
from pathlib import Path
import random


def percentile(values, q):
    values = sorted(values); position = (len(values) - 1) * q
    lo = int(position); hi = min(lo + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (position - lo)


def summarize(report, seed=1313, replicates=10000):
    if replicates < 1000:
        raise ValueError('Use at least 1000 descriptive resamples')
    groups = {}; keys = set()
    before, after = report['before']['rows'], report['after']['rows']
    if not before or len(before) != len(after):
        raise ValueError('Missing or unmatched rows')
    for a, b in zip(before, after):
        key = (a['id'], a['cut'], a['mode'])
        if key in keys or key != (b['id'], b['cut'], b['mode']) or a['expected'] != b['expected']:
            raise ValueError('Mismatched paired labels or duplicate observations')
        keys.add(key)
        group = groups.setdefault(a['id'], [0, 0, 0, 0, 0])
        group[0] += int(b['rank'] == 1) - int(a['rank'] == 1)
        group[1] += int(0 < b['rank'] <= 5) - int(0 < a['rank'] <= 5)
        group[2] += b['characterErrors'] - a['characterErrors']
        group[3] += 1; group[4] += len(a['expected'])
    values = list(groups.values()); rng = random.Random(seed)
    draws = [[], [], []]
    for _ in range(replicates):
        totals = [0] * 5
        for _ in values:
            item = values[rng.randrange(len(values))]
            for i in range(5): totals[i] += item[i]
        draws[0].append(totals[0] / totals[3]); draws[1].append(totals[1] / totals[3])
        draws[2].append(totals[2] / totals[4])
    totals = [sum(g[i] for g in values) for i in range(5)]
    observed = [totals[0] / totals[3], totals[1] / totals[3], totals[2] / totals[4]]
    return dict(schemaVersion=1, scope='Post-test descriptive source-cluster bootstrap, not a new selection gate or a population guarantee',
        seed=seed, replicates=replicates, sourceSentences=len(groups), rows=len(before),
        sourceFirstGains=sum(g[0] > 0 for g in values), sourceFirstLosses=sum(g[0] < 0 for g in values),
        metrics={name: dict(observedDelta=observed[i], percentile95=[percentile(draws[i], .025), percentile(draws[i], .975)])
                 for i, name in enumerate(['top1', 'top5', 'cer'])},
        limitations=['Whole and midpoint states are dependent and resampled as one source sentence.',
                     'One prepared Tatoeba corpus and single-reference labels; intervals do not certify daily input quality.',
                     'Describes the already frozen model; no further weights are chosen from these results.'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('report', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args(); result = summarize(json.loads(args.report.read_text('utf-8')))
    result['inputSha256'] = hashlib.sha256(args.report.read_bytes()).hexdigest()
    with args.output.open('x', encoding='utf-8') as out:
        json.dump(result, out, ensure_ascii=False, indent=2); out.write('\n')
    print(json.dumps(result, ensure_ascii=False))
