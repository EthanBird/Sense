"""Summarize a frozen fast-input comparison, retaining every failed block."""
import argparse
import json
from pathlib import Path

from summarize_injection_cadence import require
from summarize_input_latency import metrics, summarize, latency_regression_gate


def validate_record(row, sequence, expected, prefixes=False):
    require(row['sequence'] == sequence and row['expected'] == expected, 'Unexpected input sequence')
    source = row['source']
    require(source['status'] == 'ok' and source['sourceUid'] == 2000 and
            type(source['targetUid']) is int and source['targetUid'] >= 10000, 'Unexpected event source')
    starts = source['keyStartMs']
    require(len(starts) == len(sequence) and all(type(t) is int and t > 0 for t in starts), 'Missing key timestamps')
    intervals = [b-a for a,b in zip(starts, starts[1:])]
    require(all(t >= 32 for t in intervals), 'Invalid sender cadence')
    actions = source['actions']
    require([(a['keyIndex'], a['action']) for a in actions] == [(i,a) for i in range(len(sequence)) for a in [0,1]],
            'Missing, reordered, or duplicated touch actions')
    previous = -1
    for action in actions:
        require(action['accepted'] is True and type(action['beginNs']) is int and type(action['endNs']) is int
                and previous <= action['beginNs'] <= action['endNs'], 'Invalid action receipt or clock')
        previous = action['endNs']
    changes = row['editorChanges']
    require(changes and all(type(c['uptimeMs']) is int and c['uptimeMs'] > 0 and type(c['text']) is str for c in changes),
            'Missing editor observations')
    require(all(a['uptimeMs'] <= b['uptimeMs'] for a,b in zip(changes, changes[1:])), 'Invalid editor clock')
    if prefixes:
        query = sequence[:-1]
        raw = [c for c in changes if c['text'] and query.startswith(c['text'])]
        require([c['text'] for c in raw] == [query[:i] for i in range(1, len(query)+1)], 'Missing or reordered raw prefix')
        require(all(c['uptimeMs'] >= starts[i] for i,c in enumerate(raw)), 'Prefix precedes touch')
        require(row['prefixesIntact'] is True, 'Reported prefix failure')
    commits = [c['uptimeMs'] for c in changes if c['text'] == expected]
    require(commits and changes[-1]['text'] == expected and row['firstExpectedTextMs'] == commits[0], 'Missing final editor text')
    latency = commits[0]-starts[-1]
    require(0 <= latency <= 8000, 'Invalid confirmation time')
    require(row['passed'] is True and row['composingFinished'] is True and row['actual'] == expected, 'Failed end-to-end input')
    return intervals, latency


def summarize_fast(measurements, lock, directory):
    require(measurements['protocol'] == 'shell-touch-v1', 'Unexpected event protocol')
    require(measurements['apkSha256'] == {m: v['sha256'] for m,v in lock['apks'].items()}, 'Product APK mismatch')
    require(measurements['helperSha256'] == lock['helperSha256'], 'Event helper mismatch')
    runs = measurements['runs']
    require([r['mode'] for r in runs] == lock['order'] and [r['block'] for r in runs] == [1,2,3,4], 'Incomplete ABBA')
    queries = lock['queries']
    expected_order = [(i,q,e) for i,items in enumerate([queries, list(reversed(queries))]) for q,e in items]
    output = dict(schemaVersion=1, protocol='shell-touch-v1', scope=measurements['scope'],
                  apkSha256=measurements['apkSha256'], blocks=[], failures=[], comparison=None,
                  promotionPassed=False, stableReleaseReady=False)
    for run in runs:
        folder = directory / run['artifactDirectory']
        report = dict(block=run['block'], mode=run['mode'], functionalPassed=False, cadencePassed=False)
        output['blocks'].append(report)
        try:
            require(run['passed'] is True and not run.get('errors'), 'Runner or functional assertion failed')
            require(run['installedApkSha256'] == lock['apks'][run['mode']]['sha256'], 'Installed product differs')
            log = (folder/'instrumentation.log').read_text('utf-8')
            require('OK (1 test)' in log and 'FAILURES!!!' not in log, 'Instrumentation failed')
            rows = [json.loads(line) for line in (folder/'device/fast-input.jsonl').read_text('utf-8').splitlines()]
            require([(r['round'],r['query'],r['expected']) for r in rows] == expected_order, 'Input workload differs')
            require([(r['round'],r['query'],r['expectedBaselineOutput']) for r in run['samples']] == expected_order, 'Timing workload differs')
            intervals = []
            for row, timing in zip(rows, run['samples']):
                cadence, latency = validate_record(row, row['query']+' ', row['expected'], prefixes=True)
                require(latency == timing['spaceToEditorMs'] and timing['passed'] is True and
                        timing['actual'] == row['actual'], 'Timing and raw observation disagree')
                intervals += cadence
            continued = [json.loads(line) for line in (folder/'device/continued-input.jsonl').read_text('utf-8').splitlines()]
            require([r['sequence'] for r in continued] == lock['followupInputs'], 'Continuous followup workload differs')
            for row, index in zip(continued, [3,7]):
                cadence, _ = validate_record(row, queries[index][0]+' nihao ', queries[index][1]+'你好')
                intervals += cadence
            report['senderIntervalsMs'] = metrics(intervals)
            report['functionalPassed'] = True
            report['cadencePassed'] = report['senderIntervalsMs']['medianMs'] <= 40 and report['senderIntervalsMs']['observedP95Ms'] <= 48
        except (ValueError, KeyError, OSError, TypeError) as error:
            output['failures'].append(dict(block=run['block'], mode=run['mode'], reason=str(error)))
    if all(r['functionalPassed'] for r in output['blocks']):
        try:
            comparison = summarize(measurements, directory)
            comparison['limitations'][2] = ('32ms-target shell touch; measured actual sender cadence and external editor callbacks. '
                                           'Includes injection and scheduling, excludes initial loading; not rendered-frame timing.')
            comparison['latencyGate'] = latency_regression_gate(comparison)
            output['comparison'] = comparison
            output['promotionPassed'] = all(r['cadencePassed'] for r in output['blocks']) and comparison['latencyGate']['passed']
        except ValueError as error:
            output['failures'].append(dict(reason=str(error)))
    return output


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory',type=Path);p.add_argument('output',type=Path)
    args=p.parse_args()
    result=summarize_fast(json.loads((args.directory/'measurements.json').read_text('utf-8')),
                          json.loads((args.directory/'protocol-lock.json').read_text('utf-8')),args.directory)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(promotionPassed=result['promotionPassed'],blocks=result['blocks'],failures=result['failures'],
                         confirmationMs=(result['comparison'] or {}).get('confirmationMs')),ensure_ascii=False,indent=2))


if __name__=='__main__':main()
