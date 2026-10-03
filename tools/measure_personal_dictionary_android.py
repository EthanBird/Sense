"""Fixed 1k/10k/10k/1k personal-SQLite acceptance on the disposable debug AVD.

No production bridge; only the dedicated emulator's debug profile is reset.
Keep failed blocks and all raw input receipts. Timings are descriptive, not an
optimization promotion gate or physical-phone certification.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

from personal_dictionary_fixture import PACKAGE, DATABASE, create_database, validate_restored_database
from summarize_fast_input_latency import validate_record
from summarize_input_latency import metrics

FIXTURE = 'io.github.ethanbird.senseime.inputqualityfixture'
HELPER = '/data/local/tmp/sense-input-quality-touch/classes.dex'
HELPER_SHA = 'c43716637ff8e3df199ec668e86c0b7c3b4fc85bab9760ca291b8699bafc1de9'


def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,x): p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('output',type=Path)
    p.add_argument('--adb',default='F:/Android/Sdk/platform-tools/adb.exe')
    p.add_argument('--serial',default='emulator-5580')
    args=p.parse_args()
    if not re.fullmatch(r'emulator-\d+',args.serial) or not re.fullmatch(r'[a-zA-Z0-9_-]+',args.output.name):
        raise ValueError('Dedicated emulator and simple evidence name required')
    if args.output.exists(): raise ValueError('Retain prior evidence; choose a fresh directory')
    root=Path(__file__).resolve().parents[1]
    source=root/'ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/PersistentUserLexicon.kt'
    apk=root/'app/build/outputs/apk/debug/app-debug.apk'
    release=json.loads((root/'benchmarks/results/release-v0.4.16-rc.3.json').read_text('utf-8'))
    if sha(apk)!=release['androidFunctionalEvidence']['debugApkSha256']:
        raise ValueError('This fixed capacity run targets the tested rc.3 debug artifact')

    def adb(*parts,data=None,required=True,instrument=False):
        result=subprocess.run([args.adb,'-s',args.serial,*map(str,parts)],input=data,
                              stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=None if instrument else 120)
        if required and result.returncode:
            raise RuntimeError(f'adb {parts[:3]} failed: {result.stderr.decode("utf-8",errors="replace")}')
        return result
    def text(*parts,**kwargs): return adb(*parts,**kwargs).stdout.decode('utf-8',errors='replace')
    if text('emu','avd','name').splitlines()[0].strip()!='sense-input-quality':
        raise ValueError('Only the dedicated disposable AVD is supported')
    if text('shell','sha256sum',HELPER).split()[0]!=HELPER_SHA: raise ValueError('Frozen touch helper changed')
    original=text('shell','settings','get','secure','default_input_method').strip()
    if original in ('','null') or original.startswith(PACKAGE+'/'):
        raise ValueError('Select a different original IME on the disposable AVD before this test')
    hard=text('shell','settings','get','secure','show_ime_with_hard_keyboard').strip()
    args.output.mkdir(parents=True)
    report=dict(schemaVersion=1,order=[1000,10000,10000,1000],apkSha256=sha(apk),helperSha256=HELPER_SHA,
                serial=args.serial,sdk=text('shell','getprop','ro.build.version.sdk').strip(),
                scope='Fixed synthetic personal capacity, genuine system touch and SQLite restoration; not natural accuracy or phone frames',
                policy='Four blocks, seven inputs before/after real process restart. Require every prefix, final commit, durable count and all database rows/context maps intact. No timing pass threshold; retain all blocks.',
                noProductionChanges=True,originalIme=original,fixtureApks={},runs=[],restoration={})
    report['sources']={str(path.relative_to(root)).replace('\\','/'):sha(path) for path in [Path(__file__),root/'tools/personal_dictionary_fixture.py',source,
        root/'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorCapacityTest.kt']}
    def save(): write(args.output/'result.json',report)
    save()
    try:
        for package,path in [(PACKAGE,apk),
            (FIXTURE,root/'input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk'),
            (FIXTURE+'.test',root/'input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk')]:
            if 'Success' not in text('install','-r',path): raise ValueError('Fixture install failed')
            remote=text('shell','pm','path',package).strip().removeprefix('package:')
            if text('shell','sha256sum',remote).split()[0]!=sha(path): raise ValueError('Installed APK differs')
            report['fixtureApks'][package]=sha(path)
        save()
        for block,size in enumerate(report['order'],1):
            name=f'{args.output.name}-{block}-{size}'
            folder=args.output/name;folder.mkdir()
            run=dict(block=block,size=size,name=name,passed=False,errors=[]);report['runs'].append(run);save()
            print(json.dumps({'block':block,'size':size,'state':'starting'}),flush=True)
            try:
                text('shell','am','force-stop',PACKAGE)
                if 'Success' not in text('shell','pm','clear',PACKAGE): raise ValueError('Debug profile clear failed')
                if 'All broadcast queues are idle' not in text('shell','am','wait-for-broadcast-idle'): raise ValueError('Stop barrier failed')
                if text('shell',f'pidof {PACKAGE}:ime || true').strip(): raise ValueError('Expected stopped IME')
                now=int(text('shell','date','+%s').strip())*1000
                db=folder/'seed.db';seed=create_database(db,size,now,source.read_text('utf-8'))
                public={k:v for k,v in seed.items() if k!='rows'};public['databaseSha256']=sha(db)
                seed_path=folder/'seed.json';write(seed_path,public);run['seedSha256']=sha(seed_path);run['seedDatabaseSha256']=sha(db)
                text('shell','run-as',PACKAGE,'mkdir','-p','databases')
                # Windows adb shell stdin returned success after forwarding only 7,959
                # bytes in the preserved probe. Push verifies a complete transport, then
                # use a device-local pipe into the fixed debug UID, never binary host stdin.
                transfer=f'/data/local/tmp/{name}-seed.db'
                try:
                    text('push',db,transfer)
                    if text('shell','sha256sum',transfer).split()[0]!=sha(db): raise ValueError('ADB push differs')
                    text('shell',f"cat {transfer} | run-as {PACKAGE} sh -c 'cat > databases/{DATABASE}'")
                    run['installedDatabaseSha256']=text('shell','run-as',PACKAGE,'sha256sum',f'databases/{DATABASE}').split()[0]
                    if run['installedDatabaseSha256']!=sha(db): raise ValueError('Installed database bytes differ')
                finally:
                    text('shell','rm','-f',transfer)
                remote=f'/sdcard/Android/data/{FIXTURE}/files/input-quality/{name}'
                text('shell','mkdir','-p',remote);text('push',seed_path,remote+'/seed.json')
                result=adb('shell','am','instrument','-w','-r','-e','evidenceRun',name,
                    '-e','seedSha256',sha(seed_path),'-e','touchInjectorSha256',HELPER_SHA,
                    '-e','class',FIXTURE+'.ExternalEditorCapacityTest',FIXTURE+'.test/androidx.test.runner.AndroidJUnitRunner',instrument=True)
                log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(folder/'instrumentation.log').write_text(log,encoding='utf-8')
                text('pull',remote,folder/'device')
                # Capture the complete durable journal only after the real IME process stops.
                text('shell','am','force-stop',PACKAGE);text('shell','am','wait-for-broadcast-idle')
                raw=folder/'raw-journal';raw.mkdir()
                for suffix in ('','-wal','-shm'):
                    result=adb('exec-out','run-as',PACKAGE,'cat',f'databases/{DATABASE}{suffix}',required=False)
                    if result.returncode==0: (raw/(DATABASE+suffix)).write_bytes(result.stdout)
                    elif suffix=='' or b'No such file' not in result.stderr+result.stdout: raise ValueError('Journal capture failed')
                run['journalSha256']={path.name:sha(path) for path in raw.iterdir()}
                analysis=folder/'analysis-journal';shutil.copytree(raw,analysis)
                records=[json.loads(line) for line in (folder/'device/capacity.jsonl').read_text().splitlines()]
                rows=[r for r in records if r['type']=='row'];restarts=[r for r in records if r['type']=='restart']
                run['observedRows']=len(rows);run['observedRestarts']=len(restarts)
                run['instrumentationPassed']='OK (1 test)' in log and 'FAILURES!!!' not in log
                if len(rows)!=14 or len(restarts)!=1: raise ValueError('Missing system observations')
                intervals=[];times=[]
                for index,row in enumerate(rows):
                    case=seed['cases'][index%7]
                    if row['phase']!=index//7 or row['context']!=case['context'] or not row['prefixesIntact']:
                        raise ValueError('Input order or raw prefix failed')
                    cadence,latency=validate_record(row,case['query']+' ',case['context']+case['expected'])
                    if latency!=row['spaceToEditorMs']: raise ValueError('Timestamp mismatch')
                    intervals.extend(cadence);times.append(latency)
                restart=restarts[0]
                if not restart['oldPid'] or not restart['newPid'] or restart['oldPid']==restart['newPid']:
                    raise ValueError('No real process restart')
                if 'OK (1 test)' not in log or 'FAILURES!!!' in log: raise ValueError('Instrumentation assertion failed')
                run['durable']=validate_restored_database(analysis/DATABASE,seed)
                run.update(passed=True,confirmationMs=metrics(times),senderIntervalsMs=metrics(intervals),restart=restart,samples=times)
            except Exception as error:
                run['errors'].append(repr(error))
            save();print(json.dumps({'block':block,'size':size,'passed':run['passed'],'errors':run['errors']}),flush=True)
    finally:
        # The whole profile was disposable before the experiment; leave no bulky synthetic dictionary.
        report['restoration']['debugProfileCleared']='Success' in text('shell','pm','clear',PACKAGE)
        text('shell','am','wait-for-broadcast-idle')
        if original not in ('','null'): text('shell','ime','set',original)
        if hard in ('0','1'): text('shell','settings','put','secure','show_ime_with_hard_keyboard',hard)
        else: text('shell','settings','delete','secure','show_ime_with_hard_keyboard')
        report['restoration']['ime']=text('shell','settings','get','secure','default_input_method').strip()
        report['passed']=len(report['runs'])==4 and all(r['passed'] for r in report['runs'])
        save()
    if not report['passed']: raise SystemExit('Capacity acceptance failed; complete evidence retained')


if __name__=='__main__': main()
