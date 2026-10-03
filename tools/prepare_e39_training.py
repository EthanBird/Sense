"""Freeze source-isolated weak training rows before candidate/teacher outputs."""
import argparse
import json
from pathlib import Path
import subprocess
from fetch_e37_model import digest
from prepare_p2c import overlaps
from run_e37_rescoring import write_new


def prepare(root, out):
    policy_path = root / 'benchmarks/corpus/e39-student-policy.json'
    policy = json.loads(policy_path.read_text('utf-8'))
    source = root / policy['training']['source']
    manifest = json.loads((source / 'manifest.json').read_text('utf-8'))
    for name, key in [('train.tsv', 'trainSha256'), ('selected.jsonl', 'selectedSha256'),
                      ('attribution.jsonl', 'attributionSha256')]:
        if digest(source / name) != manifest[key]:
            raise ValueError('Training provenance changed: ' + name)
    corpus = root / '.artifacts/input-quality/lm-corpus-v1'
    if digest(corpus / 'train.jsonl') != manifest['corpusTrainSha256']:
        raise ValueError('Training corpus changed')
    if digest(corpus / 'corpus.json') != manifest['corpusManifestSha256']:
        raise ValueError('Corpus manifest changed')
    records = {split: [json.loads(l) for l in (corpus / f'{split}.jsonl').read_text('utf-8').splitlines()]
               for split in ['train', 'dev', 'test']}
    groups = {split: {r['group'] for r in rows} for split, rows in records.items()}
    if groups['train'] & (groups['dev'] | groups['test']) or groups['dev'] & groups['test']:
        raise ValueError('Corpus family leakage')
    heldout = []
    for name in policy['evaluationInputs']:
        heldout += [l.split('\t')[2] for l in (root / name).read_text('utf-8').splitlines()
                    if l and not l.startswith('#')]
    selected = [json.loads(l) for l in (source / 'selected.jsonl').read_text('utf-8').splitlines()]
    inputs = {l.split('\t')[0]: l for l in (source / 'train.tsv').read_text('utf-8').splitlines()
              if l and not l.startswith('#')}
    if len(selected) != policy['training']['sentencesBeforeExclusions'] or len(inputs) != len(selected):
        raise ValueError('Incomplete weak source')
    kept = []; excluded = []
    train_segments = {(r['group'], s) for r in records['train'] for s in r['segments']}
    for row in selected:
        if row['partition'] != 'train' or (row['group'], row['text']) not in train_segments:
            raise ValueError('Source text missing from train family')
        fields = inputs[row['id']].split('\t')
        if fields[2] != row['text'] or fields[4].split() != row['syllables']:
            raise ValueError('Annotation drift')
        reason = overlaps(row['text'], heldout)
        # Symmetric substring exclusion (the old helper checks one direction).
        reason = reason or ('contains-heldout' if any(t in row['text'] for t in heldout) else None)
        if reason:
            excluded.append(dict(id=row['id'], text=row['text'], reason=reason))
        else:
            kept.append(row)
    out.mkdir(parents=True, exist_ok=True)
    with (out / 'train.tsv').open('x', encoding='utf-8', newline='\n') as f:
        f.write('# E39 source-isolated automatic weak supervision, not reviewed gold\n')
        for row in kept:
            f.write(inputs[row['id']] + '\n')
    keep_groups = {r['group'] for r in kept}
    with (out / 'attribution.jsonl').open('x', encoding='utf-8', newline='\n') as f:
        for line in (source / 'attribution.jsonl').read_text('utf-8').splitlines():
            row = json.loads(line)
            if row['group'] in keep_groups:
                if row['split'] != 'train' or row['license'] != 'CC-BY-2.0-FR':
                    raise ValueError('Unexpected attribution')
                f.write(line + '\n')
    write_new(out / 'selected.json', kept)
    write_new(out / 'pre-export-lock.json', dict(
        policySha256=digest(policy_path), checkoutCommit=subprocess.check_output(
            ['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        sourceManifestSha256=digest(source / 'manifest.json'),
        sourceFiles={name: digest(root / name) for name in [
            'tools/prepare_e39_training.py', 'tools/prepare_p2c.py',
            'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/M19ScoreFeatureBenchmark.kt']},
        corpus={s: digest(corpus / f'{s}.jsonl') for s in records},
        heldout={name: digest(root / name) for name in policy['evaluationInputs']},
        selected=len(selected), kept=len(kept), excluded=excluded,
        trainSha256=digest(out / 'train.tsv'), attributionSha256=digest(out / 'attribution.jsonl'),
        corpusFamiliesDisjoint=True, candidateOutputsUsed=False, weakLabels=True))
    print(json.dumps(dict(kept=len(kept), excluded=excluded), ensure_ascii=False), flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(__doc__)
    p.add_argument('root', type=Path); p.add_argument('out', type=Path)
    a = p.parse_args(); prepare(a.root.resolve(), a.out.resolve())
