"""Evaluate the predeclared E16 trials and freeze one coefficient before test decoding."""
import json
from pathlib import Path
from audit_sentence_corpus import sha256
from evaluate_cross_domain import compare, load

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'.artifacts/input-quality/e16'


def choose(trials):
    eligible=[]
    for alpha,domains in trials.items():
        if not (domains['aishell']['strictImprovementPassed'] and domains['tatoeba']['nonregressionPassed']): continue
        scores=[v['after'] for v in domains.values()]
        key=(-sum(s['top1'] for s in scores),sum(s['characterErrors'] for s in scores),
             -sum(s['top5'] for s in scores),float(alpha))
        eligible.append((key,alpha))
    return min(eligible)[1] if eligible else None


def freeze():
    destination=ART/'development-freeze.json'
    if destination.exists():raise ValueError('Preserve frozen development')
    lock=json.loads((ART/'pre-dev-lock.json').read_text('utf-8'))
    for path,pin in lock['files'].items():
        if sha256(Path(path))!=pin:raise ValueError('Changed pre-development input '+path)
    if not lock['outputsAbsent'] or lock['testCandidatesViewed']:raise ValueError('Invalid first-use evidence')
    if list(ART.glob('test-*.jsonl')):raise ValueError('Test candidates opened before selection')
    policy=json.loads((ROOT/'benchmarks/corpus/e16-mixture-policy.json').read_text('utf-8'))
    trials={str(a):{} for a in policy['trials']};pins={};baseline_equivalence={}
    for domain,dataset in [('aishell','p2c-aishell-v7'),('tatoeba','p2c-supervised-v6')]:
        source=ROOT/f'benchmarks/corpus/{dataset}/dev.tsv';text=source.read_text('utf-8')
        base=ART/f'dev-{domain}-0.jsonl';bh,b=load(base.read_text('utf-8').splitlines(),text)
        if bh['inputSha256']!=sha256(source) or bh['modelSha256']!=policy['baselineModelSha256']:raise ValueError('Unexpected baseline')
        prior=ROOT/f'.artifacts/input-quality/e15/dev-{domain}-baseline.jsonl'
        _,old=load(prior.read_text('utf-8').splitlines(),text)
        if b.keys()!=old.keys() or any(r['resultSha256']!=old[k]['resultSha256'] for k,r in b.items()):raise ValueError('Baseline changed')
        baseline_equivalence[domain]=len(b);pins[str(prior)]=sha256(prior);pins[str(base)]=sha256(base)
        for alpha in trials:
            file=ART/f'dev-{domain}-{alpha}.jsonl';h,new=load(file.read_text('utf-8').splitlines(),text)
            if h['mixture']!=dict(baselineSha256=policy['baselineModelSha256'],adaptationSha256=policy['adaptationModelSha256'],
                    alpha=float(alpha),projection='other-unigram-unk-split-v1'):raise ValueError('Changed mixture trial')
            result=compare(bh,b,h,new);result['pins']={str(p):sha256(p) for p in [base,file,source]}
            out=ART/f'dev-{domain}-{alpha}-comparison.json'
            with out.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
            pins[str(out)]=sha256(out);pins[str(file)]=sha256(file)
            trials[alpha][domain]={k:result[k] for k in ['before','after','nonregressionPassed','strictImprovementPassed']}
            trials[alpha][domain].update(firstGains=len(result['firstGains']),firstLosses=len(result['firstLosses']),rankLosses=len(result['rankLosses']))
    selected=choose(trials)
    result=dict(schemaVersion=1,selectedAlpha=selected,developmentGatePassed=selected is not None,trials=trials,
        policySha256=sha256(ROOT/'benchmarks/corpus/e16-mixture-policy.json'),preDevLockSha256=sha256(ART/'pre-dev-lock.json'),
        runnerSha256=sha256(Path(__file__)),evaluatorSha256=sha256(Path(__file__).with_name('evaluate_cross_domain.py')),
        pins=pins,testCandidatesViewedForSelection=False,baselineEquivalentStates=baseline_equivalence,
        decision='freeze-for-heldout-evaluation' if selected else 'hold-baseline-no-trial-passed',productionChanged=False)
    with destination.open('x',encoding='utf-8') as out:json.dump(result,out,indent=2);out.write('\n')
    print(json.dumps(dict(selectedAlpha=selected,trials=trials),ensure_ascii=False))


if __name__=='__main__':freeze()
