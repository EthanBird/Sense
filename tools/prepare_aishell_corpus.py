"""Prepare text-family-isolated AISHELL-NER transcripts for cross-domain Pinyin work."""
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re
import unicodedata
from audit_sentence_corpus import han, sha256, replay_texts
from prepare_sentence_corpus import near_duplicate_groups, write_jsonl

PARTITIONS = ['train', 'dev', 'test']
CLOSE = {')': '(', ']': '[', '>': '<'}


def strip_entities(text):
    """Validate markup and retain original code-point spans; no regex text guessing."""
    stack = []; output = []; entities = []
    for ch in text:
        if ch in '([<': stack.append((ch, len(output)))
        elif ch in CLOSE:
            if not stack or stack[-1][0] != CLOSE[ch]: raise ValueError('Unbalanced entity markup')
            opening, start = stack.pop()
            if start == len(output): raise ValueError('Empty entity')
            entities.append(dict(marker=opening, start=start, end=len(output), text=''.join(output[start:])))
        else: output.append(ch)
    if stack: raise ValueError('Unclosed entity markup')
    return ''.join(output), entities


def normalize(text, convert):
    plain, entities = strip_entities(text)
    normalized = convert(unicodedata.normalize('NFKC', plain)).strip()
    if not 2 <= len(normalized) <= 64: return None, entities, 'length'
    if not all(han(c) for c in normalized): return None, entities, 'non-Han'
    return normalized, entities, None


def partition_records(rows, protected, convert):
    by_text = defaultdict(list); counts = Counter()
    for row in rows:
        text, entities, exclusion = normalize(row['text'], convert)
        if exclusion:
            counts['excluded-' + exclusion] += 1; continue
        by_text[text].append(dict(**row, entities=entities))
    groups = near_duplicate_groups(set(by_text) | set(protected))
    blocked = {groups[text] for text in protected}
    priorities = {}
    for text, sources in by_text.items():
        group = groups[text]
        priorities[group] = max(priorities.get(group, 0), *(PARTITIONS.index(r['partition']) for r in sources))
    result = {p: [] for p in PARTITIONS}; attribution = []
    for text, sources in sorted(by_text.items()):
        group = groups[text]
        if group in blocked:
            counts['excluded-protected-family-records'] += len(sources); continue
        split = PARTITIONS[priorities[group]]
        identity = hashlib.sha256(('aishell-ner-v1:' + text).encode()).hexdigest()
        result[split].append(dict(id=identity, group=group, segments=[text], domain='aishell-read-speech'))
        for row in sources:
            counts['reassigned-original-rows'] += row['partition'] != split
            attribution.append(dict(recordId=identity, group=group, split=split, sourceId='aishell-ner-fb11b64',
                sentenceId=row['id'], originalPartition=row['partition'], originalLine=row['line'],
                originalText=row['text'], normalizedText=text, entitiesBeforeNormalization=row['entities'],
                contributor='AISHELL-NER authors; underlying AISHELL-1 providers', license='Apache-2.0',
                url=row['url'], transformation='Validate and remove entity markers; NFKC; OpenCC t2s; exact dedup and near-family holdout priority'))
    counts['unique-normalized-before-exclusion'] = len(by_text)
    counts['retained-families'] = len({r['group'] for values in result.values() for r in values})
    counts['retained-original-rows'] = len(attribution)
    return result, attribution, dict(counts)


def prepare(source, old_corpus, replays, output):
    import opencc
    if importlib.metadata.version('OpenCC') != '1.4.2': raise ValueError('Pin OpenCC 1.4.2')
    convert = opencc.OpenCC('t2s.json').convert
    manifest = json.loads((source / 'source-manifest.json').read_text())
    for name, pin in manifest['files'].items():
        if sha256(source / name) != pin['sha256']: raise ValueError('Changed source file')
    old = json.loads((old_corpus / 'corpus.json').read_text())
    protected = set()
    for part in PARTITIONS:
        path = old_corpus / f'{part}.jsonl'
        if sha256(path) != old['outputs'][part]['sha256']: raise ValueError('Changed protected corpus')
        for line in path.read_text('utf-8').splitlines(): protected.update(json.loads(line)['segments'])
    protected.update(''.join(c for c in convert(t) if han(c)) for t in replay_texts(replays))
    protected.discard('')
    rows = []; ids = set()
    for part in PARTITIONS:
        name = f'data/aishell_ner_transcript.{part}.txt'
        for index, line in enumerate((source / name).read_text('utf-8').splitlines(), 1):
            identity, text = line.split(maxsplit=1)
            if identity in ids or not re.fullmatch(r'BAC[0-9]+S[0-9]+W[0-9]+', identity): raise ValueError('Invalid source identity')
            ids.add(identity)
            rows.append(dict(id=identity, text=text, line=index, partition=part,
                url=f'https://github.com/{manifest["repository"]}/blob/{manifest["commit"]}/{name}#L{index}'))
    partitions, attribution, audit = partition_records(rows, protected, convert)
    output.mkdir(parents=True, exist_ok=False)
    pins = {}
    for part, values in partitions.items():
        path = output / f'{part}.jsonl'; write_jsonl(path, values)
        pins[part] = dict(fileName=path.name, sha256=sha256(path), records=len(values), segments=len(values),
                         characters=sum(len(r['segments'][0]) for r in values))
    write_jsonl(output / 'attribution.jsonl', attribution)
    resources = Path(opencc.__file__).parent / 'clib/share/opencc'
    result = dict(schemaVersion=1, source=manifest, sourceManifestSha256=sha256(source / 'source-manifest.json'),
        scriptSha256=sha256(Path(__file__)), groupingScriptSha256=sha256(Path(__file__).with_name('prepare_sentence_corpus.py')),
        grouping='Exact text dedup; edit <=1 families for 6..64 chars; test > dev > train priority across original splits',
        corpusTask='P2C text-domain evaluation, not original ASR benchmark split metrics',
        normalization=dict(opencc='1.4.2', config='t2s.json', unicodeVersion=unicodedata.unidata_version,
            resources={p.relative_to(resources).as_posix(): sha256(p) for p in sorted(resources.rglob('*')) if p.is_file()}),
        protectedCorpusManifestSha256=sha256(old_corpus / 'corpus.json'), protectedTexts=len(protected),
        protectedReplayHashes={p.name: sha256(p) for p in sorted(replays.glob('*.tsv'))},
        originalRows=len(rows), originalPartitions=dict(Counter(r['partition'] for r in rows)),
        audit=audit, outputs=pins, attribution=dict(fileName='attribution.jsonl',
            sha256=sha256(output / 'attribution.jsonl'), rows=len(attribution)),
        productionReady=False, annotation='Text is sourced; Pinyin still requires explicit proposed-label review')
    (output / 'corpus.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(dict(audit=audit, partitions=pins), ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__)
    for name in ['source', 'old_corpus', 'replays', 'output']: parser.add_argument(name, type=Path)
    args = parser.parse_args(); prepare(args.source, args.old_corpus, args.replays, args.output)
