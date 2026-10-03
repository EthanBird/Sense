"""Train a bounded six-feature pairwise reranker on a frozen Chinese candidate pool.

No runtime weight installation. References label pairs only; they never select lexical paths.
The optimizer is projected batch gradient descent, not a reproduction of neural RankNet.
"""
from __future__ import annotations
import argparse
from collections import Counter
import gzip
import hashlib
import json
import math
from pathlib import Path
from audit_score_features import FEATURES, PRIORITY, check_row, f32

GROUPS = {
    'LEXICAL': ['LOG_FREQUENCY', 'FALLBACK_TIER', 'UNIGRAM_MASS', 'NORMALIZATION_ANCHOR'],
    'INTERNAL_BIGRAM': ['INTERNAL_BIGRAM'], 'WORD_BOUNDARY': ['WORD_BOUNDARY'],
    'CHARACTER_LM': ['CHARACTER_LM'], 'EXTERNAL_BIGRAM': ['EXTERNAL_BIGRAM'], 'SPELLING': ['SPELLING'],
}
ZERO = [0.0] * len(GROUPS)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text('utf-8'))


def write_new(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')


def vector(path):
    # Calibration anchor and lexical mass are bound, not fitted as independent length knobs.
    return [math.fsum(path['features'][FEATURES.index(name)] for name in group) for group in GROUPS.values()]


def load_export(path, input_tsv):
    expected = {}
    for line in input_tsv.read_text('utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        identity, query, text, stratum, units = line.split('\t')
        units = units.split()
        if len(units) != len(text) or ''.join(units) != query:
            raise ValueError('Invalid aligned input')
        for cut in sorted({0, len(text) // 2}):
            mode = 'empty' if cut == 0 else 'editor'
            expected[(identity, cut, mode)] = dict(query=''.join(units[cut:]), expected=text[cut:],
                context=text[max(0, cut - 2):cut], stratum=stratum)
    rows = []; keys = set(); paths = 0; max_error = 0; summary = None
    with gzip.open(path, 'rt', encoding='utf-8') as stream:
        header = json.loads(next(stream))
        if (header.get('featureVersion') != 'actual-path-v1' or header.get('mode') != 'sampled'
                or header.get('features') != FEATURES or header.get('inputSha256') != sha(input_tsv)
                or header.get('configuration') != dict(lmWeight=.5, oovFeature=-4, correctionBoost=12, limit=255)):
            raise ValueError('Wrong export version, configuration, or input pin')
        for line in stream:
            row = json.loads(line)
            if summary is not None:
                raise ValueError('Data after summary')
            if row['type'] == 'summary':
                summary = row
                continue
            key = (row['id'], row['cut'], row['mode'])
            if key in keys or key not in expected or any(row[k] != v for k, v in expected[key].items()):
                raise ValueError('Changed sampled workload or labels')
            keys.add(key); paths += len(row['pool']); max_error = max(max_error, check_row(row))
            pool = []
            for index in row['winnerIndices']:
                candidate = row['pool'][index]
                if any(candidate['features'][FEATURES.index(name)] != 0 for name in
                       ['PERSONAL_BASE', 'PERSONAL_FLOOR', 'PERSONAL_ADJUSTMENT']):
                    raise ValueError('This experiment requires an empty personal store')
                pool.append(dict(text=candidate['text'], kind=candidate['kind'],
                    baseline=f32(candidate['total']), vector=vector(candidate), originalPath=index))
            row = {k: row[k] for k in ['id', 'cut', 'mode', 'stratum', 'query', 'context', 'expected', 'rank']}
            row['pool'] = pool
            rows.append(row)
    if (not summary or summary['rows'] != len(rows) or summary['paths'] != paths
            or not summary['observationEquivalent'] or keys != set(expected)):
        raise ValueError('Incomplete export or mismatched coverage')
    # The actual UI rank can include an English suggestion. This experiment ranks Chinese only.
    if any(order(row, ZERO) != list(range(len(row['pool']))) for row in rows):
        raise ValueError('Zero delta fails to preserve exact baseline ordering')
    return header, rows, dict(rows=len(rows), paths=paths, maxArithmeticError=max_error,
                             completeWorkloadChecked=True, zeroDeltaEquivalent=True)


def order(row, delta):
    def key(index):
        c = row['pool'][index]
        total = c['baseline'] + math.fsum(a * b for a, b in zip(delta, c['vector']))
        return (-total, PRIORITY[c['kind']], len(c['text']), c['text'].encode('utf-16-be'))
    return sorted(range(len(row['pool'])), key=key)


def build_pairs(rows, negative_limit):
    groups = []
    for row in rows:
        gold = next((c for c in row['pool'] if c['text'] == row['expected']), None)
        if gold is None:
            continue
        negatives = [c for c in row['pool'] if c['text'] != row['expected']][:negative_limit]
        if negatives:
            groups.append([(n['baseline'] - gold['baseline'],
                [a - b for a, b in zip(n['vector'], gold['vector'])]) for n in negatives])
    if not groups:
        raise ValueError('No recalled positive/negative pairs')
    return [(gap, diff, 1.0 / (len(groups) * len(group))) for group in groups for gap, diff in group], len(groups)


def objective(delta, pairs, regularization):
    loss = regularization * math.fsum(x * x for x in delta) / 2
    gradient = [regularization * x for x in delta]
    for gap, diff, weight in pairs:
        z = gap + math.fsum(a * b for a, b in zip(delta, diff))
        loss += weight * (max(z, 0.0) + math.log1p(math.exp(-abs(z))))
        p = 1.0 / (1.0 + math.exp(-z)) if z >= 0 else math.exp(z) / (1 + math.exp(z))
        for i, value in enumerate(diff):
            gradient[i] += weight * p * value
    return loss, gradient


def fit(pairs, regularization, policy):
    delta = ZERO.copy(); step = policy['initialStep']; history = []; converged = False
    lower, upper = policy['deltaBounds']
    for iteration in range(policy['maxIterations']):
        loss, gradient = objective(delta, pairs, regularization)
        for _ in range(60):
            proposed = [min(upper, max(lower, x - step * g)) for x, g in zip(delta, gradient)]
            diff = [a - b for a, b in zip(proposed, delta)]
            new_loss, _ = objective(proposed, pairs, regularization)
            bound = loss + math.fsum(g * d for g, d in zip(gradient, diff)) + math.fsum(d * d for d in diff) / (2 * step)
            if new_loss <= bound + 1e-12:
                break
            step *= .5
        else:
            raise ValueError('Optimizer line search exhausted')
        delta = proposed
        history.append(dict(iteration=iteration + 1, objective=new_loss, step=step,
                            projectedGradient=max(abs(d) for d in diff) / step))
        if history[-1]['projectedGradient'] < policy['tolerance']:
            converged = True
            break
        step = min(policy['initialStep'], step * 1.2)
    return dict(delta=delta, multipliers=[1 + x for x in delta], regularization=regularization,
                converged=converged, iterations=len(history), history=history)


def distance(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def evaluate(rows, delta):
    results = []
    for row in rows:
        ranked = [row['pool'][i] for i in order(row, delta)]
        rank = next((i + 1 for i, c in enumerate(ranked) if c['text'] == row['expected']), 0)
        results.append({k: row[k] for k in ['id', 'cut', 'mode', 'stratum', 'query', 'context', 'expected']} |
                       dict(rank=rank, first=ranked[0]['text'], top5=[c['text'] for c in ranked[:5]],
                            characterErrors=distance(ranked[0]['text'], row['expected'])))
    def metrics(values):
        return dict(count=len(values), first=sum(r['rank'] == 1 for r in values),
            top5=sum(0 < r['rank'] <= 5 for r in values), oracleRecall=sum(r['rank'] > 0 for r in values),
            mrr=sum(1 / r['rank'] if r['rank'] else 0 for r in values) / len(values),
            cer=sum(r['characterErrors'] for r in values) / sum(len(r['expected']) for r in values))
    return dict(overall=metrics(results), byMode={m: metrics([r for r in results if r['mode'] == m])
                for m in sorted({r['mode'] for r in results})}, rows=results)


def compare(before, after):
    gains = []; losses = []; rank_losses = []; changes = []
    for a, b in zip(before['rows'], after['rows']):
        if (a['id'], a['cut'], a['mode']) != (b['id'], b['cut'], b['mode']):
            raise ValueError('Evaluation identities differ')
        if (a['rank'], a['first']) != (b['rank'], b['first']):
            change = dict(id=a['id'], cut=a['cut'], mode=a['mode'], context=a['context'], query=a['query'],
                expected=a['expected'], beforeRank=a['rank'], afterRank=b['rank'], beforeFirst=a['first'], afterFirst=b['first'])
            changes.append(change)
            if a['rank'] != 1 and b['rank'] == 1: gains.append(change)
            if a['rank'] == 1 and b['rank'] != 1: losses.append(change)
            if (b['rank'] or 256) > (a['rank'] or 256): rank_losses.append(change)
    return dict(firstGains=gains, firstLosses=losses, rankLosses=rank_losses, changedRows=changes)


def fit_stage(args):
    policy = read(args.policy); train_manifest = read(args.train_manifest); frozen = read(args.frozen)
    if policy['features'] != list(GROUPS) or policy['deltaBounds'][0] <= -1:
        raise ValueError('Changed feature/positive-multiplier contract')
    train_tsv = args.train_manifest.parent / 'train.tsv'; dev_tsv = args.frozen.parent / 'dev.tsv'
    if sha(train_tsv) != train_manifest['trainSha256'] or sha(dev_tsv) != frozen['outputs']['dev']['sha256']:
        raise ValueError('Changed prepared data')
    if args.output.exists():
        raise ValueError('Retain previous fitting evidence')
    th, train, train_audit = load_export(args.train, train_tsv)
    dh, dev, dev_audit = load_export(args.dev, dev_tsv)
    if th['sources'] != dh['sources'] or th['assets'] != dh['assets']:
        raise ValueError('Training/development decoder drift')
    pairs, eligible = build_pairs(train, policy['negativeLimit'])
    before = evaluate(dev, ZERO); trained = []; eligible_models = []
    for regularization in policy['regularization']:
        model = fit(pairs, regularization, policy)
        measured = evaluate(dev, model['delta'])
        model['development'] = {k: v for k, v in measured.items() if k != 'rows'}
        model['changes'] = compare(before, measured)
        model['training'] = evaluate(train, model['delta'])['overall']
        trained.append(model)
        if measured['overall']['first'] > before['overall']['first'] and measured['overall']['top5'] >= before['overall']['top5']:
            eligible_models.append(model)
        print(json.dumps(dict(regularization=regularization, iterations=model['iterations'], converged=model['converged'],
            delta=model['delta'], development=measured['overall']), ensure_ascii=False), flush=True)
    chosen = max(eligible_models, key=lambda m: (m['development']['overall']['first'], m['development']['overall']['top5'],
                 m['development']['overall']['mrr'], m['regularization'])) if eligible_models else None
    args.output.mkdir(parents=True)
    report = dict(schemaVersion=1, scope='Chinese fixed-baseline-path reranking only; not runtime or natural typing accuracy',
        trainAudit=train_audit, developmentAudit=dev_audit, trainQueries=len(train), recalledTrainingQueries=eligible,
        pairs=len(pairs), trainBaseline=evaluate(train, ZERO)['overall'], developmentBaseline=before,
        models=trained, selectedRegularization=chosen['regularization'] if chosen else None,
        delta=chosen['delta'] if chosen else ZERO, decision='candidate-selected-for-test' if chosen else 'retain-baseline',
        policySha256=sha(args.policy), trainExportSha256=sha(args.train), devExportSha256=sha(args.dev),
        trainManifestSha256=sha(args.train_manifest), dataFrozenSha256=sha(args.frozen),
        trainerSha256=sha(Path(__file__)), featureGroups=GROUPS, productionChanged=False)
    write_new(args.output / 'development.json', report)
    write_new(args.output / 'frozen-model.json', dict(schemaVersion=1, featureVersion='actual-path-v1', featureGroups=GROUPS,
        delta=report['delta'], selectedRegularization=report['selectedRegularization'], decision=report['decision'],
        policy=policy, policySha256=sha(args.policy), sources=th['sources'], assets=th['assets'],
        trainerSha256=sha(Path(__file__)), auditSha256=sha(Path(__file__).with_name('audit_score_features.py')),
        trainExportSha256=sha(args.train), devExportSha256=sha(args.dev),
        developmentSha256=sha(args.output / 'development.json'), dataFrozenSha256=sha(args.frozen),
        testInputSha256=frozen['outputs']['test']['sha256'],
        scope='Freeze before evaluating test candidates. Offline weights only; no APK or search promotion.'))


def test_stage(args):
    frozen = read(args.model)
    if frozen['decision'] != 'candidate-selected-for-test':
        raise ValueError('No development-selected candidate')
    if frozen['trainerSha256'] != sha(Path(__file__)) or frozen['auditSha256'] != sha(Path(__file__).with_name('audit_score_features.py')):
        raise ValueError('Changed evaluation implementation after freeze')
    if sha(args.input) != frozen['testInputSha256']:
        raise ValueError('Changed frozen test input')
    header, rows, audit = load_export(args.export, args.input)
    if header['sources'] != frozen['sources'] or header['assets'] != frozen['assets']:
        raise ValueError('Changed decoder or assets after freeze')
    before = evaluate(rows, ZERO); after = evaluate(rows, frozen['delta'])
    passed = (after['overall']['first'] > before['overall']['first'] and after['overall']['top5'] >= before['overall']['top5']
              and all(after['byMode'][m]['first'] >= v['first'] for m, v in before['byMode'].items()))
    report = dict(schemaVersion=1, scope='Independent sentence families; fixed Chinese pool and winning lexical paths; not runtime acceptance',
        frozenModelSha256=sha(args.model), exportSha256=sha(args.export), audit=audit, before=before, after=after,
        **compare(before, after), passedOfflineGate=passed, productionChanged=False, releaseReady=False, goalComplete=False)
    write_new(args.output, report)
    print(json.dumps(dict(before=before['overall'], after=after['overall'], passedOfflineGate=passed,
        gains=len(report['firstGains']), losses=len(report['firstLosses']), rankLosses=len(report['rankLosses'])), ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__); sub = parser.add_subparsers(dest='command', required=True)
    command = sub.add_parser('fit')
    for name in ('train', 'dev', 'policy', 'train_manifest', 'frozen', 'output'): command.add_argument(name, type=Path)
    command = sub.add_parser('test')
    for name in ('model', 'export', 'input', 'output'): command.add_argument(name, type=Path)
    args = parser.parse_args()
    (fit_stage if args.command == 'fit' else test_stage)(args)
