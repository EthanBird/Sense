"""Seal E24 input-source calibration without changing a production performance gate."""
import gzip
import hashlib
import json
from pathlib import Path
from summarize_injection_cadence import summarize

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'.artifacts/input-quality/e24'
read=lambda p:json.loads(p.read_text('utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    archive=ROOT/'benchmarks/results/e24-evidence';manifest=ROOT/'benchmarks/results/e24-evidence-manifest.json'
    gate=ROOT/'benchmarks/results/e24-acceptance-gate.json'
    assert not any(p.exists() for p in [archive,manifest,gate])
    lock=read(ART/'before-lock.json');previous=read(ROOT/'benchmarks/results/e23-acceptance-gate.json')
    assert all(sha(ROOT/n)==h for n,h in lock['productionSourcePins'].items())
    assert all(sha(ROOT/'ime-service/src/main/assets'/n)==h for n,h in lock['assetPins'].items())
    assert sha(ROOT/'app/build/outputs/apk/debug/app-debug.apk')==lock['debugApkSha256']
    assert sha(ROOT/'app/build/outputs/apk/release/app-release.apk')==lock['releaseApkSha256']
    before=ROOT/'.artifacts/input-quality/external-editor-e24-cadence-trial1'
    partial=ROOT/'.artifacts/input-quality/external-editor-e24-cadence-shell1'
    final=ROOT/'.artifacts/input-quality/external-editor-e24-cadence-shell2'
    warm=ROOT/'.artifacts/input-quality/external-editor-e24-default-geometry'
    assert 'NoSuchMethodException' in (before/'instrumentation.log').read_text('utf-8-sig')
    assert (before/'device/injection-cadence.jsonl').stat().st_size==0
    assert 'Shell source failed:' in (partial/'instrumentation.log').read_text('utf-8-sig')
    assert len((partial/'device/injection-cadence.jsonl').read_text().splitlines())==3
    assert 'OK (1 test)' in (final/'instrumentation.log').read_text('utf-8-sig')
    assert 'OK (10 tests)' in (warm/'instrumentation.log').read_text('utf-8-sig')
    rows=[json.loads(line) for line in (final/'device/injection-cadence.jsonl').read_text('utf-8').splitlines()]
    summary=summarize(rows);summary['environment']=read(final/'environment.json')
    assert summary==read(ART/'cadence-summary.json')
    env=summary['environment'];assert env['apks']['app/build/outputs/apk/debug/app-debug.apk']==lock['debugApkSha256']
    assert read(warm/'environment.json')['apks']==env['apks']
    assert env['touchInjectorSha256']==sha(ROOT/'build/input-quality-touch/classes.dex')
    assert env['touchInjectorSourceSha256']==sha(ROOT/'tools/android-fixture/TouchBurst.java')
    assert 'BUILD SUCCESSFUL' in (ART/'build-fixture-shell2.log').read_text('utf-8-sig')
    py=(ART/'python-tests.log').read_text('utf-8-sig')
    assert 'Ran 422 tests' in py and 'OK (skipped=1)' in py
    restored=read(ART/'restored-device.json')
    assert restored['defaultIme'].startswith('com.google.android.inputmethod.latin/')
    assert restored['debugApkSha256']==lock['debugApkSha256'] and restored['releaseApkSha256']==lock['releaseApkSha256']
    sources=['input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorTestFixture.kt',
             'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorInjectionCadenceTest.kt',
             'tools/android-fixture/TouchBurst.java','tools/prepare_touch_injector.ps1','tools/test_external_editor.ps1',
             'tools/summarize_injection_cadence.py','tools/test_summarize_injection_cadence.py','tools/collect_injection_cadence_stage.py',
             'docs/research/input-quality-stage-e24-2026-10-03.md','docs/development/input-quality-program-v2.md','input-quality-device/README.md']
    files={n:ROOT/n for n in sources}
    for directory in [ART,before,partial,final,warm]:
        for p in directory.rglob('*'):
            if p.is_file() and p.name not in ['emulator.stdout.log','emulator.stderr.log']:
                files[p.relative_to(ROOT).as_posix()]=p
    archive.mkdir();entries={}
    for name,path in sorted(files.items()):
        raw=path.read_bytes();packed=gzip.compress(raw,mtime=0)
        target=archive/(hashlib.sha256(name.encode()).hexdigest()[:20]+'.gz');target.write_bytes(packed)
        assert gzip.decompress(target.read_bytes())==raw
        entries[name]=dict(archive=target.relative_to(ROOT).as_posix(),sha256=sha(path),archiveSha256=sha(target),bytes=len(raw))
    dependency=sha(ROOT/'benchmarks/results/e23-evidence-manifest.json')
    manifest.write_text(json.dumps(dict(schemaVersion=1,dependencyManifestSha256=dependency,files=entries),indent=2)+'\n')
    result=dict(schemaVersion=1,stage='E24',decision='fast-event-source-and-editor-delivery-calibrated-no-product-speedup-claim',
                productionChanged=False,calibration=summary,ordinarySystemTests=10,python=dict(run=422,passed=421,skipped=1),
                sourcePins=lock['productionSourcePins'],assetPins=lock['assetPins'],debugApkSha256=lock['debugApkSha256'],
                releaseApkSha256=lock['releaseApkSha256'],inheritedPerformanceGate=previous['performanceGate'],
                stableReleaseReady=False,previewRelease='v0.4.16-rc.1',goalComplete=False,
                evidenceManifestSha256=sha(manifest),archivedFiles=len(entries))
    gate.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'E24 sealed: {len(entries)} files; 12 calibration bursts + 10 ordinary cases; production unchanged.')


if __name__=='__main__':main()
