"""Replay source-family CV and external decisions; retain all gains and losses."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

from candidate_student import load_features, reorder_student
from context_only_ranker import ContextScorer, feature_vocabulary
from context_ranker_cv import evaluate_groups, family_fold, metrics as cv_metrics, select_ridge, split_groups
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from run_e37_rescoring import write_new
from run_e45_context_calibration import label, now, verify_lock

read=lambda p:json.loads(p.read_text('utf-8'))


def collect(root,out):
    lock=verify_lock(root,out);spec=lock['policy'];count=spec['folds']['count'];seed=spec['folds']['seed']
    groups=[json.loads(l) for l in (out/'cv-groups.jsonl').read_text('utf-8').splitlines()]
    assigned=read(out/'fold-assignments.json');families={k:v['family'] for k,v in assigned.items()}
    selected=read(out.parent/'e44/train/selected.json')
    if families!={r['id']:r['group'] for r in selected if not r['exclusion']}:
        raise ValueError('CV family mapping differs from source provenance')
    for entry in assigned.values():
        if entry['fold']!=family_fold(entry['family'],seed,count):raise ValueError('Changed fold assignment')
    pooled={r:[] for r in spec['regularization']};fits={r:[] for r in pooled}
    for fold in range(count):
        train,validation=split_groups(groups,families,seed,fold,count)
        keys=feature_vocabulary(train,families)
        for ridge in pooled:
            stem=f'fold-{fold}-ridge-{label(ridge)}'
            model=read(out/(stem+'-model.json'));fit=read(out/(stem+'-fit.json'))
            if model['vocabulary']!=keys:raise ValueError('Vocabulary includes non-fitting-family features')
            records,scores=evaluate_groups(validation,ContextScorer(model))
            for record in records:record['fold']=fold
            if records!=read(out/(stem+'-validation.json')):raise ValueError('Held-out scores or slots changed')
            if (fit['validationMetrics']!=scores or fit['ridge']!=ridge or fit['fold']!=fold
                    or fit['groups']!=len(train) or fit['parameters']!=len(keys)
                    or fit['validationStates']!=len(validation)
                    or fit['fittingFamilies']!=len({families[g['id']] for g in train})
                    or fit['validationFamilies']!=len({families[g['id']] for g in validation})):
                raise ValueError('Fitting/validation identity changed')
            pooled[ridge]+=records;fits[ridge].append(fit)
    reports=[]
    expected={(g['id'],g['cut']) for g in groups}
    for ridge,records in pooled.items():
        if len(records)!=len(groups) or {(r['id'],r['cut']) for r in records}!=expected:
            raise ValueError('Each eligible state must have exactly one OOF prediction')
        reports.append(dict(ridge=ridge,allConverged=all(f['converged'] for f in fits[ridge]),metrics=cv_metrics(records),
            folds=[{k:f[k] for k in ['fold','validationStates','parameters','iterations','converged','validationMetrics']} for f in fits[ridge]],
            firstGains=[r for r in records if r['beforeRank']!=1 and r['afterRank']==1],
            firstLosses=[r for r in records if r['beforeRank']==1 and r['afterRank']!=1]))
    chosen=select_ridge(reports);selection=read(out/'cv-selection.json')
    if selection!=dict(selectedRidge=chosen,reports=reports,externalDevUsedForSelection=False):
        raise ValueError('CV decision differs from prescribed selector')
    decision=read(out/'decision.json');external={}
    if decision['selectedRidge']!=chosen or decision['cvGate']!=(chosen is not None):
        raise ValueError('Decision changed selected ridge')
    if chosen is None:
        if decision['externalDevEvaluated'] or decision['eligibleForFurtherValidation'] or (out/'pre-dev-freeze.json').exists():
            raise ValueError('Failed CV must not fall through to external evaluation')
    else:
        freeze=read(out/'pre-dev-freeze.json')
        for name,key in [('pre-cv-lock.json','lockSha256'),('cv-selection.json','selectionSha256'),('model.json','modelSha256'),('fit.json','fitSha256')]:
            if digest(out/name)!=freeze[key]:raise ValueError('Final freeze changed')
        model=read(out/'model.json');fit=read(out/'fit.json')
        if (model['vocabulary']!=feature_vocabulary(groups,families) or fit['ridge']!=chosen
                or fit['groups']!=len(groups) or freeze['selectedRidge']!=chosen):raise ValueError('Final refit inputs differ')
        if not fit['converged']:
            if decision['externalDevEvaluated'] or decision['eligibleForFurtherValidation']:raise ValueError('Unconverged refit continued')
        else:
            if not decision['externalDevEvaluated']:raise ValueError('Missing prescribed external evaluation')
            for name,sha in read(out/'external-input-pins.json').items():
                if digest(Path(name))!=sha:raise ValueError('External evaluation input changed')
            scorer=ContextScorer(model)
            sources={'aishell':root/'benchmarks/corpus/p2c-aishell-v7/dev.tsv',
                     'tatoeba':root/'benchmarks/corpus/p2c-supervised-v6/dev.tsv',
                     'diagnostic':root/'.artifacts/input-quality/e28/diagnostic.tsv'}
            for domain,source in sources.items():
                rows,_=load_features(root,source,out.parent/'e43'/(domain+'-features.jsonl.gz'))
                detail=read(out/(domain+'-details.json'));after=[]
                if [(d['id'],d['cut']) for d in detail]!=list(rows):raise ValueError('Incomplete external states')
                for d in detail:
                    row=rows[(d['id'],d['cut'])]
                    new,meta=reorder_student(row['context'],row['candidates'],row['evidence'],scorer)
                    rank=new.index(row['expected'])+1 if row['expected'] in new else 0
                    if (d['beforeCandidates']!=row['candidates'] or d['afterCandidates']!=new or d['metadata']!=meta
                            or d['beforeRank']!=row['rank'] or d['afterRank']!=rank or sorted(new)!=sorted(row['candidates'])):
                        raise ValueError('External model scores/slots/rank changed')
                    after.append({**row,'candidates':new,'rank':rank})
                report=read(out/(domain+'-comparison.json'))
                if report['before']!=metrics(list(rows.values())) or report['after']!=metrics(after):
                    raise ValueError('External metrics changed')
                if report['nonregression']!=nonregression(report['before'],report['after']):raise ValueError('Changed domain gate')
                external[domain]={k:report[k] for k in ['before','after','nonregression','timing','reasons']}
            quality=all(external[d]['nonregression'] for d in ['aishell','tatoeba']) and sum(external[d]['after']['top1']-external[d]['before']['top1'] for d in ['aishell','tatoeba'])>0
            cost=all(external[d]['timing'].get('p95Ms',float('inf'))<=100 for d in ['aishell','tatoeba'])
            if (decision['qualityGate']!=quality or decision['hostCostGate']!=cost or decision['eligibleForFurtherValidation']!=(quality and cost)):
                raise ValueError('External adoption gate changed')
    pins=read(out.parent/'release-0.4.16-rc.5/input-source-pins.json')
    for name,sha in pins.items():
        if digest(root/name)!=sha:raise ValueError('Production source changed')
    release=read(root/'benchmarks/results/release-v0.4.16-rc.5.json')
    apk=root/'build/releases/v0.4.16-rc.5/Sense-v0.4.16-rc.5.apk'
    if digest(apk)!=release['apkSha256']:raise ValueError('Published local APK changed')
    tests=read(out/'test-summary.json')
    if (tests['failures'] or tests['errors'] or tests['skipped'] or tests['run']!=97
            or len(tests['testIds'])!=97 or len(set(tests['testIds']))!=97
            or not {'test_context_ranker_cv','test_e45_slot_join','test_e43_frozen_trial'}<=set(tests['modules'])):
        raise ValueError('Required complete host tool suite did not pass')
    write_new(out/'closure-check.json',dict(checkedAt=now(),productionSourcesUnchanged=len(pins),
        releaseApkSha256=digest(apk),foldModelsReplayed=count*len(pooled),eligibleStates=len(groups),
        vocabularyFittingFamiliesOnly=True,actualSlotsAndAllOutOfFoldPredictionsReplayed=True,
        externalScoresAndMetricsReplayed=bool(external),tests=tests,physicalPhoneTested=False,androidExecuted=False))
    files={'raw/'+p.relative_to(out).as_posix():p for p in out.rglob('*') if p.is_file()}
    for name in [*lock['sourceFiles'], 'tools/collect_e45_calibration.py',
                 'docs/research/input-quality-stage-e45-2026-10-04.md','docs/development/input-quality-program-v2.md']:
        files['workspace/'+name]=root/name
    folder=root/'benchmarks/results/e45-evidence';folder.mkdir(exist_ok=False)
    manifest=dict(schemaVersion=1,rawDirectory=str(out),files={},
        dependencies={s:digest(root/f'benchmarks/results/{s}-evidence-manifest.json') for s in ['e43','e44']},
        inheritedInputs=lock['inputFiles'],scope='All CV models, held-out predictions, fit logs and compact inputs archived; full raw candidate export and previous source attribution reused by pinned E44 manifest')
    for name,path in sorted(files.items()):
        data=path.read_bytes();target=folder/(hashlib.sha256(name.encode()).hexdigest()[:20]+'.gz')
        target.write_bytes(gzip.compress(data,mtime=0))
        manifest['files'][name]=dict(archive=target.relative_to(root).as_posix(),bytes=len(data),
            sha256=hashlib.sha256(data).hexdigest(),archiveSha256=digest(target))
    mp=root/'benchmarks/results/e45-evidence-manifest.json';write_new(mp,manifest)
    gate=dict(decision,stage='E45',cvReports=reports,externalReports=external,tests=tests,productionSourcesUnchanged=len(pins),
        publishedApkUnchanged=True,goalComplete=False,evidenceManifestSha256=digest(mp),archives=len(files))
    write_new(root/'benchmarks/results/e45-acceptance-gate.json',gate)
    print(json.dumps({k:gate[k] for k in ['selectedRidge','eligibleForFurtherValidation','archives','tests']}))


if __name__=='__main__':
    parser=argparse.ArgumentParser(__doc__);parser.add_argument('root',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args();collect(args.root.resolve(),args.output.resolve())
