"""Seal E22 diagnostics without promoting a held production APK or discarding failures."""
import gzip
import hashlib
import json
from pathlib import Path
import re

from summarize_burst_input import describe_incomplete_attempt
from summarize_frozen_apk_engine import summarize as summarize_engine

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e22'


def read(path):
    return json.loads(path.read_text('utf-8-sig'))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def digest(path):
    return sha(path.read_bytes())


def main():
    previous_manifest = ROOT / 'benchmarks/results/e21-evidence-manifest.json'
    previous = read(ROOT / 'benchmarks/results/e21-acceptance-gate.json')
    lock = read(ART / 'before-lock.json')
    archive = ROOT / 'benchmarks/results/e22-evidence'
    manifest = ROOT / 'benchmarks/results/e22-evidence-manifest.json'
    gate = ROOT / 'benchmarks/results/e22-acceptance-gate.json'
    assert not any(p.exists() for p in [archive, manifest, gate])
    assert digest(previous_manifest) == lock['dependencyManifestSha256'] == previous['evidenceManifestSha256']
    assert previous['performanceGate']['passed'] is False
    assert lock['sourcePins'] == previous['sourcePins'] and lock['assetPins'] == previous['assetPins']
    for group in ['sourcePins', 'presentationSourcePins']:
        for name, expected in previous[group].items():
            assert digest(ROOT / name) == expected, name
    for name, expected in lock['assetPins'].items():
        assert digest(ROOT / 'ime-service/src/main/assets' / name) == expected
    for mode in ['reference', 'candidate']:
        assert digest(Path(lock[mode+'Apk']['path'])) == lock[mode+'Apk']['sha256']
    assert digest(ROOT / 'app/build/outputs/apk/debug/app-debug.apk') == previous['apk']['sha256'] == lock['candidateApk']['sha256']

    # Earlier error is an invalid harness run, not an engine performance sample.
    initial_engine = read(ART / 'engine-abba/measurements.json')
    assert len(initial_engine['runs']) == 1 and not initial_engine['runs'][0]['passed']
    initial_log = (ART / 'engine-abba' / initial_engine['runs'][0]['directory'] / 'instrumentation.log').read_text('utf-8')
    assert 'NoSuchMethodException' in initial_log and 'java.util.List' in initial_log
    directory = ART / 'engine-abba-v2'
    measurement = read(directory / 'measurements.json')
    paths = [directory/r['directory']/'device/engine.jsonl' for r in measurement['runs']]
    engine = summarize_engine(measurement, [[json.loads(line) for line in p.read_text('utf-8').splitlines()] for p in paths], lock)
    stored = read(ART / 'engine-summary.json')
    input_files = stored.pop('inputFiles')
    assert engine == stored
    assert all(digest(ROOT / p) == h for p, h in input_files.items())
    assert engine['calls'] == 240
    assert sum(q['fullCandidateEqual'] for q in engine['perQuery']) == 8

    failed_directory = ROOT / '.artifacts/input-quality/e22-burst-abba'
    failed = read(failed_directory / 'measurements.json')
    assert [r['passed'] for r in failed['runs']] == [False, False, False, True]
    assert [len(r['samples']) for r in failed['runs']] == [0, 0, 0, 16]
    expected_failures = ['System did not show Sense keyboard', 'Production runtime ready', 'Production runtime ready']
    for r, expected_failure in zip(failed['runs'][:3], expected_failures):
        log = (failed_directory/r['artifactDirectory']/'instrumentation.log').read_text('utf-8')
        assert expected_failure in log
    for index in range(1, 4):
        log = (ART/f'focus-startup/e22-focus-startup-{index}/instrumentation.log').read_text('utf-8')
        assert 'OK (1 test)' in log and 'FAILURES!!!' not in log
    assert 'OK (1 test)' in (ART/'no-animation-smoke/instrumentation.log').read_text('utf-8')

    directory = ROOT / '.artifacts/input-quality/e22-no-animation-abba'
    burst_measurement = read(directory/'measurements.json')
    burst = describe_incomplete_attempt(burst_measurement, directory)
    assert burst == read(ART/'no-animation-summary.json')
    assert burst['apkSha256'] == dict(baseline=lock['referenceApk']['sha256'], optimized=lock['candidateApk']['sha256'])
    before = read(ART/'no-animation-abba-before.json')
    for name, expected in before['pins'].items():
        assert digest(Path(name) if Path(name).is_absolute() else ROOT/name) == expected
    assert burst['confirmationMs'] is None and not burst['completePairedComparison']
    assert [b['recordedConfirmations'] for b in burst['blocks']] == [16,16,16,0]
    assert 'Production runtime ready' in burst['blocks'][-1]['failure']

    # A fixture lifecycle change must keep warm/cold and explicit-dismissal scenarios.
    lifecycle = {}
    for label, tests in [('warm', 10), ('cold', 4)]:
        folder = ROOT/f'.artifacts/input-quality/external-editor-e22-fixture-{label}'
        env = read(folder/'environment.json')
        log = (folder/'instrumentation.log').read_text('utf-8-sig')
        assert f'OK ({tests} tests)' in log and 'FAILURES!!!' not in log
        assert env['apks']['app/build/outputs/apk/debug/app-debug.apk'] == previous['apk']['sha256']
        for name, expected in env['apks'].items():
            assert digest(ROOT/name) == expected
        if label == 'cold':
            files = [p for p in (folder/'device').glob('*.txt') if not p.name.endswith('-cadence.txt')]
            assert len(files) == 4
            assert all('ready=false' in p.read_text('utf-8') and 'All broadcast queues are idle' in p.read_text('utf-8') for p in files)
        lifecycle[label] = dict(tests=tests, environment=env)
    python = (ART/'final-python-tests.log').read_text('utf-8-sig')
    assert re.search(r'Ran 409 tests\b', python) and 'OK (skipped=1)' in python
    restored = read(ART/'restored-device.json')
    assert restored['defaultIme'].startswith('com.google.android.inputmethod.latin/')
    assert restored['apkSha256'] == previous['apk']['sha256']
    prefix = read(ART/'lexical-prefix-coverage.json')
    assert prefix['assetSha256'] == lock['assetPins']['pinyin_lexicon.bin']
    assert prefix['targetCanonicalOrdinal1'] == 130 and prefix['scanLimit'] == 96
    assert prefix['canonicalPrefixRecordCount'] == 838 and prefix['maximumStatisticalPrefixLength'] == 4
    assert prefix['targetRecord']['targetEntries'] == [dict(text='北京',weight=22789,tier=0)]

    directories = [ART, failed_directory, directory] + [ROOT/f'.artifacts/input-quality/external-editor-e22-fixture-{label}' for label in ['warm','cold']]
    files = {p.relative_to(ROOT).as_posix(): p for folder in directories for p in folder.rglob('*') if p.is_file()}
    sources = [
        'tools/measure_frozen_apk_engine.py', 'tools/summarize_frozen_apk_engine.py', 'tools/test_summarize_frozen_apk_engine.py',
        'tools/measure_input_latency.py', 'tools/summarize_burst_input.py', 'tools/test_summarize_burst_input.py',
        'tools/collect_runtime_isolation_stage.py',
        'input-quality-device/src/main/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorActivity.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorTestFixture.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/FrozenApkEngineTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorBurstInputTest.kt',
        'docs/research/input-quality-stage-e22-2026-10-03.md', 'docs/development/input-quality-program-v2.md',
    ]
    files.update({name: ROOT/name for name in sources})
    assert all(p.suffix != '.apk' for p in files.values())
    archive.mkdir(); entries = {}
    for name, path in sorted(files.items()):
        raw = path.read_bytes(); packed = gzip.compress(raw, mtime=0)
        out = archive/(sha(name.encode())[:20]+'.gz'); out.write_bytes(packed)
        entries[name] = dict(archive=out.relative_to(ROOT).as_posix(), sha256=sha(raw), bytes=len(raw), archiveSha256=sha(packed))
        assert gzip.decompress(out.read_bytes()) == raw
    manifest.write_text(json.dumps(dict(schemaVersion=1, dependency=dict(stage='E21',manifestSha256=digest(previous_manifest)),files=entries),indent=2)+'\n')
    result = dict(schemaVersion=1, stage='E22', decision='runtime-measurements-isolated-production-promotion-still-held',
        productionChanged=False, sourcePins=previous['sourcePins'], presentationSourcePins=previous['presentationSourcePins'],
        assetPins=lock['assetPins'], apk=previous['apk'], inheritedPerformanceGate=previous['performanceGate'],
        frozenEngine=engine, touchDiagnostic=burst, keyboardShowFailuresRetained=1, runtimeReadyFailuresRetained=3, startupSmokePassed=3,
        knownPrefixGap=prefix,
        fixtureLifecycle=lifecycle, python=dict(run=409,passed=408,skipped=1),
        hostRegression='E21 921 passing tests retained; production unchanged, not rerun in E22',
        fast32msTypingCoverage=False, releaseReady=False, goalComplete=False,
        evidenceManifestSha256=digest(manifest), archivedFiles=len(entries))
    gate.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'E22 sealed: {len(entries)} files; 240 engine calls; incomplete touch comparison retained; production promotion still held')


if __name__ == '__main__':
    main()
