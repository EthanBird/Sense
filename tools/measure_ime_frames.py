"""Capture actual IME-process HWUI frames on the dedicated disposable emulator only."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess

from fetch_e37_model import digest
from run_e37_rescoring import write_new

PRODUCT = 'io.github.ethanbird.senseime.debug'
FIXTURE = 'io.github.ethanbird.senseime.inputqualityfixture'
HELPER = '/data/local/tmp/sense-input-quality-touch/classes.dex'


def run(root, out, adb, serial):
    if not re.fullmatch(r'emulator-\d+', serial) or not re.fullmatch(r'[a-zA-Z0-9_-]+', out.name):
        raise ValueError('Dedicated emulator and simple evidence directory required')
    if out.exists():
        raise ValueError('Preserve all previous evidence')
    def call(*args):
        p = subprocess.run([str(adb), '-s', serial, *map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                           timeout=None if args[:3] == ('shell','am','instrument') else 180)
        value = p.stdout.decode('utf-8',errors='replace')
        if p.returncode:
            raise RuntimeError(value)
        return value
    if call('emu','avd','name').splitlines()[0].strip() != 'sense-input-quality':
        raise ValueError('Only the dedicated input-quality AVD may be changed')
    protocol = json.loads((root / '.artifacts/input-quality/e34/android-v2-protocol.json').read_text('utf-8'))
    expected = protocol['apks']['optimized']['sha256']
    remote = call('shell','pm','path',PRODUCT).strip().removeprefix('package:')
    if call('shell','sha256sum',remote).split()[0] != expected:
        raise ValueError('Installed product differs from the verified E34 input implementation')
    helper = call('shell','sha256sum',HELPER).split()[0]
    if helper != protocol['helperSha256']:
        raise ValueError('Event source changed')
    if call('shell','settings','get','global','debug_app').strip() not in ['', 'null']:
        raise ValueError('Debug wait configuration must be clear')
    original = call('shell','settings','get','secure','default_input_method').strip()
    fixtures = [root / 'input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk',
        root / 'input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk']
    out.mkdir(parents=True)
    lock = dict(stage='E40',sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
        serial=serial,avd='sense-input-quality',productApkSha256=expected,helperSha256=helper,
        fixtureApks={str(p):digest(p) for p in fixtures},
        sourceFiles={name:digest(root/name) for name in ['tools/measure_ime_frames.py',
            'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorFrameTimingTest.kt',
            'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/ExternalEditorTestFixture.kt']},
        sdk=call('shell','getprop','ro.build.version.sdk').strip(), originalIme=original,
        display=call('shell','wm','size'),density=call('shell','wm','density'),
        fontScale=call('shell','settings','get','system','font_scale').strip(),
        scope='Fixed two rounds: three 32ms-target typing sequences and one expanded scroll per round. '
              'HWUI frame completion is distinct from editor acknowledgement and physical presentation. '
              'Dedicated API37 emulator, no phone measurement, no CPU profiler or product instrumentation.',
        createdAt=datetime.now(timezone.utc).isoformat())
    write_new(out / 'pre-run-lock.json', lock)
    result = dict(passed=False,errors=[],restoration={})
    try:
        for path, package in zip(fixtures,[FIXTURE,FIXTURE+'.test']):
            install=call('install','-r',path)
            if 'Success' not in install:raise RuntimeError(install)
            remote=call('shell','pm','path',package).strip().removeprefix('package:')
            if call('shell','sha256sum',remote).split()[0] != digest(path):raise ValueError('Fixture install mismatch')
        # This is the sole data reset, restricted above to this disposable debug app.
        if 'Success' not in call('shell','pm','clear',PRODUCT):raise RuntimeError('Debug profile reset failed')
        log=call('shell','am','instrument','-w','-r','-e','evidenceRun',out.name,'-e','noLearning','true',
                 '-e','touchInjectorSha256',helper,'-e','class',FIXTURE+'.ExternalEditorFrameTimingTest',
                 FIXTURE+'.test/androidx.test.runner.AndroidJUnitRunner')
        (out/'instrumentation.log').write_text(log,encoding='utf-8')
        result['passed']='OK (1 test)' in log and 'FAILURES!!!' not in log
        print(log,flush=True)
    except Exception as e:
        result['errors'].append(repr(e))
    finally:
        try:
            call('pull',f'/sdcard/Android/data/{FIXTURE}/files/input-quality/{out.name}',out/'device')
        except Exception as e:
            result['errors'].append('evidence: '+repr(e))
        if original not in ['', 'null']:
            call('shell','ime','set',original)
        result['restoration']['ime']=call('shell','settings','get','secure','default_input_method').strip()
        result['restoration']['debugApp']=call('shell','settings','get','global','debug_app').strip()
        result['finishedAt']=datetime.now(timezone.utc).isoformat()
        write_new(out/'execution.json',result)
    if not result['passed'] or result['errors']:
        raise RuntimeError('Incomplete frame workload retained: '+str(result))
    print('Eight frame windows captured; frame-stream completeness still requires host audit',flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__)
    p.add_argument('root',type=Path);p.add_argument('out',type=Path)
    p.add_argument('--adb',type=Path,default=Path('F:/Android/Sdk/platform-tools/adb.exe'))
    p.add_argument('--serial',default='emulator-5580')
    a=p.parse_args();run(a.root.resolve(),a.out.resolve(),a.adb,a.serial)
