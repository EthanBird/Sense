"""Full train preparation, never count heldout or calibration source families."""
from collections import Counter, defaultdict
import json

from fetch_e37_model import digest
from full_word_context import Counts
from layered_lexicon import project_base
from prepare_p2c import one_edit, read_lines
from project_pinyin_base import BASE_SHA256
from run_e37_rescoring import write_new
from train_association_model import Segmenter, dictionary_words


class HeldoutFilter:
    """Indexed equivalent of symmetric substring + one-edit overlap, not a fuzzy label repair."""
    def __init__(self, values):
        self.values = set(values)
        if not self.values or any(not isinstance(x, str) or not x for x in self.values):
            raise ValueError('Empty heldout isolation target')
        self.lengths = sorted({len(x) for x in self.values})
        self.substrings = {text[a:b] for text in self.values for a in range(len(text))
                           for b in range(a+1, len(text)+1)}
        self.deleted = defaultdict(set)
        for text in self.values:
            for i in range(len(text)):
                self.deleted[text[:i] + text[i+1:]].add(text)

    def reason(self, text):
        if text in self.substrings:
            return 'substring'
        for size in self.lengths:
            if size > len(text): break
            if any(text[start:start+size] in self.values for start in range(len(text)-size+1)):
                return 'contains-heldout'
        if text in self.deleted:
            return 'near'
        for i in range(len(text)):
            key = text[:i] + text[i+1:]
            if key in self.values or any(one_edit(text, other) for other in self.deleted.get(key, ())):
                return 'near'
        return None


def prepare(root, output, calibration_selected):
    import hashlib
    corpus = root / '.artifacts/input-quality/lm-corpus-v1'
    manifest = json.loads((corpus / 'corpus.json').read_text('utf-8'))
    rows = {}
    for split in ('train', 'dev', 'test'):
        if digest(corpus / (split + '.jsonl')) != manifest['outputs'][split]['sha256']:
            raise ValueError('Corpus partition changed')
        rows[split] = read_lines(corpus / (split + '.jsonl'))
    if digest(corpus / 'attribution.jsonl') != manifest['attribution']['sha256']:
        raise ValueError('Corpus attribution changed')
    families = {split: {r['group'] for r in records} for split, records in rows.items()}
    if families['train'] & (families['dev'] | families['test']) or families['dev'] & families['test']:
        raise ValueError('Background/heldout source family overlap')
    calibration = {r['group'] for r in calibration_selected if not r['exclusion']}
    if not calibration <= families['train']:
        raise ValueError('Calibration source is not train')
    protected = sorted(set((root / 'benchmarks/corpus').rglob('dev.tsv')) |
                       set((root / 'benchmarks/corpus').rglob('test.tsv')))
    protected.append(root / '.artifacts/input-quality/e28/diagnostic.tsv')
    target = {line.split('\t')[2] for p in protected for line in p.read_text('utf-8').splitlines()
              if line and not line.startswith('#')}
    blocked = HeldoutFilter(target)
    reasons = {}; by_family = defaultdict(list)
    for row in rows['train']:
        by_family[row['group']].append(row)
    for family, members in by_family.items():
        if family in calibration:
            reasons[family] = 'calibration-family'
        else:
            for row in members:
                reason = next((why for text in row['segments'] if (why := blocked.reason(text))), None)
                if reason:
                    reasons[family] = reason; break
    selected = [r for r in rows['train'] if r['group'] not in reasons]
    if not selected:
        raise ValueError('No isolated background text')
    actual_families = {r['group'] for r in selected}
    if actual_families & calibration:
        raise ValueError('Calibration family entered counts')
    write_new(output / 'background-selection.json', dict(recordIds=[r['id'] for r in selected],
        familyExclusions=reasons, calibrationFamilies=sorted(calibration)))
    source_ids = {r['id'] for r in selected}; seen = set()
    with (output / 'background-attribution.jsonl').open('x', encoding='utf-8', newline='\n') as out:
        for row in read_lines(corpus / 'attribution.jsonl'):
            if row['recordId'] in source_ids:
                if row['split'] != 'train' or row['license'] != 'CC-BY-2.0-FR':
                    raise ValueError('Unexpected background attribution')
                seen.add(row['recordId']); out.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + '\n')
    if seen != source_ids:
        raise ValueError('Background attribution missing')
    lexicon = root / 'ime-service/src/main/assets/pinyin_lexicon.bin'
    base = project_base(lexicon.read_bytes())
    if hashlib.sha256(base).hexdigest() != BASE_SHA256:
        raise ValueError('F1 base projection changed')
    segmenter = Segmenter(dictionary_words(base)); counts = Counts()
    segments = characters = 0
    with (output / 'background-segments.jsonl').open('x', encoding='utf-8', newline='\n') as out:
        for index, row in enumerate(selected):
            for text in row['segments']:
                units = segmenter.units(text)
                counts.add(row['group'], text, units); segments += 1; characters += len(text)
                out.write(json.dumps(dict(recordId=row['id'], family=row['group'], text=text, words=units), ensure_ascii=False) + '\n')
            if (index + 1) % 10000 == 0:
                print(f'Counted {index+1} background records / {segments} segments', flush=True)
    model = counts.export()
    report = dict(corpusManifestSha256=digest(corpus / 'corpus.json'), source=manifest['source'],
        partitions={split: digest(corpus / (split + '.jsonl')) for split in rows},
        attributionSha256=digest(corpus / 'attribution.jsonl'), lexiconSha256=digest(lexicon),
        baseProjectionSha256=BASE_SHA256, protected={p.relative_to(root).as_posix(): digest(p) for p in protected},
        inputTrainRecords=len(rows['train']), backgroundRecords=len(selected), backgroundFamilies=len(actual_families),
        calibrationFamilies=len(calibration), excludedFamilies=dict(Counter(reasons.values())),
        backgroundSegments=segments, backgroundCharacters=characters, rawPairs=len(counts.pairs),
        retainedPairs=len(model['entries']), backgroundCalibrationDisjoint=True, heldoutFamiliesDisjoint=True,
        outputs={p.name: digest(p) for p in output.iterdir() if p.name.startswith('background-')})
    return model, report
