"""Pair E21 against the sealed E20 runtime; report losses as well as gains."""
import gzip
import json
from pathlib import Path

from audit_sentence_corpus import sha256
from collect_boundary_stage import read, write_new
from evaluate_cross_domain import load, compare

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e21'
CHANGED = {'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/' + name for name in
           ['PinyinDecoder.kt', 'PinyinLanguageScorer.kt', 'AdaptivePinyinDecoder.kt']}


def paired_sources(before, after):
    assert before.keys() == after.keys()
    assert {p for p in before if before[p] != after[p]} == CHANGED
    assert all(sha256(ROOT / p) == h for p, h in after.items())


def evaluate(mode='trial2'):
    lock = read(ART / 'before-lock.json')
    previous = ROOT / 'benchmarks/results/e20-evidence-manifest.json'
    assert sha256(previous) == lock['manifestSha256']
    assert sha256(ART / 'literal-probes.tsv') == lock['probeSha256']
    report = dict(scope='Known regression sets and hand-authored incomplete-spelling controls; not fresh blind or natural-error accuracy')
    probes = []
    for name in ['before', mode]:
        rows = [json.loads(s) for s in gzip.decompress((ART / f'{name}-probe.jsonl.gz').read_bytes()).decode().splitlines()]
        assert rows[-1]['observationEquivalent'] and rows[-1]['rows'] == lock['probeCount']
        assert rows[0]['inputSha256'] == lock['probeSha256']
        probes.append((rows[0], {r['id']: r for r in rows[1:-1]}))
    (bh, b), (ah, a) = probes
    paired_sources(bh['sources'], ah['sources'])
    assert bh['sources'] == lock['sourcePins'] and bh['assets'] == ah['assets']
    assert b.keys() == a.keys()
    report['literalProbes'] = dict(count=len(a), beforeFirstMatches=sum(r['rank'] == 1 for r in b.values()),
        afterFirstMatches=sum(r['rank'] == 1 for r in a.values()),
        changes=[dict(query=b[k]['query'], expected=b[k]['expected'], stratum=b[k]['stratum'],
            before=b[k]['actual'], after=a[k]['actual'], beforeRank=b[k]['rank'], afterRank=a[k]['rank'])
            for k in b if b[k]['actual'][:1] != a[k]['actual'][:1]])

    report['typoRegressions'] = {}
    def metrics(rows):
        return dict(rows=len(rows), top1=sum(r['rank']==1 for r in rows),
            top10=sum(0 < r['rank'] <= 10 for r in rows),
            recall=sum(r['rank']>0 for r in rows), characterErrors=sum(r['characterErrors'] for r in rows))
    for dataset in ['typo-v1', 'typo-layered-v4']:
        old, new = (read(ART / f'{name}-{dataset}.json') for name in ['before', mode])
        paired_sources(*({f'core-input/src/main/kotlin/{k}': v for k, v in x['sources'].items()} for x in [old, new]))
        for field in ['inputSha256', 'assets', 'lmWeight', 'oovFeature', 'correctionCompositionBoost', 'candidateLimit', 'graphDiagnosticLimit', 'learning']:
            assert old[field] == new[field]
        assert old['inputSha256'] == sha256(ROOT / f'benchmarks/corpus/{dataset}/test.tsv')
        b, a = ({r['id']: r for r in x['observations']} for x in [old, new])
        assert b.keys() == a.keys()
        for k in b:
            for field in ['typed', 'expected', 'canonical', 'operation', 'graphCanonicalRank', 'graphAlignedRank', 'selectedProbes']:
                assert b[k][field] == a[k][field], (k, field)
        def change(k):
            return {**{f:b[k][f] for f in ['typed', 'expected', 'operation']},
                'beforeRank':b[k]['rank'], 'afterRank':a[k]['rank'],
                'before':[c['text'] for c in b[k]['top5']], 'after':[c['text'] for c in a[k]['top5']]}
        report['typoRegressions'][dataset] = dict(before=metrics(list(b.values())), after=metrics(list(a.values())),
            firstGains=[change(k) for k in b if b[k]['rank']!=1 and a[k]['rank']==1],
            firstLosses=[change(k) for k in b if b[k]['rank']==1 and a[k]['rank']!=1],
            rankLosses=[change(k) for k in b if b[k]['rank']>0 and (a[k]['rank']==0 or a[k]['rank']>b[k]['rank'])],
            operations={op:dict(before=metrics([r for r in b.values() if r['operation']==op]),
                after=metrics([r for r in a.values() if r['operation']==op])) for op in sorted({r['operation'] for r in b.values()})})

    report['cleanRegressions'] = {}
    for part in ['dev', 'test']:
        for domain in ['aishell', 'tatoeba']:
            dataset = 'p2c-aishell-v7' if domain == 'aishell' else ('p2c-supervised-v6' if part == 'dev' else 'p2c-daily-mixture-v8')
            tsv = (ROOT / f'benchmarks/corpus/{dataset}/{part}.tsv').read_text('utf-8')
            bh, b = load((ROOT / f'.artifacts/input-quality/e20/final-{part}-{domain}.jsonl').read_text('utf-8').splitlines(), tsv)
            ah, a = load((ART / f'{mode}-{part}-{domain}.jsonl').read_text('utf-8').splitlines(), tsv)
            paired_sources(bh['sources'], ah['sources'])
            result = compare({**bh, 'sources': ah['sources']}, b, ah, a)
            result['fullProgressiveEqual'] = sum(b[k]['resultSha256'] == a[k]['resultSha256'] for k in b)
            report['cleanRegressions'][f'{part}-{domain}'] = result

    def interaction(path):
        rows = [json.loads(s) for s in path.read_text('utf-8').splitlines()]
        end = rows[-1]
        assert end['passed'] and end['states'] == 1468 and end['repeatedCompleteResults'] == 2936 and end['personalAssertions'] == 12
        return {(r['query'], r['context'], r['limit']): r for r in rows if r['type'] == 'row'}
    b = interaction(ROOT / '.artifacts/input-quality/e20/final-interaction.jsonl')
    a = interaction(ART / f'{mode}-interaction.jsonl')
    assert b.keys() == a.keys()
    report['interactionFirstChanges'] = {field:[dict(query=k[0], context=k[1], limit=k[2], before=b[k][field], after=a[k][field])
        for k in b if b[k][field][:1] != a[k][field][:1]] for field in ['beforeTop5', 'afterTop5']}
    return report


if __name__ == '__main__':
    result = evaluate()
    write_new(ART / 'trial2-comparison.json', result)
    print(json.dumps({**{k:{s:{m:r[m] for m in ['before','after']} for s,r in v.items()} for k,v in result.items() if k in ['typoRegressions','cleanRegressions']},
        'literalProbes':result['literalProbes'], 'interactionChanges':{k:len(v) for k,v in result['interactionFirstChanges'].items()}}, ensure_ascii=False))
