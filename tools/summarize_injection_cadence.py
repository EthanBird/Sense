"""Validate one fixed-APK ABBA event-source calibration; do not infer product speedup."""
from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path


CASES = [("nihao", "你好"), ("woxihuanbeijing", "我喜欢北京"),
         ("qingjianchayixiayoujian", "请检查一下邮件")]
CHANNELS = ["ui-automation", "input-manager", "input-manager", "ui-automation"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def distribution(values):
    require(bool(values), "Empty distribution")
    ordered = sorted(values)
    return dict(n=len(values), median=round(statistics.median(ordered), 3),
                p95=round(ordered[math.ceil(len(ordered) * .95) - 1], 3),
                minimum=round(ordered[0], 3), maximum=round(ordered[-1], 3))


def summarize(rows):
    expected_order = [(b, channel, query, expected) for b, channel in enumerate(CHANNELS) for query, expected in CASES]
    require([(r['block'], r['channel'], r['query'], r['expected']) for r in rows] == expected_order,
            "Incomplete, duplicated, or reordered calibration")
    grouped = {channel:dict(senderIntervals=[], actionCalls=[], downCalls=[], upCalls=[],
                            prefixDelays=[], prefixIntervals=[], commitDelays=[]) for channel in set(CHANNELS)}
    details = []
    for row in rows:
        query = row['query']; expected = row['expected']; starts = row['keyStartMs']; actions = row['actions']
        require(row['targetIntervalMs'] == 32, "Unexpected target cadence")
        require(len(starts) == len(query) + 1 and all(type(t) is int and t > 0 for t in starts), "Invalid key timestamps")
        intervals = [b-a for a,b in zip(starts, starts[1:])]
        require(all(t > 0 for t in intervals), "Nonmonotonic key timestamps")
        require([(a['keyIndex'], a['action']) for a in actions] == [(i,a) for i in range(len(starts)) for a in [0,1]],
                "Missing, duplicated, or reordered touch actions")
        previous_end = -1
        for action in actions:
            require(action['accepted'] is True, "Unaccepted touch action")
            require(type(action['beginNs']) is int and type(action['endNs']) is int and
                    previous_end <= action['beginNs'] <= action['endNs'], "Invalid event call timestamps")
            previous_end = action['endNs']
        changes = row['editorChanges']
        require(all(type(c['uptimeMs']) is int and c['uptimeMs'] > 0 and type(c['text']) is str for c in changes),
                "Invalid editor observation")
        require(all(a['uptimeMs'] <= b['uptimeMs'] for a,b in zip(changes, changes[1:])), "Nonmonotonic editor observations")
        prefixes = [c for c in changes if c['text'] and query.startswith(c['text'])]
        require([c['text'] for c in prefixes] == [query[:i] for i in range(1, len(query)+1)], "Raw prefix lost or reordered")
        delays = [c['uptimeMs']-starts[i] for i,c in enumerate(prefixes)]
        require(all(d >= 0 for d in delays), "Editor prefix observed before its injection")
        commits = [c for c in changes if c['text'] == expected]
        require(len(commits) == 1 and changes[-1]['text'] == expected, "Missing, duplicate, or stale final text")
        first_commit = commits[0]['uptimeMs']
        require(row['firstCommitMs'] == first_commit and 0 <= first_commit-starts[-1] <= 8000, "Invalid commit time")
        require(row['passed'] is True and row['prefixesIntact'] is True and row['composingFinished'] is True and
                row['actual'] == expected, "Failed end-to-end input")
        g = grouped[row['channel']]
        g['senderIntervals'] += intervals
        g['prefixDelays'] += delays
        g['prefixIntervals'] += [b['uptimeMs']-a['uptimeMs'] for a,b in zip(prefixes,prefixes[1:])]
        g['commitDelays'].append(first_commit-starts[-1])
        for action in actions:
            elapsed = (action['endNs']-action['beginNs'])/1e6
            g['actionCalls'].append(elapsed)
            g['downCalls' if action['action'] == 0 else 'upCalls'].append(elapsed)
        details.append(dict(block=row['block'],channel=row['channel'],query=query,
                            senderIntervalMs=distribution(intervals), prefixDelayMs=distribution(delays),
                            spaceToFirstExpectedTextMs=first_commit-starts[-1]))
    return dict(schemaVersion=1, scope='Fixed APK, event-source calibration; editor callbacks are not rendered-frame timestamps.',
                productSpeedupClaim=False, completeBursts=len(rows), allPrefixesAndCommitsVerified=True,
                channels={name:{key+'Ms':distribution(v) for key,v in data.items()} for name,data in sorted(grouped.items())},
                bursts=details)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('folder',type=Path);p.add_argument('report',type=Path)
    p.add_argument('--expected-apk-sha256',required=True)
    args=p.parse_args()
    env=json.loads((args.folder/'environment.json').read_text('utf-8-sig'))
    require(env['apks']['app/build/outputs/apk/debug/app-debug.apk']==args.expected_apk_sha256, 'APK identity mismatch')
    log=(args.folder/'instrumentation.log').read_text('utf-8-sig')
    require('OK (1 test)' in log and 'FAILURES!!!' not in log, 'Calibration instrumentation did not pass')
    rows=[json.loads(line) for line in (args.folder/'device/injection-cadence.jsonl').read_text('utf-8').splitlines()]
    result=summarize(rows);result['environment']=env
    args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result['channels'],ensure_ascii=False,indent=2))


if __name__=='__main__':main()
