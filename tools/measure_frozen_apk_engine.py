"""Run the preregistered E22 ART engine ABBA independently of InputMethodService/UI."""
import hashlib
import argparse
import json
from pathlib import Path
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    art = root / '.artifacts/input-quality/e22'
    lock = json.loads((art / 'before-lock.json').read_text())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', default='engine-abba')
    name = parser.parse_args().run
    if not name.replace('-', '').isalnum():
        raise ValueError('Use a simple evidence name')
    out = art / name
    if out.exists():
        raise ValueError('Preserve prior run; diagnose failures before choosing a new run directory')
    adb = ['F:/Android/Sdk/platform-tools/adb.exe','-s','emulator-5580']
    def call(*args):
        return subprocess.check_output(adb + list(map(str,args)),timeout=300).decode('utf-8',errors='replace')
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()
    if call('emu','avd','name').splitlines()[0].strip() != 'sense-input-quality':
        raise ValueError('Only the dedicated disposable AVD is supported')
    fixture = 'io.github.ethanbird.senseime.inputqualityfixture'
    original_ime = call('shell','settings','get','secure','default_input_method').strip()
    out.mkdir()
    packages = ['input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk',
                'input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk']
    result = dict(scope=lock['scope'],protocol=lock['protocol'],fixtureApks={p:sha(root/p) for p in packages},runs=[])
    for p in packages:
        assert 'Success' in call('install','-r',root/p)
    remote = f'/sdcard/Android/data/{fixture}/files/e22-apks'
    call('shell','mkdir','-p',remote)
    for mode in ['reference','candidate']:
        apk = lock[mode+'Apk']; path = Path(apk['path'])
        assert sha(path) == apk['sha256']
        call('push',path,f'{remote}/{mode}.apk')
        assert call('shell','sha256sum',f'{remote}/{mode}.apk').split()[0] == apk['sha256']
    for block, mode in enumerate(lock['protocol']['order'],1):
        run = f'e22-{name}-{block}-{mode}'
        directory = out/run; directory.mkdir()
        call('shell','am','force-stop',fixture)
        (directory/'dexopt-before.txt').write_text(call('shell','cmd','package','art','dump',fixture),encoding='utf-8')
        log = call('shell','am','instrument','-w','-r','-e','evidenceRun',run,
                   '-e','engineMode',mode,'-e','engineSha256',lock[mode+'Apk']['sha256'],
                   '-e','class',f'{fixture}.FrozenApkEngineTest',f'{fixture}.test/androidx.test.runner.AndroidJUnitRunner')
        (directory/'instrumentation.log').write_text(log,encoding='utf-8')
        # Pull partial evidence even when instrumentation failed.
        call('pull',f'/sdcard/Android/data/{fixture}/files/input-quality/{run}',directory/'device')
        (directory/'dexopt-after.txt').write_text(call('shell','cmd','package','art','dump',fixture),encoding='utf-8')
        passed = 'OK (1 test)' in log and 'FAILURES!!!' not in log
        result['runs'].append(dict(block=block,mode=mode,passed=passed,directory=run))
        (out/'measurements.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(result['runs'][-1]),flush=True)
        if not passed:
            raise ValueError('Engine fixture failed; preserve this diagnostic and fix the harness before continuing')
    assert call('shell','settings','get','secure','default_input_method').strip() == original_ime


if __name__ == '__main__':
    main()
