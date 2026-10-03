"""One bounded first/repeated real-touch ART diagnostic on the dedicated AVD."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

from summarize_art_profile import summarize


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('output',type=Path)
    p.add_argument('--apk',type=Path,help='Explicit frozen debug artifact, only on the dedicated AVD')
    p.add_argument('--apk-sha256',help='Required SHA-256 for --apk')
    p.add_argument('--restore-apk',type=Path,help='Required original installed artifact for --apk; verified before any install')
    args=p.parse_args()
    if args.output.exists():raise ValueError('Retain prior evidence')
    name=args.output.name
    if not name.replace('-','').replace('_','').isalnum():raise ValueError('Simple evidence name required')
    root=Path(__file__).resolve().parents[1]
    adb_path='F:/Android/Sdk/platform-tools/adb.exe';serial='emulator-5580'
    package='io.github.ethanbird.senseime.debug';fixture='io.github.ethanbird.senseime.inputqualityfixture'
    def adb(*parts):
        return subprocess.check_output([adb_path,'-s',serial,*map(str,parts)],timeout=180).decode('utf-8',errors='replace')
    def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
    if adb('emu','avd','name').splitlines()[0].strip()!='sense-input-quality':raise ValueError('Dedicated AVD required')
    original=adb('shell','settings','get','secure','default_input_method').strip()
    remote=adb('shell','pm','path',package).strip().removeprefix('package:')
    original_hash=adb('shell','sha256sum',remote).split()[0]
    expected='7ce9874adf9f9b0a84408aa67c90727b8d98ab5f4e7f1433e1d4eded05ee16ed'
    explicit=bool(args.apk)
    if explicit != bool(args.apk_sha256) or explicit != bool(args.restore_apk):
        raise ValueError('Explicit artifact, pinned SHA and verified restore artifact are required together')
    if explicit:
        if sha(args.apk)!=args.apk_sha256 or sha(args.restore_apk)!=original_hash:
            raise ValueError('Frozen or original artifact differs')
        expected=args.apk_sha256
    elif original_hash!=expected:raise ValueError('Expected the restored frozen E23 APK')
    helper_hash=adb('shell','sha256sum','/data/local/tmp/sense-input-quality-touch/classes.dex').split()[0]
    if helper_hash!='c43716637ff8e3df199ec668e86c0b7c3b4fc85bab9760ca291b8699bafc1de9':raise ValueError('Unknown event source')
    args.output.mkdir(parents=True)
    metadata=dict(apkSha256=expected,originalApkSha256=original_hash,helperSha256=helper_hash,serial=serial,samplingIntervalUs=1000,
                  scope='Intrusive method sampling; nihao warmup then first/repeated woxihuanbeijing. Not normal latency.',fixtureApks={})
    metadata['sources']={str(path.relative_to(root)).replace('\\','/'):sha(path) for path in [Path(__file__),
        root/'tools/summarize_art_profile.py',
        root/'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorFastProfileTest.kt']}
    (args.output/'protocol.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8',newline='\n')
    try:
        if explicit:
            assert 'Success' in adb('install','-r','-d',args.apk)
            installed=adb('shell','pm','path',package).strip().removeprefix('package:')
            assert adb('shell','sha256sum',installed).split()[0]==expected
        paths=[root/'input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk',
               root/'input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk']
        for path,pkg in zip(paths,[fixture,fixture+'.test']):
            assert 'Success' in adb('install','-r',path)
            installed=adb('shell','pm','path',pkg).strip().removeprefix('package:')
            digest=adb('shell','sha256sum',installed).split()[0];assert digest==sha(path)
            metadata['fixtureApks'][pkg]=digest
        assert 'Success' in adb('shell','pm','clear',package)
        log=adb('shell','am','instrument','-w','-r','-e','evidenceRun',name,'-e','noLearning','true',
                '-e','touchInjectorSha256',helper_hash,'-e','class',fixture+'.ExternalEditorFastProfileTest',
                fixture+'.test/androidx.test.runner.AndroidJUnitRunner')
        (args.output/'instrumentation.log').write_text(log,encoding='utf-8',newline='\n')
        adb('pull',f'/sdcard/Android/data/{fixture}/files/input-quality/{name}',args.output/'device')
        metadata['functionalPassed']='OK (1 test)' in log and 'FAILURES!!!' not in log
        if not metadata['functionalPassed']:raise ValueError('Profiled input failed; retain observations')
        records=[json.loads(line) for line in (args.output/'device/profile-input.jsonl').read_text('utf-8').splitlines()]
        assert len(records)==3 and all(r['expected']==r['actual'] and r['composingFinished'] for r in records)
        metadata['profiles']=[]
        for row in records[1:]:
            target=args.output/f"{row['index']}.methods"
            adb('pull',row['remoteProfile'],target);adb('shell','rm',row['remoteProfile'])
            report=summarize(target.read_bytes(),include_all_methods=True)
            metadata['profiles'].append(dict(index=row['index'],**report))
            print(json.dumps(dict(index=row['index'],observedDecodeCpuUs=report['observedDecodeCpuUs'],exclusive=report['exclusiveCpuUs'][:10])),flush=True)
    finally:
        if original not in ('','null'):adb('shell','ime','set',original)
        if explicit:
            assert 'Success' in adb('install','-r','-d',args.restore_apk)
            installed=adb('shell','pm','path',package).strip().removeprefix('package:')
            metadata['restoredApkSha256']=adb('shell','sha256sum',installed).split()[0]
            assert metadata['restoredApkSha256']==original_hash
        metadata['restoredIme']=adb('shell','settings','get','secure','default_input_method').strip()
        (args.output/'report.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8',newline='\n')


if __name__=='__main__':main()
