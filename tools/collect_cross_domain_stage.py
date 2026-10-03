"""Close E15's failed development gate without opening held-out candidate results."""
import gzip
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from collect_boundary_stage import sha, read, write_new
from evaluate_cross_domain import load, compare

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e15'
EXT = Path('G:/workspace/sense-input-quality-reference/e15-aishell')


def digest(path): return sha(path.read_bytes())


def main():
    manifest = ROOT / 'benchmarks/results/e15-evidence-manifest.json'
    gate = ROOT / 'benchmarks/results/e15-acceptance-gate.json'
    archive = ROOT / 'benchmarks/results/e15-evidence'
    assert not any(p.exists() for p in [manifest,gate,archive]), 'Keep closed evidence'
    previous = ROOT / 'benchmarks/results/e14-evidence-manifest.json'
    prior = read(ROOT / 'benchmarks/results/e14-acceptance-gate.json')
    assert digest(previous) == prior['evidenceManifestSha256']
    for entry in read(previous)['files'].values():
        packed=(ROOT/entry['archive']).read_bytes()
        assert sha(packed)==entry['archiveSha256'] and sha(gzip.decompress(packed))==entry['sha256']
    e13=read(ROOT/'benchmarks/results/e13-acceptance-gate.json')
    assert all(digest(ROOT/p)==h for p,h in e13['sourcePins'].items())
    assert all(digest(ROOT/'ime-service/src/main/assets'/p)==h for p,h in e13['assetPins'].items())
    assert digest(ROOT/'app/build/outputs/apk/debug/app-debug.apk')==prior['unchangedExistingApk']['sha256']
    lock=read(ART/'pre-dev-lock.json')
    assert lock['outputsAbsent'] and not lock['testCandidatesViewed'] and len(lock['files'])==87
    for p,h in lock['files'].items(): assert digest(Path(p))==h,p
    assert not list(ART.glob('test-*.jsonl')), 'Failed development must not open test candidates'
    corpus=read(EXT/'corpus/corpus.json'); source=read(EXT/'source/source-manifest.json')
    assert corpus['sourceManifestSha256']==digest(EXT/'source/source-manifest.json')
    assert corpus['scriptSha256']==digest(ROOT/'tools/prepare_aishell_corpus.py')
    for p,pin in source['files'].items(): assert digest(EXT/'source'/p)==pin['sha256']
    for split,pin in corpus['outputs'].items(): assert digest(EXT/'corpus'/pin['fileName'])==pin['sha256']
    assert digest(EXT/'corpus/attribution.jsonl')==corpus['attribution']['sha256']
    train_lock=read(EXT/'balanced-model/training-lock.json'); train_report=read(EXT/'balanced-model/training-report.json')
    assert train_report['trainingLockSha256']==digest(EXT/'balanced-model/training-lock.json')
    assert train_report['modelSha256']==digest(EXT/'balanced-model/balanced.scng')
    assert train_lock['new']['characters']==776711 and train_lock['old']['characters']==776701
    assert train_lock['pins']['policy']==digest(ROOT/'benchmarks/corpus/e15-model-policy.json')
    assert train_lock['pins']['selectedNewTrain']==digest(EXT/'balanced-model/selected-new-train.jsonl')
    assert not train_lock['testReadForTraining'] and not train_report['testEvaluated']
    labels=read(ROOT/'benchmarks/corpus/p2c-aishell-v7/frozen.json')
    for split,pin in labels['outputs'].items():
        assert digest(ROOT/f'benchmarks/corpus/p2c-aishell-v7/{split}.tsv')==pin['sha256']
    assert labels['outputs']['dev']['rows']==64 and labels['outputs']['test']['rows']==124
    assert read(ART/'cross-source-selection-audit.json')['overlaps']==[]

    reports={}; repeated=0
    for domain,data in [('aishell','p2c-aishell-v7'),('tatoeba','p2c-supervised-v6')]:
        text=(ROOT/f'benchmarks/corpus/{data}/dev.tsv').read_text('utf-8')
        runs={}
        for mode in ['baseline','balanced','balanced-repeat']:
            h,rows=load((ART/f'dev-{domain}-{mode}.jsonl').read_text('utf-8').splitlines(),text)
            assert all(digest(ROOT/p)==pin for p,pin in h['sources'].items())
            assert all(digest(ROOT/'ime-service/src/main/assets'/p)==pin for p,pin in h['assets'].items())
            assert h['inputSha256']==digest(ROOT/f'benchmarks/corpus/{data}/dev.tsv')
            expected=e13['assetPins']['pinyin_character_lm.scng'] if mode=='baseline' else train_report['modelSha256']
            assert h['modelSha256']==expected
            runs[mode]=(h,rows)
        report=read(ART/f'dev-{domain}-comparison.json')
        checked=compare(*runs['baseline'],*runs['balanced'])
        assert all(report[k]==v for k,v in checked.items())
        for p,pin in report['pins'].items(): assert digest(ROOT/p)==pin
        assert runs['balanced'][0]==runs['balanced-repeat'][0]
        for key,r in runs['balanced'][1].items():
            repeat=runs['balanced-repeat'][1][key]
            assert {k:v for k,v in r.items() if k!='hostNanos'}=={k:v for k,v in repeat.items() if k!='hostNanos'}
            repeated+=1
        reports[domain]={k:report[k] for k in ['before','after','nonregressionPassed','strictImprovementPassed']}
        reports[domain].update(firstGains=len(report['firstGains']),firstLosses=len(report['firstLosses']),rankLosses=len(report['rankLosses']))
    assert reports['aishell']['strictImprovementPassed'] and not reports['tatoeba']['nonregressionPassed']
    assert repeated==256
    baseline=read(ART/'baseline-equivalence.json')
    assert baseline['states']==128 and all(v==128 for v in baseline['checks'].values())
    # Recompute the historical full-result identity comparison, not just trust its report.
    old={ (r['id'],r['cut']):r for r in map(json.loads,gzip.open(ROOT/'.artifacts/input-quality/e13/dev-features.jsonl.gz','rt',encoding='utf-8')) if r['type']=='row' }
    h,actual=load((ART/'dev-tatoeba-baseline.jsonl').read_text('utf-8').splitlines(),(ROOT/'benchmarks/corpus/p2c-supervised-v6/dev.tsv').read_text('utf-8'))
    assert old.keys()==actual.keys() and all(old[k]['resultSha256']==actual[k]['resultSha256'] for k in old)
    xml=list((ROOT/'core-input/build/test-results/test').glob('TEST-*.xml'))
    units={k:sum(int(ET.parse(p).getroot().get(k,0)) for p in xml) for k in ['tests','failures','errors','skipped']}
    assert units==dict(tests=331,failures=0,errors=0,skipped=0)
    assert 'BUILD SUCCESSFUL' in (ART/'core-tests.log').read_text('utf-8-sig')
    python=(ART/'python-tests.log').read_text('utf-8-sig')
    assert 'Ran 385 tests' in python and 'OK (skipped=1)' in python

    files={p.relative_to(ROOT).as_posix():p for p in ART.iterdir() if p.is_file()}
    files.update({p.relative_to(ROOT).as_posix():p for p in (ROOT/'benchmarks/corpus/p2c-aishell-v7').iterdir() if p.is_file()})
    paths=['benchmarks/corpus/e15-selection-policy.json','benchmarks/corpus/e15-model-policy.json',
        'tools/fetch_aishell_ner_text.py','tools/prepare_aishell_corpus.py','tools/test_prepare_aishell_corpus.py',
        'tools/train_balanced_character_lm.py','tools/test_train_balanced_character_lm.py',
        'tools/evaluate_cross_domain.py','tools/test_evaluate_cross_domain.py','tools/inspect_cross_domain_lm_losses.py',
        'tools/collect_cross_domain_stage.py','tools/prepare_p2c.py','tools/train_character_lm.py','tools/prepare_sentence_corpus.py',
        'tools/audit_sentence_corpus.py','tools/collect_boundary_stage.py','tools/train_candidate_ranker.py',
        'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/M20CrossDomainBenchmark.kt',
        'docs/research/input-quality-stage-e15-2026-10-03.md','docs/development/input-quality-program-v2.md']
    files.update({p:ROOT/p for p in paths})
    files.update({p.relative_to(ROOT).as_posix():p for p in xml})
    for p in ['source/source-manifest.json','source/upstream-tree.json','source/LICENSE','source/README.md',
              'corpus/corpus.json','balanced-model/training-lock.json','balanced-model/training-report.json']:
        files['external-aishell/'+p]=EXT/p
    archive.mkdir();entries={}
    for logical,path in sorted(files.items()):
        data=path.read_bytes();packed=gzip.compress(data,mtime=0)
        dest=archive/(sha(logical.encode())[:20]+'.gz');assert not dest.exists();dest.write_bytes(packed)
        assert gzip.decompress(dest.read_bytes())==data
        entries[logical]=dict(archive=dest.relative_to(ROOT).as_posix(),sha256=sha(data),bytes=len(data),archiveSha256=sha(packed))
    external={p.relative_to(EXT).as_posix():dict(sha256=digest(p),bytes=p.stat().st_size) for p in EXT.rglob('*') if p.is_file()}
    write_new(manifest,dict(schemaVersion=1,dependency=dict(stage='E14',manifestSha256=digest(previous)),
        externalRoot=str(EXT),externalPins=external,files=entries))
    write_new(gate,dict(schemaVersion=1,stage='E15',decision='hold-balanced-model-development-gate-failed',
        developmentGatePassed=False,modelPromoted=False,productionChanged=False,releaseReady=False,goalComplete=False,
        newDomain=reports['aishell'],oldDomain=reports['tatoeba'],primaryStates=512,repeatedStates=256,
        completeRepeatEqual=True,baselineHistoricalEquivalentStates=128,
        newCorpusPartitions=corpus['outputs'],selectedNewTrain=train_lock['new'],oldTrain=train_lock['old'],
        proposedModelSha256=train_report['modelSha256'],proposedModelBytes=train_report['modelBytes'],
        newTestSentencesReserved=124,testCandidatesEvaluated=False,core=units,python=dict(run=385,passed=384,skipped=1),
        androidExecutionsThisStage=0,apkRebuiltThisStage=False,unchangedExistingApk=prior['unchangedExistingApk'],
        evidenceManifestSha256=digest(manifest),archivedFiles=len(entries)))
    print(f'E15 closed: development gate failed, test untouched, core=331, Python=385, archives={len(entries)}')


if __name__=='__main__':main()
