"""Explain measured development losses using model-local character probabilities and train counts.

This float64 diagnostic is NOT the decoder's whole path score or a causal ablation.
It reads already-opened development rows only, and performs no new model selection.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
from audit_sentence_corpus import sha256
from train_character_lm import BOS, read_model


def feature(model, context, text, query):
    a,b=([BOS,BOS]+list(map(ord,context)))[-2:]
    terms=[]
    for ch in text:
        cp=ord(ch); known=cp in model.unigrams
        logp=model.log_probability(a,b,cp)
        terms.append(dict(context=''.join(chr(c) for c in [a,b] if c!=BOS),text=ch,known=known,
                          logProbability=logp,rawFeature=max(-6,min(6,logp+6)) if known else -4))
        a,b=b,cp
    return dict(terms=terms,feature=sum(t['rawFeature'] for t in terms)*.5/max(1,len(query)/6))


def main(a):
    old=read_model(a.baseline.read_bytes());new=read_model(a.proposed.read_bytes())
    losses=[]
    for path in a.comparisons:
        report=json.loads(path.read_text('utf-8'))
        if report['baselineModel']!=sha256(a.baseline) or report['proposedModel']!=sha256(a.proposed):
            raise ValueError('Diagnostic model mismatch')
        for row in report['firstLosses']:
            target=row['expected'];wrong=row['afterTop5'][0]
            score={name:{label:feature(m,row['context'],text,row['query']) for label,text in [('target',target),('winner',wrong)]}
                   for name,m in [('baseline',old),('balanced',new)]}
            losses.append(dict(**row,scores=score))
    needles=set()
    for r in losses:
        for t in [r['expected'],r['afterTop5'][0]]:
            needles.update(t[i:i+n] for n in [2,3] for i in range(len(t)-n+1))
    counts={}
    for name,path in [('oldTrain',a.old_train),('selectedNewTrain',a.new_train)]:
        values=Counter()
        for line in path.read_text('utf-8').splitlines():
            for text in json.loads(line)['segments']:
                for n in [2,3]:
                    values.update(text[i:i+n] for i in range(len(text)-n+1) if text[i:i+n] in needles)
        counts[name]={k:values[k] for k in sorted(needles)}
    report=dict(schemaVersion=1,scope=__doc__,losses=losses,trainSubstringCounts=counts,
        pins={str(p):sha256(p) for p in [a.baseline,a.proposed,a.old_train,a.new_train,*a.comparisons,Path(__file__)]})
    with a.output.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2);f.write('\n')
    for r in losses:
        margins={m:s['target']['feature']-s['winner']['feature'] for m,s in r['scores'].items()}
        print(json.dumps(dict(expected=r['expected'],winner=r['afterTop5'][0],lmTargetMinusWinner=margins),ensure_ascii=False))
    print(json.dumps({name:{k:v for k,v in cs.items() if k in ['散步','三不','起散步','起三不','露天','路天','路天国','大豆','大都']} for name,cs in counts.items()},ensure_ascii=False))


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__)
    for n in ['baseline','proposed','old_train','new_train','output']:p.add_argument(n,type=Path)
    p.add_argument('comparisons',nargs='+',type=Path);main(p.parse_args())
