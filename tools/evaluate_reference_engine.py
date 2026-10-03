"""Pair an external full-sentence engine with known Sense replay rows, not a blind test."""
import argparse
import hashlib
import json
import math
from pathlib import Path
from train_candidate_ranker import distance


def load_reference(lines):
    header = None; rows = []; summary = None; ids = set()
    for line in lines:
        entry = json.loads(line)
        if summary is not None:
            raise ValueError('Data after summary')
        if header is None:
            if (entry.get('type') != 'header' or entry.get('schemaVersion') != 1 or
                    entry.get('mode') != 'whole-sentence-incremental' or entry.get('nbest') != 10 or
                    entry.get('engine') != 'libime-1.0.11-ubuntu-jammy' or entry.get('learning') is not False or
                    entry.get('fuzzy') != ['Inner']):
                raise ValueError('Unexpected engine configuration')
            header = entry
        elif entry.get('type') == 'summary':
            summary = entry
        elif entry.get('type') == 'query':
            identity = entry['id']; query = entry['query']
            if not identity or identity in ids or not query or entry['typingMicros'] < 0:
                raise ValueError('Duplicate or invalid query')
            ids.add(identity)
            full = []; seen = set()
            for c in entry['candidates']:
                if (not c['text'] or not math.isfinite(c['score']) or type(c['consumed']) is not int or
                        not 0 < c['consumed'] <= len(query)):
                    raise ValueError('Malformed candidate')
                if c['consumed'] == len(query) and c['text'] not in seen:
                    full.append(c['text']); seen.add(c['text'])
            if not full:
                raise ValueError('Missing full-coverage candidate')
            rows.append(dict(id=identity, query=query, fullTop10=full[:10],
                displayFirst=entry['candidates'][0]['text'], sentence=entry['sentence'],
                typingMicros=entry['typingMicros']))
        else:
            raise ValueError('Unexpected record type')
    if not header or not summary or summary.get('rows') != len(rows) or not rows:
        raise ValueError('Missing or inconsistent complete workload summary')
    return header, rows


def compare(baseline, reference, tsv):
    gold = {}
    for line in tsv.splitlines():
        if not line or line.startswith('#'): continue
        identity, query, expected, stratum, units = line.split('\t')
        if identity in gold or ''.join(units.split()) != query or len(units.split()) != len(expected):
            raise ValueError('Invalid or duplicate aligned gold')
        gold[identity] = dict(query=query, expected=expected, stratum=stratum)
    old = {r['id']: r for r in baseline if r['mode'] == 'empty' and r['cut'] == 0}
    if len(old) != sum(r['mode'] == 'empty' and r['cut'] == 0 for r in baseline):
        raise ValueError('Duplicate baseline states')
    new = {r['id']: r for r in reference}
    if len(new) != len(reference) or not gold or old.keys() != new.keys() or old.keys() != gold.keys():
        raise ValueError('Unpaired or partial query identities')
    results = []
    for identity, g in gold.items():
        b = old[identity]; r = new[identity]
        if b['expected'] != g['expected'] or b['query'] != r['query'] or r['query'] != g['query'] or b['context']:
            raise ValueError('Changed query, context, or expected text')
        target = g['expected']; candidates = r['fullTop10']
        rank = next((i + 1 for i, text in enumerate(candidates) if text == target), 0)
        results.append(dict(id=identity, **g, senseRank=b['rank'], referenceRank=rank,
            senseFirst=b['first'], referenceFirst=candidates[0], referenceTop10=candidates,
            senseErrors=distance(b['first'], target), referenceErrors=distance(candidates[0], target),
            displayFirst=r['displayFirst'], sentence=r['sentence'], typingMicros=r['typingMicros']))
    def metrics(prefix):
        return dict(count=len(results), top1=sum(r[prefix+'Rank'] == 1 for r in results),
            top5=sum(0 < r[prefix+'Rank'] <= 5 for r in results),
            top10=sum(0 < r[prefix+'Rank'] <= 10 for r in results),
            mrrAt10=sum(1/r[prefix+'Rank'] if 0 < r[prefix+'Rank'] <= 10 else 0 for r in results)/len(results),
            cer=sum(r[prefix+'Errors'] for r in results)/sum(len(r['expected']) for r in results))
    return dict(schemaVersion=1,
        scope='Known E13 whole-sentence diagnostic; upstream model training overlap unknown; no Android or fresh-test claim',
        sense=metrics('sense'), reference=metrics('reference'),
        referenceFirstGains=[r for r in results if r['senseRank'] != 1 and r['referenceRank'] == 1],
        referenceFirstLosses=[r for r in results if r['senseRank'] == 1 and r['referenceRank'] != 1],
        referenceDisplayDiffersFromFull=sum(r['displayFirst'] != r['referenceFirst'] for r in results), rows=results,
        productionChanged=False, releaseReady=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('baseline', type=Path); parser.add_argument('baseline_key', choices=['before', 'developmentBaseline'])
    parser.add_argument('reference', type=Path); parser.add_argument('input', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args()
    header, rows = load_reference(args.reference.read_text('utf-8-sig').splitlines())
    report = compare(json.loads(args.baseline.read_text('utf-8'))[args.baseline_key]['rows'], rows,
                     args.input.read_text('utf-8'))
    report['referenceConfiguration'] = header
    report['inputPins'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in [args.baseline, args.reference, args.input, Path(__file__)]}
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(report, output, ensure_ascii=False, indent=2, allow_nan=False); output.write('\n')
    print(json.dumps({k: report[k] for k in ['sense', 'reference', 'referenceDisplayDiffersFromFull']}, ensure_ascii=False))
