"""Separate a post-audit compatibility repair from the frozen quality experiment."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e7'


def read(p): return json.loads(p.read_text('utf-8'))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    out = ROOT / 'benchmarks/results/e7-routing-equivalence.json'
    if out.exists(): raise ValueError('Retain previous evidence')
    freeze = read(ROOT / 'benchmarks/results/e7-routing-freeze.json')
    changed = set(freeze['changedPins'])
    assert len(changed) == 1
    for p, values in freeze['changedPins'].items(): assert sha(ROOT / p) == values['after']
    results = {}
    for name in ('known-long', 'known-e4', 'known-e6', 'known-short', 'vocabulary-audit', 'p2c-audit', 'long-dev'):
        before = ART / (name + '-layer-1.json')
        after = ART / (name + '-routed.json')
        a, b = read(before), read(after)
        observed_changes = {k for k in a['sources'].keys() | b['sources'].keys() if a['sources'].get(k) != b['sources'].get(k)}
        assert {'core-input/src/main/kotlin/' + k for k in observed_changes} == changed
        for k in observed_changes:
            assert a['sources'][k] == freeze['changedPins']['core-input/src/main/kotlin/' + k]['before']
            assert b['sources'][k] == freeze['changedPins']['core-input/src/main/kotlin/' + k]['after']
        def normalize(d):
            d.pop('sources')
            for row in d['observations']: row.pop('decodeNs')
            return d
        assert normalize(a) == normalize(b), name
        results[name] = {'rows': len(a['observations']), 'equal': True, 'beforeSha256': sha(before), 'afterSha256': sha(after)}
    a, b = read(ART / 'learning-layered-1.json'), read(ART / 'learning-routed.json')
    a.pop('sources'); b.pop('sources')
    assert a == b
    legacy = (ART / 'legacy-routing.tsv').read_text('utf-8').splitlines()[3:]
    assert len(legacy) == 176 and all(line.split('\t')[2] == 'true' for line in legacy)
    result = {'schemaVersion': 1, 'passed': True, 'scope': 'Known replay equivalence after LM-only routing; not a second blind audit',
              'comparedFields': 'Every recorded prediction, score, rank, count and graph diagnostic; only timing and reviewed source hash differ',
              'reports': results, 'rows': sum(r['rows'] for r in results.values()), 'learningEqual': True,
              'legacyCompleteResultsEqual': 72, 't9CompleteResultsEqual': 104,
              'legacyReportSha256': sha(ART / 'legacy-routing.tsv'), 'routingFreezeSha256': sha(ROOT / 'benchmarks/results/e7-routing-freeze.json')}
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__': main()
