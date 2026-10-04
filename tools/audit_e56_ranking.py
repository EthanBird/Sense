"""Recompute E56 rank permutations/metrics independently from raw native scores."""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import struct

from audit_e55_scores import han, metrics


def read(path):return json.loads(path.read_text('utf-8'))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def audit(root,out):
    lock=read(out/'pre-run-lock.json');reg=read(out/'regression-freeze.json');freeze=read(out/'pre-dev-freeze.json')
    for name,digest in lock['sourceFiles'].items():
        if sha(root/name)!=digest:raise ValueError('Changed frozen scoring code')
    for name,digest in lock['inputs'].items():
        if sha(Path(name))!=digest:raise ValueError('Changed native/calibration input')
    if sha(out/'scalar-fit.json')!=freeze['fitSha256'] or freeze['weight']!=reg['weight']:
        raise ValueError('Changed frozen scalar')
    states=scored=guarded=values=0;error=0.;result={}
    for domain in ['aishell','tatoeba','aishell-test','tatoeba-test','daily-mixture-test','known-diagnostic']:
        export=(out.parent/'e53' if domain in ['aishell','tatoeba'] else out)/(domain+'-features.jsonl.gz')
        with gzip.open(export,'rt',encoding='utf-8') as f:
            raw={(r['id'],r['cut']):r for line in f if (r:=json.loads(line))['type']=='row'}
        with (out/(domain+'-native.jsonl')).open(encoding='utf-8') as f:
            native={(r['context'],r['text']):r for line in f if (r:=json.loads(line))['type']=='row'}
        details=read(out/(domain+'-details.json'));before=[];after=[]
        if len(details)!=len(raw) or {(r['id'],r['cut']) for r in details}!=raw.keys():raise ValueError('Missing audit states')
        for row in details:
            baseline=raw[row['id'],row['cut']];wins=[baseline['pool'][i] for i in baseline['winnerIndices']]
            candidates=[c['text'] for c in wins];proposed=list(candidates)
            if candidates!=row['beforeCandidates'] or row['expected']!=baseline['expected']:raise ValueError('Changed raw candidate/reference')
            slots=([i for i,t in enumerate(candidates[:8]) if len(t)==len(candidates[0]) and han(t)]
                   if candidates and 2<=len(candidates[0])<=24 and han(candidates[0]) else [])
            reason=('fewer-than-two-eligible' if len(slots)<2 else
                    'protected-source' if any(wins[i]['kind'] not in ['BASE_EXACT','BASE_COMPOSED'] for i in slots) else
                    'personalized-path' if any(any(wins[i]['features'][j]!=0. for j in [9,10,11]) or
                        any(e[2] is None for e in wins[i]['edges']) for i in slots) else 'scored')
            if reason=='scored' and any(native[baseline['context'],candidates[i]]['unknowns'] for i in slots):reason='unknown-character'
            if reason!=row['metadata']['reason']:raise ValueError('Wrong eligibility decision')
            if reason=='scored':
                logs=[native[baseline['context'],candidates[i]]['log10Score'] for i in slots]
                mean=math.fsum(logs)/len(logs);scale=math.log(10.)/math.sqrt(len(candidates[0]));scores=[]
                for i,logp in zip(slots,logs):
                    total=struct.unpack('!f',struct.pack('!f',wins[i]['total']))[0]
                    scores.append(total+8*math.tanh(freeze['weight']*((logp-mean)*scale)/8))
                if len(scores)!=len(row['metadata']['scores']):raise ValueError('Wrong score count')
                error=max(error,max(abs(a-b) for a,b in zip(scores,row['metadata']['scores'])))
                if error>1e-12:raise ValueError('Independent native residual differs')
                for target,source in zip(slots,sorted(range(len(slots)),key=lambda j:(-scores[j],j))):proposed[target]=candidates[slots[source]]
                values+=len(scores);scored+=1
            else:guarded+=1
            gold=baseline['expected'];before.append((candidates,gold));after.append((proposed,gold));states+=1
            if proposed!=row['afterCandidates']:raise ValueError('Wrong permutation')
            if (proposed.index(gold)+1 if gold in proposed else 0)!=row['afterRank']:raise ValueError('Wrong resulting rank')
        report=read(out/(domain+'-comparison.json'))
        if metrics(before)!=report['before'] or metrics(after)!=report['after']:raise ValueError('Wrong paired metrics')
        result[domain]=dict(before=metrics(before),after=metrics(after))
    report=dict(passed=True,states=states,scored=scored,guarded=guarded,recomputedValues=values,maxScoreError=error,
        metrics=result,auditScriptSha256=sha(Path(__file__)),scope='Independent residual/permutation/metric check; native graph verified separately by exhaustive enumeration.')
    with (out/'independent-ranking-audit.json').open('x',encoding='utf-8',newline='\n') as f:
        json.dump(report,f,ensure_ascii=False,indent=2);f.write('\n')
    print(json.dumps({k:v for k,v in report.items() if k!='metrics'}))


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('root',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();audit(a.root.resolve(),a.output.resolve())
