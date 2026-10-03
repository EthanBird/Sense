"""Compare E23 recall changes to frozen E21 code and explicitly retain rank losses."""
import gzip
import hashlib
import json
from pathlib import Path

from evaluate_cross_domain import load, compare

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT/'.artifacts/input-quality/e23'
OLD = ROOT/'.artifacts/input-quality/e21'
CHANGED = {'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/'+n for n in ['PinyinDecoder.kt','PinyinSyllableSegmenter.kt']}


def read(path):
    return json.loads(path.read_text('utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rank_metrics(rows):
    return dict(rows=len(rows), top1=sum(r['rank']==1 for r in rows), top10=sum(0<r['rank']<=10 for r in rows), recall=sum(r['rank']>0 for r in rows))


def evaluate():
    lock = read(ART/'before-lock.json'); after = read(ART/'candidate-replay-lock.json')
    def validate_sources(before_sources, after_sources):
        assert before_sources == lock['sourcePins'] and after_sources == after['sources']
        assert before_sources.keys() == after_sources.keys()
        assert {k for k in before_sources if before_sources[k] != after_sources[k]} == CHANGED
        assert all(sha(ROOT/k) == h for k,h in after_sources.items())
    validate_sources(lock['sourcePins'], after['sources'])
    def probe(path):
        records = [json.loads(s) for s in gzip.decompress(path.read_bytes()).decode().splitlines()]
        h,*rows,end = records
        assert end['rows'] == len(rows) == read(ART/'probe-lock.json')['rows'] == 115 and end['observationEquivalent']
        assert h['inputSha256'] == sha(ART/'prefix-probes.tsv') == read(ART/'probe-lock.json')['sha256']
        assert h['assets'] == lock['assetPins'] and h['configuration'] == dict(lmWeight=.5,oovFeature=-4,correctionBoost=12,limit=255)
        return h,{r['id']:r for r in rows}
    bh,b = probe(ART/'before-probe.jsonl.gz'); ah,a = probe(ART/'candidate-probe.jsonl.gz')
    validate_sources(bh['sources'],ah['sources']); assert b.keys() == a.keys()
    def change(k, before, new):
        return dict(query=before[k]['query'],expected=before[k]['expected'],beforeRank=before[k]['rank'],afterRank=new[k]['rank'],
                    beforeTop5=before[k]['actual'],afterTop5=new[k]['actual'])
    probes = dict(before=rank_metrics(list(b.values())), after=rank_metrics(list(a.values())),
                  changes=[change(k,b,a) for k in b if b[k]['rank'] != a[k]['rank'] or b[k]['actual'][:1] != a[k]['actual'][:1]],
                  firstLosses=[change(k,b,a) for k in b if b[k]['rank']==1 and a[k]['rank']!=1],
                  rankLosses=[change(k,b,a) for k in b if b[k]['rank']>0 and (a[k]['rank']==0 or a[k]['rank']>b[k]['rank'])],
                  completeProgressiveEqual=sum(b[k]['resultSha256']==a[k]['resultSha256'] for k in b))
    clean = {}
    for part in ['dev','test']:
        for domain in ['aishell','tatoeba']:
            dataset = 'p2c-aishell-v7' if domain == 'aishell' else ('p2c-supervised-v6' if part == 'dev' else 'p2c-daily-mixture-v8')
            source = ROOT/f'benchmarks/corpus/{dataset}/{part}.tsv'; text = source.read_text('utf-8')
            bh,b = load((OLD/f'perf-{part}-{domain}.jsonl').read_text('utf-8').splitlines(),text)
            ah,a = load((ART/f'candidate-{part}-{domain}.jsonl').read_text('utf-8').splitlines(),text)
            validate_sources(bh['sources'],ah['sources'])
            assert bh['inputSha256'] == sha(source) == ah['inputSha256']
            report = compare({**bh,'sources':ah['sources']},b,ah,a)
            report['productionChanged'] = True
            report['fullProgressiveEqual'] = sum(b[k]['resultSha256']==a[k]['resultSha256'] for k in b)
            clean[f'{part}-{domain}'] = report
    typo = {}
    for dataset in ['typo-v1','typo-layered-v4']:
        before,new = read(OLD/f'perf-{dataset}.json'),read(ART/f'candidate-{dataset}.json')
        validate_sources(*({f'core-input/src/main/kotlin/{k}':v for k,v in r['sources'].items()} for r in [before,new]))
        assert before['inputSha256'] == sha(ROOT/f'benchmarks/corpus/{dataset}/test.tsv')
        for key in ['inputSha256','assets','lmWeight','oovFeature','correctionCompositionBoost','candidateLimit','graphDiagnosticLimit','learning']:
            assert before[key] == new[key]
        b,a = ({r['id']:r for r in v['observations']} for v in [before,new]); assert b.keys() == a.keys()
        graph_equal = all(all(b[k][f]==a[k][f] for f in ['graphCanonicalRank','graphAlignedRank','selectedProbes']) for k in b)
        def delta(k):
            return dict(query=b[k]['typed'],expected=b[k]['expected'],operation=b[k]['operation'],beforeRank=b[k]['rank'],afterRank=a[k]['rank'],
                        beforeTop5=[c['text'] for c in b[k]['top5']],afterTop5=[c['text'] for c in a[k]['top5']])
        def metrics(rows):
            return {**rank_metrics(rows),'characterErrors':sum(r['characterErrors'] for r in rows)}
        typo[dataset] = dict(before=metrics(list(b.values())),after=metrics(list(a.values())),graphEvidenceEqual=graph_equal,
                            firstGains=[delta(k) for k in b if b[k]['rank']!=1 and a[k]['rank']==1],
                            firstLosses=[delta(k) for k in b if b[k]['rank']==1 and a[k]['rank']!=1],
                            rankLosses=[delta(k) for k in b if b[k]['rank']>0 and (a[k]['rank']==0 or a[k]['rank']>b[k]['rank'])])
    return dict(scope='Known regressions and hand-authored prefix recall probes; not fresh natural accuracy or phone performance',
                probes=probes,clean=clean,typo=typo,sourcePins=after['sources'],assets=lock['assetPins'])


if __name__ == '__main__':
    result = evaluate(); output = ART/'comparison.json'
    assert not output.exists()
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'probes':result['probes'],**{g:{k:{f:v[f] for f in ['before','after','firstLosses','rankLosses']} for k,v in result[g].items()} for g in ['clean','typo']}},ensure_ascii=False))
