"""First-use locked evaluation of the E18 model chosen on development data."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from audit_sentence_corpus import sha256
from evaluate_cross_domain import load, compare
from freeze_fourgram_development import ROOT, ART, EXT, choose

DATA = {'aishell': 'p2c-aishell-v7', 'tatoeba': 'p2c-daily-mixture-v8'}


def write(path, value):
    with path.open('x', encoding='utf-8') as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write('\n')


def inputs():
    freeze_path = ART/'development-freeze.json'
    freeze = json.loads(freeze_path.read_text('utf-8'))
    assert freeze['developmentGatePassed'] and freeze['selectedMode'] == choose(freeze['trials'])
    assert not freeze['testCandidatesViewedForSelection']
    files = json.loads((ART/'pre-dev-lock.json').read_text('utf-8'))['files']
    files.update(freeze['pins'])
    for name, key in [('benchmarks/corpus/e18-fourgram-policy.json', 'policySha256'),
                      ('.artifacts/input-quality/e18/pre-dev-lock.json', 'preDevLockSha256'),
                      ('tools/freeze_fourgram_development.py', 'runnerSha256'),
                      ('tools/evaluate_cross_domain.py', 'evaluatorSha256')]:
        files[str(ROOT/name)] = freeze[key]
    files[str(EXT/'training-report.json')] = freeze['trainingReportSha256']
    for p, h in files.items():
        if sha256(Path(p)) != h:
            raise ValueError('Frozen input changed: '+p)
    files[str(freeze_path)] = sha256(freeze_path)
    for dataset in DATA.values():
        path = ROOT/f'benchmarks/corpus/{dataset}/test.tsv'
        assert str(path) in files, 'Reserved test must already be pinned'
    files[str(Path(__file__).resolve())] = sha256(Path(__file__))
    return freeze, files


def main(action):
    freeze, files = inputs()
    marker = ART/'test-start.json'
    if action == 'start':
        assert not list(ART.glob('test-*.jsonl'))
        write(marker, dict(selectedMode=freeze['selectedMode'], files=files,
            startedAt=datetime.now(timezone.utc).isoformat(), outputsAbsent=True,
            tuningAfterTest=False, deploymentEnabled=False))
        print('Frozen selected model; first-use test marker written')
        return
    start = json.loads(marker.read_text('utf-8'))
    # Preserve the first-use runner; only its training-report field lookup was repaired.
    old_files = dict(start['files']); current_files = dict(files)
    runner = str(Path(__file__).resolve())
    old_runner = old_files.pop(runner); new_runner = current_files.pop(runner)
    assert old_runner == sha256(ART/'evaluate_fourgram_test-start.py')
    assert old_files == current_files and start['selectedMode'] == freeze['selectedMode']
    training = json.loads((EXT/'training-report.json').read_text('utf-8'))
    policy = json.loads((ROOT/'benchmarks/corpus/e18-fourgram-policy.json').read_text('utf-8'))
    mode = freeze['selectedMode']; domains = {}; pins = {str(marker): sha256(marker)}
    for domain, dataset in DATA.items():
        source = ROOT/f'benchmarks/corpus/{dataset}/test.tsv'
        text = source.read_text('utf-8')
        baseline = ART/f'test-{domain}-baseline.jsonl'
        proposed = ART/f'test-{domain}-{mode}.jsonl'
        bh, b = load(baseline.read_text('utf-8').splitlines(), text)
        h, rows = load(proposed.read_text('utf-8').splitlines(), text)
        expected = dict(baselineSha256=policy['baselineSha256'],
            extensionSha256=training['models'][mode]['sha256'], enabled=True,
            editorSnapshot='unchanged two-character sampled context')
        assert h['fourgram'] == expected and bh['fourgram'] == {**expected, 'enabled': False}
        assert bh['inputSha256'] == sha256(source) and len(rows) == 248
        for relative, digest in h['sources'].items():
            assert files[str(ROOT/relative)] == digest
        report = compare(bh, b, h, rows)
        out = ART/f'test-{domain}-comparison.json'
        write(out, report)
        domains[domain] = {k: report[k] for k in ['before', 'after', 'nonregressionPassed', 'slices']}
        domains[domain].update(firstGains=len(report['firstGains']), firstLosses=len(report['firstLosses']),
                              rankLosses=len(report['rankLosses']))
        pins.update({str(p): sha256(p) for p in [source, baseline, proposed, out]})
    gate = all(d['nonregressionPassed'] for d in domains.values()) and (
        sum(d['after']['top1'] for d in domains.values()) > sum(d['before']['top1'] for d in domains.values()))
    result = dict(selectedMode=mode, testGatePassed=gate, domains=domains, pins=pins,
                  tuningAfterTest=False, deploymentEnabled=False, primaryStates=992,
                  evaluatorCorrection=dict(before=old_runner, after=new_runner,
                      reason='Baseline hash belongs to frozen policy, not training report; no model, output or gate change'))
    write(ART/'test-decision.json', result)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('action', choices=['start', 'finish'])
    main(parser.parse_args().action)
