"""Collect the retained F1 evidence; fail rather than report a partial/foreign APK run as passing."""
import gzip
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from audit_sentence_corpus import sha256

ROOT=Path(__file__).resolve().parents[1]


def read(path): return json.loads((ROOT/path).read_text('utf-8-sig'))
def save(name, value):
    (ROOT/'benchmarks/results'/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def transcript(path, expected):
    text=(ROOT/path).read_text('utf-8-sig')
    assert re.search(rf'OK \({expected} tests?\)',text) and not re.search('FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED',text),path
    return {'path':str(path),'sha256':sha256(ROOT/path),'tests':expected,'seconds':float(re.search(r'Time: ([\d.]+)',text)[1])}


def main():
    apk='app/build/outputs/apk/debug/app-debug.apk'; apk_hash=sha256(ROOT/apk)
    sources={str(p.relative_to(ROOT)).replace('\\','/'):sha256(p) for module in ('core-input','ime-service','ime-ui')
             for p in (ROOT/module/'src/main/kotlin').rglob('*.kt')}
    about='app/src/main/kotlin/io/github/ethanbird/senseime/AboutSettingsScreen.kt';sources[about]=sha256(ROOT/about)
    reference=json.loads(gzip.decompress((ROOT/'benchmarks/results/baselines/e3-test-anchored.json.gz').read_bytes()))
    unchanged={name:sha256(ROOT/'core-input/src/main/kotlin'/name)==digest for name,digest in reference['sources'].items() if not name.endswith('/UserAssociation.kt')}
    assert all(unchanged.values()),'Unrelated decoder source changed'
    assert all(sha256(ROOT/'ime-service/src/main/assets'/name)==digest for name,digest in reference['assets'].items())
    save('f1-implementation-snapshot.json',{'schemaVersion':1,'productionSources':sources,'decoderSourcesUnchangedFromE3':unchanged,
        'existingDecoderAssets':reference['assets'],'model':read('ime-service/src/main/assets/association_words_notice.json')['model'],
        'apkSha256':apk_hash,'scope':'All core/service/UI Kotlin plus About source pins; existing P2C algorithms and five assets unchanged from E3'})
    modules={}
    for module,variant in [('core-input','test'),('ime-ui','testDebugUnitTest'),('ime-service','testDebugUnitTest'),('app','testDebugUnitTest')]:
        files=list((ROOT/module/'build/test-results'/variant).glob('TEST*.xml'));assert files
        rows=[ET.parse(p).getroot() for p in files]
        modules[module]={k:sum(int(row.get(k,0)) for row in rows) for k in ('tests','errors','failures','skipped')}
    assert sum(m['tests'] for m in modules.values())==814 and all(m[k]==0 for m in modules.values() for k in ('failures','errors','skipped'))
    python_log=ROOT/'.artifacts/input-quality/f1-python-tests.log'
    assert re.search(r'Ran 48 tests.*\bOK\b',python_log.read_text('utf-8'),re.S)
    save('f1-host-validation.json',{'schemaVersion':1,'modules':modules,'pythonTests':48,'apkSha256':apk_hash,
        'scope':'Full core/UI/service host suite and targeted App About tests; unchanged Gradle tasks can be cached',
        'logs':{str(p.relative_to(ROOT)).replace('\\','/'):sha256(p) for p in (ROOT/'.artifacts/input-quality').glob('f1-*') if p.is_file() and ('build' in p.name or p==python_log)}})
    runs=[]
    for name,count in [('associations',9),('warm',10),('cold',4),('corrections',3),('learning',2)]:
        folder=Path(f'.artifacts/input-quality/external-editor-f1-{name}-final')
        environment=read(folder/'environment.json')
        assert environment['apks'][apk]==apk_hash
        evidence={p.name:p.read_text('utf-8') for p in (ROOT/folder/'device').glob('*.txt')}
        runs.append({'name':name,'environment':environment,'instrumentation':transcript(folder/'instrumentation.log',count),'evidence':evidence})
    save('f1-system-ime.json',{'schemaVersion':1,'scope':'Dedicated API 37 emulator, true system IME to external InputConnection; not physical phone certification','passedTests':28,'runs':runs})
    fault=read('.artifacts/input-quality/f1-slow-system/result.json')
    assert fault['apkSha256']['io.github.ethanbird.senseime.debug']==apk_hash and len(fault['tests'])==4 and all(t['passed'] for t in fault['tests'])
    save('f1-slow-system.json',fault)
    ui=transcript(Path('.artifacts/input-quality/f1-ui-layout.log'),13)
    ui['testApkSha256']=sha256(ROOT/'ime-ui/build/outputs/apk/androidTest/debug/ime-ui-debug-androidTest.apk')
    save('f1-ui-layout.json',ui)
    art=transcript(Path('.artifacts/input-quality/f1-art-final-fast-han.log'),1)
    art['testApkSha256']=sha256(ROOT/'ime-service/build/outputs/apk/androidTest/debug/ime-service-debug-androidTest.apk')
    art['reportSha256']=sha256(ROOT/'benchmarks/results/f1-association-art.json')
    save('f1-art-instrumentation.json',art)
    asset=read('benchmarks/results/f1-apk-asset-audit.json');assert asset['apkSha256']==apk_hash and asset['passed']
    quality=read('benchmarks/results/f1-association-test.json')
    assert quality['freezeSha256']==sha256(ROOT/'benchmarks/results/f1-association-development-freeze.json')
    assert quality['modelSha256']==asset['model']['sha256'] and all(quality['acceptance'].values())
    for name,meta in read('benchmarks/results/f1-raw-manifest.json').items():
        data=(ROOT/'benchmarks/results/f1-raw'/name).read_bytes()
        import hashlib
        assert hashlib.sha256(data).hexdigest()==meta['sha256'] and hashlib.sha256(gzip.decompress(data)).hexdigest()==meta['uncompressedSha256']
    learning=read('benchmarks/results/f1-learning-persistence.json')
    assert learning['forbiddenPrivatePhraseAbsent'] and {r['text'] for r in learning['records']}=={'程彻','智能体'}
    save('f1-acceptance-gate.json',{'schemaVersion':1,'scope':'F1 local phase only; broader input-quality objective remains active','passed':True,
        'apkSha256':apk_hash,'checks':{'sourcePinsAndUnchangedP2c':True,'modelFrozenBeforeTestAndRawEvidenceMatches':True,'testRetrievalGates':True,
        'host814':True,'python48':True,'systemIme28':True,'fault4':True,'androidView13':True,'artModel1':True,'sameFinalApkAcrossSystemEvidence':True,
        'attributionAssetAudit':True,'learnedWordsPersist':True,'privateLearningAbsent':True},
        'limitations':['Corpus proxy, not human acceptability','Lower prefix Top8 than legacy','Word-boundary exact Top3 still 4.6%','ART load still adds startup cost','No new system latency distribution or physical phone test']})
    reports={p.name:sha256(p) for p in sorted((ROOT/'benchmarks/results').glob('f1-*.json')) if p.name!='f1-evidence-manifest.json'}
    save('f1-evidence-manifest.json',{'schemaVersion':1,'reports':reports,'collectorSha256':sha256(Path(__file__)),
        'visuallyInspectedInitialSystemImages':{str(p.relative_to(ROOT)).replace('\\','/'):sha256(p) for p in [
            ROOT/'.artifacts/input-quality/external-editor-f1-associations/device/dismissStaysClosedUntilANewCommitAndTheNewSuggestionIsSelectable-visible.png',
            ROOT/'.artifacts/input-quality/external-editor-f1-associations/device/aKnownCompletedGreetingKeepsToolbarRatherThanOfferingNoisyCharacters-stop.png']}})
    print(json.dumps({'passed':True,'hostTests':814,'pythonTests':48,'androidExecutions':46,'apkSha256':apk_hash}))


if __name__=='__main__': main()
