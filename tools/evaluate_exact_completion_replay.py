"""E20 regression comparison; known labels, permitted recall changes, no new accuracy claim."""
import json
from pathlib import Path
from audit_sentence_corpus import sha256
from evaluate_cross_domain import load, compare
from collect_boundary_stage import write_new, read

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e20'
CHANGED = {'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/' + n for n in
           ['PinyinDecoder.kt', 'PinyinSyllableSegmenter.kt']}


def evaluate():
    prior = read(ROOT / 'benchmarks/results/e19-acceptance-gate.json')
    reports = {}
    for part in ['dev', 'test']:
        for domain in ['aishell', 'tatoeba']:
            dataset = 'p2c-aishell-v7' if domain == 'aishell' else ('p2c-supervised-v6' if part == 'dev' else 'p2c-daily-mixture-v8')
            source = ROOT / f'benchmarks/corpus/{dataset}/{part}.tsv'
            before = ROOT / f'.artifacts/input-quality/e19/accepted-{part}-{domain}.jsonl'
            after = ART / f'final-{part}-{domain}.jsonl'
            text = source.read_text('utf-8')
            bh, b = load(before.read_text('utf-8').splitlines(), text)
            nh, n = load(after.read_text('utf-8').splitlines(), text)
            assert bh['sources'] == prior['sourcePins']
            assert {p for p in bh['sources'] if bh['sources'][p] != nh['sources'][p]} == CHANGED
            assert all(sha256(ROOT / p) == h for p, h in nh['sources'].items())
            assert bh['fourgram'] == nh['fourgram'] and not nh['fourgram']['enabled']
            assert bh['inputSha256'] == nh['inputSha256'] == sha256(source)
            report = compare({**bh, 'sources': nh['sources']}, b, nh, n)
            report['sourceChanges'] = {p: dict(before=bh['sources'][p], after=nh['sources'][p]) for p in sorted(CHANGED)}
            report['fullProgressiveEqual'] = sum(b[k]['resultSha256'] == n[k]['resultSha256'] for k in b)
            report['pins'] = {str(p): sha256(p) for p in [source, before, after]}
            reports[f'{part}-{domain}'] = report

    def interaction(path):
        rows = [json.loads(s) for s in path.read_text('utf-8').splitlines()]
        end = rows[-1]
        records = {(r['query'], r['context'], r['limit']): r for r in rows if r['type'] == 'row'}
        assert end['passed'] and end['states'] == len(records) == 1468
        assert end['repeatedCompleteResults'] == 2936 and end['personalAssertions'] == 12
        return records, end
    old, _ = interaction(ROOT / '.artifacts/input-quality/e19/accepted-interaction.jsonl')
    new, end = interaction(ART / 'final-interaction.jsonl')
    assert old.keys() == new.keys()
    changes = {}
    for model, field in [('default-threegram', 'beforeTop5'), ('optional-fourgram', 'afterTop5')]:
        changes[model] = [dict(query=k[0], context=k[1], limit=k[2], before=old[k][field], after=new[k][field])
                          for k in old if old[k][field][:1] != new[k][field][:1]]
    return dict(knownRegressions=reports, interaction=end, interactionFirstChanges=changes,
                permittedRuntimeSourceChanges=sorted(CHANGED), defaultModelUnchanged=True,
                scope='Previously opened regression data and synthetic controls, not independent natural-input accuracy')


if __name__ == '__main__':
    report = evaluate()
    write_new(ART / 'final-replay-comparison.json', report)
    print(json.dumps(dict(quality={k: dict(before=v['before'], after=v['after'],
        losses=len(v['firstLosses']), rankLosses=len(v['rankLosses'])) for k, v in report['knownRegressions'].items()},
        interactionFirstChanges={k: len(v) for k, v in report['interactionFirstChanges'].items()}), ensure_ascii=False))
