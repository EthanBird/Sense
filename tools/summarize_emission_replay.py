"""Audit full progressive equality and model-call counts, not timing or accuracy gains."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re

FIELDS = ['id', 'scope', 'query', 'context', 'resultSha256', 'probabilityCalls', 'membershipCalls']


def load(path):
    lines = path.read_text('utf-8').splitlines()
    if not lines or not re.fullmatch(r'# inputSha256=[0-9a-f]{64}', lines[0]):
        raise ValueError('Input identity is required')
    reader = csv.DictReader(lines[1:], delimiter='\t')
    if reader.fieldnames != FIELDS:
        raise ValueError('Unexpected full-result schema')
    rows = list(reader)
    if not rows:
        raise ValueError('Empty replay')
    identities = set()
    for row in rows:
        if set(row) != set(FIELDS) or any(value is None for value in row.values()):
            raise ValueError('Truncated or extra columns')
        if not row['id'] or not row['scope'] or not re.fullmatch(r"[a-z']{1,192}", row['query']):
            raise ValueError('Invalid row identity/query')
        key = row['scope'], row['id']
        if key in identities:
            raise ValueError('Duplicate replay identity')
        identities.add(key)
        if not re.fullmatch(r'[0-9a-f]{64}', row['resultSha256']):
            raise ValueError('Missing complete-result fingerprint')
        for field in FIELDS[-2:]:
            if not re.fullmatch(r'0|[1-9][0-9]*', row[field]):
                raise ValueError('Invalid call count')
            row[field] = int(row[field])
    return lines[0].split('=', 1)[1], rows


def compare(before, after, input_path):
    digest = hashlib.sha256(input_path.read_bytes()).hexdigest()
    ah, aa = load(before)
    bh, bb = load(after)
    inputs = [line.split('\t') for line in input_path.read_text('utf-8').splitlines()
              if line and not line.startswith('#')]
    if ah != digest or bh != digest or len(aa) != len(bb) or len(aa) != len(inputs):
        raise ValueError('Replay source/count mismatch')
    groups = {}
    differences = []
    for source, a, b in zip(inputs, aa, bb):
        if source != [a[k] for k in FIELDS[:4]] or source != [b[k] for k in FIELDS[:4]]:
            raise ValueError('Query order or context differs from input')
        if a['resultSha256'] != b['resultSha256']:
            differences.append(dict(scope=a['scope'], id=a['id'], query=a['query']))
        group = groups.setdefault(a['scope'], dict(rows=0, beforeCalls=0, afterCalls=0,
            beforeMembership=0, afterMembership=0))
        group['rows'] += 1
        group['beforeCalls'] += a['probabilityCalls']
        group['afterCalls'] += b['probabilityCalls']
        group['beforeMembership'] += a['membershipCalls']
        group['afterMembership'] += b['membershipCalls']
    return dict(rows=len(aa), differences=differences, allResultsEqual=not differences, groups=groups,
        inputSha256=digest, beforeSha256=hashlib.sha256(before.read_bytes()).hexdigest(),
        afterSha256=hashlib.sha256(after.read_bytes()).hexdigest(),
        scope='Deterministic model-call accounting on labeled host queries; no timing or natural accuracy inference')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__)
    for name in ['before', 'after', 'input', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    result = compare(args.before, args.after, args.input)
    with args.output.open('x', encoding='utf-8', newline='\n') as writer:
        writer.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
