"""Archive the E23 production fix, red/green input path, learning and known regressions."""
import gzip
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from evaluate_bounded_completion import ART, ROOT, evaluate, read, sha


def main():
    archive = ROOT/'benchmarks/results/e23-evidence'
    manifest = ROOT/'benchmarks/results/e23-evidence-manifest.json'
    gate = ROOT/'benchmarks/results/e23-acceptance-gate.json'
    assert not any(p.exists() for p in [archive,manifest,gate])
    lock = read(ART/'before-lock.json'); apk = read(ART/'apk.json')
    previous_manifest = ROOT/'benchmarks/results/e22-evidence-manifest.json'
    previous = read(ROOT/'benchmarks/results/e22-acceptance-gate.json')
    assert sha(previous_manifest) == lock['dependencyManifestSha256'] == previous['evidenceManifestSha256']
    assert apk['assetPins'] == lock['assetPins'] == previous['assetPins']
    assert apk['presentationSourcePins'] == previous['presentationSourcePins']
    for group in ['sourcePins','presentationSourcePins']:
        assert all(sha(ROOT/p) == h for p,h in apk[group].items())
    assert all(sha(ROOT/'ime-service/src/main/assets'/p) == h for p,h in apk['assetPins'].items())
    result = evaluate(); assert result == read(ART/'comparison.json')
    assert result['probes']['before'] == dict(rows=115,top1=81,top10=108,recall=114)
    assert result['probes']['after'] == dict(rows=115,top1=81,top10=112,recall=115)
    assert not result['probes']['firstLosses'] and not result['probes']['rankLosses']
    assert all(r['before']==r['after'] and not r['firstLosses'] and not r['rankLosses'] for g in ['clean','typo'] for r in result[g].values())
    assert sum(r['fullProgressiveEqual'] for r in result['clean'].values()) == 752
    assert all(r['graphEvidenceEqual'] for r in result['typo'].values())
    assert '3 tests completed, 2 failed' in (ART/'red-tests.log').read_text('utf-8-sig')
    assert '26 tests completed, 1 failed' in (ART/'green-tests.log').read_text('utf-8-sig')
    # The initial broad helper expectation omitted valid bei'ji'n... alternatives.
    assert 'beijinang, beijina' in (ART/'trial1-tests.xml').read_text('utf-8')
    counts = {}; xmls = []
    for module,task,n in [('core-input','test',378),('ime-ui','testDebugUnitTest',235),('ime-service','testDebugUnitTest',312),('app','testDebugUnitTest',3)]:
        paths = list((ROOT/f'{module}/build/test-results/{task}').glob('TEST-*.xml')); xmls += paths
        counts[module] = {k:sum(int(ET.parse(p).getroot().get(k,0)) for p in paths) for k in ['tests','failures','errors','skipped']}
        assert counts[module] == dict(tests=n,failures=0,errors=0,skipped=0)
    assert counts == apk['hostTests']
    assert 'BUILD SUCCESSFUL' in (ART/'final-host-tests.log').read_text('utf-8-sig')
    assert 'BUILD SUCCESSFUL' in (ART/'build-apk.log').read_text('utf-8-sig')
    assert 'CN=Android Debug' in (ART/'signature.txt').read_text('utf-8-sig')
    assert sha(ROOT/'app/build/outputs/apk/debug/app-debug.apk') == sha(Path(apk['delivery'])) == apk['sha256']
    with ZipFile(apk['delivery']) as a, ZipFile(lock['apk']['delivery']) as b:
        assets = [n for n in a.namelist() if n.startswith('assets/') and not n.endswith('/')]
        assert set(assets) == {n for n in b.namelist() if n.startswith('assets/') and not n.endswith('/')}
        assert all(a.read(n) == b.read(n) for n in assets)
        assert [n for n in a.namelist() if n not in b.namelist() or a.read(n) != b.read(n)] == apk['changedZipMembers'] == ['classes7.dex']

    old_dir = ROOT/'.artifacts/input-quality/external-editor-e23-before-bounded'
    old = read(old_dir/'environment.json'); log = (old_dir/'instrumentation.log').read_text('utf-8-sig')
    assert 'Tests run: 4,  Failures: 2' in log
    assert log.count('INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: Dictionary completion 北京 must be visible') == 2
    assert old['apks'] == {'app/build/outputs/apk/debug/app-debug.apk':lock['apk']['sha256'],**apk['boundedFixturePins']}
    android = {}
    final_fixture = read(ART/'final-fixture.json')
    for name,n in [('after-bounded',4),('completion',10),('context',8),('mixed',3),('boundary',6),('correction',8),('association',9),('warm',10),('cold',4),('learning',4)]:
        folder = ROOT/f'.artifacts/input-quality/external-editor-e23-{name}'
        env = read(folder/'environment.json'); log = (folder/'instrumentation.log').read_text('utf-8-sig')
        assert f'OK ({n} tests)' in log and 'FAILURES!!!' not in log
        fixture = apk['boundedFixturePins'] if name == 'after-bounded' else final_fixture['sha256']
        assert env['apks'] == {'app/build/outputs/apk/debug/app-debug.apk':apk['sha256'],**fixture}
        android[name] = dict(tests=n,environment=env)
        if name == 'cold':
            states = [p.read_text('utf-8') for p in (folder/'device').glob('*.txt') if not p.name.endswith('-cadence.txt')]
            assert len(states) == 4 and all('ready=false' in s and 'All broadcast queues are idle' in s for s in states)
        if name == 'learning':
            alias = (folder/'device/recalledCompletionAliasSurvivesDefaultReuseAndProcessRestart.txt').read_text('utf-8')
            assert all(s in alias for s in ['before_completion_alias_first=北极','completion_alias_reuse_1_first=北京',
                'completion_alias_reuse_2_first=北京','completion_alias_after_process_restart_first=北京',
                'completion_canonical_after_process_restart_first=北京','process_restart='])
            names = (folder/'device/progressiveNameSelectionSurvivesImmediateReuseAndProcessRestart.txt').read_text('utf-8')
            assert 'after_process_restart_first=程彻' in names and 'intelligent_agent_restart_first=智能体' in names
    assert sum(x['tests'] for x in android.values()) == 66
    py = (ART/'python-tests.log').read_text('utf-8-sig')
    assert 'Ran 409 tests' in py and 'OK (skipped=1)' in py
    restored = read(ART/'restored-device.json')
    assert restored['defaultIme'].startswith('com.google.android.inputmethod.latin/') and restored['apkSha256'] == apk['sha256']
    for p,h in final_fixture['sha256'].items():
        assert sha(ROOT/p) == h
    for p,h in lock['compiledClasses'].items():
        assert sha(ART/'before-classes'/p) == h

    files = {p.relative_to(ROOT).as_posix():p for p in ART.rglob('*') if p.is_file() and 'before-classes' not in p.parts}
    files.update({p.relative_to(ROOT).as_posix():p for p in xmls})
    for folder in (ROOT/'.artifacts/input-quality').glob('external-editor-e23-*'):
        files.update({p.relative_to(ROOT).as_posix():p for p in folder.rglob('*') if p.is_file()})
    names = [
        'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinDecoder.kt',
        'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinSyllableSegmenter.kt',
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/PinyinBoundedCompletionTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorBoundedCompletionTest.kt',
        'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorLearningTest.kt',
        'tools/test_external_editor.ps1','tools/run_bounded_completion_replay.py','tools/evaluate_bounded_completion.py',
        'tools/collect_bounded_completion_stage.py','docs/research/input-quality-stage-e23-2026-10-03.md',
        'docs/development/input-quality-program-v2.md',
    ]
    files.update({n:ROOT/n for n in names})
    assert all(p.suffix != '.apk' for p in files.values())
    archive.mkdir(); entries = {}
    import hashlib
    digest = lambda raw: hashlib.sha256(raw).hexdigest()
    for name,p in sorted(files.items()):
        raw = p.read_bytes(); packed = gzip.compress(raw,mtime=0)
        output = archive/(digest(name.encode())[:20]+'.gz'); output.write_bytes(packed)
        assert gzip.decompress(output.read_bytes()) == raw
        entries[name] = dict(archive=output.relative_to(ROOT).as_posix(),sha256=digest(raw),bytes=len(raw),archiveSha256=digest(packed))
    manifest.write_text(json.dumps(dict(schemaVersion=1,dependency=dict(stage='E22',manifestSha256=sha(previous_manifest)),files=entries),indent=2)+'\n')
    gate.write_text(json.dumps(dict(schemaVersion=1,stage='E23',decision='bounded-final-syllable-recall-and-learning-verified-performance-promotion-still-held',
        comparisons=result,sourcePins=apk['sourcePins'],presentationSourcePins=apk['presentationSourcePins'],assetPins=apk['assetPins'],
        hostTests=counts,python=dict(run=409,passed=408,skipped=1),android=android,validatedFunctionalExecutions=66,
        performanceGate=previous['inheritedPerformanceGate'],performanceGateInheritedFrom='E21; not remeasured in E23',
        fast32msTypingCoverage=False,apk=apk,goalComplete=False,releaseReady=False,evidenceManifestSha256=sha(manifest),archivedFiles=len(entries)),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'E23 sealed: {len(entries)} files; host 928; Python 409/1 skipped; Android 66; no performance promotion')


if __name__ == '__main__':
    main()
