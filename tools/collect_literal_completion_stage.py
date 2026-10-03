"""Seal E21's actual ranking/alias fix, real editor regressions and measured cost."""
import gzip
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

from collect_boundary_stage import sha, read, write_new
from evaluate_literal_completion_stage import ROOT, ART, CHANGED, evaluate
from summarize_input_latency import summarize, latency_regression_gate
from verify_literal_completion_refactor import verify as verify_refactor
from summarize_art_profile import summarize as summarize_art


def digest(path):
    return sha(path.read_bytes())


def main():
    archive = ROOT / 'benchmarks/results/e21-evidence'
    manifest = ROOT / 'benchmarks/results/e21-evidence-manifest.json'
    gate = ROOT / 'benchmarks/results/e21-acceptance-gate.json'
    assert not any(p.exists() for p in [archive, manifest, gate])
    previous = ROOT / 'benchmarks/results/e20-evidence-manifest.json'
    prior = read(ROOT / 'benchmarks/results/e20-acceptance-gate.json')
    before = read(ART / 'before-lock.json')
    assert digest(previous) == before['manifestSha256'] == prior['evidenceManifestSha256']
    for item in read(previous)['files'].values():
        packed = (ROOT / item['archive']).read_bytes()
        assert sha(packed) == item['archiveSha256'] and sha(gzip.decompress(packed)) == item['sha256']
    apk = read(ART / 'perf-apk.json')
    earlier_apk = read(ART / 'apk.json')
    assert digest(Path(earlier_apk['delivery'])) == earlier_apk['sha256']
    sources = {p.relative_to(ROOT).as_posix(): digest(p) for p in (ROOT / 'core-input/src/main/kotlin').rglob('*.kt')}
    assert sources == apk['sourcePins'] and before['sourcePins'] == prior['sourcePins']
    assert {p for p in sources if sources[p] != before['sourcePins'][p]} == CHANGED
    assert apk['assetPins'] == prior['assetPins'] == before['assetPins']
    for p, h in apk['assetPins'].items():
        assert digest(ROOT / 'ime-service/src/main/assets' / p) == h
    for p, h in apk['presentationSourcePins'].items():
        assert h == prior['presentationSourcePins'][p] == digest(ROOT / p)

    report = evaluate('perf')
    assert report == read(ART / 'perf-comparison.json')
    refactor = verify_refactor()
    assert refactor == read(ART / 'perf-equivalence.json')
    assert refactor['completeScoreProbeRowsEqual'] == 74 and refactor['completeCleanProgressiveRowsEqual'] == 752
    assert refactor['typoRecordedObservationsEqual'] == 2833 and refactor['interactionRowsEqual'] == 1468
    assert report['literalProbes']['count'] == 74
    assert report['literalProbes']['beforeFirstMatches'] == 54 and report['literalProbes']['afterFirstMatches'] == 59
    assert all(r['before'] == r['after'] and not r['firstLosses'] and not r['rankLosses']
               for key in ['cleanRegressions', 'typoRegressions'] for r in report[key].values())
    assert sum(r['fullProgressiveEqual'] for r in report['cleanRegressions'].values()) == 752
    assert all(len(rows) == 3 for rows in report['interactionFirstChanges'].values())
    assert '17 tests completed, 2 failed' in (ART / 'red-tests.log').read_text('utf-8-sig')
    assert '22 tests completed, 2 failed' in (ART / 'trial1-tests.log').read_text('utf-8-sig')
    assert 'expected:&lt;你[的]&gt; but was:&lt;你[们]&gt;' in (ART / 'trial1-test-results.xml').read_text('utf-8-sig')
    assert '20 tests completed, 2 failed' in (ART / 'perf-red-tests.log').read_text('utf-8-sig')
    red = (ART / 'perf-red-test-results.xml').read_text('utf-8-sig')
    assert 'expected:&lt;238&gt; but was:&lt;368&gt;' in red
    assert 'expected:&lt;3575&gt; but was:&lt;3705&gt;' in red
    remaining = read(ART / 'remaining-probes.json')
    beiji = next(r for r in remaining['rows'] if r['query'] == 'beiji')
    assert beiji['rank'] == 0 and beiji['targetPaths'] == []

    counts = {}; xmls = []
    for module, task, expected in [('core-input','test',371), ('ime-ui','testDebugUnitTest',235),
            ('ime-service','testDebugUnitTest',312), ('app','testDebugUnitTest',3)]:
        files = list((ROOT / f'{module}/build/test-results/{task}').glob('TEST-*.xml'))
        xmls.extend(files)
        counts[module] = {k:sum(int(ET.parse(p).getroot().get(k,0)) for p in files) for k in ['tests','failures','errors','skipped']}
        assert counts[module] == dict(tests=expected, failures=0, errors=0, skipped=0)
        assert re.search(r'> Task :' + re.escape(module + ':' + task) + r'(?: UP-TO-DATE)?\n',
                         (ART / 'perf-host-tests.log').read_text('utf-8-sig'))
    assert 'BUILD SUCCESSFUL' in (ART / 'perf-host-tests.log').read_text('utf-8-sig')
    assert counts == apk['hostTests']
    py = (ART / 'final-python-tests.log').read_text('utf-8-sig')
    assert 'Ran 401 tests' in py and 'OK (skipped=1)' in py

    android = {}
    groups = {'completion':10, 'learning':3, 'warm':10, 'context':8, 'mixed':3,
              'boundary':6, 'correction':8, 'association':9, 'coldstart':4}
    runs = [('efficient-' + k,v) for k,v in groups.items()]
    expected_apks = {'app/build/outputs/apk/debug/app-debug.apk':apk['sha256'], **apk['fixturePins']}
    for name, count in runs:
        directory = ROOT / f'.artifacts/input-quality/external-editor-e21-{name}'
        env = read(directory / 'environment.json')
        log = (directory / 'instrumentation.log').read_text('utf-8-sig')
        assert re.search(r'OK \(' + str(count) + r' tests\)', log)
        assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', log)
        assert env['serial'] == 'emulator-5580' and env['sdk'] == '37' and env['apks'] == expected_apks
        android[name] = dict(tests=count, environment=env)
        if name.endswith('coldstart'):
            states = [p.read_text('utf-8') for p in (directory / 'device').glob('*.txt') if not p.name.endswith('-cadence.txt')]
            assert len(states) == 4 and all('before_typing=' in s and 'ready=false' in s and 'All broadcast queues are idle' in s for s in states)
        if name.endswith('learning'):
            alias = (directory / 'device/explicitlySelectedCorrectionAliasSurvivesReuseAndProcessRestart.txt').read_text('utf-8')
            assert all(x in alias for x in ['before_alias_first=你们', 'alias_reuse_1_first=你的',
                'alias_reuse_2_first=你的', 'alias_after_process_restart_first=你的', 'process_restart='])
            learned = (directory / 'device/progressiveNameSelectionSurvivesImmediateReuseAndProcessRestart.txt').read_text('utf-8')
            assert 'after_process_restart_first=程彻' in learned and 'intelligent_agent_restart_first=智能体' in learned
            rank = re.search(r'selected=候选词，(\d+)，程', learned)
            assert rank and int(rank[1]) <= 32
    assert sum(r['tests'] for r in android.values()) == 61
    earlier_android = {}
    for name, count in [('accepted-' + k,v) for k,v in groups.items()] + [('post-latency-learning',3)]:
        directory = ROOT / f'.artifacts/input-quality/external-editor-e21-{name}'
        env = read(directory / 'environment.json')
        log = (directory / 'instrumentation.log').read_text('utf-8-sig')
        assert re.search(r'OK \(' + str(count) + r' tests\)', log)
        assert env['apks'] == {'app/build/outputs/apk/debug/app-debug.apk':earlier_apk['sha256'], **apk['fixturePins']}
        earlier_android[name] = dict(tests=count, environment=env)
    old = ROOT / '.artifacts/input-quality/external-editor-e21-before-completion'
    old_log = (old / 'instrumentation.log').read_text('utf-8-sig')
    assert 'Tests run: 10,  Failures: 2' in old_log and 'actual=你的' in old_log and 'actual=人的' in old_log
    old_env = read(old / 'environment.json')
    assert old_env['apks']['app/build/outputs/apk/debug/app-debug.apk'] == before['beforeApk']
    for path, h in apk['fixturePins'].items():
        assert old_env['apks'][path] == h == digest(ROOT / path)

    latency_dir = ROOT / '.artifacts/input-quality/e21-efficient-latency-abba'
    latency = summarize(read(latency_dir / 'measurements.json'), latency_dir)
    assert latency == read(ART / 'perf-latency-summary.json')
    assert latency['apkSha256'] == dict(baseline=before['beforeApk'], optimized=apk['sha256'])
    assert all(r['count'] == 32 for r in latency['confirmationMs'].values())
    performance_gate = latency_regression_gate(latency)
    assert performance_gate == read(ART / 'perf-latency-gate.json')
    earlier_latency_dir = ROOT / '.artifacts/input-quality/e21-latency-abba'
    earlier_latency = summarize(read(earlier_latency_dir / 'measurements.json'), earlier_latency_dir)
    assert earlier_latency == read(ART / 'latency-summary.json')
    assert earlier_latency['apkSha256'] == dict(baseline=before['beforeApk'], optimized=earlier_apk['sha256'])
    contract = read(ART / 'perf-run-contract.json')
    assert contract['rule'] == performance_gate['rule']
    assert contract['refactorSourceSha256'] == sources['core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinDecoder.kt']
    assert contract['previousRunGate'] == latency_regression_gate(earlier_latency)
    assert not contract['previousRunGate']['passed']
    diagnostic_dir = ROOT / '.artifacts/input-quality/e21-art-diagnostic'
    diagnostic = read(diagnostic_dir / 'report.json')
    assert [r['mode'] for r in diagnostic['runs']] == ['baseline', 'candidate']
    for run in diagnostic['runs']:
        expected_hash = before['beforeApk'] if run['mode'] == 'baseline' else apk['sha256']
        assert run['apkSha256'] == expected_hash
        actual = summarize_art((diagnostic_dir / run['mode'] / 'methods.trace').read_bytes(), include_all_methods=True)
        assert actual['traceSha256'] == run['summary']['traceSha256']
        assert actual['observedDecodeCpuUs'] == run['summary']['observedDecodeCpuUs']
        # Tied display rows have no semantic order; verify every reported count against
        # the untruncated trace, not Counter insertion order across Python processes.
        for field in ('exclusiveCpuUs','inclusiveCpuUs'):
            all_methods = dict(actual[field])
            assert all(all_methods[method] == value for method,value in run['summary'][field])
    app = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'; delivery = Path(apk['delivery'])
    assert digest(app) == digest(delivery) == apk['sha256'] and app.stat().st_size == apk['bytes']
    with ZipFile(app) as n, ZipFile(Path(prior['apk']['delivery'])) as b:
        assets = {p for p in n.namelist() if p.startswith('assets/') and not p.endswith('/')}
        assert assets == {p for p in b.namelist() if p.startswith('assets/') and not p.endswith('/')}
        assert all(n.read(p) == b.read(p) for p in assets)
        assert [p for p in n.namelist() if p in b.namelist() and n.read(p) != b.read(p)] == apk['changedZipMembers'] == ['classes7.dex']
    assert 'CN=Android Debug' in (ART / 'perf-signature.txt').read_text('utf-8-sig')
    restored = read(ART / 'perf-restored-device.json')
    assert restored['defaultIme'].startswith('com.google.android.inputmethod.latin/') and restored['apkSha256'] == apk['sha256']
    visual = read(ART / 'perf-visual-review.json')
    assert visual['apkSha256'] == apk['sha256']
    for path, h in visual['screenshots'].items():
        assert digest(ROOT / path) == h

    files = {p.relative_to(ROOT).as_posix():p for p in ART.rglob('*') if p.is_file() and not {'before-classes','trial2-classes'}.intersection(p.parts)}
    classes = {p.relative_to(ART / 'before-classes').as_posix():digest(p) for p in (ART / 'before-classes').rglob('*.class')}
    assert classes == read(ART / 'baseline-compiled-classes.json')['sha256']
    files.update({p.relative_to(ROOT).as_posix():p for p in xmls})
    for directory in list((ROOT / '.artifacts/input-quality').glob('external-editor-e21-*')) + [latency_dir, earlier_latency_dir, diagnostic_dir]:
        files.update({p.relative_to(ROOT).as_posix():p for p in directory.rglob('*') if p.is_file()})
    names = sorted(CHANGED) + [
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/PinyinCompletionLanguageTest.kt',
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/CandidateRankingCalibrationTest.kt',
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/AdaptivePinyinDecoderTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorCompletionTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorLearningTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorTestFixture.kt',
        'input-quality-device/src/main/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorActivity.kt',
        'tools/test_external_editor.ps1','tools/evaluate_literal_completion_stage.py','tools/collect_literal_completion_stage.py',
        'tools/verify_literal_completion_refactor.py','tools/test_summarize_input_latency.py',
        'tools/profile_literal_completion_runtime.py','tools/summarize_art_profile.py','tools/test_summarize_art_profile.py',
        'tools/evaluate_cross_domain.py','tools/measure_input_latency.py','tools/summarize_input_latency.py',
        'docs/research/input-quality-stage-e21-2026-10-03.md','docs/development/input-quality-program-v2.md',
    ]
    files.update({n:ROOT/n for n in names})
    assert not any(p.suffix == '.apk' for p in files.values())
    archive.mkdir(); entries = {}
    for logical,path in sorted(files.items()):
        data = path.read_bytes(); packed = gzip.compress(data,mtime=0)
        out = archive / (sha(logical.encode())[:20] + '.gz'); out.write_bytes(packed)
        assert gzip.decompress(out.read_bytes()) == data
        entries[logical] = dict(archive=out.relative_to(ROOT).as_posix(), sha256=sha(data), bytes=len(data), archiveSha256=sha(packed))
    write_new(manifest,dict(schemaVersion=1, dependency=dict(stage='E20',manifestSha256=digest(previous)),
        generatedClassSnapshot='Retained locally, hashes archived in baseline-compiled-classes.json; not duplicated in archives',files=entries))
    write_new(gate,dict(schemaVersion=1,stage='E21',decision=(
        'literal-completion-and-alias-fix-with-reused-scoring-features' if performance_gate['passed']
        else 'functional-ranking-fix-retained-promotion-held-for-latency'), performanceGate=performance_gate,
        sourcePins=sources,assetPins=apk['assetPins'],presentationSourcePins=apk['presentationSourcePins'],defaultModelUnchanged=True,
        comparisons=report,refactorEquivalence=refactor,hostTests=counts,python=dict(run=401,passed=400,skipped=1),android=android,
        intrusiveRuntimeDiagnostic=diagnostic,
        earlierCandidate=dict(apk=earlier_apk,android=earlier_android,latency=earlier_latency,performanceGate=contract['previousRunGate']),
        validatedFunctionalExecutions=61,latencyConfirmations=64,latency=latency,apk=apk,
        knownIncompleteCoverage=beiji,goalComplete=False,releaseReady=False,evidenceManifestSha256=digest(manifest),archivedFiles=len(entries)))
    print(f"E21 closed: host=921, Python=401/1 skipped, Android=61, ABBA=64, performanceGate={performance_gate['passed']}, archives={len(entries)}")


if __name__ == '__main__':
    main()
