"""Close E14 with the negative replacement result and observed repeat instability intact."""
import gzip
from pathlib import Path
from collect_boundary_stage import sha, read, write_new
from evaluate_reference_engine import load_reference, compare
from freeze_libime_reference import entry

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e14'


def main():
    manifest = ROOT / 'benchmarks/results/e14-evidence-manifest.json'
    gate = ROOT / 'benchmarks/results/e14-acceptance-gate.json'
    archive = ROOT / 'benchmarks/results/e14-evidence'
    assert not manifest.exists() and not gate.exists() and not archive.exists(), 'Keep closed evidence'
    previous = ROOT / 'benchmarks/results/e13-evidence-manifest.json'
    previous_gate = read(ROOT / 'benchmarks/results/e13-acceptance-gate.json')
    assert sha(previous.read_bytes()) == previous_gate['evidenceManifestSha256']
    for record in read(previous)['files'].values():
        packed = (ROOT / record['archive']).read_bytes()
        assert sha(packed) == record['archiveSha256'] and sha(gzip.decompress(packed)) == record['sha256']
    assert all(sha((ROOT / p).read_bytes()) == h for p, h in previous_gate['sourcePins'].items())
    assert all(sha((ROOT / 'ime-service/src/main/assets' / p).read_bytes()) == h for p, h in previous_gate['assetPins'].items())
    assert sha((ROOT / 'app/build/outputs/apk/debug/app-debug.apk').read_bytes()) == previous_gate['unchangedExistingApk']['sha256']

    original = read(ART / 'reference-lock.json'); updated = read(ART / 'data-ablation-lock.json')
    external = Path(original['externalRoot'])
    assert updated['originalReferenceLock'] == entry(ART / 'reference-lock.json')
    for lock in [original, updated]:
        assert lock['knownDataDiagnostic'] and lock['outputFilesAbsentAtFreeze']
        for p, pin in lock['localPins'].items(): assert entry(ROOT / p) == pin, p
        for p, pin in lock['externalPins'].items(): assert entry(external / p) == pin, p
    model = entry(external / 'current-data/zh_CN.lm')
    assert entry(external / 'current-data/rebuilt-model-only.lm') == model
    assert 'SUCCESS' in (ART / 'model-rebuild.log').read_text('utf-8-sig')
    excluded = (external / 'current-data/excluded-dictionary-rows.tsv').read_text('utf-8').splitlines()
    assert len(excluded) == 99 and excluded[-1] == 'TOTAL\t300624\tACCEPTED\t300526\tEXCLUDED\t98'

    results = {}; total = 0
    for split in ['dev', 'test']:
        baseline_path = ROOT / ('.artifacts/input-quality/e13/fit/development.json' if split == 'dev'
                                else '.artifacts/input-quality/e13/independent-test.json')
        key = 'developmentBaseline' if split == 'dev' else 'before'
        baseline = read(baseline_path)[key]['rows']
        input_path = ROOT / f'benchmarks/corpus/p2c-supervised-v6/{split}.tsv'
        for mode in ['reference', 'model-only', 'dictionary-only', 'both'] + (['both-repeat'] if split == 'test' else []):
            raw = ART / f'{split}-{mode}.jsonl'
            comparison_path = ART / (f'{split}-comparison.json' if mode == 'reference' else f'{split}-{mode}-comparison.json')
            report = read(comparison_path)
            header, rows = load_reference(raw.read_text('utf-8').splitlines())
            assert header['beamSize'] == 20 and header['frameSize'] == 40
            checked = compare(baseline, rows, input_path.read_text('utf-8'))
            assert all(report[k] == v for k, v in checked.items())
            for p, pin in report['inputPins'].items(): assert sha((ROOT / p).read_bytes()) == pin
            assert (ART / f'{split}-{mode}.jsonl.stderr').stat().st_size == 0
            total += len(rows)
            results[f'{split}-{mode}'] = dict(sense=report['sense'], reference=report['reference'],
                firstGains=len(report['referenceFirstGains']), firstLosses=len(report['referenceFirstLosses']))
    assert total == 876
    assert results['test-reference']['reference']['top1'] == 69
    assert results['test-both']['reference']['top1'] == 82
    assert results['test-both-repeat']['reference']['top1'] == 81
    assert results['test-both']['sense']['top1'] == 91
    controls = read(ART / 'controls.json')
    assert not controls['passed'] and controls['completeCandidateRepeatEqualRows'] == 123
    assert controls['answerMutationUnchanged'] == 2 and controls['freshContextRepeatUnchanged'] == 1
    assert controls['dictionaryControlAccepted'] == controls['dictionaryControlExcluded'] == 2
    assert len(controls['repeatChanges']) == 1
    python = (ART / 'python-tests.log').read_text('utf-8-sig')
    assert 'Ran 375 tests' in python and 'OK (skipped=1)' in python

    files = {p.relative_to(ROOT).as_posix(): p for p in ART.rglob('*') if p.is_file()}
    for lock in [original, updated]:
        files.update({p: ROOT / p for p in lock['localPins']})
    for p in ['tools/check_libime_reference_controls.py', 'tools/build_libime_reference_model.sh',
              'tools/collect_reference_engine_stage.py', 'tools/collect_boundary_stage.py',
              'docs/research/input-quality-stage-e14-2026-10-03.md', 'docs/development/input-quality-program-v2.md']:
        files[p] = ROOT / p
    # Preserve the small manifests and licenses; large source/model/build artifacts remain externally pinned.
    for p in ['package-metadata.txt', 'package-sha256.txt', 'linked-libraries.txt', 'host-environment.txt',
              'root/usr/share/doc/libime-data/copyright', 'root/usr/share/doc/libime-data-language-model/copyright',
              'data-tools/package-metadata.txt', 'data-tools/package-sha256.txt',
              'current-data/source-manifest.json', 'current-data/upstream-data-CMakeLists.txt',
              'current-data/excluded-dictionary-rows.tsv']:
        files['external-reference/' + p] = external / p
    archive.mkdir(); entries = {}
    for logical, path in sorted(files.items()):
        data = path.read_bytes(); packed = gzip.compress(data, mtime=0)
        dest = archive / (sha(logical.encode())[:20] + '.gz'); assert not dest.exists(); dest.write_bytes(packed)
        assert gzip.decompress(dest.read_bytes()) == data
        entries[logical] = dict(archive=dest.relative_to(ROOT).as_posix(), sha256=sha(data),
                               bytes=len(data), archiveSha256=sha(packed))
    write_new(manifest, dict(schemaVersion=1, dependency=dict(stage='E13', manifestSha256=sha(previous.read_bytes())),
        externalRoot=str(external), externalPins=original['externalPins'] | updated['externalPins'], files=entries))
    write_new(gate, dict(schemaVersion=1, stage='E14', decision='retain-sense-reference-not-promoted',
        referenceEnginePromoted=False, qualityImprovementClaimed=False, productionChanged=False,
        independentEvaluation=False, currentUpstreamEngineEvaluated=False,
        releaseReady=False, goalComplete=False, python=dict(run=375, passed=374, skipped=1),
        hostKotlinExecutionsThisStage=0, androidExecutionsThisStage=0, apkRebuiltThisStage=False,
        unchangedExistingApk=previous_gate['unchangedExistingApk'], results=results,
        diagnosticSentenceFamilies=188, primaryQueries=752, repeatedQueries=124, isolationQueries=4,
        completeCandidateRepeatEqualRows=123, completeCandidateRepeatComparedRows=124, repeatGatePassed=False,
        modelRebuildByteIdentical=True, currentDataModel=model, dictionaryAcceptedRecords=300526,
        dictionaryExcludedRecords=98, evidenceManifestSha256=sha(manifest.read_bytes()), archivedFiles=len(entries)))
    print(f'E14 closed: reference not promoted, repeat instability retained, Python=375, archives={len(entries)}')


if __name__ == '__main__': main()
