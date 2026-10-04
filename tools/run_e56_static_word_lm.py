"""Fixed E56 candidate-scoring/calibration pipeline; no Android asset mutation."""
import argparse
import datetime
import gzip
import json
import os
from pathlib import Path
import subprocess
import time

from aligned_word_context import select_guarded_inputs
from candidate_student import load_features
from context_ranker_cv import evaluate_groups
from fetch_e37_model import digest
from run_e37_rescoring import distribution, write_new
from run_e53_word_context import enrich_evaluation
from run_e55_aligned_context import add_provenance, evaluate
from static_word_lm_ranker import requests, write_requests, load_scores, fit, StaticWordScorer

SOURCES = ['word_lm_graph.h', 'word_lm_graph_test.cpp', 'static_word_lm_probe.cpp', 'build_static_word_lm_probe.sh',
    'static_word_lm_ranker.py', 'test_static_word_lm_ranker.py', 'run_e56_static_word_lm.py',
    'aligned_word_context.py', 'candidate_student.py', 'context_only_ranker.py', 'context_ranker_cv.py',
    'run_e55_aligned_context.py', 'run_e53_word_context.py', 'evaluate_cross_domain.py',
    'train_candidate_ranker.py', 'audit_score_features.py', 'masked_lm_rescore.py', 'run_e37_rescoring.py']


def read(path): return json.loads(path.read_text('utf-8'))


def linux(path):
    p = Path(path).resolve(); value = p.as_posix()
    if len(p.drive) != 2 or value[1:3] != ':/': raise ValueError('Expected local drive path for WSL')
    return '/mnt/' + value[0].lower() + value[2:]


def run_native(out, reference, name, workload):
    input_path = out / (name + '-requests.tsv'); output_path = out / (name + '-native.jsonl')
    write_requests(input_path, workload)
    command = ['wsl.exe', '-d', 'Ubuntu', '--', 'env',
        'LD_LIBRARY_PATH=' + linux(reference / 'root/usr/lib/x86_64-linux-gnu'),
        linux(out / 'static-word-lm'), linux(reference / 'current-data/zh_CN.lm'), linux(input_path)]
    write_new(out / (name + '-command.json'), dict(command=command, inputSha256=digest(input_path)))
    start = time.perf_counter()
    with output_path.open('xb') as stdout, (out / (name + '-stderr.log')).open('xb') as stderr:
        completed = subprocess.run(command, stdout=stdout, stderr=stderr, check=False)
    if completed.returncode: raise RuntimeError('Native process failed: ' + name)
    records = load_scores(output_path, workload)
    report = dict(requests=len(workload), totalSeconds=time.perf_counter()-start,
        inputSha256=digest(input_path), outputSha256=digest(output_path),
        timing=distribution([r['nanos'] for r in records.values()]),
        unknownRequests=sum(r['unknowns'] > 0 for r in records.values()),
        maximumStates=max(r['states'] for r in records.values()), maximumTransitions=max(r['transitions'] for r in records.values()))
    write_new(out / (name + '-run.json'), report)
    print('Native', name, json.dumps(report), flush=True)
    return records


def dev_requests(rows):
    groups = []
    for row in rows.values():
        slots, _, reason = select_guarded_inputs(row['candidates'], row['evidence'])
        if not reason:
            groups.append(dict(context=row['context'], texts=[row['candidates'][i] for i in slots]))
    return requests(groups)


def same_scores(before, after):
    if before.keys() != after.keys(): raise ValueError('Repeated native workload changed')
    for key in before:
        if {k: v for k, v in before[key].items() if k != 'nanos'} != {k: v for k, v in after[key].items() if k != 'nanos'}:
            raise ValueError('Native scorer order/state nondeterminism')


def run(root, out, reference):
    start = time.perf_counter(); e53, e55 = out.parent / 'e53', out.parent / 'e55'
    policy = root / 'benchmarks/corpus/e56-static-word-lm-policy.json'; specification = read(policy)
    previous = {s: read(root / f'benchmarks/results/{s}-evidence-manifest.json') for s in ['e53', 'e55']}
    pins = {}
    for stage, names in [('e53', ['aishell-features.jsonl.gz', 'tatoeba-features.jsonl.gz', 'current-diagnostics.jsonl.gz']),
                         ('e55', ['guarded-training-groups.jsonl', 'calibration-provenance.json'])]:
        for name in names:
            path = out.parent / stage / name
            if digest(path) != previous[stage]['files']['raw/' + name]['sha256']: raise ValueError('Changed inherited input')
            pins[str(path)] = digest(path)
    old_lock = read(root / '.artifacts/input-quality/e14/reference-lock.json')
    for name, entry in old_lock['externalPins'].items():
        if name.startswith('root/usr/lib/') and '.so.' in name:
            path = reference / name
            if digest(path) != entry['sha256']: raise ValueError('Changed native library: ' + name)
            pins[str(path)] = entry['sha256']
    model = reference / 'current-data/zh_CN.lm'; arpa = reference / 'current-data/lm_sc.arpa'
    if digest(model) != specification['model']['sha256'] or model.stat().st_size != specification['model']['bytes']:
        raise ValueError('Wrong native model')
    if digest(arpa) != specification['model']['sourceArpaSha256']: raise ValueError('Wrong source model')
    with arpa.open(encoding='utf-8') as f:
        head = ''.join(next(f) for _ in range(6))
    if 'ngram 3=2189434' not in head or 'ngram 4=' in head: raise ValueError('Trigram metadata changed')
    pins[str(model)] = digest(model); pins[str(arpa)] = digest(arpa)
    for name in ['static-word-lm', 'word-graph-test']:
        pins[str(out / name)] = digest(out / name)
    with gzip.open(e53 / 'aishell-features.jsonl.gz', 'rt', encoding='utf-8') as f:
        header = json.loads(next(f))
    current_sources = {p.relative_to(root).as_posix(): digest(p) for p in (root / 'core-input/src/main/kotlin').rglob('*.kt')}
    if header['sources'] != current_sources: raise ValueError('Inherited exports no longer match current decoder')
    for name, sha in header['assets'].items():
        if digest(root / 'ime-service/src/main/assets' / name) != sha: raise ValueError('Production assets changed')
    source_files = {'tools/' + name: digest(root / 'tools' / name) for name in SOURCES}
    lock = dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(), sourceFiles=source_files,
        policySha256=digest(policy), inputs=pins, corpusOverlapKnown=False, productionSources=current_sources,
        assets=header['assets'], dependencies={s: digest(root / f'benchmarks/results/{s}-evidence-manifest.json') for s in previous})
    write_new(out / 'pre-run-lock.json', lock)
    with (e55 / 'guarded-training-groups.jsonl').open(encoding='utf-8') as f:
        groups = [json.loads(line) for line in f]
    native = run_native(out, reference, 'train', requests(groups))
    weight, eligible, fitted = fit(groups, native)
    scorer = StaticWordScorer(native, weight); zero = StaticWordScorer(native, 0.)
    for g in eligible:
        if zero(g['context'], g['texts'], g['evidence']) != [e['baseline'] for e in g['evidence']]:
            raise ValueError('Zero residual changes baseline')
    predictions, training = evaluate_groups(eligible, scorer)
    write_new(out / 'scalar-fit.json', fitted | dict(weight=weight, training=training, zeroResidualExact=True))
    write_new(out / 'training-predictions.json', predictions)
    write_new(out / 'pre-dev-freeze.json', dict(weight=weight, fitSha256=digest(out / 'scalar-fit.json'),
        lockSha256=digest(out / 'pre-run-lock.json'), createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        trainOutputSha256=digest(out / 'train-native.jsonl')))
    print('Fit', json.dumps(fitted | dict(weight=weight, training=training)), flush=True)
    if not fitted['converged']:
        write_new(out / 'decision.json', dict(eligibleForFurtherValidation=False, reason='fit-convergence',productionChanged=False))
        return
    reports = {}
    for domain, folder in [('aishell','p2c-aishell-v7'), ('tatoeba','p2c-supervised-v6')]:
        export = e53 / (domain + '-features.jsonl.gz')
        rows, audit = load_features(root, root / 'benchmarks/corpus' / folder / 'dev.tsv', export)
        enrich_evaluation(rows, export); provenance = add_provenance(rows, export)
        workload = dev_requests(rows)
        native = run_native(out, reference, domain, workload)
        repeated = run_native(out, reference, domain + '-repeat', workload)
        same_scores(native, repeated)
        details, result = evaluate(rows, StaticWordScorer(native, weight))
        combined_cost = []; native_cost = []
        for row in details:
            if row['metadata']['reason'] in ['scored', 'unknown-character']:
                cost = sum(native[row['context'], row['beforeCandidates'][i]]['nanos'] for i in row['metadata']['slots'])
                combined_cost.append(cost + row['hostNanos']); native_cost.append(cost)
        result['nativeGroupTiming'] = distribution(native_cost)
        result['combinedHostCostProxy'] = distribution(combined_cost)
        result['deterministicNativeRepeat'] = True
        result['costBoundary'] = 'Sum of measured separate native requests plus Python reorder; excludes model load, JNI/IPC, original decoder and Android UI.'
        write_new(out / (domain + '-details.json'), details); write_new(out / (domain + '-comparison.json'), result)
        write_new(out / (domain + '-audit.json'), dict(export=audit,personal=provenance))
        reports[domain] = result
        print('Quality', domain, json.dumps({k: result[k] for k in ['before','after','nonregression','combinedHostCostProxy','reasons']}),flush=True)
    quality = (all(r['nonregression'] for r in reports.values())
               and sum(r['after']['top1'] - r['before']['top1'] for r in reports.values()) > 0)
    cost = all(r['combinedHostCostProxy'].get('p95Ms',float('inf')) <= 100 for r in reports.values())
    for name, sha in source_files.items():
        if digest(root / name) != sha: raise ValueError('Frozen source changed')
    for name, sha in pins.items():
        if digest(Path(name)) != sha: raise ValueError('Frozen input changed')
    if digest(policy) != lock['policySha256']: raise ValueError('Policy changed')
    decision = dict(eligibleForFurtherValidation=quality and cost, qualityGate=quality, hostCostProxyGate=cost,
        weight=weight, productionChanged=False, androidTested=False, modelRightsReviewComplete=False,
        goalComplete=False, totalSeconds=time.perf_counter()-start)
    write_new(out / 'decision.json',decision); print('Decision',json.dumps(decision),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__)
    p.add_argument('root',type=Path);p.add_argument('output',type=Path);p.add_argument('reference',type=Path)
    a=p.parse_args();run(a.root.resolve(),a.output.resolve(),a.reference.resolve())
