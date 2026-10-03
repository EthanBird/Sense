"""One predeclared two-domain character LM; source attribution stays in pinned sidecars."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
from audit_sentence_corpus import sha256
from train_character_lm import read_partition, train_model, write_model, read_model, evaluate


def select_families(rows, budget, seed):
    if budget <= 0: raise ValueError('Positive character budget required')
    families = defaultdict(list)
    for r in rows: families[r['group']].append(r)
    selected = []; characters = 0
    for group in sorted(families, key=lambda g: hashlib.sha256((seed + '\t' + g).encode()).hexdigest()):
        values = sorted(families[group], key=lambda r: r['id'])
        selected.extend(values)
        characters += sum(len(t) for r in values for t in r['segments'])
        if characters >= budget: return selected
    raise ValueError('Not enough train characters')


def load_corpus(path):
    manifest = json.loads((path / 'corpus.json').read_text('utf-8'))
    if sha256(path / 'attribution.jsonl') != manifest['attribution']['sha256']:
        raise ValueError('Changed attribution')
    train = read_partition(path, manifest, 'train'); dev = read_partition(path, manifest, 'dev')
    if {r['group'] for r in train} & {r['group'] for r in dev}: raise ValueError('Leaked families')
    return manifest, train, dev


def fit(old, new, policy_path, baseline, output):
    policy = json.loads(policy_path.read_text('utf-8'))
    if policy['trials'] != 1: raise ValueError('Expected one predeclared trial')
    old_m, old_train, old_dev = load_corpus(old)
    new_m, new_train, new_dev = load_corpus(new)
    if new_m['protectedCorpusManifestSha256'] != sha256(old / 'corpus.json'):
        raise ValueError('New source not isolated from this old corpus')
    old_texts = {t for r in old_train + old_dev for t in r['segments']}
    if old_texts & {t for r in new_train + new_dev for t in r['segments']}:
        raise ValueError('Cross-domain exact overlap')
    budget = sum(len(t) for r in old_train for t in r['segments'])
    selected = select_families(new_train, budget, policy['seed'])
    output.mkdir(parents=True, exist_ok=False)
    selection = output / 'selected-new-train.jsonl'
    with selection.open('w', encoding='utf-8') as f:
        for row in selected: f.write(json.dumps(row, ensure_ascii=False, sort_keys=True)+'\n')
    def count(rows):
        return dict(records=len(rows), segments=sum(len(r['segments']) for r in rows),
                    characters=sum(len(t) for r in rows for t in r['segments']), families=len({r['group'] for r in rows}))
    pins = dict(policy=sha256(policy_path), trainer=sha256(Path(__file__)),
        baseTrainer=sha256(Path(__file__).with_name('train_character_lm.py')),
        baselineModel=sha256(baseline), oldCorpus=sha256(old / 'corpus.json'), newCorpus=sha256(new / 'corpus.json'),
        oldAttribution=old_m['attribution'], newAttribution=new_m['attribution'],
        oldTrain=old_m['outputs']['train'], newTrain=new_m['outputs']['train'],
        selectedNewTrain=sha256(selection))
    lock = dict(schemaVersion=1, policy=policy, pins=pins, old=count(old_train), new=count(selected),
        attributionNote='Selected record ids resolve into the pinned original sidecars; sources not merged or re-licensed',
        testReadForTraining=False)
    (output / 'training-lock.json').write_text(json.dumps(lock, indent=2)+'\n', encoding='utf-8')
    model = train_model((t for r in old_train + selected for t in r['segments']), **policy['training'])
    model_path = output / 'balanced.scng'; write_model(model, model_path)
    if model_path.stat().st_size > policy['maxModelBytes']: raise ValueError('Model exceeds cap')
    new_model = read_model(model_path.read_bytes()); old_model = read_model(baseline.read_bytes())
    report = dict(schemaVersion=1, trainingLockSha256=sha256(output / 'training-lock.json'),
        modelSha256=sha256(model_path), modelBytes=model_path.stat().st_size,
        counts=dict(unigrams=len(new_model.unigrams), bigrams=len(new_model.bigrams), trigrams=len(new_model.trigrams)),
        development={name: dict(before=evaluate(old_model, rows, 3), after=evaluate(new_model, rows, 3))
            for name, rows in [('tatoeba', old_dev), ('aishell', new_dev)]},
        testEvaluated=False, productionChanged=False, note='Likelihood includes EOS and is not P2C accuracy')
    (output / 'training-report.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(dict(modelSha256=report['modelSha256'], modelBytes=report['modelBytes'], old=lock['old'], new=lock['new'],
        development={n: {s: v['all']['perplexity'] for s, v in d.items()} for n, d in report['development'].items()})))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__)
    for n in ['old', 'new', 'policy_path', 'baseline', 'output']: parser.add_argument(n, type=Path)
    fit(**vars(parser.parse_args()))
