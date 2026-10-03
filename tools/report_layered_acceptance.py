"""Close E7 only after host, routed replay, APK, and dedicated-AVD evidence has finished."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality'
OUT = ROOT / 'benchmarks/results'


def read(p): return json.loads(p.read_text('utf-8-sig'))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    target = OUT / 'e7-acceptance-gate.json'
    if target.exists(): raise ValueError('Retain the closed stage')
    quality = read(OUT / 'e7-quality-gate.json')
    route = read(OUT / 'e7-routing-equivalence.json')
    assert quality['qualityPassed'] and route['passed']
    original = read(OUT / 'e7-candidate-freeze.json')
    routing = read(OUT / 'e7-routing-freeze.json')
    for p, digest in original['pins'].items():
        assert sha(ROOT / p) == routing['changedPins'].get(p, {}).get('after', digest), p
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    apk_sha = sha(apk)
    repack = read(OUT / 'e7-apk-repack-equivalence.json')
    assert repack['passed'] and repack['after']['sha256'] == apk_sha
    assert repack['before']['sha256'] == sha(ART / 'e7/candidate.apk')
    for name in ('host-build-final.log', 'clean-package-build.log'):
        assert 'BUILD SUCCESSFUL' in (ART / 'e7' / name).read_text('utf-8')
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in (ART / 'e7/compact-signature.log').read_text('utf-8')
    evidence = [ROOT / p for p in original['pins']]
    host = {}
    for module, task, expected in (('core-input','test',307),('ime-ui','testDebugUnitTest',234),
                                   ('ime-service','testDebugUnitTest',297),('app','testDebugUnitTest',3)):
        files = sorted((ROOT / module / 'build/test-results' / task).glob('TEST-*.xml'))
        counts = {k: sum(int(ET.parse(p).getroot().get(k, '0')) for p in files) for k in ('tests','failures','errors','skipped')}
        assert counts == {'tests':expected,'failures':0,'errors':0,'skipped':0}, (module, counts)
        host[module] = counts
        evidence.extend(files)
    python = (ART / 'e7/all-tool-tests-complete.log').read_text('utf-8')
    assert 'Ran 339 tests' in python and 'OK (skipped=1)' in python
    device = []
    for suffix, expected_apk in (('final', repack['before']['sha256']), ('compact', apk_sha)):
        for group, count in (('correction',8),('warm',10),('cold',4),('association',9),('learning',2)):
            folder = ART / f'external-editor-e7-{group}-{suffix}'
            log = (folder / 'instrumentation.log').read_text('utf-8-sig')
            assert f'OK ({count} tests)' in log and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', log), folder
            env = read(folder / 'environment.json')
            assert env['serial'] == 'emulator-5580'
            assert env['apks']['app/build/outputs/apk/debug/app-debug.apk'] == expected_apk
            device.append({'package':suffix,'group':group,'tests':count,'passed':True})
            evidence.extend(p for p in folder.rglob('*') if p.is_file())
    baseline = ART / 'external-editor-e7-correction-baseline'
    assert 'Tests run: 8,  Failures: 1' in (baseline / 'instrumentation.log').read_text('utf-8-sig')
    a, b = read(baseline / 'environment.json'), read(ART / 'external-editor-e7-correction-compact/environment.json')
    for name, digest in a['apks'].items():
        if name.startswith('input-quality-device/'): assert b['apks'][name] == digest
    assert a['apks']['app/build/outputs/apk/debug/app-debug.apk'] == sha(ART / 'e7/baseline.apk')
    evidence.extend(p for p in baseline.rglob('*') if p.is_file())
    cold_base = ART / 'external-editor-e7-cold-baseline'
    assert 'OK (4 tests)' in (cold_base / 'instrumentation.log').read_text('utf-8-sig')
    evidence.extend(p for p in cold_base.rglob('*') if p.is_file())
    measurements = read(ART / 'e7-latency/measurements.json')
    assert len(measurements['runs']) == 4 and all(r['passed'] and len(r['samples']) == 16 and all(s['passed'] for s in r['samples']) for r in measurements['runs'])
    assert measurements['apkSha256']['optimized'] == repack['before']['sha256']
    assert measurements['apkSha256']['baseline'] == sha(ART / 'e7/baseline.apk')
    personal = read(ART / 'e7/personalization-compact.json')
    assert personal['forbiddenPrivatePhraseAbsent']
    rows = {r['text']: r for r in personal['records']}
    assert rows['程彻']['useCount'] >= 4 and rows['智能体']['useCount'] >= 3
    assert all(r['recentPeakEvidence'] > 1 for r in rows.values())
    for name in ('apk-association-compact.json','apk-language-compact.json'):
        d = read(ART / 'e7' / name)
        assert d['passed'] and d['apkSha256'] == apk_sha
    for folder in (ART / 'e7', ART / 'e7-latency'):
        evidence.extend(p for p in folder.rglob('*') if p.is_file() and p.suffix not in ('.apk','.bin') and p.name != 'collection.log')
    evidence.extend(p for p in OUT.glob('e7-*.json') if p.name not in ('e7-evidence-manifest.json',target.name))
    evidence.extend(p for folder in ('core-input/src/test','ime-service/src/test','input-quality-device/src','ime-service/src/main/lexicon','licenses')
                    for p in (ROOT / folder).rglob('*') if p.is_file() and p.suffix not in ('.bin','.yaml'))
    evidence.extend(p for p in (ROOT / 'tools').glob('*') if p.is_file() and any(s in p.name for s in ('layered','apk_payload','project_pinyin','package_association','audit_association','test_external_editor','VerifyLayered')))
    evidence.extend([ROOT / 'ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/BundledPinyinLexicon.kt',
                     ROOT / 'app/src/main/kotlin/io/github/ethanbird/senseime/AboutSettingsScreen.kt',
                     ROOT / 'app/src/test/kotlin/io/github/ethanbird/senseime/AboutNoticeControllerTest.kt'])
    evidence.extend(ROOT / p for p in ('NOTICE',
        'docs/development/input-quality-program-v2.md', 'docs/research/input-quality-stage-e7-2026-10-03.md',
        'ime-service/src/main/assets/NOTICE.txt', 'ime-service/src/main/assets/RIME-ICE-NOTICE.txt',
        'ime-service/src/main/assets/RIME-ICE-GPL-3.0.txt', 'ime-service/src/main/assets/association_words_notice.json',
        'ime-service/src/main/assets/ASSOCIATION-MODEL-NOTICE.txt'))
    evidence.extend((ROOT / 'docs/research/images').glob('e7-*.png'))
    archive = OUT / 'e7-evidence'; archive.mkdir(exist_ok=True)
    manifest = {}
    for path in sorted(set(evidence)):
        raw = path.read_bytes(); label = path.relative_to(ROOT).as_posix()
        dest = archive / (hashlib.sha256(label.encode()).hexdigest()[:20] + '.gz')
        data = gzip.compress(raw, mtime=0)
        if dest.exists(): assert dest.read_bytes() == data
        else: dest.write_bytes(data)
        assert gzip.decompress(dest.read_bytes()) == raw
        manifest[label] = {'archive':dest.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw),'archiveSha256':sha(dest)}
    original_snapshots = {}
    for p, digest in original['pins'].items():
        name = hashlib.sha256(p.encode()).hexdigest()[:20] + '.gz'
        saved = ART / 'e7/initial-source' / name
        assert hashlib.sha256(gzip.decompress(saved.read_bytes())).hexdigest() == digest
        original_snapshots[p] = {'sha256':digest,'snapshotEvidenceKey':saved.relative_to(ROOT).as_posix()}
    manifest_path = OUT / 'e7-evidence-manifest.json'
    manifest_path.write_text(json.dumps({'schemaVersion':1,'files':manifest,'originalCandidateSourceSnapshots':original_snapshots}, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
    result = {'schemaVersion':1,'stage':'E7','passed':True,'goalComplete':False,'releaseReady':False,
              'scope':'Local debug stage; synthetic/known host replays and one API37 x86_64 dedicated AVD',
              'qualityGateSha256':sha(OUT/'e7-quality-gate.json'),'routingEquivalenceSha256':sha(OUT/'e7-routing-equivalence.json'),
              'host':host,'hostTests':sum(c['tests'] for c in host.values()),'python':{'run':339,'passed':338,'skipped':1,'skipReason':'Windows symbolic-link privilege'},
              'device':device,'finalApkScenarioExecutions':33,'preCompactScenarioExecutions':33,'baselineScenarioExecutions':12,
              'baselineExpectedFailures':1,'latencyConfirmations':64,'latencyPackage':'Precompact candidate with all 299 ZIP-entry bytes identical to final APK; timings not remeasured after layout-only repackaging',
              'personalization':personal,'dictionarySupplementAdopted':True,'supplementalRuntimeScope':'LM-bound full pinyin only',
              'apk':{'path':apk.relative_to(ROOT).as_posix(),'bytes':apk.stat().st_size,'sha256':apk_sha,'signing':'local debug, not release certificate'},
              'evidenceManifest':{'file':manifest_path.relative_to(ROOT).as_posix(),'sha256':sha(manifest_path),'files':len(manifest)},
              'openItems':['No aggregate Top1 improvement in fresh audits','Known-long character errors +1','Fixed-workload confirmation median +8 ms; post-cold PSS about +16 MiB',
                           'Unlearned 跨会话 and LM-path yisscp remain weak','Frontend explicit syllable-boundary lifecycle','Natural input and external-domain evidence']}
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:result[k] for k in ('passed','hostTests','python','finalApkScenarioExecutions','apk','evidenceManifest')},ensure_ascii=False))


if __name__ == '__main__': main()
