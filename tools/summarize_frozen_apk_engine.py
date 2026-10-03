"""Validate the E22 frozen-APK Android diagnostic; retain phase and per-query costs."""
import argparse
import hashlib
import json
import math
from pathlib import Path
from statistics import median


def metrics(values):
    values = sorted(values)
    return dict(count=len(values), medianMs=median(values)/1e6,
                observedP95Ms=values[math.ceil(.95*len(values))-1]/1e6, maxMs=max(values)/1e6)


def summarize(measurements, blocks, lock):
    modes = lock['protocol']['order']
    if [r['mode'] for r in measurements['runs']] != modes or len(blocks) != 4:
        raise ValueError('Incomplete or reordered ABBA run')
    queries = lock['protocol']['queries']
    totals = {mode: [] for mode in set(modes)}
    fingerprints = {mode: {} for mode in set(modes)}
    pids = set()
    block_reports = []
    for run, records in zip(measurements['runs'], blocks):
        mode = run['mode']; header, *rows, footer = records
        if not run['passed'] or footer != dict(type='summary', rows=60, passed=True) or len(rows) != 60:
            raise ValueError('Failed or incomplete engine run')
        if header['type'] != 'header' or header['mode'] != mode or header['apkSha256'] != lock[mode+'Apk']['sha256']:
            raise ValueError('APK identity mismatch')
        if header['assets'] != lock['assetPins'] or header['configuration'] != dict(lmWeight=.5, oovFeature=-4, limit=255, learning=False):
            raise ValueError('Assets or decoder configuration changed')
        if header['queries'] != queries or header['repetitions'] != 6 or header['pid'] in pids:
            raise ValueError('Workload or process isolation mismatch')
        pids.add(header['pid'])
        expected_order = [(r,q) for r in range(6) for q in (queries if r%2==0 else list(reversed(queries)))]
        if [(r['round'],r['query']) for r in rows] != expected_order:
            raise ValueError('Missing or reordered engine observations')
        for row in rows:
            if not row['passed'] or not row['stable'] or row['actual'] != row['expected'] or min(row['wallNs'],row['threadCpuNs']) < 0:
                raise ValueError('Failed output, unstable candidates or invalid timing')
            old = fingerprints[mode].setdefault(row['query'], row['resultSha256'])
            if old != row['resultSha256']:
                raise ValueError('Complete results changed across blocks')
        totals[mode].extend(rows)
        block_reports.append(dict(block=run['block'],mode=mode,pid=header['pid'],
            wall=metrics([r['wallNs'] for r in rows]),cpu=metrics([r['threadCpuNs'] for r in rows]),
            gcCounts=[r.get('gcCount') for r in rows]))
    def report(predicate):
        return {mode: {field:metrics([r[field] for r in rows if predicate(r)]) for field in ['wallNs','threadCpuNs']}
                for mode,rows in sorted(totals.items())}
    return dict(scope='Fixed uncancelled ART calls from actual APK bytecode, not UI/IME or phone latency; not fresh language accuracy.',
                calls=240,firstPass=report(lambda r:r['round']==0),repeated=report(lambda r:r['round']>0),
                perQuery=[dict(query=q,fullCandidateEqual=fingerprints['reference'][q]==fingerprints['candidate'][q],
                    firstPass=report(lambda r:r['query']==q and r['round']==0),
                    repeated=report(lambda r:r['query']==q and r['round']>0)) for q in queries],
                fingerprints=fingerprints,blocks=block_reports)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('output',type=Path)
    args=parser.parse_args()
    if args.output.exists():raise ValueError('Preserve prior report')
    lock=json.loads((args.directory.parent/'before-lock.json').read_text())
    measurements=json.loads((args.directory/'measurements.json').read_text())
    paths=[args.directory/r['directory']/'device/engine.jsonl' for r in measurements['runs']]
    report=summarize(measurements,[[json.loads(line) for line in p.read_text('utf-8').splitlines()] for p in paths],lock)
    report['inputFiles']={p.as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:report[k] for k in ['calls','firstPass','repeated']},ensure_ascii=False))


if __name__=='__main__':main()
