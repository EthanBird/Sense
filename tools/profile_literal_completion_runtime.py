"""Paired intrusive ART diagnosis after E21's latency guard failed; never an acceptance timer."""
import hashlib
import json
from pathlib import Path
import subprocess

from summarize_art_profile import summarize


def main():
    root = Path(__file__).resolve().parents[1]
    out = root / '.artifacts/input-quality/e21-art-diagnostic'
    if out.exists():
        raise ValueError('Preserve the earlier diagnostic')
    adb_path = 'F:/Android/Sdk/platform-tools/adb.exe'
    serial = 'emulator-5580'
    package = 'io.github.ethanbird.senseime.debug'
    process = package + ':ime'
    fixture = 'io.github.ethanbird.senseime.inputqualityfixture'

    def adb(*args):
        return subprocess.check_output([adb_path, '-s', serial, *map(str,args)], timeout=180).decode('utf-8', errors='replace')

    if adb('emu','avd','name').splitlines()[0].strip() != 'sense-input-quality':
        raise ValueError('Only the disposable AVD is supported')
    apks = {
        'baseline': Path('G:/workspace/sense-input-quality-reference/e20/sense-input-quality-e20-accepted-debug.apk'),
        'candidate': Path('G:/workspace/sense-input-quality-reference/e21/sense-input-quality-e21-efficient-debug.apk'),
    }
    original = adb('shell','settings','get','secure','default_input_method').strip()
    out.mkdir()
    result = dict(scope='Intrusive method sampling, one known complete query per variant after a nihao warmup. Not a new successful latency comparison.',
                  samplingIntervalUs=5000, runs=[])
    try:
        for mode, apk in apks.items():
            directory = out / mode
            directory.mkdir()
            assert 'Success' in adb('install','-r',apk)
            assert 'Success' in adb('shell','pm','clear',package)
            (directory / 'broadcast-barrier.txt').write_text(adb('shell','am','wait-for-broadcast-idle'),encoding='utf-8')
            (directory / 'dexopt-before.txt').write_text(adb('shell','cmd','package','art','dump',package),encoding='utf-8')

            def instrument(suffix, method):
                name = f'e21-art-{mode}-{suffix}'
                log = adb('shell','am','instrument','-w','-r','-e','evidenceRun',name,'-e','noLearning','true',
                          '-e','class',f'{fixture}.ExternalEditorInputTest#{method}',
                          f'{fixture}.test/androidx.test.runner.AndroidJUnitRunner')
                (directory / f'{suffix}-instrumentation.log').write_text(log,encoding='utf-8')
                adb('pull',f'/sdcard/Android/data/{fixture}/files/input-quality/{name}',directory / suffix)
                return 'OK (1 test)' in log and 'FAILURES!!!' not in log

            if not instrument('warmup','touchscreenPinyinCandidateCommitsThroughExternalInputConnection'):
                raise ValueError('Unprofiled warmup failed; retain the diagnostic, not a valid pair')
            pid = adb('shell','pidof',process).strip()
            if not pid.isdigit():
                raise ValueError('The warmed IME process is no longer running')
            remote = f'/data/local/tmp/e21-art-{mode}.trace'
            start = adb('shell','am','profile','start','--sampling','5000','--clock-type','dual',
                        '--profiler-output-version','3',pid,remote)
            (directory / 'profile-start.txt').write_text(start,encoding='utf-8')
            if 'Error' in start or 'Exception' in start:
                raise ValueError('ART profiler did not start')
            try:
                passed = instrument('sampled','rapidTypingAndImmediateSpaceCommitTheLatestSentenceOnce')
            finally:
                (directory / 'profile-stop.txt').write_text(adb('shell','am','profile','stop',process),encoding='utf-8')
                adb('pull',remote,directory / 'methods.trace')
                adb('shell','rm',remote)
            report = summarize((directory / 'methods.trace').read_bytes())
            (directory / 'profile-summary.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
            (directory / 'dexopt-after.txt').write_text(adb('shell','cmd','package','art','dump',package),encoding='utf-8')
            run = dict(mode=mode, apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                       pid=pid, profiledFunctionalPassed=passed, summary=report)
            result['runs'].append(run)
            (out / 'report.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
            print(json.dumps(dict(mode=mode,passed=passed,cpuUs=report['observedDecodeCpuUs'],top=report['exclusiveCpuUs'][:8])),flush=True)
    finally:
        assert 'Success' in adb('install','-r',apks['candidate'])
        if original not in ('','null'):
            adb('shell','ime','set',original)


if __name__ == '__main__':
    main()
