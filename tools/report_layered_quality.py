"""Evaluate the frozen E7 dictionary gates without retuning or hiding unchanged results."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e7'
OUT = ROOT / 'benchmarks/results'


def read(path):
    return json.loads(path.read_text('utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    target = OUT / 'e7-quality-gate.json'
    if target.exists():
        raise ValueError('Retain closed quality reports')
    freeze = read(OUT / 'e7-candidate-freeze.json')
    for path, digest in freeze['pins'].items():
        assert sha(ROOT / path) == digest, path
    names = ('known-long', 'known-e4', 'known-e6', 'known-short', 'vocabulary-audit', 'p2c-audit')
    comparisons = {name: read(ART / f'compare-{name}.json') for name in names}
    comparisons['development'] = read(ART / 'compare-long-dev-1.json')
    gates = {}
    for name, data in comparisons.items():
        if name == 'development':
            continue
        gates[name + '-no-correct-top1-loss'] = data['top1Losses'] == 0
        gates[name + '-top10'] = data['after']['top10'] >= data['before']['top10']
        if name in ('vocabulary-audit', 'p2c-audit'):
            gates[name + '-top1'] = data['after']['top1'] >= data['before']['top1']
            gates[name + '-errors'] = data['after']['characterErrors'] <= data['before']['characterErrors']
        if name == 'vocabulary-audit':
            gates[name + '-top3'] = data['after']['top3'] >= data['before']['top3']
    learning = read(ART / 'learning-layered-1.json')
    required = [r for r in learning['observations'] if r['required']]
    gates['required-memory'] = len(required) == 4 and all(r[s]['rank'] == 1 for r in required for s in ('learned', 'reused', 'restored'))
    # Complete candidate equality is checked by the Kotlin runner, not inferred from top five.
    gates['restore-and-forget'] = learning['restoreCompleteOutputEqual'] and learning['forgetCompleteOutputEqual']
    result = {'schemaVersion': 1, 'stage': 'E7', 'qualityPassed': all(gates.values()),
              'scope': 'Frozen synthetic source reconstruction and authored isolated words, not natural typing accuracy',
              'candidateFreezeSha256': sha(OUT / 'e7-candidate-freeze.json'), 'gates': gates,
              'comparisons': comparisons, 'requiredMemoryCases': required,
              'limitations': ['New vocabulary audit Top1 is unchanged; no general accuracy improvement claim',
                              'Known-long and development character errors each increase by one; known CER was not a frozen rejection gate',
                              'Engineering/Android/cost gates remain separate; this is not release approval']}
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    report = read(ART / 'known-long-layer-1.json')
    reference = {'schemaVersion': 1, 'scope': 'E7 versioned host reference; known 125 families, no relabeling',
                 **{k: report[k] for k in ('lmWeight', 'oovFeature', 'correctionCompositionBoost', 'inputSha256', 'assets')},
                 'hostSourceReportSha256': sha(ART / 'known-long-layer-1.json'),
                 'observations': [{'id': r['sourceId'], 'query': r['typed'], 'expected': r['expected'], 'rank': r['rank'],
                                   'top5': [v['text'] for v in r['top5']]} for r in report['observations'] if r['operation'] == 'clean']}
    assert len(reference['observations']) == 125
    (OUT / 'e7-android-binding-reference.json').write_text(json.dumps(reference, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': result['qualityPassed'], 'gates': gates}))


if __name__ == '__main__':
    main()
