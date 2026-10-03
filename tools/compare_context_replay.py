"""Compare two decoder versions on identical paired-context replay rows."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
from summarize_context_replay import summarize


def compare(before, after):
    a, b = summarize(before), summarize(after)
    def index(rows):
        return {(r['id'], r['cut'], r['mode']): r for r in rows}
    left, right = index(before), index(after)
    if left.keys() != right.keys():
        raise ValueError('Version comparison requires identical replay identities')
    invariant_changes, first_gains, first_losses, rank_losses, changes = [], [], [], [], []
    for key, x in left.items():
        y = right[key]
        if any(x[k] != y[k] for k in ('stratum', 'context', 'query', 'expected')):
            raise ValueError('Version comparison changed labels')
        changed = x['resultSha256'] != y['resultSha256']
        detail = {k: x[k] for k in ('id', 'cut', 'mode', 'stratum', 'context', 'query', 'expected')}
        detail.update(beforeRank=int(x['rank']), afterRank=int(y['rank']), beforeFirst=x['top1'], afterFirst=y['top1'])
        if changed and (x['mode'] in ('empty', 'boundary') or len(x['query']) <= 6):
            invariant_changes.append(detail)
        if x['mode'] != 'editor':
            continue
        if changed: changes.append(detail)
        old, new = detail['beforeRank'], detail['afterRank']
        if old != 1 and new == 1: first_gains.append(detail)
        if old == 1 and new != 1: first_losses.append(detail)
        if old and (not new or new > old): rank_losses.append(detail)
    not_lower = all(b['metrics']['editor'][k] >= a['metrics']['editor'][k] for k in ('first', 'top5', 'recall255'))
    return dict(schemaVersion=1, suffixes=a['suffixes'], before=a['metrics'], after=b['metrics'],
                passedAggregateGate=not_lower,
                passedInvariants=not invariant_changes and a['passedContextEquivalence'] and b['passedContextEquivalence'],
                invariantChanges=invariant_changes, firstGains=first_gains, firstLosses=first_losses,
                rankLosses=rank_losses, changedEditorResults=changes,
                scope='Oracle-prefix reconstruction from attributed sentences; report all target losses; not natural typing accuracy')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before', type=Path)
    parser.add_argument('after', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.output.exists(): raise ValueError('Retain previous evidence')
    def read(path):
        with path.open(encoding='utf-8') as stream:
            return list(csv.DictReader((line for line in stream if not line.startswith('#')), delimiter='\t'))
    value = compare(read(args.before), read(args.after))
    value['inputHashes'] = {name: hashlib.sha256(p.read_bytes()).hexdigest() for name,p in [('before',args.before),('after',args.after)]}
    args.output.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:value[k] for k in ('suffixes','before','after','passedAggregateGate','passedInvariants')},ensure_ascii=False))


if __name__ == '__main__': main()
