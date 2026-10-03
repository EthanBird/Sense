"""Create train-only weak Pinyin supervision; never label this automatic corpus as gold."""
import argparse
from collections import Counter
import importlib.metadata
import json
from pathlib import Path
from prepare_p2c import read_json, read_lines, select_rows, write_json, write_lines
from audit_sentence_corpus import sha256


def valid_readings(text, syllables, allowed):
    if len(text) != len(syllables):
        return 'alignment'
    if any(unit not in allowed for unit in syllables):
        return 'unknown-syllable'
    if len(''.join(syllables)) > 96:
        return 'query-length'
    return None


def prepare(corpus, policy_path, syllables_path, output):
    from pypinyin import lazy_pinyin, Style
    policy = read_json(policy_path)
    if importlib.metadata.version('pypinyin') != policy['proposal']['version']:
        raise ValueError('Changed annotation package')
    manifest = read_json(corpus / 'corpus.json')
    if sha256(corpus / 'train.jsonl') != manifest['outputs']['train']['sha256']:
        raise ValueError('Changed training partition')
    if sha256(corpus / 'attribution.jsonl') != manifest['attribution']['sha256']:
        raise ValueError('Changed attribution')
    if output.exists():
        raise ValueError('Retain previous training preparation')
    data = read_lines(corpus / 'train.jsonl')
    selected = select_rows(data, 'train', policy['trainCount'], policy)
    allowed = set(syllables_path.read_text('utf-8').splitlines())
    accepted = []
    for row in selected:
        row['syllables'] = lazy_pinyin(row['text'], style=Style.NORMAL, v_to_u=False, tone_sandhi=False)
        row['exclusion'] = valid_readings(row['text'], row['syllables'], allowed)
        row['labelQuality'] = 'automatic weak supervision; polyphone and source-text errors possible'
        if row['exclusion'] is None:
            accepted.append(row)
    source_ids = {row['recordId'] for row in selected}
    attributions = [row for row in read_lines(corpus / 'attribution.jsonl') if row['recordId'] in source_ids]
    if {row['recordId'] for row in attributions} != source_ids:
        raise ValueError('Missing training attribution')
    output.mkdir(parents=True)
    write_lines(output / 'selected.jsonl', selected)
    write_lines(output / 'attribution.jsonl', attributions)
    target = output / 'train.tsv'
    target.write_text('# id\tquery\texpected\tstratum\tweakAutomaticSyllables\n' + ''.join(
        '\t'.join([row['id'], ''.join(row['syllables']), row['text'], row['stratum'], ' '.join(row['syllables'])]) + '\n'
        for row in accepted), encoding='utf-8')
    result = dict(schemaVersion=1, scope='train partition only; not human or agent-reviewed gold',
                  selected=len(selected), accepted=len(accepted),
                  exclusions=dict(Counter(row['exclusion'] for row in selected if row['exclusion'])),
                  trainSha256=sha256(target), selectedSha256=sha256(output / 'selected.jsonl'),
                  attributionSha256=sha256(output / 'attribution.jsonl'),
                  corpusManifestSha256=sha256(corpus / 'corpus.json'), corpusTrainSha256=sha256(corpus / 'train.jsonl'),
                  policySha256=sha256(policy_path), syllablesSha256=sha256(syllables_path),
                  scriptSha256=sha256(Path(__file__)), selectionScriptSha256=sha256(Path(__file__).with_name('prepare_p2c.py')),
                  annotationPackage=policy['proposal'], candidateOutputsUsed=False,
                  lmTrainingOverlap='Intentional: this partition also trained the current char LM; dev/test are separate corpus partitions.')
    write_json(output / 'manifest.json', result)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__)
    for name in ('corpus', 'policy', 'syllables', 'output'):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    prepare(args.corpus, args.policy, args.syllables, args.output)
