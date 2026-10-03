"""Keep the E26 negative performance experiment and prove default-source restoration."""
import gzip
import hashlib
import json
from pathlib import Path

from summarize_fast_input_latency import summarize_fast

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'.artifacts/input-quality/e26'
RUN=ROOT/'.artifacts/input-quality/external-editor-e26-fast-abba'


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path):return json.loads(path.read_text('utf-8-sig'))


def main():
    archive=ROOT/'benchmarks/results/e26-evidence'
    manifest=ROOT/'benchmarks/results/e26-evidence-manifest.json'
    gate=ROOT/'benchmarks/results/e26-acceptance-gate.json'
    assert not any(p.exists() for p in [archive,manifest,gate])
    before=read(ART/'before-lock.json');lock=read(ART/'protocol-lock.json')
    assert all(sha(ROOT/name)==digest for name,digest in before['sourcePins'].items())
    name='core-input/src/main/kotlin/io/github/ethanbird/senseime/core/CharacterLanguageModel.kt'
    assert sha(ART/'candidate-CharacterLanguageModel.kt')==lock['productionSourcePins'][name]
    assert sha(ART/'candidate-CharacterLanguageModel.kt')!=before['sourcePins'][name]
    measurements=read(RUN/'measurements.json')
    assert measurements['protocolSha256']==sha(ART/'protocol-lock.json')
    assert all(sha(Path(item['path']))==item['sha256'] for item in lock['apks'].values())
    summary=summarize_fast(measurements,lock,RUN)
    assert summary==read(ART/'summary.json') and not summary['promotionPassed']
    assert all(b['functionalPassed'] and b['cadencePassed'] for b in summary['blocks'])
    assert summary['comparison']['latencyGate']['checks']=={'medianMs':False,'observedP95Ms':True}
    equivalent=read(ART/'equivalence-summary.json');model=read(ART/'model-equivalence.json')
    assert equivalent['observations']==1790 and equivalent['fullFingerprintMismatches']==[] and model['passed']
    assert model['candidateRetainedBytes']-model['baselineRetainedBytes']==8192
    assert read(ART/'apk-assets.json')['identical']
    restore=read(ART/'restoration.json')
    assert restore['allProductionSourcePinsRestored'] and restore['coreClassPayloadsRestored']==420
    assert restore['installedDebugApkSha256']==lock['apks']['baseline']['sha256']
    assert restore['defaultIme']==measurements['originalIme']
    assert sha(ROOT/'app/build/outputs/apk/release/app-release.apk')==restore['releaseApkSha256']
    for name in ['build-and-tests.log','restored-build-and-tests.log']:
        assert 'BUILD SUCCESSFUL' in (ART/name).read_text('utf-8-sig')
    py=(ART/'python-tests.log').read_text('utf-8-sig')
    assert 'Ran 430 tests' in py and 'OK (skipped=1)' in py
    sources=[
        '.gitattributes','tools/LanguageModelEquivalence.java','tools/measure_fast_input_latency.py',
        'tools/collect_lm_membership_stage.py','tools/summarize_fast_input_latency.py',
        'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/CharacterLanguageModel.kt',
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/CharacterLanguageModelTest.kt',
        'core-input/build/test-results/test/TEST-io.github.ethanbird.senseime.core.CharacterLanguageModelTest.xml',
        'docs/research/input-quality-stage-e26-2026-10-03.md','docs/development/input-quality-program-v2.md',
    ]
    files={name:ROOT/name for name in sources}
    for directory in [ART,RUN]:
        for path in directory.rglob('*'):
            if path.is_file() and path.suffix not in ['.jar','.class']:
                files[path.relative_to(ROOT).as_posix()]=path
    archive.mkdir();entries={}
    for name,path in sorted(files.items()):
        raw=path.read_bytes();target=archive/(hashlib.sha256(name.encode()).hexdigest()[:20]+'.gz')
        target.write_bytes(gzip.compress(raw,mtime=0))
        assert gzip.decompress(target.read_bytes())==raw
        entries[name]=dict(archive=target.relative_to(ROOT).as_posix(),sha256=sha(path),archiveSha256=sha(target),bytes=len(raw))
    dependency=sha(ROOT/'benchmarks/results/e25-evidence-manifest.json')
    assert dependency==lock['dependencyManifestSha256']
    manifest.write_text(json.dumps(dict(schemaVersion=1,dependencyManifestSha256=dependency,files=entries),indent=2)+'\n',
                        encoding='utf-8',newline='\n')
    report=dict(schemaVersion=1,stage='E26',decision='membership-index-rejected-by-fixed-median-gate-default-restored',
                productionChanged=False,experimentalProductionChangeArchived=True,performance=summary,
                fullResultEquivalence=equivalent,modelEquivalence=model,restoration=restore,hostTests=read(ART/'host-tests.json'),
                python=dict(run=430,passed=429,skipped=1),stableReleaseReady=False,goalComplete=False,
                previewRelease='v0.4.16-rc.1',archivedFiles=len(entries),evidenceManifestSha256=sha(manifest))
    gate.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(f'E26 sealed: {len(entries)} files; no default production change; negative performance result retained.')


if __name__=='__main__':main()
