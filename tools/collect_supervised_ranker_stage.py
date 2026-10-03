"""Close E13's offline experiment without promoting learned weights or reusing device counts."""
import gzip
from pathlib import Path
import xml.etree.ElementTree as ET
from collect_boundary_stage import sha, read, write_new
from train_candidate_ranker import load_export, evaluate, compare, ZERO

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e13'


def digest(path):
    return sha(path.read_bytes())


def main():
    manifest = ROOT / 'benchmarks/results/e13-evidence-manifest.json'
    gate = ROOT / 'benchmarks/results/e13-acceptance-gate.json'
    target = ROOT / 'benchmarks/results/e13-evidence'
    assert not manifest.exists() and not gate.exists() and not target.exists(), 'Retain closed evidence'
    previous = ROOT / 'benchmarks/results/e12-evidence-manifest.json'
    previous_gate = read(ROOT / 'benchmarks/results/e12-acceptance-gate.json')
    assert digest(previous) == previous_gate['evidenceManifestSha256']
    for entry in read(previous)['files'].values():
        packed = (ROOT / entry['archive']).read_bytes()
        assert sha(packed) == entry['archiveSha256'] and sha(gzip.decompress(packed)) == entry['sha256']

    fit = read(ART / 'fit/development.json')
    model_path = ART / 'fit/frozen-model.json'
    model = read(model_path); freeze = read(ART / 'pre-test-freeze.json')
    test_path = ART / 'independent-test.json'; test = read(test_path)
    uncertainty = read(ART / 'uncertainty.json')
    assert freeze['testExportAbsent'] and freeze['modelSha256'] == test['frozenModelSha256'] == digest(model_path)
    assert model['developmentSha256'] == freeze['developmentSha256'] == digest(ART / 'fit/development.json')
    assert model['policySha256'] == freeze['fitPolicySha256'] == digest(ROOT / 'benchmarks/corpus/e13-fit-policy.json')
    assert model['trainerSha256'] == digest(ROOT / 'tools/train_candidate_ranker.py')
    assert model['auditSha256'] == digest(ROOT / 'tools/audit_score_features.py')
    assert model['dataFrozenSha256'] == digest(ROOT / 'benchmarks/corpus/p2c-supervised-v6/frozen.json')
    assert model['testInputSha256'] == freeze['testInputSha256'] == digest(ROOT / 'benchmarks/corpus/p2c-supervised-v6/test.tsv')
    assert model['sources'] == freeze['sources'] and model['assets'] == freeze['assets'] == previous_gate['assetPins']
    assert all(digest(ROOT / p) == h for p, h in model['sources'].items())
    assert all(digest(ROOT / 'ime-service/src/main/assets' / p) == h for p, h in model['assets'].items())
    changed = [p for p, h in previous_gate['sourcePins'].items() if digest(ROOT / p) != h]
    assert len(model['sources']) == len(previous_gate['sourcePins']) == 62
    assert [Path(p).name for p in changed] == ['M19ScoreFeatureBenchmark.kt']
    assert digest(ROOT / 'app/build/outputs/apk/debug/app-debug.apk') == previous_gate['apk']['sha256']

    corpus = ROOT / 'benchmarks/corpus'
    train_manifest_path = corpus / 'ranker-train-v1/manifest.json'; train_manifest = read(train_manifest_path)
    data_frozen = read(corpus / 'p2c-supervised-v6/frozen.json')
    assert fit['trainManifestSha256'] == digest(train_manifest_path)
    for filename, field in [('train.tsv', 'trainSha256'), ('selected.jsonl', 'selectedSha256'),
                            ('attribution.jsonl', 'attributionSha256')]:
        assert digest(corpus / 'ranker-train-v1' / filename) == train_manifest[field]
    assert train_manifest['policySha256'] == digest(corpus / 'e13-selection-policy.json')
    assert train_manifest['scriptSha256'] == digest(ROOT / 'tools/prepare_weak_ranker_train.py')
    assert train_manifest['selectionScriptSha256'] == data_frozen['scriptSha256'] == digest(ROOT / 'tools/prepare_p2c.py')
    for filename, field in [('selection.json', 'selectionSha256'), ('annotations.tsv', 'annotationsSha256'),
                            ('attribution.jsonl', 'attributionSha256')]:
        assert digest(corpus / 'p2c-supervised-v6' / filename) == data_frozen[field]
    for name in ['dev', 'test']:
        assert digest(corpus / f'p2c-supervised-v6/{name}.tsv') == data_frozen['outputs'][name]['sha256']
    assert train_manifest['corpusTrainSha256'] == digest(ROOT / '.artifacts/input-quality/lm-corpus-v1/train.jsonl')
    assert train_manifest['corpusManifestSha256'] == digest(ROOT / '.artifacts/input-quality/lm-corpus-v1/corpus.json')

    audits = {}
    selected = next(m for m in fit['models'] if m['regularization'] == .1)
    assert model['delta'] == fit['delta'] == selected['delta'] and selected['converged'] and selected['iterations'] == 199
    assert fit['trainQueries'] == 1024 and fit['recalledTrainingQueries'] == 1006 and fit['pairs'] == 8048
    assert [m['regularization'] for m in fit['models']] == [.001, .01, .1, 1.]
    for name, tsv, expected_count in [('train', corpus / 'ranker-train-v1/train.tsv', 1024),
                                     ('dev', corpus / 'p2c-supervised-v6/dev.tsv', 128),
                                     ('test', corpus / 'p2c-supervised-v6/test.tsv', 248)]:
        export = ART / f'{name}-features.jsonl.gz'
        header, rows, audit = load_export(export, tsv)
        assert header['sources'] == model['sources'] and header['assets'] == model['assets']
        assert audit['rows'] == expected_count and audit['zeroDeltaEquivalent'] and audit['completeWorkloadChecked']
        assert audit['maxArithmeticError'] < .000006
        before = evaluate(rows, ZERO); after = evaluate(rows, model['delta'])
        if name == 'train':
            assert digest(export) == model['trainExportSha256'] == fit['trainExportSha256']
            assert before['overall'] == fit['trainBaseline'] and after['overall'] == selected['training']
            assert audit == fit['trainAudit']
        elif name == 'dev':
            assert digest(export) == model['devExportSha256'] == fit['devExportSha256']
            assert before == fit['developmentBaseline']
            assert {k: v for k, v in after.items() if k != 'rows'} == selected['development']
            assert compare(before, after) == selected['changes'] and audit == fit['developmentAudit']
        else:
            assert digest(export) == test['exportSha256'] and audit == test['audit']
            assert before == test['before'] and after == test['after']
            assert all(test[k] == v for k, v in compare(before, after).items())
        audits[name] = audit
        del rows
    assert test['passedOfflineGate'] and not test['productionChanged'] and not test['releaseReady'] and not test['goalComplete']
    assert [len(test[k]) for k in ['firstGains', 'firstLosses', 'rankLosses']] == [13, 10, 18]
    assert uncertainty['inputSha256'] == digest(test_path)
    assert uncertainty['rows'] == 248 and uncertainty['sourceSentences'] == 124
    assert uncertainty['metrics']['top1']['percentile95'][0] < 0 < uncertainty['metrics']['top1']['percentile95'][1]
    assert test['after']['overall']['cer'] > test['before']['overall']['cer']

    files = set(); host = {}
    for module, task in [('core-input', 'test'), ('ime-ui', 'testDebugUnitTest'),
                         ('ime-service', 'testDebugUnitTest'), ('app', 'testDebugUnitTest')]:
        paths = list((ROOT / module / 'build/test-results' / task).glob('TEST-*.xml'))
        suites = [ET.parse(p).getroot() for p in paths]
        counts = {k: sum(int(r.get(k, 0)) for r in suites) for k in ['tests', 'failures', 'errors', 'skipped']}
        assert counts['tests'] and counts['failures'] == counts['errors'] == counts['skipped'] == 0
        files.update(paths); host[module] = counts
    assert sum(v['tests'] for v in host.values()) == 879
    assert 'BUILD SUCCESSFUL' in (ART / 'host-tests.log').read_text('utf-8-sig')
    python = (ART / 'python-tests.log').read_text('utf-8-sig')
    assert 'Ran 370 tests' in python and 'OK (skipped=1)' in python
    files.update(p for p in ART.rglob('*') if p.is_file())
    files.update(ROOT / p for p in model['sources'])
    for folder in ['ranker-train-v1', 'p2c-supervised-v6']:
        files.update(p for p in (corpus / folder).rglob('*') if p.is_file())
    for p in ['benchmarks/corpus/e13-selection-policy.json', 'benchmarks/corpus/e13-fit-policy.json',
              '.artifacts/input-quality/lm-corpus-v1/corpus.json',
              'tools/prepare_weak_ranker_train.py', 'tools/test_prepare_weak_ranker_train.py',
              'tools/train_candidate_ranker.py', 'tools/test_train_candidate_ranker.py',
              'tools/summarize_ranker_uncertainty.py', 'tools/test_summarize_ranker_uncertainty.py',
              'tools/audit_score_features.py', 'tools/prepare_p2c.py', 'tools/audit_sentence_corpus.py',
              'tools/collect_boundary_stage.py', 'tools/collect_supervised_ranker_stage.py',
              'docs/research/input-quality-stage-e13-2026-10-03.md',
              'docs/development/input-quality-learned-ranker-v1.md', 'docs/development/input-quality-program-v2.md']:
        files.add(ROOT / p)
    target.mkdir()
    entries = {}
    for path in sorted(files):
        relative = path.relative_to(ROOT).as_posix(); data = path.read_bytes(); packed = gzip.compress(data, mtime=0)
        dest = target / (sha(relative.encode())[:20] + '.gz'); assert not dest.exists(); dest.write_bytes(packed)
        assert gzip.decompress(dest.read_bytes()) == data
        entries[relative] = dict(archive=dest.relative_to(ROOT).as_posix(), sha256=sha(data),
                                bytes=len(data), archiveSha256=sha(packed))
    write_new(manifest, dict(schemaVersion=1, dependency=dict(stage='E12', manifestSha256=digest(previous)), files=entries))
    write_new(gate, dict(schemaVersion=1, stage='E13', decision='offline-prototype-hold-not-deploy',
        passedOriginalOfflineGate=True, learnedRankingPromoted=False, productionChanged=False,
        generalQualityImprovementClaimed=False, releaseReady=False, goalComplete=False,
        host=host, hostTests=879, python=dict(run=370, passed=369, skipped=1),
        sourcePins=model['sources'], assetPins=model['assets'], modelSha256=digest(model_path), audits=audits,
        testBefore=test['before']['overall'], testAfter=test['after']['overall'],
        firstGains=13, firstLosses=10, rankLosses=18, uncertainty=uncertainty,
        holdReasons=['Whole-sentence top1 unchanged', 'Ten first-choice losses retained',
                     'CER increased by one character', 'Descriptive source-cluster top1 interval crosses zero'],
        androidExecutionsThisStage=0, apkRebuiltThisStage=False, unchangedExistingApk=previous_gate['apk'],
        evidenceManifestSha256=digest(manifest), archivedFiles=len(entries)))
    print(f'E13 closed: original offline gate passed; HOLD, not deployed; host=879, Python=370, archives={len(entries)}')


if __name__ == '__main__':
    main()
