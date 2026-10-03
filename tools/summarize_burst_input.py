"""Describe actual burst cadence and main-thread editor confirmations, without protocol mixing."""
import argparse
from collections import defaultdict
import csv
import json
from pathlib import Path

from summarize_input_latency import metrics, summarize


def read_cadence(run, directory):
    path = directory / run['artifactDirectory'] / 'device/burst-cadence.tsv'
    with path.open(encoding='utf-8') as source:
        rows = list(csv.DictReader(source, delimiter='\t'))
    expected = {(r['round'], r['query']): r for r in run['samples']}
    if len(expected) != 16:
        raise ValueError('Expected 16 distinct confirmation rows')
    seen = set(); intervals = []
    for row in rows:
        identity = (int(row['round']), row['query'])
        if identity in seen or identity not in expected:
            raise ValueError('Duplicate or unmatched cadence row')
        seen.add(identity)
        values = list(map(int, row['actualStartIntervalsMs'].split(',')))
        if int(row['targetIntervalMs']) != 32 or len(values) != len(row['query']) - 1 or min(values) < 32:
            raise ValueError('Invalid actual key intervals')
        start, observed = int(row['spaceInjectionStartMs']), int(row['firstExpectedTextMs'])
        if start < 0 or observed < start or observed - start != expected[identity]['spaceToEditorMs']:
            raise ValueError('Editor timestamp differs from reported confirmation')
        intervals.extend(values)
    if seen != set(expected):
        raise ValueError('Missing cadence rows')
    return intervals


def describe_incomplete_attempt(measurements, directory):
    """Retain an unsuccessful experiment without comparing unbalanced success subsets."""
    runs = measurements['runs']
    if measurements.get('protocol') != 'async-touch-no-animation-wait-v1' or [r['mode'] for r in runs] != ['baseline','optimized','optimized','baseline']:
        raise ValueError('Wrong or incomplete attempted protocol')
    if all(r['passed'] for r in runs):
        raise ValueError('Use the complete comparison for a successful experiment')
    blocks = []
    identities = None
    for run in runs:
        block = {k: run[k] for k in ['block', 'mode', 'passed', 'artifactDirectory']}
        rows = run['samples']
        block['recordedConfirmations'] = len(rows)
        if run['passed']:
            if not all(r['passed'] and r['actual'] == r['expectedBaselineOutput'] for r in rows):
                raise ValueError('Incorrect output labeled as success')
            current = {(r['round'], r['query']) for r in rows}
            if identities is not None and current != identities:
                raise ValueError('Successful blocks used different workloads')
            identities = current
            block['actualKeyIntervalsMs'] = metrics(read_cadence(run, directory))
        else:
            log = (directory/run['artifactDirectory']/'instrumentation.log').read_text('utf-8')
            block['failure'] = next((s.strip() for s in log.splitlines() if s.startswith('INSTRUMENTATION_STATUS: stack=')), 'See instrumentation.log')
        blocks.append(block)
    return dict(protocol=measurements['protocol'], apkSha256=measurements['apkSha256'], blocks=blocks,
                completePairedComparison=False, confirmationMs=None,
                decision='Incomplete ABBA; no pooled success-only latency comparison or promotion decision',
                limitations='Actual cadence from successful blocks is diagnostic, not evidence of 32ms touch delivery. Failures are retained.')


def summarize_burst(measurements, directory):
    if measurements.get('protocol') != 'async-touch-no-animation-wait-v1':
        raise ValueError('An explicit no-animation-wait protocol is required')
    report = summarize(measurements, directory)
    cadence = defaultdict(list)
    blocks = []
    for run in measurements['runs']:
        intervals = read_cadence(run, directory)
        cadence[run['mode']].extend(intervals)
        blocks.append({'block': run['block'], 'mode': run['mode'], **metrics(intervals)})
    report['actualKeyIntervalsMs'] = {mode: metrics(values) for mode, values in cadence.items()}
    report['cadenceByBlock'] = blocks
    report['protocol'] = measurements['protocol']
    report['limitations'][2] = ('Timestamp on external editor main thread when expected text first appears; final text and '
        'non-composing state independently asserted. Includes queued injection/system scheduling; excludes asset loading. '
        'Not comparable to the legacy synchronous-injection/polling confirmation measurements.')
    report['promotionDecision'] = 'diagnostic-only; earlier failed production latency gate remains unchanged'
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('measurements', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--describe-incomplete', action='store_true')
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Preserve the previous report')
    method = describe_incomplete_attempt if args.describe_incomplete else summarize_burst
    result = method(json.loads(args.measurements.read_text('utf-8')), args.measurements.parent)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: result[k] for k in ['confirmationMs', 'actualKeyIntervalsMs', 'traces', 'blocks'] if k in result}))


if __name__ == '__main__':
    main()
