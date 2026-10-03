"""Report paired prefix diagnostics and keep every expected-rank loss visible."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

MODES = ('empty', 'editor', 'boundary', 'accepted')


def summarize(rows):
    groups = {}
    for row in rows:
        key = (row['id'], row['cut'])
        group = groups.setdefault(key, {})
        if row['mode'] not in MODES or row['mode'] in group:
            raise ValueError('Unexpected or duplicate paired mode')
        group[row['mode']] = row
    if not groups:
        raise ValueError('Empty replay')
    scores = {m: {'count': 0, 'first': 0, 'top5': 0, 'recall255': 0} for m in MODES}
    changed, first_losses, rank_losses, editor_mismatch, boundary_mismatch = [], [], [], [], []
    for key, group in groups.items():
        if set(group) != set(MODES):
            raise ValueError('Missing paired mode')
        original = group['empty']
        for mode, row in group.items():
            if any(row[k] != original[k] for k in ('stratum', 'context', 'query', 'expected')):
                raise ValueError('Paired labels differ')
            rank = int(row['rank'])
            if not 0 <= rank <= 255 or (rank == 1) != (row['top1'] == row['expected']):
                raise ValueError('Invalid rank or first label')
            score = scores[mode]
            score['count'] += 1
            score['first'] += rank == 1
            score['top5'] += 0 < rank <= 5
            score['recall255'] += rank > 0
        contextual = group['editor']
        detail = {k: original[k] for k in ('id', 'cut', 'stratum', 'context', 'query', 'expected')}
        detail.update(beforeRank=int(original['rank']), afterRank=int(contextual['rank']),
                      beforeFirst=original['top1'], afterFirst=contextual['top1'])
        if original['resultSha256'] != contextual['resultSha256']:
            changed.append(detail)
        if detail['beforeRank'] == 1 and detail['afterRank'] != 1:
            first_losses.append(detail)
        if detail['beforeRank'] and (not detail['afterRank'] or detail['afterRank'] > detail['beforeRank']):
            rank_losses.append(detail)
        if contextual['resultSha256'] != group['accepted']['resultSha256']:
            editor_mismatch.append(detail)
        if original['resultSha256'] != group['boundary']['resultSha256']:
            boundary_mismatch.append(detail)
    return dict(schemaVersion=1, suffixes=len(groups), metrics=scores,
                contextChangedResults=changed, contextFirstLosses=first_losses, contextRankLosses=rank_losses,
                acceptedContextMismatches=editor_mismatch, punctuationBoundaryMismatches=boundary_mismatch,
                passedContextEquivalence=not editor_mismatch and not boundary_mismatch,
                interpretation='Existing reviewed P2C sentences reused with oracle-correct prefixes; not independent or natural user accuracy; no parameters selected on these outputs.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Retain existing report')
    with args.input.open(encoding='utf-8') as stream:
        rows = list(csv.DictReader((line for line in stream if not line.startswith('#')), delimiter='\t'))
    value = summarize(rows)
    value['replaySha256'] = hashlib.sha256(args.input.read_bytes()).hexdigest()
    args.output.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: value[k] for k in ('suffixes', 'metrics', 'passedContextEquivalence')}))


if __name__ == '__main__':
    main()
