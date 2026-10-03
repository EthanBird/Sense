"""Compare fixed-query retrieval without treating abbreviated dictionary labels as user intent."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


def read(path):
    lines = path.read_text('utf-8').splitlines()
    meta = dict(line[2:].split('=', 1) for line in lines if line.startswith('# '))
    rows = list(csv.DictReader((line for line in lines if not line.startswith('#')), delimiter='\t'))
    assert rows and len({r['id'] for r in rows}) == len(rows)
    return meta, rows


def metrics(rows):
    return {'rows': len(rows), 'targetFirst': sum(int(r['rank']) == 1 for r in rows),
            'targetTop10': sum(0 < int(r['rank']) <= 10 for r in rows),
            'targetRecall255': sum(int(r['rank']) > 0 for r in rows)}


def compare(a, b):
    assert len(a) == len(b)
    changes, rank_losses, first_losses, recall_losses = [], [], [], []
    for x, y in zip(a, b):
        assert all(x[k] == y[k] for k in ('id', 'stratum', 'mode', 'query', 'expected'))
        row = {k: x[k] for k in ('id', 'mode', 'query', 'expected')}
        row.update(beforeRank=int(x['rank']), afterRank=int(y['rank']), beforeFirst=x['top1'], afterFirst=y['top1'])
        if x['resultSha256'] != y['resultSha256']:
            changes.append(row)
        if int(x['rank']) > 0 and (int(y['rank']) == 0 or int(y['rank']) > int(x['rank'])):
            rank_losses.append(row)
        if x['rank'] == '1' and y['rank'] != '1':
            first_losses.append(row)
        if int(x['rank']) > 0 and int(y['rank']) == 0:
            recall_losses.append(row)
    groups = sorted({r['mode'] for r in a})
    return {'schemaVersion': 1, 'scope': 'Fixed diagnostic retrieval, not natural typing accuracy or Android latency',
            'before': metrics(a), 'after': metrics(b),
            'byMode': {g: {'before': metrics([r for r in a if r['mode'] == g]), 'after': metrics([r for r in b if r['mode'] == g])} for g in groups},
            'changedCompleteResults': changes, 'rankLosses': rank_losses,
            'targetFirstLosses': first_losses, 'targetRecallLosses': recall_losses,
            'passedNoFirstOrRecallLoss': not first_losses and not recall_losses}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('before', type=Path); p.add_argument('after', type=Path); p.add_argument('report', type=Path)
    p.add_argument('--base-dictionary', type=Path)
    args = p.parse_args()
    assert not args.report.exists(), 'Keep prior reports'
    ma, a = read(args.before); mb, b = read(args.after)
    assert ma == mb, 'Decoder-only comparison requires identical corpus and assets'
    report = compare(a, b)
    files = {'before': args.before, 'after': args.after}
    if args.base_dictionary:
        mbase, base = read(args.base_dictionary)
        assert mbase['inputSha256'] == ma['inputSha256']
        old = compare(base, a); new = compare(base, b)
        report['baseDictionaryControl'] = {'metrics': metrics(base),
            'beforeLost': old['targetRecallLosses'], 'afterLost': new['targetRecallLosses']}
        files['baseDictionary'] = args.base_dictionary
    report['inputSha256'] = {k: hashlib.sha256(v.read_bytes()).hexdigest() for k, v in files.items()}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: report[k] for k in ('before', 'after', 'passedNoFirstOrRecallLoss')}))


if __name__ == '__main__':
    main()
