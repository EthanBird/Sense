"""Fixed context-only coverage experiment; expanded fit is the sole candidate."""
import argparse
from collections import Counter
import datetime
import json
from pathlib import Path
import subprocess
import time

from candidate_student import load_features, reorder_student
from compact_m19_training import extract
from context_only_ranker import ContextScorer, fit
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from run_e37_rescoring import distribution, validate_baseline, write_new

read=lambda p:json.loads(p.read_text('utf-8'))


def run(root, out):
    policy=root/'benchmarks/corpus/e44-context-only-policy.json'; spec=read(policy)
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    if subprocess.run(['git','merge-base','--is-ancestor',spec['sourceBaseCommit'],head],cwd=root).returncode:
        raise ValueError('Wrong source ancestry')
    preparation=read(out/'train/pre-export-lock.json')
    if digest(policy)!=preparation['policySha256']:
        raise ValueError('Policy changed after source selection')
    for name,sha in preparation['outputs'].items():
        if digest(out/'train'/name)!=sha:raise ValueError('Prepared training input changed')
    for name,sha in preparation['heldout'].items():
        if digest(root/name)!=sha:raise ValueError('Heldout isolation target changed')
    selected=read(out/'train/selected.json')
    groups,audit=extract(root,out/'train/expanded.tsv',out/'expanded-features.jsonl.gz',out/'training-groups.jsonl',selected)
    write_new(out/'training-audit.json',audit)
    families={r['id']:r['group'] for r in selected if not r['exclusion']}
    sources=['tools/run_e44_context_ranker.py','tools/context_only_ranker.py','tools/compact_m19_training.py',
             'tools/candidate_sparse_ranker.py','tools/candidate_student.py','tools/audit_score_features.py',
             'tools/test_context_only_ranker.py','tools/test_compact_m19_training.py']
    write_new(out/'pre-fit-lock.json',dict(checkout=head,createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        policySha256=digest(policy),sourceFiles={p:digest(root/p) for p in sources},
        inputs={p:digest(out/p) for p in ['train/pre-export-lock.json','train/expanded.tsv','train/small.tsv',
            'train/selected.json','train/attribution.jsonl','training-groups.jsonl','expanded-features.jsonl.gz']},
        candidateExportAudit=audit, noDevOutputsUsedForFit=True))
    fits={}
    for cohort in ['small','expanded']:
        data=[g for g in groups if cohort=='expanded' or g['cohort']=='small']
        print(f'Fitting {cohort}: {len(data)} actual eligible states',flush=True)
        model,report=fit(data,families)
        scorer=ContextScorer(model);zero=ContextScorer({**model,'weights':[0.]*len(model['weights'])})
        before=after=0
        for g in data:
            base=[e['baseline'] for e in g['evidence']]
            if zero(g['context'],g['texts'],g['evidence'])!=base:raise ValueError('Zero residual differs')
            scores=scorer(g['context'],g['texts'],g['evidence'])
            before+=max(range(len(base)),key=lambda i:base[i])==g['gold']
            after+=max(range(len(scores)),key=lambda i:scores[i])==g['gold']
        report.update(trainTop1Before=before,trainTop1After=after,zeroIncrementExact=True)
        write_new(out/(cohort+'-model.json'),model);write_new(out/(cohort+'-fit.json'),report)
        fits[cohort]=dict(modelSha256=digest(out/(cohort+'-model.json')),fitSha256=digest(out/(cohort+'-fit.json')),
                          controlOnly=cohort=='small')
        print(cohort,json.dumps({k:v for k,v in report.items() if k!='lossHistory'}),flush=True)
    write_new(out/'pre-dev-freeze.json',dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        fits=fits,lockSha256=digest(out/'pre-fit-lock.json'),soleAdoptionCandidate='expanded'))
    evaluate(root,out)


def evaluate(root,out):
    freeze=read(out/'pre-dev-freeze.json');lock=read(out/'pre-fit-lock.json')
    if digest(out/'pre-fit-lock.json')!=freeze['lockSha256']:raise ValueError('Frozen lock changed')
    for p,sha in lock['sourceFiles'].items():
        if digest(root/p)!=sha:raise ValueError('Frozen model/evaluation source changed: '+p)
    for p,sha in lock['inputs'].items():
        if digest(out/p)!=sha:raise ValueError('Frozen fitting input changed')
    dev={'aishell':root/'benchmarks/corpus/p2c-aishell-v7/dev.tsv',
         'tatoeba':root/'benchmarks/corpus/p2c-supervised-v6/dev.tsv',
         'diagnostic':root/'.artifacts/input-quality/e28/diagnostic.tsv'}
    models={}
    for cohort,pins in freeze['fits'].items():
        if (digest(out/(cohort+'-model.json'))!=pins['modelSha256']
                or digest(out/(cohort+'-fit.json'))!=pins['fitSha256']):raise ValueError('Frozen fit changed')
        models[cohort]=ContextScorer(read(out/(cohort+'-model.json')))
    results={cohort:{} for cohort in models}
    for domain,source in dev.items():
        # E43 current-production inputs are reusable: hashes and every source
        # asset are checked, not inferred from a filename or stage number.
        rows,audit=load_features(root,source,out.parent/'e43'/(domain+'-features.jsonl.gz'))
        if domain!='diagnostic':
            _,before=validate_baseline(root,source,out.parent/'e43'/(domain+'-baseline.jsonl'))
            if rows.keys()!=before.keys() or any(rows[k]['candidates']!=before[k]['candidates']
                or rows[k]['resultSha256']!=before[k]['resultSha256'] for k in rows):raise ValueError('Baseline mismatch')
        for cohort,scorer in models.items():
            details=[];after=[];reasons=Counter();times=[]
            for row in rows.values():
                original=row['candidates'];start=time.perf_counter_ns()
                actual,meta=reorder_student(row['context'],original,row['evidence'],scorer)
                duration=time.perf_counter_ns()-start
                again,check=reorder_student(row['context'],original,row['evidence'],scorer)
                if actual!=again or meta!=check or sorted(actual)!=sorted(original):raise ValueError('Unstable score or membership')
                reasons[meta['reason']]+=1
                if meta['reason']=='scored':times.append(duration)
                rank=actual.index(row['expected'])+1 if row['expected'] in actual else 0
                after.append({**row,'candidates':actual,'rank':rank})
                details.append({k:row[k] for k in ['id','cut','mode','stratum','query','context','expected']}|
                    dict(beforeRank=row['rank'],afterRank=rank,beforeCandidates=original,afterCandidates=actual,
                        metadata=meta,hostNanos=duration))
            baseline,proposed=metrics(list(rows.values())),metrics(after)
            report=dict(before=baseline,after=proposed,nonregression=nonregression(baseline,proposed),
                reasons=dict(reasons),timing=distribution(times),audit=audit,
                firstGains=[d for d in details if d['beforeRank']!=1 and d['afterRank']==1],
                firstLosses=[d for d in details if d['beforeRank']==1 and d['afterRank']!=1],
                rankLosses=[d for d in details if d['beforeRank'] and (not d['afterRank'] or d['afterRank']>d['beforeRank'])],
                candidateSetsUnchanged=True,repeatedScoresEqual=True)
            write_new(out/(cohort+'-'+domain+'-details.json'),details)
            write_new(out/(cohort+'-'+domain+'-comparison.json'),report);results[cohort][domain]=report
            print(cohort,domain,json.dumps({k:report[k] for k in ['before','after','nonregression','timing']}),flush=True)
    r=results['expanded']
    quality=all(r[d]['nonregression'] for d in ['aishell','tatoeba']) and sum(r[d]['after']['top1']-r[d]['before']['top1'] for d in ['aishell','tatoeba'])>0
    cost=all(r[d]['timing'].get('p95Ms',float('inf'))<=100 for d in ['aishell','tatoeba'])
    write_new(out/'decision.json',dict(qualityGate=quality,hostCostGate=cost,eligibleForFurtherValidation=quality and cost,
        soleAdoptionCandidate='expanded',smallControlSelectable=False,productionChanged=False,
        modelSha256=digest(out/'expanded-model.json'),modelBytes=(out/'expanded-model.json').stat().st_size,
        scope='Known-source reconstruction and offline fixed-pool scoring; no Android or release adoption'))


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('root',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--evaluate-frozen',action='store_true');a=p.parse_args()
    (evaluate if a.evaluate_frozen else run)(a.root.resolve(),a.output.resolve())
