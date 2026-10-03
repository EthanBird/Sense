"""Close E12 host observability evidence, not a learned-ranker or release promotion."""
import gzip
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
from collect_boundary_stage import sha, read, write_new

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e12'


def main():
    manifest = ROOT / 'benchmarks/results/e12-evidence-manifest.json'
    gate = ROOT / 'benchmarks/results/e12-acceptance-gate.json'
    assert not manifest.exists() and not gate.exists(), 'Retain closed evidence'
    previous_path = ROOT / 'benchmarks/results/e11-evidence-manifest.json'
    previous_gate = read(ROOT / 'benchmarks/results/e11-acceptance-gate.json')
    assert sha(previous_path.read_bytes()) == previous_gate['evidenceManifestSha256']
    for value in read(previous_path)['files'].values():
        data = (ROOT / value['archive']).read_bytes()
        assert sha(data) == value['archiveSha256'] and sha(gzip.decompress(data)) == value['sha256']
    with gzip.open(ART / 'final-dev-features.jsonl.gz', 'rt', encoding='utf-8') as handle:
        header = json.loads(next(handle))
    assert all(sha((ROOT / p).read_bytes()) == digest for p, digest in header['sources'].items())
    assert all(sha((ROOT / 'ime-service/src/main/assets' / p).read_bytes()) == digest for p, digest in header['assets'].items())
    assert sha((ROOT / 'benchmarks/corpus/p2c-context-v5/dev.tsv').read_bytes()) == header['inputSha256']
    old_pins = read(ROOT / '.artifacts/input-quality/e11/baseline-pins.json')['sourcePins']
    changed = [p for p, digest in old_pins.items() if sha((ROOT / p).read_bytes()) != digest]
    assert {Path(p).name for p in changed} == {'PinyinDecoder.kt', 'CandidateRanker.kt'}
    assert len(header['sources']) == len(old_pins) + 2 == 62
    # Only metadata/source hash differs between the first and fast-disabled trace exports.
    with gzip.open(ART / 'known-dev-features.jsonl.gz', 'rb') as a, gzip.open(ART / 'final-dev-features.jsonl.gz', 'rb') as b:
        first = json.loads(next(a)); next(b)
        assert a.read() == b.read()
    diag_path = 'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinScoreDiagnostics.kt'
    assert sha((ART / 'trial-v1-PinyinScoreDiagnostics.kt').read_bytes()) == first['sources'][diag_path]
    audit = read(ART / 'final-audit-v2.json')
    assert audit['rows'] == audit['preChangeCompleteResultsCompared'] == 1080
    assert audit['paths'] == 200294 and audit['independentRankerAgreement'] and audit['observationEquivalent']
    assert audit['maxArithmeticError'] < .00001
    assert audit['exportSha256'] == sha((ART / 'final-dev-features.jsonl.gz').read_bytes())
    assert audit['baselineSha256'] == sha((ROOT / '.artifacts/input-quality/e11/dev-before.tsv').read_bytes())
    assert len(audit['strictlyPositiveRescalingObstacles']) == 17
    assert len({r['id'] for r in audit['strictlyPositiveRescalingObstacles']}) == 5
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    apk_hash = sha(apk.read_bytes())
    with zipfile.ZipFile(ROOT / '.artifacts/input-quality/e11/baseline.apk') as a, zipfile.ZipFile(apk) as b:
        assert set(a.namelist()) == set(b.namelist())
        assert [n for n in a.namelist() if a.read(n) != b.read(n)] == ['classes7.dex']
    assets = read(ART / 'final-packaged-assets.json')
    assert assets['passed'] and assets['apkSha256'] == apk_hash
    assert 'CN=Android Debug' in (ART / 'final-signature.txt').read_text('utf-8-sig')
    files = set(); host = {}
    for module, task in (('core-input', 'test'), ('ime-ui', 'testDebugUnitTest'),
                         ('ime-service', 'testDebugUnitTest'), ('app', 'testDebugUnitTest')):
        paths = list((ROOT / module / 'build/test-results' / task).glob('TEST-*.xml'))
        rows = [ET.parse(p).getroot() for p in paths]
        counts = {k: sum(int(r.get(k, 0)) for r in rows) for k in ('tests', 'failures', 'errors', 'skipped')}
        assert counts['tests'] and counts['failures'] == counts['errors'] == counts['skipped'] == 0
        host[module] = counts; files.update(paths)
    assert sum(value['tests'] for value in host.values()) == 879
    python = (ART / 'python-final-v2.log').read_text('utf-8-sig')
    assert 'Ran 358 tests' in python and 'OK (skipped=1)' in python
    assert 'BUILD SUCCESSFUL' in (ART / 'host-final.log').read_text('utf-8-sig')
    device = {}
    for name, count in [('warm', 10), ('context', 8), ('mixed', 3), ('correction', 8), ('learning', 2)]:
        folder = ROOT / f'.artifacts/input-quality/external-editor-e12-final-{name}'
        metadata = read(folder / 'environment.json')
        assert metadata['apks']['app/build/outputs/apk/debug/app-debug.apk'] == apk_hash
        log = (folder / 'instrumentation.log').read_text('utf-8-sig')
        assert f'OK ({count} tests)' in log and 'FAILURES!!!' not in log
        device[name] = count
    latency = read(ART / 'final-latency-summary.json')
    assert latency['apkSha256']['optimized'] == apk_hash
    assert latency['apkSha256']['baseline'] == previous_gate['apk']['sha256']
    assert all(value['count'] == 32 for value in latency['confirmationMs'].values())
    after = ROOT / '.artifacts/input-quality/external-editor-e12-after-perf-learning'
    assert read(after / 'environment.json')['apks']['app/build/outputs/apk/debug/app-debug.apk'] == apk_hash
    assert 'OK (2 tests)' in (after / 'instrumentation.log').read_text('utf-8-sig')
    assert read(ART / 'final-personalization.json')['forbiddenPrivatePhraseAbsent']
    files.update(p for p in ART.rglob('*') if p.is_file() and p.suffix != '.apk')
    files.update(p for folder in (ROOT / '.artifacts/input-quality').glob('external-editor-e12-*') for p in folder.rglob('*') if p.is_file())
    files.update(ROOT / p for p in header['sources'])
    files.update((ROOT / 'core-input/src/test/kotlin').rglob('PinyinScoreDiagnosticsTest.kt'))
    for p in ('tools/audit_score_features.py', 'tools/test_audit_score_features.py',
              'tools/collect_score_observation_stage.py', 'tools/measure_input_latency.py',
              'tools/summarize_input_latency.py', 'tools/test_external_editor.ps1',
              'docs/research/input-quality-stage-e12-2026-10-03.md',
              'docs/development/input-quality-learned-ranker-v1.md', 'docs/development/input-quality-program-v2.md'):
        files.add(ROOT / p)
    target = ROOT / 'benchmarks/results/e12-evidence'; target.mkdir(exist_ok=False)
    entries = {}
    for p in sorted(files):
        relative = p.relative_to(ROOT).as_posix(); data = p.read_bytes(); packed = gzip.compress(data, mtime=0)
        dest = target / (sha(relative.encode())[:20] + '.gz'); assert not dest.exists(); dest.write_bytes(packed)
        assert gzip.decompress(dest.read_bytes()) == data
        entries[relative] = dict(archive=dest.relative_to(ROOT).as_posix(), sha256=sha(data),
                                 bytes=len(data), archiveSha256=sha(packed))
    write_new(manifest, dict(schemaVersion=1, dependency=dict(stage='E11', manifestSha256=sha(previous_path.read_bytes())), files=entries))
    write_new(gate, dict(schemaVersion=1, stage='E12', decision='score-observation-verified',
        learnedRankingPromoted=False, qualityImprovementClaimed=False, releaseReady=False, goalComplete=False,
        host=host, hostTests=879, python=dict(run=358, passed=357, skipped=1),
        sourcePins=header['sources'], assetPins=header['assets'],
        baselineBytecodeSha256=sha((ART / 'final-bytecode.zip').read_bytes()),
        completeResultsEquivalent=1080, actualCandidatePaths=200294,
        maxArithmeticError=audit['maxArithmeticError'], positiveRescalingObstacleStates=17,
        positiveRescalingObstacleSourceSentences=5, androidFunctional=device,
        androidFinalApkFunctionalExecutions=33, androidFinalApkLatencyConfirmations=32,
        latency=latency['confirmationMs'], performanceGeneralizationClaimed=False,
        performanceConcern='Observed final P95 and maximum increased; no speed or zero-overhead claim.',
        apk=dict(sha256=apk_hash, bytes=apk.stat().st_size, signing='Android Debug v2; not a release artifact'),
        evidenceManifestSha256=sha(manifest.read_bytes()), archivedFiles=len(entries)))
    print(f'E12 closed: observation verified, no ranking promotion; host=879, archives={len(entries)}')


if __name__ == '__main__':
    main()
