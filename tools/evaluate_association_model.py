"""Evaluate a frozen SNWP model without retraining or choosing configurations on test."""
import argparse
import json
import math
from pathlib import Path
import struct

from audit_sentence_corpus import han, sha256
from train_association_model import Segmenter, CharacterPredictor, baseline, context_key, dictionary_words, evaluate
from train_character_lm import read_model, read_partition


class BinaryPredictor:
    def __init__(self, data):
        if len(data) < 10 or len(data) > 8 * 1024 * 1024 or data[:6] != b'SNWP\x00\x01':
            raise ValueError('Invalid SNWP header or budget')
        count = struct.unpack_from('>I', data, 6)[0]
        if not 0 < count <= (len(data)-10)//9:
            raise ValueError('Invalid row count')
        self.rows, offset, previous = {}, 10, 0
        def take(n):
            nonlocal offset
            if offset+n > len(data): raise ValueError('Truncated SNWP')
            result=data[offset:offset+n]; offset+=n; return result
        for _ in range(count):
            key, n = struct.unpack('>QB', take(9))
            value, chars = key, []
            while value:
                cp=value & ((1<<21)-1); value >>= 21
                if cp > 0x10ffff or not han(chr(cp)): raise ValueError('Non-Han context')
                chars.append(chr(cp))
            if not previous < key < 2**63 or not 1 <= len(chars) <= 3 or n > 8:
                raise ValueError('Invalid context ordering or successor count')
            previous=key; values=[]
            for _ in range(n):
                size=take(1)[0]
                if not 1 <= size <= 32: raise ValueError('Invalid word bytes')
                word=take(size).decode('utf-8', errors='strict')
                score=struct.unpack('>f',take(4))[0]
                if not 1 <= len(word) <= 8 or not all(map(han,word)) or not math.isfinite(score) or score > 0:
                    raise ValueError('Invalid word or log probability')
                if any(w==word for w,_ in values) or (values and score > values[-1][1]):
                    raise ValueError('Invalid successor ordering')
                values.append((word,score))
            self.rows[key]=values
        if offset != len(data): raise ValueError('Trailing SNWP bytes')

    def suggest(self, context):
        # Stop at punctuation/Latin; an explicit empty row suppresses backoff.
        chars=[]
        for char in reversed(context):
            if not han(char) or len(chars)==3: break
            chars.insert(0,char)
        for start in range(len(chars)):
            key=context_key(''.join(chars[start:]))
            if key in self.rows: return self.rows[key]
        return []


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('corpus','assets','experiment','freeze','output'): parser.add_argument(name,type=Path)
    args=parser.parse_args()
    if args.output.exists(): raise ValueError('Preserve prior evidence; choose a new output directory')
    frozen=json.loads(args.freeze.read_text('utf-8')); name=frozen['selectedModel']
    model=args.experiment/f'{name}.snwp'
    if sha256(model)!=frozen['model']['sha256']: raise ValueError('Frozen model changed')
    for file,digest in frozen['inputs']['assets'].items():
        if sha256(args.assets/file)!=digest: raise ValueError('Comparator or dictionary changed')
    manifest=json.loads((args.corpus/'corpus.json').read_text('utf-8'))
    if sha256(args.corpus/'corpus.json')!=frozen['inputs']['training']['manifestSha256']:
        raise ValueError('Corpus changed')
    predictor=BinaryPredictor(model.read_bytes())
    dev=json.loads((args.experiment/'dev.json').read_text('utf-8'))
    mismatches=sum([w for w,_ in predictor.suggest(row['context'])] != row['predictions'][name] for row in dev['observations'])
    if mismatches: raise ValueError(f'Export differs from development: {mismatches}')
    train=read_partition(args.corpus,manifest,'train'); development=read_partition(args.corpus,manifest,'dev')
    test=read_partition(args.corpus,manifest,'test')
    if ({r['group'] for r in train}|{r['group'] for r in development}) & {r['group'] for r in test}:
        raise ValueError('Test source-family leakage')
    args.output.mkdir(parents=True)
    segmenter=Segmenter(dictionary_words((args.assets/'pinyin_lexicon.bin').read_bytes()))
    legacy=baseline((args.assets/'pinyin_bigrams.bin').read_bytes())
    char=CharacterPredictor(read_model((args.assets/'pinyin_character_lm.scng').read_bytes()))
    summary=evaluate(test,segmenter,{'legacy':lambda ctx:legacy.get(ctx[-1],[]),'character_lm':char.suggest,'selected':predictor.suggest},args.output/'test.json')
    result={'schemaVersion':1,'freezeSha256':sha256(args.freeze),'modelSha256':sha256(model),'testPartition':manifest['outputs']['test'],
            'developmentExportParity':{'observations':len(dev['observations']),'mismatches':mismatches},'summary':summary,
            'testReportSha256':sha256(args.output/'test.json'),'evaluatorSha256':sha256(Path(__file__)),
            'acceptance':{'unitTop3':summary['selected']['exactUnitTop3']>summary['legacy']['exactUnitTop3'],
                          'multiCharacter':summary['selected']['multiCharacterTop3']>summary['legacy']['multiCharacterTop3'],
                          'endOffers':summary['selected']['endOffers']<summary['legacy']['endOffers']}}
    (args.output/'conclusion.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__': main()
