"""Close E19 using final-APK evidence, retaining rejected trials without counting them."""
import gzip
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

from collect_boundary_stage import sha, read, write_new
from evaluate_completion_replay import ROOT, ART, CHANGED
from evaluate_cross_domain import load, compare
from summarize_input_latency import summarize


def digest(path):
    return sha(path.read_bytes())


def main():
    archive = ROOT / 'benchmarks/results/e19-evidence'
    manifest = ROOT / 'benchmarks/results/e19-evidence-manifest.json'
    gate = ROOT / 'benchmarks/results/e19-acceptance-gate.json'
    assert not any(p.exists() for p in [archive, manifest, gate])
    previous = ROOT / 'benchmarks/results/e18-evidence-manifest.json'
    prior = read(ROOT / 'benchmarks/results/e18-acceptance-gate.json')
    assert digest(previous) == prior['evidenceManifestSha256'] == '9ff6cde832e31f7517b6486763951d31e92db988939be5ce071d134806749426'
    for item in read(previous)['files'].values():
        packed = (ROOT / item['archive']).read_bytes()
        assert sha(packed) == item['archiveSha256']
        assert sha(gzip.decompress(packed)) == item['sha256']

    old = read(ART / 'before-lock.json')['files']
    apk = read(ART / 'accepted-apk.json')
    changed = CHANGED | {'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinSyllableSegmenter.kt'}
    actual = {p.relative_to(ROOT).as_posix(): digest(p) for p in (ROOT / 'core-input/src/main/kotlin').rglob('*.kt')}
    assert actual == apk['sourcePins']
    assert {p for p, h in actual.items() if old[p] != h} == changed
    for p, h in prior['sourcePins'].items():
        assert old[p] == h
    for p, h in prior['assetPins'].items():
        assert digest(ROOT / 'ime-service/src/main/assets' / p) == h == apk['assets'][p]

    report = read(ART / 'accepted-replay-comparison.json')
    for key, saved in report['knownRegressions'].items():
        partition, domain = key.split('-')
        dataset = 'p2c-aishell-v7' if domain == 'aishell' else ('p2c-supervised-v6' if partition == 'dev' else 'p2c-daily-mixture-v8')
        source = ROOT / f'benchmarks/corpus/{dataset}/{partition}.tsv'
        before = ROOT / f'.artifacts/input-quality/e18/{key}-baseline.jsonl'
        after = ART / f'accepted-{key}.jsonl'
        bh, b = load(before.read_text('utf-8').splitlines(), source.read_text('utf-8'))
        nh, n = load(after.read_text('utf-8').splitlines(), source.read_text('utf-8'))
        assert nh['sources'] == actual
        assert {p for p in bh['sources'] if bh['sources'][p] != nh['sources'][p]} == changed
        assert not bh['fourgram']['enabled'] and not nh['fourgram']['enabled']
        assert bh['modelSha256'] == nh['modelSha256'] == apk['assets']['pinyin_character_lm.scng']
        computed = compare({**bh, 'sources': nh['sources']}, b, nh, n)
        assert all(saved[k] == v for k, v in computed.items() if k != 'scope')
        assert saved['nonregressionPassed'] and saved['before'] == saved['after']
        assert not saved['firstLosses'] and not saved['rankLosses']
        assert saved['fullProgressiveEqual'] == sum(b[k]['resultSha256'] == n[k]['resultSha256'] for k in b)
        for p, h in saved['pins'].items():
            assert digest(Path(p)) == h
    assert sum(r['after']['states'] for r in report['knownRegressions'].values()) == 752
    assert sum(r['fullProgressiveEqual'] for r in report['knownRegressions'].values()) == 749

    def interaction(path):
        lines = [json.loads(s) for s in path.read_text('utf-8').splitlines()]
        end = lines[-1]
        rows = {(r['query'], r['context'], r['limit']): r for r in lines if r['type'] == 'row'}
        assert end['passed'] and end['states'] == len(rows) == 1468
        assert end['repeatedCompleteResults'] == 2936 and end['personalAssertions'] == 12
        return rows, end

    before, _ = interaction(ROOT / '.artifacts/input-quality/e18/interaction.jsonl')
    after, interaction_summary = interaction(ART / 'accepted-interaction.jsonl')
    assert before.keys() == after.keys()
    for model, field, expected in [('default-threegram', 'beforeTop5', 20), ('optional-fourgram', 'afterTop5', 38)]:
        differences = [dict(query=k[0], context=k[1], limit=k[2], before=before[k][field], after=after[k][field])
                       for k in before if before[k][field][:1] != after[k][field][:1]]
        assert len(differences) == expected and differences == report['interactionFirstChanges'][model]

    xmls = []
    counts = {}
    for module, variant, expected, log_name in [
        ('core-input', 'test', 357, 'accepted-core-tests.log'),
        ('ime-ui', 'testDebugUnitTest', 235, 'accepted-build-tests.log'),
        ('ime-service', 'testDebugUnitTest', 310, 'accepted-build-tests.log'),
        ('app', 'testDebugUnitTest', 3, 'accepted-build-tests.log'),
    ]:
        files = list((ROOT / f'{module}/build/test-results/{variant}').glob('TEST-*.xml'))
        xmls.extend(files)
        counts[module] = {k: sum(int(ET.parse(p).getroot().get(k, 0)) for p in files)
                          for k in ['tests', 'failures', 'errors', 'skipped']}
        assert counts[module] == dict(tests=expected, failures=0, errors=0, skipped=0)
        log = (ART / log_name).read_text('utf-8-sig')
        assert f'> Task :{module}:{variant}\n' in log and 'BUILD SUCCESSFUL' in log
    py = (ART / 'accepted-python-tests.log').read_text('utf-8-sig')
    assert 'Ran 398 tests' in py and 'OK (skipped=1)' in py

    scenarios = {'completion': 6, 'warm': 10, 'context': 8, 'mixed': 3, 'boundary': 6,
                 'correction': 8, 'association': 9, 'coldstart': 4, 'learning': 2}
    android = {}
    for name, expected in [('accepted-' + k, v) for k, v in scenarios.items()] + [('post-latency-learning', 2)]:
        directory = ROOT / f'.artifacts/input-quality/external-editor-e19-{name}'
        env = read(directory / 'environment.json')
        log = (directory / 'instrumentation.log').read_text('utf-8-sig')
        assert re.search(r'OK \(' + str(expected) + r' tests\)', log)
        assert 'FAILURES!!!' not in log and 'INSTRUMENTATION_FAILED' not in log
        assert env['serial'] == 'emulator-5580' and env['sdk'] == '37'
        assert env['apks']['app/build/outputs/apk/debug/app-debug.apk'] == apk['sha256']
        android[name] = dict(tests=expected, environment=env)
    assert sum(v['tests'] for v in android.values()) == 58
    old_failure = (ROOT / '.artifacts/input-quality/external-editor-e19-before-completion/instrumentation.log').read_text('utf-8-sig')
    assert 'Tests run: 4,  Failures: 2' in old_failure and 'actual=你想要的是什么' in old_failure

    latency_dir = ROOT / '.artifacts/input-quality/e19-latency-abba'
    latency = summarize(read(latency_dir / 'measurements.json'), latency_dir)
    assert latency == read(ART / 'accepted-latency-summary.json')
    assert latency['apkSha256']['optimized'] == apk['sha256']
    assert latency['apkSha256']['baseline'] == prior['unchangedExistingApk']['sha256']
    assert all(v['count'] == 32 for v in latency['confirmationMs'].values())

    app = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    delivery = Path('G:/workspace/sense-input-quality-reference/e19/sense-input-quality-e19-debug.apk')
    baseline = delivery.parent / 'before-app.apk'
    assert digest(app) == digest(delivery) == apk['sha256']
    assert app.stat().st_size == apk['bytes']
    assert digest(baseline) == latency['apkSha256']['baseline']
    with ZipFile(app) as z, ZipFile(baseline) as b:
        assets = [n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/')]
        assert assets and set(assets) == {n for n in b.namelist() if n.startswith('assets/') and not n.endswith('/')}
        assert all(z.read(n) == b.read(n) for n in assets)
        assert not any(n.lower().endswith('.scq4') for n in z.namelist())
    signature = (ART / 'accepted-signature.txt').read_text('utf-8-sig')
    assert 'CN=Android Debug' in signature and 'e8960b5bd1b6a94564642f9bc5104b70b7066611d281e60fc7cb9ae25288386e' in signature
    restored = read(ART / 'restored-device.json')
    assert restored['apkSha256'] == apk['sha256'] and restored['defaultIme'].startswith('com.google.android.inputmethod.latin/')

    files = {p.relative_to(ROOT).as_posix(): p for p in ART.rglob('*') if p.is_file()}
    files.update({p.relative_to(ROOT).as_posix(): p for p in xmls})
    # Intermediate variants and failed experiments are retained, never counted as final acceptance.
    for directory in list((ROOT / '.artifacts/input-quality').glob('external-editor-e19-*')) + [latency_dir]:
        files.update({p.relative_to(ROOT).as_posix(): p for p in directory.rglob('*') if p.is_file()})
    names = list(changed) + [
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/PinyinCompletionLanguageTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorCompletionTest.kt',
        'tools/test_external_editor.ps1', 'tools/evaluate_completion_replay.py',
        'tools/collect_completion_stage.py', 'tools/measure_input_latency.py', 'tools/summarize_input_latency.py',
        'tools/evaluate_cross_domain.py', 'tools/VerifyFourgramInteraction.java',
        'docs/research/input-quality-stage-e19-2026-10-03.md', 'docs/development/input-quality-program-v2.md',
    ]
    files.update({n: ROOT / n for n in names})
    assert not any(p.suffix.lower() == '.apk' for p in files.values())
    archive.mkdir()
    entries = {}
    for logical, path in sorted(files.items()):
        data = path.read_bytes()
        packed = gzip.compress(data, mtime=0)
        out = archive / (sha(logical.encode())[:20] + '.gz')
        out.write_bytes(packed)
        assert gzip.decompress(out.read_bytes()) == data
        entries[logical] = dict(archive=out.relative_to(ROOT).as_posix(), sha256=sha(data),
                                bytes=len(data), archiveSha256=sha(packed))
    write_new(manifest, dict(schemaVersion=1, dependency=dict(stage='E18', manifestSha256=digest(previous)), files=entries))
    write_new(gate, dict(schemaVersion=1, stage='E19', decision='implement-phonetic-coverage-completion-fix',
        runtimeFixImplemented=True, defaultModelUnchanged=True, optionalFourgramDeployed=False,
        sourcePins=actual, assetPins=prior['assetPins'], permittedRuntimeSourceChanges=sorted(changed),
        knownReplay=report, interaction=interaction_summary, hostTests=counts,
        python=dict(run=398, passed=397, skipped=1), android=android, androidFunctionalExecutions=58,
        latencyConfirmations=64, latency=latency, apk=dict(**apk, delivery=str(delivery)),
        goalComplete=False, releaseReady=False, evidenceManifestSha256=digest(manifest), archivedFiles=len(entries)))
    print(f'E19 closed: host=905, Python=398/1 skipped, final Android=58, ABBA=64, archives={len(entries)}')


if __name__ == '__main__':
    main()
