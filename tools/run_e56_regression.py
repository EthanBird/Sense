"""Broader known regressions after E56 scalar freeze, without re-fitting."""
import argparse
import datetime
import json
from pathlib import Path
import subprocess

from candidate_student import load_features
from fetch_e37_model import digest
from run_e37_rescoring import write_new, distribution
from run_e53_word_context import enrich_evaluation
from run_e55_aligned_context import add_provenance, evaluate
from run_e56_static_word_lm import read, run_native, dev_requests, same_scores
from static_word_lm_ranker import StaticWordScorer

WORKLOADS = [('aishell-test', 'benchmarks/corpus/p2c-aishell-v7/test.tsv'),
             ('tatoeba-test', 'benchmarks/corpus/p2c-supervised-v6/test.tsv'),
             ('daily-mixture-test', 'benchmarks/corpus/p2c-daily-mixture-v8/test.tsv'),
             ('known-diagnostic', '.artifacts/input-quality/e28/diagnostic.tsv')]


def export_prefix(command):
    main = 'io.github.ethanbird.senseime.core.M19ScoreFeatureBenchmark'
    if command.count(main) != 1 or command.count('-cp') != 1:
        raise ValueError('Wrong export entrypoint or classpath')
    end = command.index(main)
    if command.index('-cp') + 2 != end:
        raise ValueError('Unexpected export launch structure')
    return command[:end + 1]


def run(root, out, reference):
    run_lock = read(out / 'pre-run-lock.json'); freeze = read(out / 'pre-dev-freeze.json')
    if not read(out / 'decision.json')['eligibleForFurtherValidation']: raise ValueError('Development gate failed')
    for path, sha in run_lock['sourceFiles'].items():
        if digest(root / path) != sha: raise ValueError('Changed frozen scorer')
    for path, sha in run_lock['inputs'].items():
        if digest(Path(path)) != sha: raise ValueError('Changed frozen input')
    if digest(out / 'scalar-fit.json') != freeze['fitSha256']: raise ValueError('Changed scalar fit')
    jar = out.parent / 'e53/baseline-core.jar'
    if digest(jar) != '4c40a4b7e65de845c51db3323f1a30893c8048a6acf6ea2736d1d9838b5b6272':
        raise ValueError('Changed current decoder bytecode')
    old_command = read(out.parent / 'e53/export-lock.json')['commands']['aishell']
    prefix = export_prefix(old_command)
    commands = {}
    for name, source in WORKLOADS:
        commands[name] = prefix + [str(root), str(root / source), str(out / (name + '-features.jsonl.gz')), 'sampled']
    policy = dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        weight=freeze['weight'], fitSha256=freeze['fitSha256'], preDevFreezeSha256=digest(out / 'pre-dev-freeze.json'),
        scriptSha256=digest(Path(__file__)), workloads={s:digest(root/s) for _,s in WORKLOADS}, commands=commands,
        gate='Each of the three known larger suites must retain top1/top5/recall and not increase character errors; combined top1 must grow. Diagnostic must retain correct first choices. No refit, no sweep.',
        dataBoundary='Previously opened regression corpora, partially overlapping domains; no fresh heldout or upstream corpus independence claim.')
    write_new(out / 'regression-freeze.json',policy)
    reports = {}
    for name, source in WORKLOADS:
        print('Exporting regression',name,flush=True)
        with (out / (name + '-export.log')).open('xb') as log:
            result = subprocess.run(commands[name],cwd=root,stdout=log,stderr=subprocess.STDOUT,check=False)
        if result.returncode: raise RuntimeError('Current decoder export failed: ' + name)
        export = out / (name + '-features.jsonl.gz')
        rows,audit = load_features(root,root/source,export)
        enrich_evaluation(rows,export); personal = add_provenance(rows,export)
        native = run_native(out,reference,name,dev_requests(rows))
        repeat = run_native(out,reference,name+'-repeat',dev_requests(rows));same_scores(native,repeat)
        details,report = evaluate(rows,StaticWordScorer(native,freeze['weight']))
        costs=[]
        for row in details:
            if row['metadata']['reason'] in ['scored','unknown-character']:
                costs.append(row['hostNanos'] + sum(native[row['context'],row['beforeCandidates'][i]]['nanos']
                                                   for i in row['metadata']['slots']))
        report['combinedHostCostProxy']=distribution(costs);report['deterministicNativeRepeat']=True
        write_new(out/(name+'-details.json'),details);write_new(out/(name+'-comparison.json'),report)
        write_new(out/(name+'-audit.json'),dict(export=audit,personal=personal))
        reports[name]=report
        print('Regression',name,json.dumps({k:report[k] for k in ['before','after','nonregression','combinedHostCostProxy','reasons']}),flush=True)
    suite=[reports[n] for n,_ in WORKLOADS if n!='known-diagnostic']
    quality=all(r['nonregression'] for r in suite) and sum(r['after']['top1']-r['before']['top1'] for r in suite)>0
    diagnostic=not reports['known-diagnostic']['firstLosses']
    costs=all(r['combinedHostCostProxy'].get('p95Ms',float('inf'))<=100 for r in reports.values())
    for path,sha in policy['workloads'].items():
        if digest(root/path)!=sha:raise ValueError('Regression workload changed')
    if digest(out/'scalar-fit.json')!=policy['fitSha256']:raise ValueError('Scalar changed during regression')
    decision=dict(qualityGate=quality,diagnosticFirstGate=diagnostic,costProxyGate=costs,
        eligibleForAndroidPrototype=quality and diagnostic and costs, productionChanged=False,androidTested=False,
        modelRightsReviewComplete=False,goalComplete=False)
    write_new(out/'regression-decision.json',decision);print('Regression decision',json.dumps(decision),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__)
    p.add_argument('root',type=Path);p.add_argument('output',type=Path);p.add_argument('reference',type=Path)
    a=p.parse_args();run(a.root.resolve(),a.output.resolve(),a.reference.resolve())
