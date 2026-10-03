"""Close E20's recall fix against E19, including rejected short-syllable behavior."""
import gzip
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

from collect_boundary_stage import sha, read, write_new
from evaluate_exact_completion_replay import ROOT, ART, CHANGED, evaluate
from summarize_input_latency import summarize


def digest(path):
    return sha(path.read_bytes())


def main():
    archive = ROOT / 'benchmarks/results/e20-evidence'
    manifest = ROOT / 'benchmarks/results/e20-evidence-manifest.json'
    gate = ROOT / 'benchmarks/results/e20-acceptance-gate.json'
    assert not any(p.exists() for p in [archive, manifest, gate])
    previous = ROOT / 'benchmarks/results/e19-evidence-manifest.json'
    prior = read(ROOT / 'benchmarks/results/e19-acceptance-gate.json')
    before = read(ART / 'before-lock.json')
    assert digest(previous) == before['manifestSha256'] == prior['evidenceManifestSha256']
    for item in read(previous)['files'].values():
        data = (ROOT / item['archive']).read_bytes()
        assert sha(data) == item['archiveSha256'] and sha(gzip.decompress(data)) == item['sha256']

    apk = read(ART / 'accepted-apk.json')
    sources = {p.relative_to(ROOT).as_posix(): digest(p) for p in (ROOT / 'core-input/src/main/kotlin').rglob('*.kt')}
    assert sources == apk['sourcePins'] and before['sourcePins'] == prior['sourcePins']
    assert {p for p in sources if sources[p] != before['sourcePins'][p]} == CHANGED
    assert len(apk['presentationSourcePins']) == 1
    for p, h in apk['presentationSourcePins'].items():
        assert digest(ROOT / p) == h != digest(ART / 'ProgressiveCandidateSnapshot-before.kt')
    assert apk['assetPins'] == before['assetPins'] == prior['assetPins']
    for name, h in apk['assetPins'].items():
        assert digest(ROOT / 'ime-service/src/main/assets' / name) == h
    report = evaluate()
    assert report == read(ART / 'final-replay-comparison.json')
    assert sum(r['after']['states'] for r in report['knownRegressions'].values()) == 752
    assert sum(r['fullProgressiveEqual'] for r in report['knownRegressions'].values()) == 748
    assert all(r['before'] == r['after'] and not r['firstLosses'] and not r['rankLosses']
               for r in report['knownRegressions'].values())
    assert all(len(r) == 6 for r in report['interactionFirstChanges'].values())
    probes = {}
    for mode in ['before', 'final']:
        rows = [json.loads(line) for line in gzip.decompress((ART / f'{mode}-probe.jsonl.gz').read_bytes()).decode('utf-8').splitlines()]
        probes[mode] = next(r for r in rows if r.get('query') == 'wome')
    assert probes['before']['actual'][0] == '我么' and probes['final']['actual'][0] == '我们'
    assert '12 tests completed, 1 failed' in (ART / 'red-tests.log').read_text('utf-8-sig')
    assert '14 tests completed, 1 failed' in (ART / 'single-syllable-red.log').read_text('utf-8-sig')

    xmls = []
    counts = {}
    for module, task, expected, log_name in [
        ('core-input', 'test', 362, 'final-core-fixture.log'),
        ('ime-ui', 'testDebugUnitTest', 235, 'final-build-tests.log'),
        ('ime-service', 'testDebugUnitTest', 312, 'accepted-build-tests.log'),
        ('app', 'testDebugUnitTest', 3, 'accepted-build-tests.log'),
    ]:
        files = list((ROOT / f'{module}/build/test-results/{task}').glob('TEST-*.xml'))
        xmls.extend(files)
        counts[module] = {k: sum(int(ET.parse(p).getroot().get(k, 0)) for p in files)
                          for k in ['tests', 'failures', 'errors', 'skipped']}
        assert counts[module] == dict(tests=expected, failures=0, errors=0, skipped=0)
        log = (ART / log_name).read_text('utf-8-sig')
        assert f'> Task :{module}:{task}\n' in log and 'BUILD SUCCESSFUL' in log
    py = (ART / 'python-tests.log').read_text('utf-8-sig')
    assert 'Ran 398 tests' in py and 'OK (skipped=1)' in py

    android = {}
    groups = {'completion': 8, 'warm': 10, 'context': 8, 'mixed': 3,
              'correction': 8, 'association': 9, 'coldstart': 4, 'learning': 2}
    runs = [('accepted-' + k, v) for k, v in groups.items()] + [
        (f'accepted-splash-boundary-{i}', 6) for i in range(1, 4)
    ] + [('accepted-post-latency-learning', 2)]
    for name, count in runs:
        directory = ROOT / f'.artifacts/input-quality/external-editor-e20-{name}'
        env = read(directory / 'environment.json')
        log = (directory / 'instrumentation.log').read_text('utf-8-sig')
        assert re.search(r'OK \(' + str(count) + r' tests\)', log)
        assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', log)
        assert env['serial'] == 'emulator-5580' and env['sdk'] == '37'
        assert env['apks']['app/build/outputs/apk/debug/app-debug.apk'] == apk['sha256']
        android[name] = dict(tests=count, environment=env)
        if name.endswith('coldstart'):
            evidence = list((directory / 'device').glob('*.txt'))
            states = [p.read_text('utf-8') for p in evidence if not p.name.endswith('-cadence.txt')]
            assert len(states) == 4
            assert all('cold_stop_barrier=' in s and 'All broadcast queues are idle' in s and 'before_typing=' in s and 'ready=false' in s for s in states)
    assert sum(r['tests'] for r in android.values()) == 72
    boundary_failure = ROOT / '.artifacts/input-quality/external-editor-e20-accepted-boundary'
    assert 'Tests run: 6,  Failures: 1' in (boundary_failure / 'instrumentation.log').read_text('utf-8-sig')
    splash_log = (ART / 'accepted-boundary-failure-logcat.txt').read_text('utf-8-sig')
    assert 'Splash Screen io.github.ethanbird.senseime.inputqualityfixture' in splash_log
    assert splash_log.count('Dropping untrusted touch event due to io.github.ethanbird.senseime.inputqualityfixture') == 3
    assert 'animationType=starting_reveal' in splash_log
    assert 'splashScreen.setOnExitAnimationListener { it.remove() }' in (
        ROOT / 'input-quality-device/src/main/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorActivity.kt'
    ).read_text('utf-8')
    fixture_pins = read(ART / 'splash-fixture-pins.json')
    assert fixture_pins['productionApkSha256'] == apk['sha256']
    for path, h in fixture_pins['sha256'].items():
        assert digest(ROOT / path) == h
        if path.endswith('.apk'):
            for name in ['accepted-coldstart', 'accepted-post-latency-learning'] + [
                f'accepted-splash-boundary-{i}' for i in range(1, 4)
            ]:
                assert android[name]['environment']['apks'][path] == h
    cold_failure = ROOT / '.artifacts/input-quality/external-editor-e20-final-coldstart'
    cold_log = (cold_failure / 'instrumentation.log').read_text('utf-8-sig')
    assert 'Tests run: 4,  Failures: 1' in cold_log and 'System did not show Sense keyboard' in cold_log
    assert (cold_failure / 'device/confirmationReplaysFollowingInputInOrder-cadence.txt').read_text('utf-8') == ''
    learning_failure = ROOT / '.artifacts/input-quality/external-editor-e20-final-learning/instrumentation.log'
    assert 'Tests run: 2,  Failures: 2' in learning_failure.read_text('utf-8-sig')
    learned = ROOT / '.artifacts/input-quality/external-editor-e20-accepted-learning/device'
    for name in ['noPersonalizedLearningEditorDoesNotPromoteItsPrivateSelections', 'progressiveNameSelectionSurvivesImmediateReuseAndProcessRestart']:
        data = (learned / f'{name}.txt').read_text('utf-8')
        rank = re.search(r'selected=候选词，(\d+)，程', data)
        assert rank and int(rank[1]) <= 32
    trial_learned = (ROOT / '.artifacts/input-quality/external-editor-e20-grid-learning/device/progressiveNameSelectionSurvivesImmediateReuseAndProcessRestart.txt').read_text('utf-8')
    assert 'selected=候选词，210，程' in trial_learned and 'grid_swipe=' in trial_learned
    assert '11 tests completed, 2 failed' in (ART / 'prefix-fairness-red.log').read_text('utf-8-sig')
    old_dir = ROOT / '.artifacts/input-quality/external-editor-e20-before-completion'
    old_env = read(old_dir / 'environment.json')
    old_log = (old_dir / 'instrumentation.log').read_text('utf-8-sig')
    assert 'Tests run: 8,  Failures: 1' in old_log and 'actual=我么' in old_log
    assert old_env['apks']['app/build/outputs/apk/debug/app-debug.apk'] == before['beforeApk']
    fixture = 'input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk'
    trial_env = read(ROOT / '.artifacts/input-quality/external-editor-e20-final-completion/environment.json')
    assert old_env['apks'][fixture] == trial_env['apks'][fixture]

    latency_dir = ROOT / '.artifacts/input-quality/e20-accepted-latency-abba'
    latency = summarize(read(latency_dir / 'measurements.json'), latency_dir)
    assert latency == read(ART / 'accepted-latency-summary.json')
    assert latency['apkSha256'] == dict(baseline=before['beforeApk'], optimized=apk['sha256'])
    assert all(v['count'] == 32 for v in latency['confirmationMs'].values())
    app = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    delivery = Path(apk['delivery'])
    baseline = Path('G:/workspace/sense-input-quality-reference/e19/sense-input-quality-e19-debug.apk')
    assert digest(app) == digest(delivery) == apk['sha256']
    assert app.stat().st_size == apk['bytes'] and digest(baseline) == before['beforeApk']
    with ZipFile(app) as z, ZipFile(baseline) as b:
        assets = {n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/')}
        assert assets == {n for n in b.namelist() if n.startswith('assets/') and not n.endswith('/')}
        assert all(z.read(n) == b.read(n) for n in assets)
    signature = (ART / 'accepted-signature.txt').read_text('utf-8-sig')
    assert 'CN=Android Debug' in signature and 'e8960b5bd1b6a94564642f9bc5104b70b7066611d281e60fc7cb9ae25288386e' in signature
    restored = read(ART / 'restored-device.json')
    assert restored['apkSha256'] == apk['sha256'] and restored['defaultIme'].startswith('com.google.android.inputmethod.latin/')
    visual = read(ART / 'accepted-visual-review.json')
    assert visual['apkSha256'] == apk['sha256']
    for p, h in visual['screenshots'].items():
        assert digest(ROOT / p) == h

    files = {p.relative_to(ROOT).as_posix(): p for p in ART.rglob('*') if p.is_file()}
    files.update({p.relative_to(ROOT).as_posix(): p for p in xmls})
    for directory in list((ROOT / '.artifacts/input-quality').glob('external-editor-e20-*')) + [latency_dir, ROOT / '.artifacts/input-quality/e20-latency-abba']:
        files.update({p.relative_to(ROOT).as_posix(): p for p in directory.rglob('*') if p.is_file()})
    names = sorted(CHANGED) + [
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/PinyinCompletionLanguageTest.kt',
        'ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/ProgressiveCandidateSnapshot.kt',
        'ime-service/src/test/kotlin/io/github/ethanbird/senseime/service/ProgressiveCandidateSnapshotTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorCompletionTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorTestFixture.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorLearningTest.kt',
        'input-quality-device/src/main/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorActivity.kt',
        'tools/test_external_editor.ps1', 'tools/evaluate_exact_completion_replay.py', 'tools/collect_exact_completion_stage.py',
        'tools/evaluate_cross_domain.py', 'tools/measure_input_latency.py', 'tools/summarize_input_latency.py',
        'docs/research/input-quality-stage-e20-2026-10-03.md', 'docs/development/input-quality-program-v2.md',
    ]
    files.update({n: ROOT / n for n in names})
    assert not any(p.suffix == '.apk' for p in files.values())
    archive.mkdir()
    entries = {}
    for logical, path in sorted(files.items()):
        data = path.read_bytes()
        packed = gzip.compress(data, mtime=0)
        out = archive / (sha(logical.encode())[:20] + '.gz')
        out.write_bytes(packed)
        assert gzip.decompress(out.read_bytes()) == data
        entries[logical] = dict(archive=out.relative_to(ROOT).as_posix(), sha256=sha(data), bytes=len(data), archiveSha256=sha(packed))
    write_new(manifest, dict(schemaVersion=1, dependency=dict(stage='E19', manifestSha256=digest(previous)), files=entries))
    write_new(gate, dict(schemaVersion=1, stage='E20', decision='enable-multisyllable-literal-continuation-alongside-exact-words',
        runtimeFixImplemented=True, defaultModelUnchanged=True, sourcePins=sources, assetPins=apk['assetPins'],
        knownReplay=report, hostTests=counts, python=dict(run=398, passed=397, skipped=1),
        android=android, acceptedFunctionalExecutions=72, presentationSourcePins=apk['presentationSourcePins'],
        previousVariantRejectedForPrefixOrdinalRegression=True, previousVariantColdSetupFailures=1,
        previousVariantLearningFixtureGestureFailures=2, coldFixtureBroadcastBarrierImplemented=True, gridBoundedSwipeImplemented=True,
        acceptedApkInitialBoundaryFailures=1, externalFixtureSplashExitImplemented=True,
        latencyConfirmations=64, latency=latency, apk=apk,
        goalComplete=False, releaseReady=False, evidenceManifestSha256=digest(manifest), archivedFiles=len(entries)))
    print(f'E20 closed: host=912, Python=398/1 skipped, accepted Android=72, ABBA=64; rejected UI variant and fixture failures retained; archives={len(entries)}')


if __name__ == '__main__':
    main()
