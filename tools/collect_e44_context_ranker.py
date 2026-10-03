"""Validate E44 frozen outputs and archive small evidence; explicitly pin the large export."""
import argparse
import datetime
import gzip
import hashlib
import json
from pathlib import Path

from candidate_student import load_features
from context_only_ranker import ContextScorer
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from run_e37_rescoring import write_new

read=lambda p:json.loads(p.read_text('utf-8'))


def collect(root,out):
    freeze=read(out/'pre-dev-freeze.json');lock=read(out/'pre-fit-lock.json')
    if digest(out/'pre-fit-lock.json')!=freeze['lockSha256']:raise ValueError('Fit lock changed')
    for name,sha in lock['sourceFiles'].items():
        if digest(root/name)!=sha:raise ValueError('Frozen execution source changed')
    for name,sha in lock['inputs'].items():
        if digest(out/name)!=sha:raise ValueError('Frozen input changed')
    # Revalidate explicit training provenance and every held-out identity.
    preparation=read(out/'train/pre-export-lock.json')
    for name,sha in preparation['heldout'].items():
        if digest(root/name)!=sha:raise ValueError('Held-out source changed')
    for name,sha in preparation['outputs'].items():
        if digest(out/'train'/name)!=sha:raise ValueError('Prepared source changed')
    policy=root/'benchmarks/corpus/e44-context-only-policy.json'
    if digest(policy)!=preparation['policySha256'] or digest(policy)!=lock['policySha256']:
        raise ValueError('Training policy drift')
    groups=[json.loads(l) for l in (out/'training-groups.jsonl').read_text('utf-8').splitlines()]
    actual={(g['id'],g['cut']):g for g in groups}
    if len(actual)!=len(groups):raise ValueError('Duplicate training group')
    old=out.parent/'e39/final/training-groups.jsonl'
    e39=read(root/'benchmarks/results/e39-evidence-manifest.json')
    if digest(old)!=e39['files']['external/e39/final/training-groups.jsonl']['sha256']:
        raise ValueError('Old train reference identity changed')
    old_groups=[json.loads(l) for l in old.read_text('utf-8').splitlines()]
    for g in old_groups:
        current=actual[(g['id'],g['cut'])]
        if any(current[k]!=g[k] for k in ['context','texts','evidence','gold']):
            raise ValueError('Existing training group changed')
    reports={}
    sources={'aishell':root/'benchmarks/corpus/p2c-aishell-v7/dev.tsv',
             'tatoeba':root/'benchmarks/corpus/p2c-supervised-v6/dev.tsv',
             'diagnostic':root/'.artifacts/input-quality/e28/diagnostic.tsv'}
    for cohort,pins in freeze['fits'].items():
        if digest(out/(cohort+'-model.json'))!=pins['modelSha256'] or digest(out/(cohort+'-fit.json'))!=pins['fitSha256']:
            raise ValueError('Model/fit changed')
        scorer=ContextScorer(read(out/(cohort+'-model.json')));reports[cohort]={}
        for domain,tsv in sources.items():
            rows,_=load_features(root,tsv,out.parent/'e43'/(domain+'-features.jsonl.gz'))
            detail=read(out/(cohort+'-'+domain+'-details.json'));before=[];after=[]
            if [(d['id'],d['cut']) for d in detail]!=list(rows):raise ValueError('Missing evidence state')
            for d in detail:
                row=rows[(d['id'],d['cut'])];old=row['candidates'];new=d['afterCandidates'];meta=d['metadata']
                if d['beforeCandidates']!=old or sorted(old)!=sorted(new):raise ValueError('Candidate set changed')
                if meta['reason']=='scored':
                    texts=[old[i] for i in meta['slots']];evidence=[row['evidence'][t] for t in texts]
                    scores=scorer(row['context'],texts,evidence)
                    if scores!=meta['scores']:raise ValueError('Frozen score replay differs')
                    order=sorted(range(len(scores)),key=lambda i:(-scores[i],i));expected=old.copy()
                    for slot,src in zip(meta['slots'],order):expected[slot]=texts[src]
                    if expected!=new:raise ValueError('Rank does not follow scores')
                elif old!=new:raise ValueError('Protected source reordered')
                rank=lambda c:c.index(row['expected'])+1 if row['expected'] in c else 0
                if d['beforeRank']!=rank(old) or d['afterRank']!=rank(new):raise ValueError('Recorded rank mismatch')
                before.append({**row,'candidates':old,'rank':rank(old)})
                after.append({**row,'candidates':new,'rank':rank(new)})
            report=read(out/(cohort+'-'+domain+'-comparison.json'))
            if metrics(before)!=report['before'] or metrics(after)!=report['after']:
                raise ValueError('Stored quality metrics mismatch')
            if nonregression(report['before'],report['after'])!=report['nonregression']:
                raise ValueError('Stored nonregression mismatch')
            reports[cohort][domain]={k:report[k] for k in ['before','after','nonregression','reasons','timing']}
    decision=read(out/'decision.json');candidate=reports['expanded']
    quality=(all(candidate[d]['nonregression'] for d in ['aishell','tatoeba']) and
             sum(candidate[d]['after']['top1']-candidate[d]['before']['top1'] for d in ['aishell','tatoeba'])>0)
    if quality!=decision['qualityGate'] or decision['soleAdoptionCandidate']!='expanded' or decision['smallControlSelectable']:
        raise ValueError('Candidate selection changed')
    pins=read(out.parent/'release-0.4.16-rc.5/input-source-pins.json')
    for name,sha in pins.items():
        if digest(root/name)!=sha:raise ValueError('Production source changed')
    release=read(root/'benchmarks/results/release-v0.4.16-rc.5.json')
    apk=root/'build/releases/v0.4.16-rc.5/Sense-v0.4.16-rc.5.apk'
    if digest(apk)!=release['apkSha256']:raise ValueError('Published local APK changed')
    # ZIP decompression is read-only and also checks assets, not only source paths.
    import zipfile
    with zipfile.ZipFile(apk) as z:
        for name,sha in release['assetHashes'].items():
            if hashlib.sha256(z.read(name)).hexdigest()!=sha:raise ValueError('APK assets changed')
    tests=(out/'final-tests.log').read_text('utf-8')
    if 'Ran 75 tests' not in tests or not tests.rstrip().endswith('OK'):raise ValueError('Final suite failed')
    write_new(out/'closure-check.json',dict(checkedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        productionSourcesUnchanged=len(pins),releaseApkSha256=digest(apk),releaseAssetsUnchanged=len(release['assetHashes']),
        existingTeacherTrainingInputsEqual=len(old_groups),reloadedScoresAndMetricsEqual=True,
        testsPassed=75,physicalPhoneTested=False,androidExecuted=False,productionChanged=False))
    files={'raw/'+p.relative_to(out).as_posix():p for p in out.rglob('*') if p.is_file()}
    external=files.pop('raw/expanded-features.jsonl.gz')
    for name in ['tools/context_only_ranker.py','tools/compact_m19_training.py','tools/prepare_e44_training.py',
                 'tools/run_e44_context_ranker.py','tools/collect_e44_context_ranker.py',
                 'tools/test_context_only_ranker.py','tools/test_compact_m19_training.py',
                 'benchmarks/corpus/e44-context-only-policy.json',
                 'docs/research/input-quality-stage-e44-2026-10-04.md','docs/development/input-quality-program-v2.md']:
        files['workspace/'+name]=root/name
    folder=root/'benchmarks/results/e44-evidence';folder.mkdir(exist_ok=False)
    manifest=dict(schemaVersion=1,rawDirectory=str(out),files={},
        dependencies={stage:digest(root/f'benchmarks/results/{stage}-evidence-manifest.json') for stage in ['e39','e43']},
        externalFiles={'expanded-features.jsonl.gz':dict(path=str(external),bytes=external.stat().st_size,
            sha256=digest(external),reproduce='Run the archived export-lock.json command against the archived expanded.tsv and source/asset-pinned production core; preserve all paths.')},
        scope='Full M19 export retained on E drive, explicitly not archived in Git. Train inputs, provenance, eligible groups, models and all dev candidates are archived. No product change.')
    for name,path in sorted(files.items()):
        data=path.read_bytes();packed=gzip.compress(data,mtime=0)
        target=folder/(hashlib.sha256(name.encode()).hexdigest()[:20]+'.gz');target.write_bytes(packed)
        manifest['files'][name]=dict(archive=target.relative_to(root).as_posix(),bytes=len(data),
            sha256=hashlib.sha256(data).hexdigest(),archiveSha256=digest(target))
    mp=root/'benchmarks/results/e44-evidence-manifest.json';write_new(mp,manifest)
    gate={**decision,'stage':'E44','reports':reports,'trainingAudit':read(out/'training-audit.json'),
        'trainingCoverageDiagnostics':read(out/'training-coverage-diagnostics.json'),
        'sourcePreparation':{k:preparation[k] for k in ['proposed','retained','smallRetained','exclusions']},
        'models':{c:{k:read(out/(c+'-fit.json'))[k] for k in ['parameters','trainingGroups','iterations','converged','trainTop1Before','trainTop1After']}
                  for c in ['small','expanded']},
        'finalTests':75,'publishedApkUnchanged':True,'goalComplete':False,
        'evidenceManifestSha256':digest(mp),'archives':len(manifest['files'])}
    write_new(root/'benchmarks/results/e44-acceptance-gate.json',gate)
    print(json.dumps({k:gate[k] for k in ['qualityGate','hostCostGate','archives','finalTests','modelBytes']}))


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('root',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    collect(a.root.resolve(),a.output.resolve())
