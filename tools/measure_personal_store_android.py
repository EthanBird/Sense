"""Fixed ABBA constructor comparison on ART, using frozen production APK classes.

Isolated fixture process, fixed clock and identical SQLite bytes for both APKs.
Not IME first-frame timing. Every observation is retained; never rerun to pass.
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
from summarize_input_latency import metrics

FIXTURE = 'io.github.ethanbird.senseime.inputqualityfixture'
DATASETS = [(1000, True, '1000.db'), (10000, True, '10000.db'), (10000, False, '10000-no-shared-alias.db')]


def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p, x): p.write_text(json.dumps(x, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')


def compare_datasets(datasets):
    results = {}
    for name, bucket in datasets.items():
        result = dict(stateEqual=len(bucket['fingerprints'])==1, stateSha256=sorted(bucket['fingerprints']),
                      timingUnit='ms', timings={})
        for variant in ['baseline', 'candidate']:
            if len(bucket[variant]) != 10: raise ValueError('Exactly ten measured rows per variant and dataset required')
            result['timings'][variant] = {field: metrics([r[field]/1e6 for r in bucket[variant]])
                for field in ['readWallNs', 'readCpuNs', 'restoreWallNs', 'restoreCpuNs']}
        a, b = result['timings']['baseline'], result['timings']['candidate']
        result['nonregression'] = all(b[f][q] <= a[f][q] for f in ['restoreWallNs', 'restoreCpuNs']
                                     for q in ['medianMs', 'observedP95Ms'])
        result['medianWallRatio'] = b['restoreWallNs']['medianMs']/a['restoreWallNs']['medianMs']
        result['passed'] = result['stateEqual'] and result['nonregression'] and (
            name != '10000-shared-True' or result['medianWallRatio'] <= .85)
        results[name] = result
    if set(results) != {f'{size}-shared-{shared}' for size, shared, _ in DATASETS}:
        raise ValueError('All three declared datasets required')
    return results, all(result['passed'] for result in results.values())


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output', type=Path)
    p.add_argument('--baseline-apk', type=Path, required=True)
    p.add_argument('--candidate-apk', type=Path, required=True)
    args = p.parse_args()
    if args.output.exists() or not re.fullmatch(r'[a-zA-Z0-9_-]+', args.output.name):
        raise ValueError('New simple evidence directory required')
    root = Path(__file__).resolve().parents[1]
    def adb(*parts):
        r = subprocess.run(['F:/Android/Sdk/platform-tools/adb.exe', '-s', 'emulator-5580', *map(str, parts)],
                           capture_output=True, timeout=300)
        if r.returncode: raise RuntimeError((r.stdout+r.stderr).decode('utf-8', errors='replace'))
        return (r.stdout+r.stderr).decode('utf-8', errors='replace')
    if adb('emu', 'avd', 'name').splitlines()[0].strip() != 'sense-input-quality':
        raise ValueError('Dedicated AVD required')
    release = json.loads((root/'benchmarks/results/release-v0.4.16-rc.3.json').read_text('utf-8'))
    if sha(args.baseline_apk) != release['androidFunctionalEvidence']['debugApkSha256']:
        raise ValueError('Baseline must be the published rc.3 debug evidence artifact')
    if sha(args.baseline_apk) == sha(args.candidate_apk): raise ValueError('Different candidate required')
    args.output.mkdir(parents=True)
    now = int(adb('shell', 'date', '+%s').strip())*1000
    source = root/'ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/PersistentUserLexicon.kt'
    for size in [1000, 10000]: create_database(args.output/f'{size}.db', size, now, source.read_text('utf-8'))
    noalias = args.output/'10000-no-shared-alias.db'; shutil.copyfile(args.output/'10000.db', noalias)
    with closing(sqlite3.connect(noalias)) as db, db:
        assert db.execute("UPDATE user_phrase SET aliases='' WHERE aliases='qqq'").rowcount == 9996
    apks = {'baseline': args.baseline_apk, 'candidate': args.candidate_apk}
    report = dict(schemaVersion=1, order=['baseline', 'candidate', 'candidate', 'baseline'], fixedClockMillis=now,
        apks={k: {'path': str(v.resolve()), 'sha256': sha(v)} for k, v in apks.items()},
        scope='Actual frozen APK constructors in the fixture UID, not system IME startup or natural typing latency',
        policy='Four fixed ABBA fresh-process blocks. Per block one warmup per dataset, then five fixed rounds. '
               'Full state equality; all three datasets median/P95 restore wall and CPU nonregression; '
               '10k saturated-alias median wall at least 15 percent faster. No timing-based reruns.',
        sources={str(path.relative_to(root)).replace('\\', '/'): sha(path) for path in [Path(__file__),
            root/'tools/personal_dictionary_fixture.py', root/'tools/summarize_input_latency.py', source,
            root/'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/UserPersonalization.kt',
            root/'input-quality-device/src/androidTest/kotlin/io/github/ethanbird/senseime/inputqualityfixture/FrozenPersonalStoreProfileTest.kt']},
        fixtureApks={}, runs=[])
    for path in [root/'input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk',
                 root/'input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk']:
        report['fixtureApks'][path.name] = sha(path)
        if 'Success' not in adb('install', '-r', path): raise ValueError('Fixture install failed')
    write(args.output/'protocol.json', report)
    datasets = {}
    for block, variant in enumerate(report['order']):
        name = f'{args.output.name}-{block}-{variant}'; folder = args.output/name; folder.mkdir()
        steps = [dict(size=size, sharedAlias=shared, file=file, sha256=sha(args.output/file),
                      warmup=round_index==0, profile=False, fingerprint=True)
                 for round_index in range(6) for size, shared, file in DATASETS]
        policy = dict(apkSha256=sha(apks[variant]), fixedClockMillis=now, steps=steps)
        write(folder/'protocol.json', policy)
        remote = f'/sdcard/Android/data/{FIXTURE}/files/input-quality/{name}'
        adb('shell', 'am', 'force-stop', FIXTURE)
        adb('shell', 'mkdir', '-p', remote)
        for path in [folder/'protocol.json']+list(args.output.glob('*.db')): adb('push', path, remote+'/'+path.name)
        adb('push', apks[variant], remote+'/source.apk')
        print(json.dumps({'block': block, 'variant': variant, 'state': 'starting'}), flush=True)
        log = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'evidenceRun', name, '-e', 'class',
                  FIXTURE+'.FrozenPersonalStoreProfileTest', FIXTURE+'.test/androidx.test.runner.AndroidJUnitRunner')
        (folder/'instrumentation.log').write_text(log, encoding='utf-8', newline='\n')
        adb('pull', remote+'/store-load.jsonl', folder/'store-load.jsonl')
        rows = [json.loads(line) for line in (folder/'store-load.jsonl').read_text('utf-8').splitlines()]
        if 'OK (1 test)' not in log or 'FAILURES!!!' in log or not rows[-1].get('passed'):
            raise ValueError('Comparison failed; keep all observations')
        assert rows[0]['apkSha256'] == sha(apks[variant]) and rows[0]['defaultConstructorMask'] == 252
        assert rows[0]['fixedClockMillis'] == now and rows[-1]['steps'] == 18 and len(rows) == 20
        report['runs'].append(dict(block=block, variant=variant, file=str((folder/'store-load.jsonl').relative_to(args.output)),
                                   sha256=sha(folder/'store-load.jsonl')))
        for index, row in enumerate(rows[1:-1]):
            size, shared, _ = DATASETS[index % 3]; key = f'{size}-shared-{shared}'
            assert row['index'] == index and row['size'] == row['restoredRecords'] == size
            assert row['sharedAlias'] == shared and row['qqqAliasCount'] == (128 if shared else 0)
            assert row['warmup'] == (index < 3) and not row['profiled']
            bucket = datasets.setdefault(key, {'fingerprints': set(), 'baseline': [], 'candidate': []})
            bucket['fingerprints'].add(row['stateSha256'])
            assert re.fullmatch('[0-9a-f]{64}', row['stateSha256'])
            if not row['warmup']: bucket[variant].append(row)
        print(json.dumps({'block': block, 'variant': variant, 'state': 'complete'}), flush=True)
    results, all_passed = compare_datasets(datasets)
    report.update(results=results, passed=all_passed)
    write(args.output/'result.json', report)
    print(json.dumps(results, ensure_ascii=False), flush=True)
    if not all_passed: raise SystemExit('Comparison gate failed; retained evidence, no rerun')


if __name__ == '__main__': main()
