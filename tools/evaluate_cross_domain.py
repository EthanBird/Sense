"""Strictly pair M20 actual-decoder outputs against the predeclared source workload."""
import argparse
import json
from pathlib import Path
from audit_sentence_corpus import sha256
from train_candidate_ranker import distance

CONFIG = dict(lmWeight=.5, oovFeature=-4, correctionBoost=12, limit=255)


def workload(tsv):
    states = {}; seen = set()
    for line in tsv.splitlines():
        if not line or line.startswith('#'): continue
        identity, query, expected, stratum, units = line.split('\t'); syllables = units.split()
        if (identity in seen or not identity or len(expected) < 2 or len(expected) != len(syllables)
                or query != ''.join(syllables) or len(query) > 96): raise ValueError('Invalid source row')
        seen.add(identity)
        for cut in [0, len(expected)//2]:
            states[(identity, cut)] = dict(id=identity, cut=cut, stratum=stratum, expected=expected[cut:],
                query=''.join(syllables[cut:]), context=expected[max(0, cut-2):cut], mode='empty' if cut == 0 else 'editor')
    if not states: raise ValueError('Empty source workload')
    return states


def load(lines, tsv):
    items = [json.loads(l) for l in lines if l.strip()]; expected = workload(tsv)
    if len(items) < 3: raise ValueError('Incomplete stream')
    h, *rows, end = items
    if (h.get('type') != 'header' or h.get('schemaVersion') != 1 or h.get('mode') != 'whole-and-midpoint'
            or h.get('configuration') != CONFIG or h.get('personalization') != 'empty; no learn'
            or end.get('type') != 'summary' or end.get('rows') != len(rows)
            or end.get('sentences')*2 != len(rows)): raise ValueError('Unexpected complete run configuration')
    by_key = {}
    for r in rows:
        key = (r['id'], r['cut'])
        if r.get('type') != 'row' or key in by_key or key not in expected: raise ValueError('Invalid state identity')
        if any(r[k] != v for k,v in expected[key].items()): raise ValueError('Changed workload state')
        c = r['candidates']
        if (not isinstance(c, list) or len(c) > 255 or len(set(c)) != len(c)
                or any(not isinstance(t,str) or not t for t in c)
                or type(r['hostNanos']) is not int or r['hostNanos'] < 0): raise ValueError('Invalid candidate output')
        rank = c.index(r['expected'])+1 if r['expected'] in c else 0
        if type(r['rank']) is not int or rank != r['rank']: raise ValueError('Rank not derived from actual candidate array')
        by_key[key] = r
    if by_key.keys() != expected.keys(): raise ValueError('Partial workload')
    return h, by_key


def metrics(rows):
    return dict(states=len(rows), top1=sum(r['rank']==1 for r in rows),
        top5=sum(0<r['rank']<=5 for r in rows), top10=sum(0<r['rank']<=10 for r in rows),
        recall255=sum(r['rank']>0 for r in rows),
        characterErrors=sum(distance(r['candidates'][0] if r['candidates'] else '', r['expected']) for r in rows),
        characters=sum(len(r['expected']) for r in rows))


def nonregression(before, after, strict=False):
    return (before['states'] == after['states'] and before['characters'] == after['characters']
        and all(after[k] >= before[k] for k in ['top1','top5','recall255'])
        and after['characterErrors'] <= before['characterErrors']
        and (not strict or after['top1'] > before['top1']))


def compare(old_header, old, new_header, new):
    if old.keys() != new.keys() or any(old_header[k] != new_header[k] for k in
            ['schemaVersion','mode','inputSha256','sources','assets','configuration','personalization']):
        raise ValueError('Unpaired execution inputs')
    def delta(k):
        b,a = old[k],new[k]
        return {**{x:b[x] for x in ['id','cut','mode','stratum','query','context','expected']},
            'beforeRank':b['rank'], 'afterRank':a['rank'],
            'beforeTop5':b['candidates'][:5], 'afterTop5':a['candidates'][:5],
            'beforeFirstKind':b['firstKind'], 'afterFirstKind':a['firstKind']}
    before=metrics(list(old.values())); after=metrics(list(new.values()))
    return dict(before=before, after=after,
        slices={key: {value: dict(before=metrics([r for r in old.values() if r[key]==value]),
                                 after=metrics([r for r in new.values() if r[key]==value]))
                       for value in sorted({r[key] for r in old.values()})} for key in ['mode','stratum']},
        firstGains=[delta(k) for k in old if old[k]['rank'] != 1 and new[k]['rank'] == 1],
        firstLosses=[delta(k) for k in old if old[k]['rank'] == 1 and new[k]['rank'] != 1],
        rankLosses=[delta(k) for k in old if old[k]['rank'] and (not new[k]['rank'] or new[k]['rank']>old[k]['rank'])],
        baselineModel=old_header['modelSha256'], proposedModel=new_header['modelSha256'],
        nonregressionPassed=nonregression(before,after), strictImprovementPassed=nonregression(before,after,True),
        productionChanged=False, scope='Source reconstruction with agent-reviewed labels; midpoint is oracle context; states within sentence are correlated')


if __name__ == '__main__':
    p=argparse.ArgumentParser(__doc__)
    for n in ['baseline','proposed','source','output']:p.add_argument(n,type=Path)
    a=p.parse_args(); text=a.source.read_text('utf-8')
    bh,b=load(a.baseline.read_text('utf-8').splitlines(),text); nh,n=load(a.proposed.read_text('utf-8').splitlines(),text)
    if bh['inputSha256'] != sha256(a.source): raise ValueError('Source file hash changed')
    report=compare(bh,b,nh,n)
    report['pins']={str(f):sha256(f) for f in [a.baseline,a.proposed,a.source,Path(__file__)]}
    with a.output.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2);f.write('\n')
    print(json.dumps({k:report[k] for k in ['before','after','nonregressionPassed','strictImprovementPassed']}))
