"""Bounded family-CV calibration; external dev is opened only after selection."""
import argparse
from collections import Counter
import datetime
import gzip
import json
from pathlib import Path
import subprocess
import time

from candidate_student import candidate_evidence, load_features, reorder_student, select_inputs
from context_only_ranker import ContextScorer
from context_ranker_cv import evaluate_groups, family_fold, fit_regularized, metrics as cv_metrics
from context_ranker_cv import prepare, restored_order, select_ridge, split_groups
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from run_e37_rescoring import distribution, validate_baseline, write_new

read = lambda p: json.loads(p.read_text('utf-8'))
now = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()


def attach_slots(group, row):
    winners = [row['pool'][i] for i in row['winnerIndices']]
    candidates = [r['text'] for r in winners]
    evidence = {c['text']: candidate_evidence(c) for c in winners[:8]}
    slots, items, reason = select_inputs(candidates, evidence)
    if (reason or (group['id'], group['cut']) != (row['id'], row['cut'])
            or group['context'] != row['context'] or group['resultSha256'] != row['resultSha256']
            or group['texts'] != [candidates[i] for i in slots] or group['evidence'] != items
            or group['texts'][group['gold']] != row['expected']):
        raise ValueError('Pinned group does not match original candidate slots')
    result = dict(group, slots=slots, originalTop8=candidates[:8])
    if restored_order(result, [e['baseline'] for e in items]) != candidates[:8]:
        raise ValueError('Zero residual changes original ordering')
    return result


def load_training(root, out):
    previous = out.parent/'e44'
    manifest = read(root/'benchmarks/results/e44-evidence-manifest.json')
    inputs = {}
    for name in ['training-groups.jsonl', 'training-audit.json', 'train/selected.json',
                 'train/pre-export-lock.json', 'train/expanded.tsv', 'train/attribution.jsonl']:
        path = previous/name
        if digest(path) != manifest['files']['raw/'+name]['sha256']:
            raise ValueError('Committed E44 input changed: '+name)
        inputs[str(path)] = digest(path)
    export = previous/'expanded-features.jsonl.gz'
    if digest(export) != manifest['externalFiles'][export.name]['sha256']:
        raise ValueError('Pinned full training export changed')
    inputs[str(export)] = digest(export)
    provenance = read(previous/'train/pre-export-lock.json')
    if not provenance['familiesDisjoint']:
        raise ValueError('Unqualified training partition')
    for name, sha in provenance['heldout'].items():
        if digest(root/name) != sha:
            raise ValueError('Held-out isolation source changed')
    old = [json.loads(l) for l in (previous/'training-groups.jsonl').read_text('utf-8').splitlines()]
    wanted = {(g['id'], g['cut']): g for g in old}
    if len(wanted) != len(old):
        raise ValueError('Duplicate training state')
    joined = {}; seen = set(); paths = 0; summary = None
    with gzip.open(export, 'rt', encoding='utf-8') as f:
        header = json.loads(next(f))
        sources = {p.relative_to(root).as_posix(): digest(p)
                   for p in (root/'core-input/src/main/kotlin').rglob('*.kt')}
        if header['sources'] != sources or header['inputSha256'] != digest(previous/'train/expanded.tsv'):
            raise ValueError('Training export is not current production')
        for name, sha in header['assets'].items():
            if digest(root/'ime-service/src/main/assets'/name) != sha:
                raise ValueError('Training asset changed')
        for line in f:
            row = json.loads(line)
            if summary is not None: raise ValueError('Data after summary')
            if row['type'] == 'summary': summary = row; continue
            key = (row['id'], row['cut'])
            if key in seen: raise ValueError('Duplicate export state')
            seen.add(key); paths += len(row['pool'])
            if key in wanted: joined[key] = attach_slots(wanted[key], row)
    audit = read(previous/'training-audit.json')
    if (joined.keys() != wanted.keys() or not summary or summary['rows'] != len(seen)
            or len(seen) != audit['states'] or paths != audit['actualPaths']
            or summary['paths'] != paths or summary['observationEquivalent'] is not True):
        raise ValueError('Incomplete training export join')
    groups = [joined[(g['id'],g['cut'])] for g in old]
    with (out/'cv-groups.jsonl').open('x', encoding='utf-8', newline='\n') as f:
        for group in groups: f.write(json.dumps(group, ensure_ascii=False, sort_keys=True)+'\n')
    families = {r['id']: r['group'] for r in read(previous/'train/selected.json') if not r['exclusion']}
    if any(g['id'] not in families for g in groups): raise ValueError('Missing training provenance')
    return groups, families, inputs, dict(eligibleStates=len(groups), originalStates=len(seen),
        actualPaths=paths, restoredTrueCandidateSlots=True, zeroResidualOrderExact=True,
        scope='Reused hash-pinned E44 full-path audit; reran current source/asset identity and every retained slot/evidence join, not all path arithmetic')


def label(ridge):
    return format(ridge, '.0e').replace('-', 'm').replace('+','p')


def run(root, out):
    policy = root/'benchmarks/corpus/e45-grouped-calibration-policy.json'; spec = read(policy)
    head = subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip()
    if subprocess.run(['git','merge-base','--is-ancestor',spec['sourceBaseCommit'],head], cwd=root).returncode:
        raise ValueError('Wrong source ancestry')
    groups, families, inputs, audit = load_training(root, out)
    seed, count = spec['folds']['seed'], spec['folds']['count']
    assignments = {k: dict(family=v, fold=family_fold(v,seed,count)) for k,v in sorted(families.items())}
    write_new(out/'fold-assignments.json', assignments)
    files = ['tools/run_e45_context_calibration.py','tools/context_ranker_cv.py','tools/test_context_ranker_cv.py',
             'tools/test_e45_slot_join.py','tools/context_only_ranker.py','tools/candidate_sparse_ranker.py',
             'tools/candidate_student.py','tools/masked_lm_rescore.py','tools/train_candidate_ranker.py',
             'tools/evaluate_cross_domain.py','tools/run_e37_rescoring.py','tools/audit_score_features.py',
             'tools/fetch_e37_model.py','tools/audit_sentence_corpus.py',policy.relative_to(root).as_posix()]
    write_new(out/'pre-cv-lock.json', dict(createdAt=now(), checkout=head, policy=spec,
        sourceFiles={p:digest(root/p) for p in files}, inputFiles=inputs,
        groupsSha256=digest(out/'cv-groups.jsonl'), assignmentsSha256=digest(out/'fold-assignments.json'),
        audit=audit, scope=spec['scope'], previousManifestSha256=digest(root/'benchmarks/results/e44-evidence-manifest.json')))
    pooled = {r:[] for r in spec['regularization']}; fits = {r:[] for r in pooled}
    for fold in range(count):
        train, validation = split_groups(groups, families, seed, fold, count)
        prepared = prepare(train, families)
        print(f'Fold {fold+1}/{count}: fit={len(train)}, validate={len(validation)}, parameters={len(prepared[0])}', flush=True)
        for ridge in spec['regularization']:
            stem = f'fold-{fold}-ridge-{label(ridge)}'
            model, fit = fit_regularized(train, families, ridge, prepared)
            records, scores = evaluate_groups(validation, ContextScorer(model))
            for row in records: row['fold'] = fold
            fit.update(fold=fold, validationStates=len(validation), validationMetrics=scores,
                       fittingFamilies=len({families[g['id']] for g in train}),
                       validationFamilies=len({families[g['id']] for g in validation}))
            write_new(out/(stem+'-model.json'), model); write_new(out/(stem+'-fit.json'), fit)
            write_new(out/(stem+'-validation.json'), records)
            pooled[ridge] += records; fits[ridge].append(fit)
            print(stem, json.dumps(dict(iterations=fit['iterations'],converged=fit['converged'],metrics=scores)), flush=True)
    reports = []
    expected = {(g['id'],g['cut']) for g in groups}
    for ridge, records in pooled.items():
        if len(records)!=len(groups) or {(r['id'],r['cut']) for r in records}!=expected:
            raise ValueError('Incomplete or duplicated out-of-fold predictions')
        scores = cv_metrics(records)
        reports.append(dict(ridge=ridge, allConverged=all(f['converged'] for f in fits[ridge]),
            metrics=scores, folds=[{k:f[k] for k in ['fold','validationStates','parameters','iterations','converged','validationMetrics']} for f in fits[ridge]],
            firstGains=[r for r in records if r['beforeRank']!=1 and r['afterRank']==1],
            firstLosses=[r for r in records if r['beforeRank']==1 and r['afterRank']!=1]))
    selected = select_ridge(reports)
    write_new(out/'cv-selection.json', dict(selectedRidge=selected, reports=reports, externalDevUsedForSelection=False))
    verify_lock(root,out)
    print('CV selected ridge:', selected, flush=True)
    if selected is None:
        write_new(out/'decision.json', dict(cvGate=False,selectedRidge=None,externalDevEvaluated=False,
            eligibleForFurtherValidation=False, productionChanged=False,reason='No predeclared ridge passed pooled out-of-fold quality/convergence gate'))
        return
    model, fit = fit_regularized(groups, families, selected)
    write_new(out/'model.json',model); write_new(out/'fit.json',fit)
    write_new(out/'pre-dev-freeze.json',dict(createdAt=now(),selectedRidge=selected,
        lockSha256=digest(out/'pre-cv-lock.json'),selectionSha256=digest(out/'cv-selection.json'),
        modelSha256=digest(out/'model.json'),fitSha256=digest(out/'fit.json')))
    if not fit['converged']:
        write_new(out/'decision.json',dict(cvGate=True,selectedRidge=selected,externalDevEvaluated=False,
            eligibleForFurtherValidation=False,productionChanged=False,reason='Selected full-data refit did not converge'))
        return
    evaluate(root,out)


def verify_lock(root,out):
    lock = read(out/'pre-cv-lock.json')
    for name,sha in lock['sourceFiles'].items():
        if digest(root/name)!=sha: raise ValueError('Frozen execution source changed: '+name)
    for name,sha in lock['inputFiles'].items():
        if digest(Path(name))!=sha: raise ValueError('Frozen training input changed: '+name)
    for name,key in [('cv-groups.jsonl','groupsSha256'),('fold-assignments.json','assignmentsSha256')]:
        if digest(out/name)!=lock[key]: raise ValueError('Frozen partition changed')
    return lock


def evaluate(root,out):
    verify_lock(root,out)
    freeze=read(out/'pre-dev-freeze.json')
    for name,key in [('pre-cv-lock.json','lockSha256'),('cv-selection.json','selectionSha256'),
                     ('model.json','modelSha256'),('fit.json','fitSha256')]:
        if digest(out/name)!=freeze[key]: raise ValueError('Frozen model selection changed')
    if not read(out/'fit.json')['converged']: raise ValueError('Unconverged selected refit')
    scorer=ContextScorer(read(out/'model.json')); reports={}
    sources={'aishell':root/'benchmarks/corpus/p2c-aishell-v7/dev.tsv',
             'tatoeba':root/'benchmarks/corpus/p2c-supervised-v6/dev.tsv',
             'diagnostic':root/'.artifacts/input-quality/e28/diagnostic.tsv'}
    dev_pins={}
    for domain,source in sources.items():
        export=out.parent/'e43'/(domain+'-features.jsonl.gz')
        rows,audit=load_features(root,source,export)
        dev_pins[str(source)]=digest(source);dev_pins[str(export)]=digest(export)
        if domain!='diagnostic':
            baseline=out.parent/'e43'/(domain+'-baseline.jsonl')
            _,before=validate_baseline(root,source,baseline);dev_pins[str(baseline)]=digest(baseline)
            if rows.keys()!=before.keys() or any(rows[k]['candidates']!=before[k]['candidates']
                or rows[k]['resultSha256']!=before[k]['resultSha256'] for k in rows): raise ValueError('Baseline mismatch')
        details=[];after=[];reasons=Counter();times=[]
        for row in rows.values():
            original=row['candidates'];start=time.perf_counter_ns()
            actual,meta=reorder_student(row['context'],original,row['evidence'],scorer)
            duration=time.perf_counter_ns()-start
            again,check=reorder_student(row['context'],original,row['evidence'],scorer)
            if actual!=again or meta!=check or sorted(actual)!=sorted(original): raise ValueError('Unstable scoring or membership')
            reasons[meta['reason']]+=1
            if meta['reason']=='scored': times.append(duration)
            rank=actual.index(row['expected'])+1 if row['expected'] in actual else 0
            after.append({**row,'candidates':actual,'rank':rank})
            details.append({k:row[k] for k in ['id','cut','mode','stratum','query','context','expected']}|
                dict(beforeRank=row['rank'],afterRank=rank,beforeCandidates=original,afterCandidates=actual,metadata=meta,hostNanos=duration))
        baseline,proposed=metrics(list(rows.values())),metrics(after)
        report=dict(before=baseline,after=proposed,nonregression=nonregression(baseline,proposed),
            reasons=dict(reasons),timing=distribution(times),audit=audit,candidateSetsUnchanged=True,repeatedScoresEqual=True,
            firstGains=[d for d in details if d['beforeRank']!=1 and d['afterRank']==1],
            firstLosses=[d for d in details if d['beforeRank']==1 and d['afterRank']!=1],
            rankLosses=[d for d in details if d['beforeRank'] and (not d['afterRank'] or d['afterRank']>d['beforeRank'])])
        write_new(out/(domain+'-details.json'),details);write_new(out/(domain+'-comparison.json'),report);reports[domain]=report
        print(domain,json.dumps({k:report[k] for k in ['before','after','nonregression','timing']}),flush=True)
    write_new(out/'external-input-pins.json',dev_pins)
    quality=all(reports[d]['nonregression'] for d in ['aishell','tatoeba']) and sum(reports[d]['after']['top1']-reports[d]['before']['top1'] for d in ['aishell','tatoeba'])>0
    cost=all(reports[d]['timing'].get('p95Ms',float('inf'))<=100 for d in ['aishell','tatoeba'])
    write_new(out/'decision.json',dict(cvGate=True,selectedRidge=freeze['selectedRidge'],externalDevEvaluated=True,
        qualityGate=quality,hostCostGate=cost,eligibleForFurtherValidation=quality and cost,productionChanged=False,
        modelSha256=digest(out/'model.json'),modelBytes=(out/'model.json').stat().st_size,
        scope='Known development sets; offline fixed-pool scoring, no Android/runtime adoption'))


if __name__=='__main__':
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('root',type=Path);parser.add_argument('output',type=Path)
    parser.add_argument('--evaluate-frozen',action='store_true')
    args=parser.parse_args()
    (evaluate if args.evaluate_frozen else run)(args.root.resolve(),args.output.resolve())
