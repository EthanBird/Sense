"""Close the rejected E11 experiments and prove restoration; never promote a failed trial."""
import gzip
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from collect_boundary_stage import sha, read, write_new

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e11'


def main():
    manifest = ROOT / 'benchmarks/results/e11-evidence-manifest.json'
    gate = ROOT / 'benchmarks/results/e11-acceptance-gate.json'
    assert not manifest.exists() and not gate.exists(), 'Retain closed evidence'
    previous_path = ROOT / 'benchmarks/results/e10-evidence-manifest.json'
    previous_gate = read(ROOT / 'benchmarks/results/e10-acceptance-gate.json')
    assert sha(previous_path.read_bytes()) == previous_gate['evidenceManifestSha256']
    previous = read(previous_path)
    for entry in previous['files'].values():
        packed = (ROOT / entry['archive']).read_bytes()
        assert sha(packed) == entry['archiveSha256']
        assert sha(gzip.decompress(packed)) == entry['sha256']
    baseline = read(ART / 'baseline-pins.json')
    pins = baseline['sourcePins']
    assert len(pins) == 60 and all(sha((ROOT / p).read_bytes()) == digest for p, digest in pins.items())
    assert len(list((ROOT / 'core-input/src/main/kotlin').rglob('*.kt'))) == 60
    assert sha((ART / 'baseline-bytecode.zip').read_bytes()) == baseline['baselineBytecodeSha256']
    frozen = read(ART / 'development-freeze.json')
    overlays = {'PinyinDecoder.kt', 'PinyinLanguageScorer.kt', 'PinyinWordPairs.kt'}
    for p, digest in frozen['sourcePins'].items():
        source = ART / ('frozen-candidate-' + Path(p).name) if Path(p).name in overlays else ROOT / p
        assert sha(source.read_bytes()) == digest
    assert all(sha((ROOT / p).read_bytes()) == digest for p, digest in frozen['assetPins'].items())
    assert frozen['dataFrozenSha256'] == sha((ROOT / 'benchmarks/corpus/p2c-context-v5/frozen.json').read_bytes())
    reports = {}
    for label, name in (
        ('normalizedDev', 'development'), ('ungatedDev', 'competition-development'),
        ('lexicalDev', 'lexical-development'), ('frozenTest', 'frozen'), ('knownRegression', 'known'),
    ):
        value = read(ROOT / f'benchmarks/results/e11-context-{name}.json')
        assert value['passedInvariants']
        reports[label] = value
    assert not reports['normalizedDev']['passedAggregateGate'] and not reports['ungatedDev']['passedAggregateGate']
    assert reports['lexicalDev']['passedAggregateGate'] and not reports['frozenTest']['passedAggregateGate']
    assert frozen['developmentReportSha256'] == sha((ROOT / 'benchmarks/results/e11-context-lexical-development.json').read_bytes())
    trial = reports['frozenTest']
    assert trial['suffixes'] == 550 and trial['before']['editor']['first'] == 432 and trial['after']['editor']['first'] == 430
    assert len(trial['firstGains']) == 1 and len(trial['firstLosses']) == 3 and len(trial['rankLosses']) == 5
    assert 'Tests run: 5,  Failures: 2' in (ART / 'lexical-competition-baseline.log').read_text('utf-8-sig')
    assert 'BUILD SUCCESSFUL' in (ART / 'lexical-competition-unit.log').read_text('utf-8-sig')
    restored = read(ART / 'restoration.json')
    assert restored['passed'] and restored['completeReplayRowsIdentical'] == 1080
    assert (ART / 'restored-dev.tsv').read_bytes() == (ART / 'dev-before.tsv').read_bytes()
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    apk_hash = sha(apk.read_bytes())
    assert apk_hash == sha((ART / 'baseline.apk').read_bytes()) == previous_gate['apk']['sha256'] == restored['apkSha256']
    files, host = set(), {}
    for module, task in (('core-input', 'test'), ('ime-ui', 'testDebugUnitTest'),
                         ('ime-service', 'testDebugUnitTest'), ('app', 'testDebugUnitTest')):
        paths = list((ROOT / module / 'build/test-results' / task).glob('TEST-*.xml'))
        rows = [ET.parse(p).getroot() for p in paths]
        counts = {k: sum(int(r.get(k, 0)) for r in rows) for k in ('tests', 'failures', 'errors', 'skipped')}
        assert counts['tests'] and counts['failures'] == counts['errors'] == counts['skipped'] == 0
        host[module] = counts
        files.update(paths)
    assert sum(v['tests'] for v in host.values()) == 866
    log = (ART / 'tools-tests.log').read_text('utf-8-sig')
    assert 'Ran 352 tests' in log and 'OK (skipped=1)' in log
    files.update(p for p in ART.iterdir() if p.is_file() and p.suffix != '.apk')
    files.update((ROOT / 'benchmarks/corpus/p2c-context-v5').iterdir())
    files.update((ROOT / 'benchmarks/corpus').glob('e11-*.json'))
    files.update((ROOT / 'benchmarks/results').glob('e11-context-*.json'))
    for p in (
        'tools/ContextReplay.java', 'tools/summarize_context_replay.py', 'tools/compare_context_replay.py',
        'tools/test_compare_context_replay.py', 'tools/prepare_p2c.py', 'tools/collect_context_experiment.py',
        'docs/research/input-quality-stage-e11-2026-10-03.md',
        'docs/development/input-quality-learned-ranker-v1.md', 'docs/development/input-quality-program-v2.md',
    ):
        files.add(ROOT / p)
    target = ROOT / 'benchmarks/results/e11-evidence'
    assert all(p.is_file() for p in files)
    target.mkdir(exist_ok=False)
    entries = {}
    for p in sorted(files):
        relative = p.relative_to(ROOT).as_posix()
        data = p.read_bytes(); packed = gzip.compress(data, mtime=0)
        dest = target / (sha(relative.encode())[:20] + '.gz')
        assert not dest.exists()
        dest.write_bytes(packed)
        assert gzip.decompress(dest.read_bytes()) == data
        entries[relative] = {'archive': dest.relative_to(ROOT).as_posix(), 'sha256': sha(data),
                             'bytes': len(data), 'archiveSha256': sha(packed)}
    write_new(manifest, {'schemaVersion': 1, 'dependency': {'stage': 'E10', 'manifestSha256': sha(previous_path.read_bytes())}, 'files': entries})
    write_new(gate, {'schemaVersion': 1, 'stage': 'E11', 'decision': 'reject-candidate',
        'candidateQualityGatePassed': False, 'candidatePromoted': False, 'restorationVerified': True,
        'goalComplete': False, 'releaseReady': False,
        'host': host, 'hostTests': 866, 'python': {'run': 352, 'passed': 351, 'skipped': 1},
        'developmentSuffixes': 270, 'frozenSuffixes': 550,
        'frozenBefore': trial['before']['editor'], 'frozenCandidate': trial['after']['editor'],
        'frozenFirstGains': 1, 'frozenFirstLosses': 3, 'frozenRankLosses': 5,
        'restoredCoreSourceFiles': 60, 'restoredCompleteResultsIdentical': 1080,
        'newAndroidExecutions': 0, 'newAndroidLatencyMeasurements': 0,
        'reusedSystemEvidence': {'stage': 'E10', 'tests': 50, 'sameApkBytesVerified': True},
        'apk': {'sha256': apk_hash, 'bytes': apk.stat().st_size, 'signing': 'Unchanged E10 Android Debug APK'},
        'evidenceManifestSha256': sha(manifest.read_bytes()), 'archivedFiles': len(entries)})
    print(f'E11 closed: candidate rejected, baseline restored, host=866, archives={len(entries)}')


if __name__ == '__main__':
    main()
