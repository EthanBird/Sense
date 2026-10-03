"""Separate SQLite row materialization from index restore using a frozen APK on ART.

One fixed diagnostic, including a predeclared shared-alias ablation. Profiling
is intrusive and runs in the standalone fixture UID, not the IME process.
"""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess

from personal_dictionary_fixture import create_database
from summarize_art_profile import summarize

FIXTURE='io.github.ethanbird.senseime.inputqualityfixture'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('output',type=Path)
    p.add_argument('--apk',type=Path,help='Frozen rc.3 debug artifact; SHA must match the release evidence')
    args=p.parse_args()
    if args.output.exists() or not re.fullmatch(r'[a-zA-Z0-9_-]+',args.output.name):
        raise ValueError('Fresh simple evidence directory required')
    root=Path(__file__).resolve().parents[1]
    adb_path='F:/Android/Sdk/platform-tools/adb.exe';serial='emulator-5580'
    def adb(*parts,check=True):
        r=subprocess.run([adb_path,'-s',serial,*map(str,parts)],capture_output=True,timeout=240)
        if check and r.returncode: raise RuntimeError(r.stderr.decode(errors='replace'))
        return (r.stdout+r.stderr).decode('utf-8',errors='replace')
    def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
    def write(p,j):p.write_text(json.dumps(j,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    if adb('emu','avd','name').splitlines()[0].strip()!='sense-input-quality':raise ValueError('Dedicated AVD required')
    apk=args.apk or root/'app/build/outputs/apk/debug/app-debug.apk'
    release=json.loads((root/'benchmarks/results/release-v0.4.16-rc.3.json').read_text())
    if sha(apk)!=release['androidFunctionalEvidence']['debugApkSha256']:raise ValueError('Pinned rc.3 Debug APK required')
    args.output.mkdir(parents=True)
    source=root/'ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/PersistentUserLexicon.kt'
    now=int(adb('shell','date','+%s').strip())*1000
    for size in [1000,10000]:create_database(args.output/f'{size}.db',size,now,source.read_text('utf-8'))
    noalias=args.output/'10000-no-shared-alias.db';shutil.copyfile(args.output/'10000.db',noalias)
    with closing(sqlite3.connect(noalias)) as db, db:
        changed=db.execute("UPDATE user_phrase SET aliases='' WHERE aliases='qqq'").rowcount
        assert changed==9996
    protocol=dict(schemaVersion=1,apkSha256=sha(apk),samplingIntervalUs=1000,sharedAliasAblationRows=changed,
        scope='Frozen production classes, fixture UID. Separate read and restore; profiled row is intrusive. Not system startup latency.',
        fixtureSources={str(p.relative_to(root)).replace('\\','/'):sha(p) for p in [Path(__file__),root/'tools/summarize_art_profile.py',
            root/'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/FrozenPersonalStoreProfileTest.kt']},
        steps=[dict(size=size,profile=profile,sharedAlias=shared,file=name,sha256=sha(args.output/name))
               for size,profile,shared,name in [(1000,False,True,'1000.db'),(10000,False,True,'10000.db'),
                   (10000,False,False,'10000-no-shared-alias.db'),(1000,False,True,'1000.db'),(10000,True,True,'10000.db')]])
    write(args.output/'protocol.json',protocol)
    for path in [root/'input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk',
                 root/'input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk']:
        if 'Success' not in adb('install','-r',path):raise ValueError('Fixture install failed')
    remote=f'/sdcard/Android/data/{FIXTURE}/files/input-quality/{args.output.name}'
    adb('shell','mkdir','-p',remote)
    for path in list(args.output.glob('*.db'))+[args.output/'protocol.json']:adb('push',path,remote+'/'+path.name)
    adb('push',apk,remote+'/source.apk')
    log=adb('shell','am','instrument','-w','-r','-e','evidenceRun',args.output.name,'-e','class',
        FIXTURE+'.FrozenPersonalStoreProfileTest',FIXTURE+'.test/androidx.test.runner.AndroidJUnitRunner')
    (args.output/'instrumentation.log').write_text(log,encoding='utf-8',newline='\n')
    # Pull only actual observations, not the redundant 70 MB APK copied for class loading.
    adb('pull',remote+'/store-load.jsonl',args.output/'store-load.jsonl')
    if 'OK (1 test)' not in log or 'FAILURES!!!' in log:raise ValueError('Frozen-class diagnostic failed; keep logs')
    rows=[json.loads(x) for x in (args.output/'store-load.jsonl').read_text().splitlines()]
    assert rows[-1]['passed'] and rows[-1]['steps']==5
    reports={}
    for row in rows:
        if row['type']=='row' and row['profiled']:
            path=args.output/f"{row['index']}.methods";adb('pull',row['remoteProfile'],path)
            for phase,root_method in [('read','io.github.ethanbird.senseime.service.UserLexiconDatabase.loadAll'),
                    ('restore','io.github.ethanbird.senseime.core.MemoryUserLexicon.<init>')]:
                result=summarize(path.read_bytes(),thread_name=rows[0]['thread'],include_all_methods=True,root_method=root_method)
                result['observedRootCpuUs']=result.pop('observedDecodeCpuUs');result['rootMethod']=root_method
                result['scope']='Intrusive frozen-class ART attribution, not normal startup latency'
                reports[phase]=result
            adb('shell','rm',row['remoteProfile'])
    write(args.output/'profile-summary.json',reports)
    print(json.dumps([r for r in rows if r['type']=='row'],ensure_ascii=False))
    print(json.dumps({k:{'observedRootCpuUs':v['observedRootCpuUs'],'topExclusive':v['exclusiveCpuUs'][:8]} for k,v in reports.items()}))


if __name__=='__main__':main()
