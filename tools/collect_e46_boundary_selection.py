"""Close the edge-pruning correction with paired host and dedicated-AVD evidence."""
import argparse
import datetime
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

from evaluate_cross_domain import load,metrics,nonregression
from fetch_e37_model import digest
from run_e37_rescoring import write_new
from summarize_fast_input_latency import summarize_fast

read=lambda p:json.loads(p.read_text('utf-8-sig'))


def collect(root,out):
    lock=read(out/'development-lock.json');protocol=read(out/'android-protocol.json')
    policy=root/'benchmarks/corpus/e46-boundary-selection-policy.json'
    if digest(policy)!=lock['policySha256'] or digest(policy)!=protocol['stagePolicySha256']:
        raise ValueError('Predeclared policy changed')
    for name,sha in protocol['productionSourcePins'].items():
        if digest(root/name)!=sha:raise ValueError('Candidate source changed: '+name)
    for name,sha in lock['sourceFiles'].items():
        if digest(root/name)!=sha:raise ValueError('Core source changed')
    if digest(out/'candidate-core.jar')!=lock['candidateJarSha256']:raise ValueError('Host JAR changed')
    for apk in protocol['apks'].values():
        if digest(Path(apk['path']))!=apk['sha256']:raise ValueError('Frozen device APK changed')
    counts={}
    # The final core run strengthens the same four fixture tests, not four new tests.
    for module,task in [('core-input','test'),('ime-service','testDebugUnitTest'),('ime-ui','testDebugUnitTest')]:
        target=out/'host-results-final'/module;target.mkdir(parents=True)
        count={k:0 for k in ['tests','failures','errors','skipped']}
        for p in (root/module/'build/test-results'/task).glob('TEST-*.xml'):
            shutil.copy2(p,target/p.name);attrs=ET.parse(p).getroot().attrib
            for key in count:count[key]+=int(attrs.get(key,0))
        counts[module]=count
    if (sum(v['tests'] for v in counts.values())!=969
            or any(v[k] for v in counts.values() for k in ['failures','errors','skipped'])):
        raise ValueError('Host suite incomplete')
    red=read(out/'baseline-counterfactual-strengthened.json')
    test=root/'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/PinyinEdgeSelectionTest.kt'
    redlog=out/'baseline-counterfactual-strengthened.log'
    if digest(test)!=red['testSourceSha256'] or digest(redlog)!=red['logSha256']:
        raise ValueError('Red/green test identity changed')
    if 'Tests run: 4,  Failures: 2' not in redlog.read_text('utf-8') or 'limit=255' not in redlog.read_text('utf-8'):
        raise ValueError('Missing production-cap counterfactual')
    domains={}
    for domain,corpus in [('aishell','p2c-aishell-v7'),('tatoeba','p2c-supervised-v6')]:
        tsv=(root/'benchmarks/corpus'/corpus/'dev.tsv').read_text('utf-8')
        bh,b=load((out.parent/'e43'/(domain+'-baseline.jsonl')).read_text('utf-8').splitlines(),tsv)
        ah,a=load((out/(domain+'-candidate.jsonl')).read_text('utf-8').splitlines(),tsv)
        if ah['sources']!=lock['sourceFiles'] or ah['assets']!=bh['assets'] or ah['modelSha256']!=bh['modelSha256']:
            raise ValueError('Paired decoder/model identity changed')
        if {n for n in ah['sources'] if ah['sources'][n]!=bh['sources'][n]}!=set(lock['permittedChangedSources']):
            raise ValueError('Unexpected baseline source differences')
        report=read(out/(domain+'-comparison.json'));before=metrics(list(b.values()));after=metrics(list(a.values()))
        if before!=report['before'] or after!=report['after'] or not nonregression(before,after):
            raise ValueError('Development quality gate changed')
        domains[domain]=dict(before=before,after=after,changedFullResults=report['changedFullResults'])
    regression=read(out/'regression-decision.json')
    def stats(rows):
        return dict(states=len(rows),top1=sum(r['rank']==1 for r in rows),top5=sum(0<r['rank']<=5 for r in rows),
            top10=sum(0<r['rank']<=10 for r in rows),recall=sum(r['rank']>0 for r in rows),characterErrors=sum(r['characterErrors'] for r in rows))
    for split in ['replay','test']:
        old=read(out.parent/f'e42/candidate-{split}.json');new=read(out/f'candidate-{split}.json')
        for key in ['inputSha256','assets','candidateLimit','lmWeight','oovFeature','correctionCompositionBoost','graphDiagnosticLimit','learning']:
            if old[key]!=new[key]:raise ValueError('Regression workload/configuration changed')
        if [r['id'] for r in old['observations']]!=[r['id'] for r in new['observations']]:raise ValueError('Regression states differ')
        b,a=stats(old['observations']),stats(new['observations']);rep=regression['reports'][split]
        if rep['before']!=b or rep['after']!=a or not rep['passed'] or a!=b:
            raise ValueError('Observed regression metrics changed')
    groups={'learning':6,'context':8,'correction':10,'boundary':6,'association':9,'default':10}
    folders=[]
    for name,number in groups.items():
        folder=root/f'.artifacts/input-quality/external-editor-e46-{name}';env=read(folder/'environment.json')
        log=(folder/'instrumentation.log').read_text('utf-8-sig')
        if (env['serial']!='emulator-5580' or env['apks']['app/build/outputs/apk/debug/app-debug.apk']!=protocol['apks']['optimized']['sha256']
                or f'OK ({number} tests)' not in log or any(s in log for s in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed'])):
            raise ValueError('Dedicated AVD functional gate failed')
        folders.append(folder)
    measurements=read(out/'e46-fast-abba/measurements.json')
    latency=summarize_fast(measurements,protocol,out/'e46-fast-abba')
    if latency['failures'] or not all(b['functionalPassed'] and b['cadencePassed'] for b in latency['blocks']):
        raise ValueError('Incomplete Android performance workload')
    modes=latency['comparison']['confirmationMs']
    checks={k:modes['optimized'][k]<=1.1*modes['baseline'][k] for k in ['medianMs','observedP95Ms']}
    stage_gate=dict(rule='E46 predeclared paired median and P95 each <= 1.10 times reference',checks=checks,passed=all(checks.values()))
    write_new(out/'latency-summary.json',latency)
    write_new(out/'stage-latency-gate.json',stage_gate)
    # Keep the helper's stricter zero-regression verdict too; do not overwrite it.
    if not stage_gate['passed']:raise ValueError('E46 latency budget failed; preserve candidate separately')
    tools_log=(out/'tools-tests.log').read_text('utf-8-sig')
    if 'Ran 36 tests' not in tools_log or not tools_log.rstrip().endswith('OK'):raise ValueError('Tool checks failed')
    adb='F:/Android/Sdk/platform-tools/adb.exe'
    def call(*args):return subprocess.check_output([adb,'-s','emulator-5580',*args],text=True).strip()
    remote=call('shell','pm','path','io.github.ethanbird.senseime.debug').removeprefix('package:')
    environment=dict(serial='emulator-5580',avd=call('emu','avd','name'),
        ime=call('shell','settings','get','secure','default_input_method'),debugApp=call('shell','settings','get','global','debug_app'),
        forwards=call('forward','--list'),installedDebugSha256=call('shell','sha256sum',remote).split()[0])
    if environment['debugApp']!='null' or environment['forwards'] or environment['installedDebugSha256']!=protocol['apks']['optimized']['sha256']:
        raise ValueError('Unexpected final dedicated emulator state')
    release=root/'build/releases/v0.4.16-rc.5/Sense-v0.4.16-rc.5.apk'
    if digest(release)!='82a30595997386f1eb88ed8e6441842228b0d1a8748ccd45401b09f4cdd21611':
        raise ValueError('Published rc.5 artifact changed')
    write_new(out/'closure-check.json',dict(checkedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        hostTests=counts,toolTests=36,androidFunctional=groups,environment=environment,publishedRc5Unchanged=True,
        stageLatencyGate=stage_gate,accepted=True,physicalPhoneTested=False))
    files={'raw/'+p.relative_to(out).as_posix():p for p in out.rglob('*')
        if p.is_file() and p.suffix not in ['.apk','.jar','.class','.pyc'] and '__pycache__' not in p.parts}
    for folder in folders:
        for p in folder.rglob('*'):
            if p.is_file():files['workspace/'+p.relative_to(root).as_posix()]=p
    names=[*lock['permittedChangedSources'],'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/PinyinEdgeSelectionTest.kt',
        'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/QuerySelectionCacheTest.kt',
        'tools/collect_e46_boundary_selection.py','tools/measure_fast_input_latency.py','tools/summarize_fast_input_latency.py',
        'tools/summarize_input_latency.py','tools/test_external_editor.ps1','benchmarks/corpus/e46-boundary-selection-policy.json',
        'docs/research/input-quality-stage-e46-2026-10-04.md','docs/development/input-quality-program-v2.md']
    for name in names:files['workspace/'+name]=root/name
    folder=root/'benchmarks/results/e46-evidence';folder.mkdir(exist_ok=False)
    manifest=dict(schemaVersion=1,stage='E46',rawDirectory=str(out),files={},
        dependencyManifests={s:digest(root/f'benchmarks/results/{s}-evidence-manifest.json') for s in ['e42','e43']},
        largeLocalArtifacts={n:dict(path=str(out/n),bytes=(out/n).stat().st_size,sha256=digest(out/n)) for n in ['candidate-core.jar','candidate-debug.apk']})
    for name,path in sorted(files.items()):
        data=path.read_bytes();target=folder/(hashlib.sha256(name.encode()).hexdigest()[:20]+'.gz')
        target.write_bytes(gzip.compress(data,mtime=0))
        manifest['files'][name]=dict(archive=target.relative_to(root).as_posix(),bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),archiveSha256=digest(target))
    mp=root/'benchmarks/results/e46-evidence-manifest.json';write_new(mp,manifest)
    gate=dict(stage='E46',accepted=True,productionChanged=True,hostTests=counts,toolTests=36,androidFunctional=groups,
        development=domains,regression=regression,androidLatency=dict(confirmationMs=modes,stageGate=stage_gate,
            strictHelperGate=latency['comparison']['latencyGate'],perQuery=latency['comparison']['perQuery']),
        publishedReleaseAtStageCommit='v0.4.16-rc.5',releaseChanged=False,goalComplete=False,
        evidenceManifestSha256=digest(mp),archives=len(files))
    write_new(root/'benchmarks/results/e46-acceptance-gate.json',gate)
    print(json.dumps(dict(accepted=True,hostTests=969,toolTests=36,androidTests=49,latency=modes,archives=len(files))))


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('root',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    collect(a.root.resolve(),a.output.resolve())
