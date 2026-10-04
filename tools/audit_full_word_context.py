"""Independent count replay over saved background segments, not a rank metric."""
import argparse
from bisect import bisect_right
from collections import Counter, defaultdict
import json
import math
from pathlib import Path

from fetch_e37_model import digest
from full_word_context import AssociationFeature
from prepare_p2c import one_edit
from run_e37_rescoring import write_new
from word_context_ranker import pair_key


def audit(root, out):
    script_sha256 = digest(Path(__file__))
    read = lambda p: json.loads(p.read_text('utf-8'))
    model = read(out / 'association-table.json'); AssociationFeature(model)
    report = read(out / 'background-report.json'); selected = read(out / 'background-selection.json')
    corpus = root / '.artifacts/input-quality/lm-corpus-v1'
    if digest(corpus / 'corpus.json') != report['corpusManifestSha256']:
        raise ValueError('Changed corpus identity')
    for split, sha in report['partitions'].items():
        if digest(corpus / (split + '.jsonl')) != sha:
            raise ValueError('Changed corpus partition')
    if digest(corpus / 'attribution.jsonl') != report['attributionSha256']:
        raise ValueError('Changed source attribution')
    train = [json.loads(line) for line in (corpus / 'train.jsonl').read_text('utf-8').splitlines()]
    ids = set(selected['recordIds']); calibration = set(selected['calibrationFamilies'])
    retained = [r for r in train if r['id'] in ids]
    if (len(ids) != len(retained) or {r['group'] for r in retained} & calibration
            or {r['group'] for r in retained} & selected['familyExclusions'].keys()
            or set(r['group'] for r in train) != set(r['group'] for r in retained) | selected['familyExclusions'].keys()):
        raise ValueError('Background selection/family accounting disagrees')
    protected = set()
    for name, sha in report['protected'].items():
        path = root / name
        if digest(path) != sha:
            raise ValueError('Changed isolation target')
        protected.update(line.split('\t')[2] for line in path.read_text('utf-8').splitlines()
                         if line and not line.startswith('#'))
    by_length = defaultdict(list)
    for text in protected: by_length[len(text)].append(text)
    unique = sorted({text for r in retained for text in r['segments']})
    for i, text in enumerate(unique):
        if any(text in other or other in text for other in protected):
            raise ValueError('Background substring overlap')
        if any(one_edit(text, other) for size in [len(text)-1, len(text), len(text)+1]
               for other in by_length[size]):
            raise ValueError('Background one-edit overlap')
        if (i+1) % 20000 == 0:
            print(f'Independently checked {i+1} unique background texts', flush=True)
    expected = Counter((r['id'], r['group'], text) for r in retained for text in r['segments'])
    actual = Counter(); pairs = Counter(); totals = Counter(); unigrams = Counter()
    wanted = {r[0] for r in model['entries']}; families = defaultdict(set)
    for line in (out / 'background-segments.jsonl').read_text('utf-8').splitlines():
        r = json.loads(line); text, words = r['text'], r['words']
        actual[r['recordId'], r['family'], text] += 1
        if not words or any(not w or len(w) > 8 for w in words) or ''.join(words) != text:
            raise ValueError('Invalid saved segmentation')
        for word in words: unigrams['W2', word] += 1
        def pair(kind, context, word):
            key = pair_key(kind, context, word)
            totals[kind, context] += 1
            if key in wanted:
                pairs[key] += 1; families[key].add(r['family'])
        for i in range(1, len(words)): pair('W2', words[i-1], words[i])
        ends = []; offset = 0
        for word in words: offset += len(word); ends.append(offset)
        for cut in range(1, len(text)):
            word = text[cut:ends[bisect_right(ends, cut)]]
            unigrams['CW', word] += 1
            pair('CW', text[cut-1:cut], word)
            if cut >= 2: pair('CW', text[cut-2:cut], word)
    if actual != expected:
        raise ValueError('Saved background omits or duplicates a source segment')
    for kind in ['W2', 'CW']:
        total = sum(n for (k, w), n in unigrams.items() if k == kind)
        size = sum(k == kind for k, w in unigrams)
        if model['namespaces'][kind] != dict(unigramTotal=total, vocabulary=size):
            raise ValueError('Unigram counts differ from independent replay')
    for key, count, total, unigram, *_ in model['entries']:
        kind, left, right = json.loads(key)
        if (count != pairs[key] or total != totals[kind, left]
                or unigram != unigrams[kind, right] or len(families[key]) < 2):
            raise ValueError('Retained pair evidence differs from independent replay')
    attributed = set()
    for line in (out / 'background-attribution.jsonl').read_text('utf-8').splitlines():
        r = json.loads(line)
        if r['license'] != 'CC-BY-2.0-FR' or r['split'] != 'train' or r['recordId'] not in ids:
            raise ValueError('Attribution outside background selection')
        attributed.add(r['recordId'])
    if attributed != ids:
        raise ValueError('Incomplete background attribution')
    return dict(backgroundRecords=len(ids), sourceSegments=sum(actual.values()), uniqueBackgroundTexts=len(unique),
        independentlyRecountedPairs=len(wanted), everyRetainedPairHasTwoFamilies=True,
        backgroundCalibrationDisjoint=True, everyBackgroundTextCheckedForSubstringAndOneEditOverlap=True,
        completeAttribution=True, tableSha256=digest(out / 'association-table.json'),
        backgroundReportSha256=digest(out / 'background-report.json'), auditScriptSha256=script_sha256)


if __name__ == '__main__':
    p = argparse.ArgumentParser(__doc__); p.add_argument('root', type=Path); p.add_argument('output', type=Path)
    p.add_argument('--report', default='independent-count-audit.json')
    a = p.parse_args(); result = audit(a.root.resolve(), a.output.resolve())
    if Path(a.report).name != a.report or not a.report.endswith('.json'):
        raise ValueError('Expected a report filename, not a path')
    write_new(a.output / a.report, result)
    print(json.dumps(result), flush=True)
