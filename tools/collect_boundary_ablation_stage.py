"""Close E17 while verifying archived trial inputs and the restored production hot path."""
import gzip
from pathlib import Path
import xml.etree.ElementTree as ET
from collect_boundary_stage import sha,read,write_new
from evaluate_cross_domain import load,compare
from freeze_boundary_development import choose

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'.artifacts/input-quality/e17'
def digest(p):return sha(p.read_bytes())


def main():
    manifest=ROOT/'benchmarks/results/e17-evidence-manifest.json';gate=ROOT/'benchmarks/results/e17-acceptance-gate.json'
    archive=ROOT/'benchmarks/results/e17-evidence'
    assert not any(p.exists() for p in [manifest,gate,archive])
    prev=ROOT/'benchmarks/results/e16-evidence-manifest.json';prior=read(ROOT/'benchmarks/results/e16-acceptance-gate.json')
    assert digest(prev)==prior['evidenceManifestSha256']
    for e in read(prev)['files'].values():
        data=(ROOT/e['archive']).read_bytes();assert sha(data)==e['archiveSha256'] and sha(gzip.decompress(data))==e['sha256']
    lock=read(ART/'pre-dev-lock.json');freeze=read(ART/'development-freeze.json');restoration=read(ART/'restoration.json')
    assert freeze['selectedMode'] is None and choose(freeze['trials']) is None
    assert not freeze['testCandidatesViewedForSelection'] and not list(ART.glob('test-*.jsonl'))
    assert freeze['preDevLockSha256']==digest(ART/'pre-dev-lock.json')
    assert freeze['runnerSha256']==digest(ROOT/'tools/freeze_boundary_development.py')
    assert freeze['evaluatorSha256']==digest(ROOT/'tools/evaluate_cross_domain.py')
    assert restoration['scriptSha256']==digest(ROOT/'tools/restore_boundary_trial.py')
    for p,h in lock['files'].items():
        path=Path(p);relative=path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else None
        if relative in restoration['files']:
            snapshot=restoration['files'][relative];assert digest(ROOT/snapshot['snapshot'])==snapshot['trialSha256']==h
        else:assert digest(path)==h,p
    for p,h in freeze['pins'].items():assert digest(Path(p))==h,p
    for p,h in prior['sourcePins'].items():assert digest(ROOT/p)==h,p
    for p,h in prior['assetPins'].items():assert digest(ROOT/'ime-service/src/main/assets'/p)==h,p
    assert not list((ROOT/'core-input/build/classes/kotlin/main').rglob('M22BoundaryAblationBenchmark*'))
    restored=0
    for domain,data in [('aishell','p2c-aishell-v7'),('tatoeba','p2c-supervised-v6')]:
        text=(ROOT/f'benchmarks/corpus/{data}/dev.tsv').read_text('utf-8')
        bh,b=load((ART/f'dev-{domain}-baseline.jsonl').read_text('utf-8').splitlines(),text)
        for mode in ['daily-no-boundary','mixture-no-boundary']:
            h,rows=load((ART/f'dev-{domain}-{mode}.jsonl').read_text('utf-8').splitlines(),text)
            checked=compare(bh,b,h,rows);report=read(ART/f'dev-{domain}-{mode}-comparison.json')
            assert all(report[k]==v for k,v in checked.items())
        rh,r=load((ART/f'restored-{domain}-baseline.jsonl').read_text('utf-8').splitlines(),text)
        _,old=load((ROOT/f'.artifacts/input-quality/e16/dev-{domain}-0.jsonl').read_text('utf-8').splitlines(),text)
        assert r.keys()==b.keys()==old.keys()
        assert all(row['resultSha256']==b[k]['resultSha256']==old[k]['resultSha256'] for k,row in r.items())
        assert rh['sources']==prior['sourcePins'];restored+=len(r)
    assert restored==256
    def counts(paths):return {k:sum(int(ET.parse(p).getroot().get(k,0)) for p in paths) for k in ['tests','failures','errors','skipped']}
    trial=counts(list((ART/'trial-test-results').glob('TEST-*.xml')))
    final_xml=list((ROOT/'core-input/build/test-results/test').glob('TEST-*.xml'));final=counts(final_xml)
    assert trial==dict(tests=345,failures=0,errors=0,skipped=0) and final==dict(tests=340,failures=0,errors=0,skipped=0)
    for name in ['trial-core-tests.log','restored-core-tests.log']:
        log=(ART/name).read_text('utf-8-sig');assert 'BUILD SUCCESSFUL' in log and ':core-input:test FROM-CACHE' not in log
    python=(ART/'python-tests.log').read_text('utf-8-sig');assert 'Ran 391 tests' in python and 'OK (skipped=1)' in python
    assert digest(ROOT/'app/build/outputs/apk/debug/app-debug.apk')==prior['unchangedExistingApk']['sha256']

    files={p.relative_to(ROOT).as_posix():p for p in ART.rglob('*') if p.is_file()}
    files.update({p.relative_to(ROOT).as_posix():p for p in final_xml})
    paths=['benchmarks/corpus/e17-boundary-policy.json','tools/freeze_boundary_development.py','tools/test_freeze_boundary_development.py',
        'tools/restore_boundary_trial.py','tools/collect_boundary_ablation_stage.py','tools/evaluate_cross_domain.py',
        'tools/collect_boundary_stage.py','docs/research/input-quality-stage-e17-2026-10-03.md','docs/development/input-quality-program-v2.md']
    paths+=['core-input/src/main/kotlin/io/github/ethanbird/senseime/core/'+n for n in ['PinyinDecoder.kt','PinyinLanguageScorer.kt']]
    files.update({p:ROOT/p for p in paths})
    archive.mkdir();entries={}
    for logical,p in sorted(files.items()):
        data=p.read_bytes();packed=gzip.compress(data,mtime=0);out=archive/(sha(logical.encode())[:20]+'.gz')
        out.write_bytes(packed);assert gzip.decompress(out.read_bytes())==data
        entries[logical]=dict(archive=out.relative_to(ROOT).as_posix(),sha256=sha(data),bytes=len(data),archiveSha256=sha(packed))
    write_new(manifest,dict(schemaVersion=1,dependency=dict(stage='E16',manifestSha256=digest(prev)),files=entries))
    write_new(gate,dict(schemaVersion=1,stage='E17',decision='hold-boundary-ablation-and-restore-runtime',
        developmentGatePassed=False,selectedMode=None,trials=freeze['trials'],trialRuntimeRestored=True,
        productionChanged=False,releaseReady=False,goalComplete=False,sourcePins=prior['sourcePins'],assetPins=prior['assetPins'],
        baselineEquivalentStates=256,primaryStates=768,restoredEquivalentStates=256,
        testCandidatesEvaluated=False,reservedTestSentences=dict(aishell=124,daily=124),trialCore=trial,restoredCore=final,
        restoredTestsActuallyRerun=True,python=dict(run=391,passed=390,skipped=1),
        androidExecutionsThisStage=0,apkRebuiltThisStage=False,unchangedExistingApk=prior['unchangedExistingApk'],
        evidenceManifestSha256=digest(manifest),archivedFiles=len(entries)))
    print(f'E17 closed: trial source archived, runtime restored, core trial=345/final=340, Python=391, archives={len(entries)}')


if __name__=='__main__':main()
