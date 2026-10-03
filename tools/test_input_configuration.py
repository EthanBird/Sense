"""Real IME configuration/interaction-timeout tests on the disposable AVD, restoring system settings."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('output',type=Path);p.add_argument('--timeout-only',action='store_true')
    p.add_argument('--expect-timeout-failure',action='store_true');p.add_argument('--skip-install',action='store_true')
    p.add_argument('--adb',default='F:/Android/Sdk/platform-tools/adb.exe');p.add_argument('--serial',default='emulator-5580')
    a=p.parse_args()
    if not re.fullmatch('emulator-[0-9]+',a.serial): raise ValueError('Dedicated emulator required')
    if a.output.exists(): raise ValueError('Retain prior evidence; use a fresh output path')
    if not re.fullmatch('[A-Za-z0-9_-]+',a.output.name): raise ValueError('Simple evidence folder name required')
    def adb(*args):return subprocess.check_output([a.adb,'-s',a.serial,*map(str,args)],text=True,encoding='utf-8').strip()
    if adb('emu','avd','name').splitlines()[0]!='sense-input-quality': raise ValueError('Unexpected AVD')
    root=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True)
    fixture='io.github.ethanbird.senseime.inputqualityfixture';app='io.github.ethanbird.senseime.debug'
    pairs=[(app,'app/build/outputs/apk/debug/app-debug.apk'),(fixture,'input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk'),
           (fixture+'.test','input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk')]
    result={'schemaVersion':1,'serial':a.serial,'sdk':adb('shell','getprop','ro.build.version.sdk'),'scope':'True system IME in explicit font/orientation configurations; emulator only','apks':{},'runs':[]}
    for package,path in pairs:
        digest=hashlib.sha256((root/path).read_bytes()).hexdigest();result['apks'][package]=digest
        if not a.skip_install:print(adb('install','-r',root/path),flush=True)
        remote=adb('shell','pm','path',package).removeprefix('package:')
        if adb('shell','sha256sum',remote).split()[0]!=digest: raise ValueError('Installed APK mismatch')
    settings=[('system','font_scale'),('system','accelerometer_rotation'),('system','user_rotation'),
              ('secure','accessibility_interactive_ui_timeout_ms'),('secure','default_input_method'),('secure','show_ime_with_hard_keyboard')]
    before={(namespace,name):adb('shell','settings','get',namespace,name) for namespace,name in settings}
    result['settingsBefore']={f'{ns}/{name}':v for (ns,name),v in before.items()}
    cases=[('timeout',1.,0,1)] if a.timeout_only else [('portrait',1.,0,3),('portrait-large',2.,0,3),('landscape',1.,1,3),('landscape-large',2.,1,3)]
    try:
        for name,scale,rotation,count in cases:
            adb('shell','settings','put','system','font_scale',scale)
            adb('shell','settings','put','system','accelerometer_rotation',0)
            adb('shell','settings','put','system','user_rotation',rotation)
            time.sleep(1.2)
            run=a.output.name+'-'+name; folder=a.output/name;folder.mkdir()
            clazz='ExternalEditorAccessibilityTimeoutTest' if a.timeout_only else 'ExternalEditorConfigurationTest'
            args=['shell','am','instrument','-w','-r','-e','class',fixture+'.'+clazz,'-e','noLearning','true',
                  '-e','evidenceRun',run,'-e','expectedFontScale',str(scale),'-e','expectedOrientation','2' if rotation else '1',
                  fixture+'.test/androidx.test.runner.AndroidJUnitRunner']
            with (folder/'instrumentation.log').open('wb') as log:
                subprocess.run([a.adb,'-s',a.serial,*args],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
            adb('pull',f'/sdcard/Android/data/{fixture}/files/input-quality/{run}/',folder/'device')
            text=(folder/'instrumentation.log').read_text('utf-8',errors='replace')
            passed=bool(re.search(rf'OK \({count} tests?\)',text)) and not re.search('FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED',text)
            item={'name':name,'fontScale':scale,'rotation':rotation,'expectedTests':count,'passed':passed,
                  'expectedFailure':bool(a.expect_timeout_failure and a.timeout_only),'logSha256':hashlib.sha256((folder/'instrumentation.log').read_bytes()).hexdigest()}
            result['runs'].append(item);print(json.dumps(item),flush=True)
            (a.output/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    finally:
        for (namespace,name),value in before.items():
            if name=='default_input_method' and value not in ('','null'): adb('shell','ime','set',value)
            elif value in ('','null'):adb('shell','settings','delete',namespace,name)
            else:adb('shell','settings','put',namespace,name,value)
        result['settingsRestored']={f'{ns}/{name}':adb('shell','settings','get',ns,name) for ns,name in settings}
        (a.output/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    if any(row['passed']==row['expectedFailure'] for row in result['runs']): raise SystemExit('Configuration acceptance differs from expectation')


if __name__=='__main__': main()
