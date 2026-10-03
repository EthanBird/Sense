"""Close the E16 negative result with source, probability and repeated-output controls."""
import gzip
from pathlib import Path
import xml.etree.ElementTree as ET
from collect_boundary_stage import sha,read,write_new
from evaluate_cross_domain import load,compare
from freeze_mixture_development import choose

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'.artifacts/input-quality/e16'
def digest(p):return sha(p.read_bytes())


def main():
    manifest=ROOT/'benchmarks/results/e16-evidence-manifest.json';gate=ROOT/'benchmarks/results/e16-acceptance-gate.json'
    archive=ROOT/'benchmarks/results/e16-evidence'
    assert not any(p.exists() for p in [manifest,gate,archive])
    prev=ROOT/'benchmarks/results/e15-evidence-manifest.json';prior=read(ROOT/'benchmarks/results/e15-acceptance-gate.json')
    assert digest(prev)==prior['evidenceManifestSha256']
    for e in read(prev)['files'].values():
        data=(ROOT/e['archive']).read_bytes();assert sha(data)==e['archiveSha256'] and sha(gzip.decompress(data))==e['sha256']
    lock=read(ART/'pre-dev-lock.json');freeze=read(ART/'development-freeze.json')
    assert freeze['selectedAlpha'] is None and not freeze['testCandidatesViewedForSelection']
    assert choose(freeze['trials']) is None and not list(ART.glob('test-*.jsonl'))
    assert freeze['preDevLockSha256']==digest(ART/'pre-dev-lock.json')
    assert freeze['runnerSha256']==digest(ROOT/'tools/freeze_mixture_development.py')
    assert freeze['evaluatorSha256']==digest(ROOT/'tools/evaluate_cross_domain.py')
    for p,h in (lock['files']|freeze['pins']).items():assert digest(Path(p))==h,p
    states=0
    for domain,data in [('aishell','p2c-aishell-v7'),('tatoeba','p2c-supervised-v6')]:
        text=(ROOT/f'benchmarks/corpus/{data}/dev.tsv').read_text('utf-8')
        bh,baseline=load((ART/f'dev-{domain}-0.jsonl').read_text('utf-8').splitlines(),text)
        for alpha in ['0.1','0.25','0.5']:
            h,rows=load((ART/f'dev-{domain}-{alpha}.jsonl').read_text('utf-8').splitlines(),text)
            calculated=compare(bh,baseline,h,rows);report=read(ART/f'dev-{domain}-{alpha}-comparison.json')
            assert all(report[k]==v for k,v in calculated.items())
            if alpha=='0.25':
                rh,repeat=load((ART/f'repeat-{domain}-0.25.jsonl').read_text('utf-8').splitlines(),text)
                assert rh==h
                for key,r in rows.items():assert {k:v for k,v in r.items() if k!='hostNanos'}=={k:v for k,v in repeat[key].items() if k!='hostNanos'}
                states+=len(rows)
    assert states==256
    mass=read(ART/'real-model-mass.json');assert mass['passed'] and mass['distributions']==24 and mass['maximumMassError']<1e-6
    assert read(ART/'holdout-cross-domain-audit.json')['overlaps']==[]
    assert read(ROOT/'benchmarks/corpus/p2c-daily-mixture-v8/frozen.json')['outputs']['test']['rows']==124
    xml=list((ROOT/'core-input/build/test-results/test').glob('TEST-*.xml'))
    units={k:sum(int(ET.parse(p).getroot().get(k,0)) for p in xml) for k in ['tests','failures','errors','skipped']}
    assert units==dict(tests=340,failures=0,errors=0,skipped=0)
    assert 'BUILD SUCCESSFUL' in (ART/'core-tests.log').read_text('utf-8-sig')
    assert 'Ran 388 tests' in (ART/'python-tests.log').read_text('utf-8-sig') and 'OK (skipped=1)' in (ART/'python-tests.log').read_text('utf-8-sig')
    assert digest(ROOT/'app/build/outputs/apk/debug/app-debug.apk')==prior['unchangedExistingApk']['sha256']
    files={p.relative_to(ROOT).as_posix():p for p in ART.iterdir() if p.is_file()}
    for directory in ['benchmarks/corpus/p2c-daily-mixture-v8','core-input/build/test-results/test']:
        files.update({p.relative_to(ROOT).as_posix():p for p in (ROOT/directory).glob('*') if p.is_file()})
    paths=['benchmarks/corpus/e16-mixture-policy.json','benchmarks/corpus/e16-holdout-selection-policy.json',
        'tools/freeze_mixture_development.py','tools/test_freeze_mixture_development.py','tools/evaluate_cross_domain.py',
        'tools/VerifyMixtureModelMass.java','tools/collect_mixture_stage.py','tools/collect_boundary_stage.py',
        'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/LinearMixtureCharacterModel.kt',
        'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/M21MixtureBenchmark.kt',
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/LinearMixtureCharacterModelTest.kt',
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/LinearMixtureBinaryTest.kt',
        'docs/research/input-quality-stage-e16-2026-10-03.md','docs/development/input-quality-program-v2.md']
    files.update({p:ROOT/p for p in paths})
    archive.mkdir();entries={}
    for logical,p in sorted(files.items()):
        data=p.read_bytes();packed=gzip.compress(data,mtime=0);out=archive/(sha(logical.encode())[:20]+'.gz')
        out.write_bytes(packed);assert gzip.decompress(out.read_bytes())==data
        entries[logical]=dict(archive=out.relative_to(ROOT).as_posix(),sha256=sha(data),bytes=len(data),archiveSha256=sha(packed))
    write_new(manifest,dict(schemaVersion=1,dependency=dict(stage='E15',manifestSha256=digest(prev)),files=entries))
    sources={p.relative_to(ROOT).as_posix():digest(p) for p in (ROOT/'core-input/src/main/kotlin').rglob('*.kt')}
    assets=read(ROOT/'benchmarks/results/e13-acceptance-gate.json')['assetPins']
    assert all(digest(ROOT/'ime-service/src/main/assets'/p)==h for p,h in assets.items())
    write_new(gate,dict(schemaVersion=1,stage='E16',decision='hold-probability-mixture-development-gate-failed',
        developmentGatePassed=False,selectedAlpha=None,productionChanged=False,modelPromoted=False,releaseReady=False,goalComplete=False,
        sourcePins=sources,assetPins=assets,trials=freeze['trials'],testCandidatesEvaluated=False,reservedTestSentences=dict(aishell=124,daily=124),
        baselineEquivalentStates=256,primaryStates=1024,repeatedStates=states,completeRepeatEqual=True,modelMassMaximumError=mass['maximumMassError'],
        core=units,python=dict(run=388,passed=387,skipped=1),androidExecutionsThisStage=0,apkRebuiltThisStage=False,
        unchangedExistingApk=prior['unchangedExistingApk'],evidenceManifestSha256=digest(manifest),archivedFiles=len(entries)))
    print(f'E16 closed: no trial passed, two test domains untouched, core=340, Python=388, archives={len(entries)}')


if __name__=='__main__':main()
