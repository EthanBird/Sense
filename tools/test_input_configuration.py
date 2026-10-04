"""Real IME configuration/interaction-timeout tests on the disposable AVD, restoring system settings."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


DISPOSABLE_AVDS = {"sense-input-quality": 37, "sense-input-quality-api29": 29,
                   "sense-input-quality-api29-google": 29}


def validate_target(serial, actual_avd, actual_sdk, expected_avd):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Dedicated emulator required")
    if expected_avd not in DISPOSABLE_AVDS or actual_avd != expected_avd:
        raise ValueError("Unexpected disposable AVD")
    if str(actual_sdk) != str(DISPOSABLE_AVDS[expected_avd]):
        raise ValueError("AVD system image does not match the expected API level")


def density_state(text):
    physical = re.findall(r"^Physical density: ([0-9]+)$", text, re.M)
    override = re.findall(r"^Override density: ([0-9]+)$", text, re.M)
    if len(physical) != 1 or len(override) > 1:
        raise ValueError("Unrecognized wm density state")
    physical = int(physical[0]); override = int(override[0]) if override else None
    if physical <= 0 or (override is not None and override <= 0):
        raise ValueError("Invalid display density")
    return {"physical": physical, "override": override, "effective": override or physical}


def assert_restored(before, after, density_before, density_after):
    if before != after or density_before != density_after:
        raise ValueError("Configuration settings were not restored exactly")


def restoration_order(items):
    # API29 materializes the default font scale while persisting a rotation change.
    # Delete an originally absent font_scale only after all other configuration writes.
    return sorted(items, key=lambda item: item[0] == ('system', 'font_scale'))


def settle_font_scale(adb, value, pause=time.sleep):
    # Configuration persistence may race the first delete on an API29 fresh profile.
    # Keep the exact original value, require three stable reads, and bound all retries.
    attempts = []
    for _ in range(4):
        if value in ('', 'null'): adb('shell', 'settings', 'delete', 'system', 'font_scale')
        else: adb('shell', 'settings', 'put', 'system', 'font_scale', value)
        observed = []
        for _ in range(3):
            pause(.25)
            observed.append(adb('shell', 'settings', 'get', 'system', 'font_scale'))
            if observed[-1] != value: break
        attempts.append(observed)
        if observed == [value] * 3: break
    return attempts


def assert_font_settled(attempts, value):
    if not attempts or attempts[-1] != [value] * 3:
        raise ValueError("Font scale did not remain stable within the bounded restore attempts")


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('output',type=Path);p.add_argument('--timeout-only',action='store_true')
    p.add_argument('--expect-timeout-failure',action='store_true');p.add_argument('--skip-install',action='store_true')
    p.add_argument('--adb',default='F:/Android/Sdk/platform-tools/adb.exe');p.add_argument('--serial',default='emulator-5580')
    p.add_argument('--avd', choices=DISPOSABLE_AVDS, default='sense-input-quality')
    p.add_argument('--density', type=int, choices=(320, 420, 480, 560))
    p.add_argument('--minimum-heap-mib', type=int, choices=(0, 112, 128), default=0)
    a=p.parse_args()
    if not re.fullmatch('emulator-[0-9]+',a.serial): raise ValueError('Dedicated emulator required')
    if a.output.exists(): raise ValueError('Retain prior evidence; use a fresh output path')
    if not re.fullmatch('[A-Za-z0-9_-]+',a.output.name): raise ValueError('Simple evidence folder name required')
    def adb(*args):return subprocess.check_output([a.adb,'-s',a.serial,*map(str,args)],text=True,encoding='utf-8').strip()
    avd=adb('emu','avd','name').splitlines()[0];sdk=adb('shell','getprop','ro.build.version.sdk')
    validate_target(a.serial,avd,sdk,a.avd)
    root=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True)
    fixture='io.github.ethanbird.senseime.inputqualityfixture';app='io.github.ethanbird.senseime.debug'
    pairs=[(app,'app/build/outputs/apk/debug/app-debug.apk'),(fixture,'input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk'),
           (fixture+'.test','input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk')]
    result={'schemaVersion':2,'serial':a.serial,'avd':avd,'sdk':sdk,'scope':'True system IME in explicit density/font/orientation configurations; emulator only','apks':{},'runs':[]}
    for package,path in pairs:
        digest=hashlib.sha256((root/path).read_bytes()).hexdigest();result['apks'][package]=digest
        if not a.skip_install:print(adb('install','-r',root/path),flush=True)
        remote=adb('shell','pm','path',package).removeprefix('package:')
        if adb('shell','sha256sum',remote).split()[0]!=digest: raise ValueError('Installed APK mismatch')
    settings=[('system','font_scale'),('system','accelerometer_rotation'),('system','user_rotation'),
              ('secure','accessibility_interactive_ui_timeout_ms'),('secure','default_input_method'),('secure','show_ime_with_hard_keyboard')]
    before={(namespace,name):adb('shell','settings','get',namespace,name) for namespace,name in settings}
    result['settingsBefore']={f'{ns}/{name}':v for (ns,name),v in before.items()}
    result['densityBefore']=density_state(adb('shell','wm','density'))
    cases=[('timeout',1.,0,1)] if a.timeout_only else [('portrait',1.,0,3),('portrait-large',2.,0,3),('landscape',1.,1,3),('landscape-large',2.,1,3)]
    try:
        if a.density is not None: adb('shell','wm','density',a.density)
        result['densityDuring']=density_state(adb('shell','wm','density'))
        if a.density is not None and result['densityDuring']['effective']!=a.density:
            raise ValueError('Requested density was not applied')
        for name,scale,rotation,count in cases:
            adb('shell','settings','put','system','font_scale',scale)
            adb('shell','settings','put','system','accelerometer_rotation',0)
            adb('shell','settings','put','system','user_rotation',rotation)
            time.sleep(1.2)
            run=a.output.name+'-'+name; folder=a.output/name;folder.mkdir()
            clazz='ExternalEditorAccessibilityTimeoutTest' if a.timeout_only else 'ExternalEditorConfigurationTest'
            args=['shell','am','instrument','-w','-r','-e','class',fixture+'.'+clazz,'-e','noLearning','true',
                  '-e','evidenceRun',run,'-e','expectedFontScale',str(scale),'-e','expectedOrientation','2' if rotation else '1',
                  '-e','expectedDensityDpi',str(result['densityDuring']['effective']),
                  '-e','minimumHeapMiB',str(a.minimum_heap_mib),
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
        if a.density is not None:
            previous=result['densityBefore']['override']
            adb('shell','wm','density','reset' if previous is None else previous)
        for (namespace,name),value in restoration_order(before.items()):
            if name=='font_scale': continue
            if name=='default_input_method' and value not in ('','null'): adb('shell','ime','set',value)
            elif value in ('','null'):adb('shell','settings','delete',namespace,name)
            else:adb('shell','settings','put',namespace,name,value)
        result['fontRestoreAttempts']=settle_font_scale(adb,before[('system','font_scale')])
        result['settingsRestored']={f'{ns}/{name}':adb('shell','settings','get',ns,name) for ns,name in settings}
        result['densityRestored']=density_state(adb('shell','wm','density'))
        (a.output/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        assert_font_settled(result['fontRestoreAttempts'],before[('system','font_scale')])
        assert_restored(result['settingsBefore'],result['settingsRestored'],result['densityBefore'],result['densityRestored'])
    if any(row['passed']==row['expectedFailure'] for row in result['runs']): raise SystemExit('Configuration acceptance differs from expectation')


if __name__=='__main__': main()
