"""Close E9's algorithm, retrieval, system-input and cost evidence once."""
import gzip
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from collect_boundary_stage import sha, read, write_new

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e9'


def main():
    gate = ROOT / 'benchmarks/results/e9-acceptance-gate.json'
    manifest = ROOT / 'benchmarks/results/e9-evidence-manifest.json'
    assert not gate.exists() and not manifest.exists(), 'Retain closed evidence'
    files, host = set(), {}
    for module, task in (('core-input', 'test'), ('ime-ui', 'testDebugUnitTest'),
                         ('ime-service', 'testDebugUnitTest'), ('app', 'testDebugUnitTest')):
        paths = list((ROOT / module / 'build/test-results' / task).glob('TEST-*.xml'))
        rows = [ET.parse(p).getroot() for p in paths]
        counts = {k: sum(int(r.get(k, 0)) for r in rows) for k in ('tests', 'failures', 'errors', 'skipped')}
        assert counts['tests'] and counts['failures'] == counts['errors'] == counts['skipped'] == 0
        host[module] = counts
        files.update(paths)
    assert sum(v['tests'] for v in host.values()) == 855
    log = (ART / 'tools-tests-final.log').read_text('utf-8-sig')
    assert 'Ran 342 tests' in log and 'OK (skipped=1)' in log
    pins = read(ART / 'candidate-source-pins.json')
    for p, digest in pins['sourcePins'].items():
        assert sha((ROOT / p).read_bytes()) == digest
    assert len(pins['changedProductionCore']) == 1
    assert sha((ART / 'baseline-bytecode.zip').read_bytes()) == pins['baselineBytecodeSha256']
    sealed = read(ART / 'sealed-baseline-check.json')
    assert sealed['passed'] and sealed['completeResultsIdentical'] == 960
    assert 'legacy=72, T9=104' in (ART / 'legacy-routing.log').read_text('utf-8-sig')
    mixed = read(ROOT / 'benchmarks/results/e9-mixed-recall.json')
    ordinary = read(ROOT / 'benchmarks/results/e9-ordinary-recall.json')
    assert mixed['passedNoFirstOrRecallLoss'] and ordinary['passedNoFirstOrRecallLoss']
    assert mixed['after']['targetRecall255'] - mixed['before']['targetRecall255'] == 17
    assert len(mixed['baseDictionaryControl']['beforeLost']) == 17
    assert not mixed['baseDictionaryControl']['afterLost']
    assert ordinary['before'] == ordinary['after'] and len(ordinary['changedCompleteResults']) == 9
    assert all(row['beforeFirst'] == row['afterFirst'] and row['beforeRank'] == row['afterRank']
               for row in ordinary['changedCompleteResults'])
    assert all(row['mode'] != 'full' for row in mixed['changedCompleteResults'])
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    apk_hash = sha(apk.read_bytes())
    baseline_hash = sha((ART / 'baseline.apk').read_bytes())
    groups = {'mixed': 3, 'boundary': 6, 'warm': 10, 'correction': 8, 'association': 9, 'cold': 4, 'learning': 2}
    device = []
    for label, count in {**{name + '-final': n for name, n in groups.items()}, 'mixed-render': 3}.items():
        folder = ROOT / f'.artifacts/input-quality/external-editor-e9-{label}'
        output = (folder / 'instrumentation.log').read_text('utf-8-sig')
        assert f'OK ({count} tests)' in output and 'FAILURES!!!' not in output
        env = read(folder / 'environment.json')
        assert env['apks']['app/build/outputs/apk/debug/app-debug.apk'] == apk_hash
        device.append({'group': label, 'tests': count, 'passed': True, 'testApkSha256':
                       env['apks']['input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk']})
    folder = ROOT / '.artifacts/input-quality/external-editor-e9-mixed-baseline'
    assert 'Tests run: 3,  Failures: 3' in (folder / 'instrumentation.log').read_text('utf-8-sig')
    assert read(folder / 'environment.json')['apks']['app/build/outputs/apk/debug/app-debug.apk'] == baseline_hash
    latency = read(ROOT / 'benchmarks/results/e9-latency.json')
    assert latency['apkSha256'] == {'baseline': baseline_hash, 'optimized': apk_hash}
    assert all(x['count'] == 32 for x in latency['confirmationMs'].values())
    measured = read(ROOT / '.artifacts/input-quality/e9-latency/measurements.json')
    assert len(measured['runs']) == 4 and all(x['passed'] for x in measured['runs'])
    audit = read(ART / 'apk-audit.json')
    assert audit['passed'] and audit['apkSha256'] == apk_hash
    with ZipFile(ART / 'baseline.apk') as old, ZipFile(apk) as new:
        assets = {n for n in old.namelist() if n.startswith('assets/')}
        assert assets == {n for n in new.namelist() if n.startswith('assets/')}
        assert all(old.read(n) == new.read(n) for n in assets)
    personal = read(ART / 'personalization-audit.json')
    assert personal['forbiddenPrivatePhraseAbsent']
    assert {r['text'] for r in personal['records']} == {'程彻', '智能体'}
    assert all(r['recentPeakEvidence'] > 1 for r in personal['records'])
    signature = (ART / 'apk-signature.log').read_text('utf-8-sig')
    assert 'CN=Android Debug' in signature and 'v2): true' in signature
    files.update(p for p in ART.iterdir() if p.is_file() and p.suffix != '.apk')
    for folder in (ROOT / '.artifacts/input-quality').glob('external-editor-e9-*'):
        files.update(p for p in folder.rglob('*') if p.is_file())
    files.update(p for p in (ROOT / '.artifacts/input-quality/e9-latency').rglob('*') if p.is_file())
    files.update((ROOT / 'core-input/src/main/kotlin').rglob('*.kt'))
    files.update((ROOT / 'benchmarks/replay/mixed-recall-v1').iterdir())
    for p in (
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/LayeredMixedRecallTest.kt',
        'ime-service/src/test/kotlin/io/github/ethanbird/senseime/service/ProductionPinyinDecodersTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorMixedRecallTest.kt',
        'tools/test_external_editor.ps1', 'tools/prepare_mixed_recall.py', 'tools/MixedRecallReplay.java',
        'tools/compare_mixed_recall.py', 'tools/test_compare_mixed_recall.py', 'tools/collect_mixed_stage.py',
        'docs/research/input-quality-stage-e9-2026-10-03.md',
        'benchmarks/results/e9-mixed-recall.json', 'benchmarks/results/e9-ordinary-recall.json',
        'benchmarks/results/e9-latency.json',
    ):
        files.add(ROOT / p)
    target = ROOT / 'benchmarks/results/e9-evidence'
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
    write_new(manifest, {'schemaVersion': 1, 'files': entries})
    write_new(gate, {'schemaVersion': 1, 'stage': 'E9', 'passed': True, 'goalComplete': False,
        'releaseReady': False, 'scope': 'Bounded mixed-spelling retrieval repair; known/synthetic diagnostics and dedicated AVD',
        'host': host, 'hostTests': 855, 'python': {'run': 342, 'passed': 341, 'skipped': 1},
        'systemEditor': device, 'systemFunctionalTests': 42, 'additionalRenderedRechecks': 3,
        'baselineFunctionalTests': {'tests': 3, 'failures': 3},
        'mixedRecall': {'before': mixed['before'], 'after': mixed['after'], 'rankDemotionsRetained': len(mixed['rankLosses'])},
        'ordinaryRecall': {'rows': 2819, 'top1OrTargetRankChanges': 0, 'completeResultChanges': 9},
        'latency': latency['confirmationMs'], 'unchangedPackagedAssets': len(assets),
        'apk': {'sha256': apk_hash, 'bytes': apk.stat().st_size, 'signing': 'Android Debug; v2 verified'},
        'evidenceManifestSha256': sha(manifest.read_bytes()), 'archivedFiles': len(entries)})
    print(f'E9 closed: host=855, functional=42, rendered=3, archives={len(entries)}')


if __name__ == '__main__':
    main()
