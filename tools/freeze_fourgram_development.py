"""Freeze the two E18 higher-order trials without changing the lower LM or using test outputs."""
import json
from pathlib import Path
from audit_sentence_corpus import sha256
from evaluate_cross_domain import load,compare

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'.artifacts/input-quality/e18'
EXT=Path('G:/workspace/sense-input-quality-reference/e18-fourgram')


def choose(trials):
    eligible=[]
    for name,domains in trials.items():
        if not all(d['nonregressionPassed'] for d in domains.values()):continue
        after=sum(d['after']['top1'] for d in domains.values())
        if after<=sum(d['before']['top1'] for d in domains.values()):continue
        eligible.append(((-after,sum(d['after']['characterErrors'] for d in domains.values()),
            -sum(d['after']['top5'] for d in domains.values()),name!='daily-fourgram'),name))
    return min(eligible)[1] if eligible else None


def freeze():
    destination=ART/'development-freeze.json'
    if destination.exists():raise ValueError('Development already frozen')
    lock=json.loads((ART/'pre-dev-lock.json').read_text('utf-8'))
    for p,h in lock['files'].items():
        if sha256(Path(p))!=h:raise ValueError('Changed input '+p)
    if list(ART.glob('test-*.jsonl')):raise ValueError('Test opened before selection')
    policy_path=ROOT/'benchmarks/corpus/e18-fourgram-policy.json';policy=json.loads(policy_path.read_text('utf-8'))
    training=json.loads((EXT/'training-report.json').read_text('utf-8'))
    trials={r['name']:{} for r in policy['trials']};pins={};equivalence={}
    for domain,dataset in [('aishell','p2c-aishell-v7'),('tatoeba','p2c-supervised-v6')]:
        source=ROOT/f'benchmarks/corpus/{dataset}/dev.tsv';text=source.read_text('utf-8')
        baseline=ART/f'dev-{domain}-baseline.jsonl';bh,b=load(baseline.read_text('utf-8').splitlines(),text)
        prior=ROOT/f'.artifacts/input-quality/e17/restored-{domain}-baseline.jsonl';_,old=load(prior.read_text('utf-8').splitlines(),text)
        if b.keys()!=old.keys() or any(r['resultSha256']!=old[k]['resultSha256'] for k,r in b.items()):raise ValueError('Default behavior changed')
        if bh['inputSha256']!=sha256(source) or bh['fourgram']['enabled'] or bh['modelSha256']!=policy['baselineSha256']:raise ValueError('Invalid baseline')
        equivalence[domain]=len(b);pins.update({str(p):sha256(p) for p in [baseline,prior,source]})
        for mode in trials:
            file=ART/f'dev-{domain}-{mode}.jsonl';h,new=load(file.read_text('utf-8').splitlines(),text)
            if h['fourgram']!=dict(baselineSha256=policy['baselineSha256'],extensionSha256=training['models'][mode]['sha256'],
                    enabled=True,editorSnapshot='unchanged two-character sampled context'):raise ValueError('Changed model trial')
            result=compare(bh,b,h,new);result['pins']={str(p):sha256(p) for p in [baseline,file,source]}
            out=ART/f'dev-{domain}-{mode}-comparison.json'
            with out.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
            pins.update({str(p):sha256(p) for p in [out,file]})
            trials[mode][domain]={k:result[k] for k in ['before','after','nonregressionPassed']}
            trials[mode][domain].update(firstGains=len(result['firstGains']),firstLosses=len(result['firstLosses']),rankLosses=len(result['rankLosses']))
    selected=choose(trials)
    report=dict(schemaVersion=1,selectedMode=selected,developmentGatePassed=selected is not None,trials=trials,pins=pins,
        policySha256=sha256(policy_path),preDevLockSha256=sha256(ART/'pre-dev-lock.json'),trainingReportSha256=sha256(EXT/'training-report.json'),
        runnerSha256=sha256(Path(__file__)),evaluatorSha256=sha256(ROOT/'tools/evaluate_cross_domain.py'),
        baselineEquivalentStates=equivalence,testCandidatesViewedForSelection=False,productionChanged=False)
    with destination.open('x',encoding='utf-8') as out:json.dump(report,out,indent=2);out.write('\n')
    print(json.dumps(dict(selectedMode=selected,trials=trials)))


if __name__=='__main__':freeze()
