"""Single-thread loader-entry fault injection on the dedicated Sense debug AVD.

Host JDK JDI class-prepare/breakpoint events avoid random suspension inside a
classloader, asset or SQLite critical section. No shipping fault switch exists.
This is deterministic lifecycle evidence, never normal-startup timing evidence.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

PACKAGE = 'io.github.ethanbird.senseime.debug'
FIXTURE = 'io.github.ethanbird.senseime.inputqualityfixture'
METHODS = [
    'coldConfirmationRetainsQueuedWordsAcrossLoadingNoticeDeadline',
    'coldEnterExplicitlyKeepsRawAndPublicationDoesNotOverwriteIt',
    'coldDeleteCorrectsPendingSpellingBeforePublication',
    'coldEditorSwitchInvalidatesOldConfirmationAndKeepsNewInput',
]


def validate_target(serial, output):
    if not re.fullmatch(r'emulator-\d+', serial) or not re.fullmatch(r'[a-zA-Z0-9_-]+', output.name):
        raise ValueError('Dedicated emulator and simple fresh output name required')
    if output.exists():
        raise ValueError('Retain prior evidence; choose a fresh output directory')


def acceptance(transcript, entry, pause_evidence):
    return ('OK (1 test)' in transcript and
            not any(t in transcript for t in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')) and
            entry.get('loaderPausedMs', 0) > 0 and entry.get('debuggerExitCode') == 0 and
            all(t in pause_evidence for t in ('thread=sense-decoder-loader', 'suspendPolicy=EVENT_THREAD',
                                             'ownedMonitors=0', 'mainSuspended=false')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--adb', default='F:/Android/Sdk/platform-tools/adb.exe')
    parser.add_argument('--jdk', type=Path, default=Path('F:/Android/Jdk/jdk-17'))
    parser.add_argument('--serial', default='emulator-5580')
    parser.add_argument('--method', choices=METHODS)
    args = parser.parse_args()
    validate_target(args.serial, args.output)

    def adb(*parts, check=True):
        r = subprocess.run([args.adb, '-s', args.serial, *map(str, parts)], capture_output=True, timeout=40)
        if check and r.returncode:
            raise RuntimeError(r.stdout.decode(errors='replace') + r.stderr.decode(errors='replace'))
        return r.stdout.decode('utf-8', errors='replace')

    if adb('emu', 'avd', 'name').splitlines()[0].strip() != 'sense-input-quality':
        raise ValueError('Only the dedicated sense-input-quality AVD is supported')
    if adb('shell', 'settings', 'get', 'global', 'debug_app').strip() not in ('', 'null'):
        raise ValueError('Existing debugger setting; retain it and stop this fixture')
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True)
    classes = root / 'build/cold-loader-debugger'
    classes.mkdir(parents=True, exist_ok=True)
    source = root / 'tools/android-fixture/ColdLoaderDebugger.java'
    subprocess.run([str(args.jdk / 'bin/javac.exe'), '--add-modules', 'jdk.jdi', '-d', str(classes), str(source)], check=True)
    apks = {
        PACKAGE: root / 'app/build/outputs/apk/debug/app-debug.apk',
        FIXTURE: root / 'input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk',
        FIXTURE + '.test': root / 'input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk',
    }
    hashes = {}
    for package, apk in apks.items():
        hashes[package] = hashlib.sha256(apk.read_bytes()).hexdigest()
        if 'Success' not in adb('install', '-r', apk): raise RuntimeError('Install failed')
    original = adb('shell', 'settings', 'get', 'secure', 'default_input_method').strip()
    keyboard = adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').strip()
    result = {'schemaVersion': 1, 'scope': 'Host JDI controlled cold-loader delay; not startup latency',
              'serial': args.serial, 'sdk': adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
              'helperSourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'apkSha256': hashes, 'tests': []}
    try:
        for index, method in enumerate([args.method] if args.method else METHODS):
            name = f'{output.name}-{index}'
            folder = output / name
            folder.mkdir()
            control = folder / 'control'
            remote = f'/sdcard/Android/data/{FIXTURE}/files/input-quality/{name}'
            def exists(suffix):
                return adb('shell', f'test -f {remote}/{method}.{suffix} && echo yes || true').strip() == 'yes'
            def marker(suffix): adb('shell', f'echo ok > {remote}/{method}.{suffix}')
            entry = {'method': method, 'passed': False}
            debugger = process = port = None
            paused_at = None
            with (folder / 'instrumentation.log').open('wb') as log, (folder / 'debugger.log').open('wb') as debug_log:
                try:
                    adb('shell', 'am', 'force-stop', PACKAGE)
                    adb('shell', 'am', 'wait-for-broadcast-idle')
                    if adb('shell', f'pidof {PACKAGE}:ime || true').strip(): raise RuntimeError('Old IME still running')
                    adb('shell', 'am', 'set-debug-app', '-w', PACKAGE + ':ime')
                    process = subprocess.Popen([args.adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                        '-e', 'evidenceRun', name, '-e', 'hostLoaderPause', 'true', '-e', 'coldStart', 'true',
                        '-e', 'noLearning', 'true', '-e', 'class', f'{FIXTURE}.ExternalEditorColdLoaderTest#{method}',
                        f'{FIXTURE}.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=subprocess.STDOUT)
                    deadline = time.monotonic() + 95
                    while process.poll() is None:
                        if time.monotonic() > deadline: raise TimeoutError('Cold-loader acceptance deadline')
                        if debugger is None:
                            pid = adb('shell', f'pidof {PACKAGE}:ime || true').strip()
                            if pid:
                                if not pid.isdigit(): raise RuntimeError('Expected one debug IME process')
                                port = int(adb('forward', 'tcp:0', f'jdwp:{pid}').strip())
                                debugger = subprocess.Popen([str(args.jdk / 'bin/java.exe'), '--add-modules', 'jdk.jdi',
                                    '-cp', str(classes), 'ColdLoaderDebugger', str(port), str(control)],
                                    stdout=debug_log, stderr=subprocess.STDOUT)
                                entry['imePid'] = int(pid)
                        if (control / 'paused').exists() and paused_at is None:
                            paused_at = time.monotonic()
                            marker('paused')
                        if paused_at is not None and not (control / 'resume').exists() and exists('resume'):
                            (control / 'resume').write_text('resume\n', encoding='utf-8')
                        if (control / 'resumed').exists() and 'loaderPausedMs' not in entry:
                            entry['loaderPausedMs'] = round((time.monotonic() - paused_at) * 1000)
                            marker('resumed')
                        if debugger is not None and debugger.poll() not in (None, 0):
                            raise RuntimeError('Host debugger failed; inspect debugger.log')
                        time.sleep(.08)
                except Exception as error:
                    entry['error'] = f'{type(error).__name__}: {error}'
                finally:
                    cleanup = []
                    def attempt(label, fn):
                        try: fn()
                        except Exception as error: cleanup.append(f'{label}: {type(error).__name__}: {error}')
                    if control.exists(): attempt('request resume', lambda: (control / 'resume').write_text('cleanup\n'))
                    if debugger is not None:
                        attempt('debugger exit', lambda: debugger.wait(timeout=6))
                        if debugger.poll() is None: attempt('terminate debugger', debugger.terminate)
                        attempt('debugger terminal', lambda: debugger.wait(timeout=6))
                        entry['debuggerExitCode'] = debugger.poll()
                    if process is not None and process.poll() is None:
                        attempt('stop fixture', lambda: adb('shell', 'am', 'force-stop', FIXTURE))
                        attempt('instrumentation terminal', lambda: process.wait(timeout=8))
                        if process.poll() is None: attempt('terminate adb', process.terminate)
                    attempt('clear debug setting', lambda: adb('shell', 'am', 'clear-debug-app'))
                    if port is not None: attempt('remove forward', lambda: adb('forward', '--remove', f'tcp:{port}'))
                    attempt('pull evidence', lambda: adb('pull', remote, folder / 'device'))
                    if cleanup: entry['cleanupErrors'] = cleanup
            transcript = (folder / 'instrumentation.log').read_text('utf-8', errors='replace')
            pause_evidence = (control / 'paused').read_text('utf-8') if (control / 'paused').exists() else ''
            entry['passed'] = not entry.get('error') and not entry.get('cleanupErrors') and acceptance(transcript, entry, pause_evidence)
            result['tests'].append(entry)
            (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
            print(json.dumps(entry), flush=True)
    finally:
        # Every cleanup is attempted even after a debugger, adb or instrumentation failure.
        try: adb('shell', 'am', 'clear-debug-app')
        finally:
            try:
                if original not in ('', 'null'): adb('shell', 'ime', 'set', original)
            finally:
                if keyboard in ('0','1'): adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', keyboard)
                else: adb('shell', 'settings', 'delete', 'secure', 'show_ime_with_hard_keyboard')
    if not result['tests'] or not all(t['passed'] for t in result['tests']): raise SystemExit('Cold-loader acceptance failed; evidence retained')


if __name__ == '__main__': main()
