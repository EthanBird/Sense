"""Seal one fixed E25 fast-input comparison, including its non-passing tail gate."""
import gzip
import hashlib
import json
from pathlib import Path

from summarize_fast_input_latency import summarize_fast

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e25'
RUN = ROOT / '.artifacts/input-quality/external-editor-e25-fast-abba'


def read(path):
    return json.loads(path.read_text('utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    archive = ROOT / 'benchmarks/results/e25-evidence'
    manifest = ROOT / 'benchmarks/results/e25-evidence-manifest.json'
    target = ROOT / 'benchmarks/results/e25-fast-input-summary.json'
    assert not any(p.exists() for p in [archive, manifest, target]), 'Keep previously sealed evidence'
    lock = read(ART / 'protocol-lock.json')
    measurements = read(RUN / 'measurements.json')
    assert (RUN / 'protocol-lock.json').read_bytes() == (ART / 'protocol-lock.json').read_bytes()
    assert sha(ART / 'protocol-lock.json') == measurements['protocolSha256']
    assert sha(ROOT / 'benchmarks/results/e24-evidence-manifest.json') == lock['dependencyManifestSha256']
    for group in [lock['productionSourcePins'], measurements['testSourceSha256']]:
        assert all(sha(ROOT / name) == digest for name, digest in group.items())
    assert all(sha(ROOT / 'ime-service/src/main/assets' / name) == digest for name, digest in lock['assetPins'].items())
    assert all(sha(Path(item['path'])) == item['sha256'] for item in lock['apks'].values())
    previous = read(ROOT / 'benchmarks/results/e24-acceptance-gate.json')
    assert sha(ROOT / 'app/build/outputs/apk/release/app-release.apk') == previous['releaseApkSha256']
    result = summarize_fast(measurements, lock, RUN)
    assert result == read(ART / 'summary.json')
    assert all(r['functionalPassed'] and r['cadencePassed'] for r in result['blocks'])
    assert not result['promotionPassed']  # Preserve the observed 1ms tail miss.
    assert measurements['restoration']['apkRestored']
    assert measurements['restoration']['ime'] == measurements['originalIme']
    assert 'BUILD SUCCESSFUL' in (ART / 'build-fixture.log').read_text('utf-8-sig')
    py = (ART / 'python-tests.log').read_text('utf-8-sig')
    assert 'Ran 428 tests' in py and 'OK (skipped=1)' in py
    sources = [
        'tools/measure_fast_input_latency.py', 'tools/summarize_fast_input_latency.py',
        'tools/test_summarize_fast_input_latency.py', 'tools/collect_fast_input_stage.py',
        'tools/summarize_input_latency.py', 'tools/summarize_pinyin_cancellation.py',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorFastLatencyTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorTestFixture.kt',
        'input-quality-device/README.md', 'input-quality-device/build.gradle.kts',
        'docs/research/input-quality-stage-e25-2026-10-03.md', 'docs/development/input-quality-program-v2.md',
    ]
    files = {name: ROOT / name for name in sources}
    for directory in [ART, RUN]:
        for path in directory.rglob('*'):
            if path.is_file():
                files[path.relative_to(ROOT).as_posix()] = path
    archive.mkdir()
    entries = {}
    for name, path in sorted(files.items()):
        raw = path.read_bytes()
        packed = archive / (hashlib.sha256(name.encode()).hexdigest()[:20] + '.gz')
        packed.write_bytes(gzip.compress(raw, mtime=0))
        assert gzip.decompress(packed.read_bytes()) == raw
        entries[name] = dict(archive=packed.relative_to(ROOT).as_posix(), sha256=sha(path),
                             archiveSha256=sha(packed), bytes=len(raw))
    manifest.write_text(json.dumps(dict(schemaVersion=1, dependencyManifestSha256=lock['dependencyManifestSha256'],
                                         files=entries), indent=2) + '\n', encoding='utf-8')
    result.update(stage='E25', evidenceManifestSha256=sha(manifest), archivedFiles=len(entries),
                  productionChanged=False, goalComplete=False, previewRelease='v0.4.16-rc.1',
                  releaseApkSha256=previous['releaseApkSha256'], python=dict(run=428, passed=427, skipped=1))
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'E25 sealed: {len(entries)} files; 64 confirmations + 8 continued sequences; product unchanged.')


if __name__ == '__main__':
    main()
