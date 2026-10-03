"""Close E10's context transport, paired diagnostics and non-promoted model trial once."""
import gzip
import json
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

from collect_boundary_stage import sha, read, write_new

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e10'


def main():
    gate = ROOT / 'benchmarks/results/e10-acceptance-gate.json'
    manifest = ROOT / 'benchmarks/results/e10-evidence-manifest.json'
    assert not gate.exists() and not manifest.exists(), 'Retain closed evidence'
    dependencies = {}
    for stage in ('e8', 'e9'):
        path = ROOT / f'benchmarks/results/{stage}-evidence-manifest.json'
        value = read(path)
        previous_gate = read(ROOT / f'benchmarks/results/{stage}-acceptance-gate.json')
        assert sha(path.read_bytes()) == previous_gate['evidenceManifestSha256']
        for entry in value['files'].values():
            packed = (ROOT / entry['archive']).read_bytes()
            assert sha(packed) == entry['archiveSha256']
            assert sha(gzip.decompress(packed)) == entry['sha256']
        dependencies[stage] = {'manifestSha256': sha(path.read_bytes()), 'verifiedArchives': len(value['files'])}
    previous = read(ROOT / 'benchmarks/results/e9-evidence-manifest.json')
    entry = previous['files']['.artifacts/input-quality/e9/candidate-source-pins.json']
    pins = json.loads(gzip.decompress((ROOT / entry['archive']).read_bytes()))['sourcePins']
    assert all(sha((ROOT / p).read_bytes()) == digest for p, digest in pins.items()), 'E10 keeps the core decoder unchanged'
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
    red = ET.parse(ART / 'context-service-red.xml').getroot()
    assert red.get('tests') == '8' and red.get('failures') == '2'
    failed = {r.get('name') for r in red.findall('testcase') if r.find('failure') is not None}
    assert failed == {'capturesTwoCompleteUnicodeScalarsOnceBeforeTheFirstComposingMutation',
                      'supplementaryPairRetainsBothCharactersAndNoOlderText'}
    log = (ART / 'tools-tests.log').read_text('utf-8-sig')
    assert 'Ran 347 tests' in log and 'OK (skipped=1)' in log
    replay = read(ROOT / 'benchmarks/results/e10-context-p2c.json')
    assert replay['suffixes'] == 540 and replay['passedContextEquivalence']
    assert len(replay['contextFirstLosses']) == 4 and len(replay['contextRankLosses']) == 18
    assert replay['metrics']['empty']['first'] == 323 and replay['metrics']['editor']['first'] == 397
    assert replay['replaySha256'] == sha((ART / 'context-p2c-v4.tsv').read_bytes())
    frozen = read(ROOT / 'benchmarks/corpus/p2c-layered-v4/frozen.json')
    input_hash = sha((ROOT / 'benchmarks/corpus/p2c-layered-v4/test.tsv').read_bytes())
    assert input_hash == frozen['outputs']['test']['sha256']
    assert (ART / 'context-p2c-v4.tsv').read_text('utf-8').startswith('# inputSha256=' + input_hash)
    diagnostic = read(ART / 'diagnostic-summary.json')
    assert diagnostic['cases'] == 40 and not diagnostic['singletonPromoted']
    assert diagnostic['modes']['context'] == {'productionFirst': 28, 'singletonFirst': 27}
    trial = read(ART / 'singleton-dev.json')
    assert trial['modelSha256'] == sha((ART / 'singleton.scng').read_bytes())
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    apk_hash = sha(apk.read_bytes())
    audit = read(ART / 'apk-audit.json')
    assert audit['passed'] and audit['apkSha256'] == apk_hash
    assert audit['characterModelAudit']['model']['sha256'] == '39ea5d90a38ce2b6f498a0dcc9f4b161ea92168759ebeedd03ce2f3340c45a7d'
    baseline = ROOT / '.artifacts/input-quality/e9/baseline.apk'
    assert sha(baseline.read_bytes()) == read(ROOT / 'benchmarks/results/e8-acceptance-gate.json')['apk']['sha256']
    with ZipFile(baseline) as old, ZipFile(apk) as new:
        assets = {n for n in old.namelist() if n.startswith('assets/')}
        assert assets == {n for n in new.namelist() if n.startswith('assets/')}
        assert all(old.read(n) == new.read(n) for n in assets)
        asset_hashes = {n: sha(new.read(n)) for n in sorted(assets)}
    groups = {'context': 8, 'warm': 10, 'boundary': 6, 'mixed': 3, 'correction': 8,
              'association': 9, 'cold': 4, 'learning': 2}
    device = []
    for label, count in groups.items():
        folder = ROOT / f'.artifacts/input-quality/external-editor-e10-{label}-final'
        output = (folder / 'instrumentation.log').read_text('utf-8-sig')
        assert f'OK ({count} tests)' in output and 'FAILURES!!!' not in output
        env = read(folder / 'environment.json')
        assert env['apks']['app/build/outputs/apk/debug/app-debug.apk'] == apk_hash
        device.append({'group': label, 'tests': count, 'passed': True,
                       'apks': env['apks'], 'serial': env['serial'], 'sdk': env['sdk']})
        files.update(p for p in folder.rglob('*') if p.is_file())
    personal = read(ART / 'personalization-audit.json')
    assert personal['forbiddenPrivatePhraseAbsent']
    assert {r['text'] for r in personal['records']} == {'程彻', '智能体'}
    assert all(r['recentPeakEvidence'] > 1 for r in personal['records'])
    signature = (ART / 'apk-signature.log').read_text('utf-8-sig')
    assert 'CN=Android Debug' in signature and 'v2): true' in signature
    files.update(p for p in ART.iterdir() if p.is_file() and p.suffix != '.apk')
    for path in (
        'ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/SenseInputMethodService.kt',
        'ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/EditorDecodeContext.kt',
        'ime-service/src/test/kotlin/io/github/ethanbird/senseime/service/PinyinContextServiceTest.kt',
        'ime-service/src/test/kotlin/io/github/ethanbird/senseime/service/EditorDecodeContextTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorContextTest.kt',
        'tools/test_external_editor.ps1', 'tools/ContextReplay.java', 'tools/summarize_context_replay.py',
        'tools/test_summarize_context_replay.py', 'tools/collect_context_stage.py', 'tools/train_character_lm.py',
        'docs/research/input-quality-stage-e10-2026-10-03.md', 'docs/development/input-quality-program-v2.md',
        'benchmarks/results/e10-context-p2c.json', 'benchmarks/results/character-lm-v1-dev.json',
    ):
        files.add(ROOT / path)
    files.update((ROOT / 'benchmarks/corpus/p2c-layered-v4').iterdir())
    target = ROOT / 'benchmarks/results/e10-evidence'
    assert all(p.is_file() for p in files)
    target.mkdir(exist_ok=False)
    entries = {}
    for path in sorted(files):
        relative = path.relative_to(ROOT).as_posix()
        data = path.read_bytes()
        packed = gzip.compress(data, mtime=0)
        dest = target / (sha(relative.encode())[:20] + '.gz')
        assert not dest.exists()
        dest.write_bytes(packed)
        assert gzip.decompress(dest.read_bytes()) == data
        entries[relative] = {'archive': dest.relative_to(ROOT).as_posix(), 'sha256': sha(data),
                             'bytes': len(data), 'archiveSha256': sha(packed)}
    write_new(manifest, {'schemaVersion': 1, 'dependencies': dependencies, 'files': entries})
    write_new(gate, {'schemaVersion': 1, 'stage': 'E10', 'passed': True, 'goalComplete': False,
        'releaseReady': False, 'scope': 'Two-scalar context transport correctness; existing-model diagnostics, not a new semantic accuracy promotion',
        'host': host, 'hostTests': 866, 'python': {'run': 347, 'passed': 346, 'skipped': 1},
        'redServiceTests': {'tests': 8, 'failures': 2}, 'systemEditor': device, 'systemFunctionalTests': 50,
        'pairedSuffixes': 540, 'pairedDecodeCalls': 2160, 'contextAblation': replay['metrics'],
        'existingContextFirstLosses': 4, 'existingContextRankLosses': 18,
        'singletonModelPromoted': False, 'coreDecoderSourcePinsUnchanged': len(pins),
        'assetHashes': asset_hashes, 'unchangedPackagedAssets': len(assets),
        'latencyMeasuredThisStage': False,
        'apk': {'sha256': apk_hash, 'bytes': apk.stat().st_size, 'signing': 'Android Debug; v2 verified'},
        'evidenceManifestSha256': sha(manifest.read_bytes()), 'archivedFiles': len(entries)})
    print(f'E10 closed: host=866, functional=50, paired=2160, archives={len(entries)}')


if __name__ == '__main__':
    main()
