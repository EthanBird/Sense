"""Offline context-to-word/suffix prediction experiment. Train-only counts, no automatic packaging.

Words are segmented with the already shipped SPLX dictionary, not with evaluation labels.
The observed next unit is an imperfect single-reference proxy, not human acceptability gold.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import struct

from audit_sentence_corpus import han, sha256
from train_character_lm import BOS, EOS, read_model, read_partition


def dictionary_words(data):
    if data[:6] != b'SPLX\x00\x03':
        raise ValueError('Expected the pinned SPLX/3 dictionary')
    records = struct.unpack_from('>I', data, 6)[0]
    offset, words = 10, {}
    def take(n):
        nonlocal offset
        if offset + n > len(data):
            raise ValueError('Truncated dictionary')
        result = data[offset:offset+n]
        offset += n
        return result
    for _ in range(records):
        code = take(take(1)[0]).decode('ascii')
        canonical = code.isascii() and code.isalpha() and code.islower()
        for _ in range(take(1)[0]):
            word = take(take(1)[0]).decode('utf-8')
            weight = struct.unpack('>I', take(4))[0]
            take(take(1)[0])
            tier = take(1)[0]
            if tier not in (0, 1):
                raise ValueError('Unknown dictionary tier')
            if canonical and 1 <= len(word) <= 8 and all(map(han, word)):
                words[word] = max(words.get(word, 0), weight)
    if offset != len(data):
        raise ValueError('Trailing dictionary bytes')
    return words


class Segmenter:
    def __init__(self, words):
        normalizer = math.log(sum(w + 1 for w in words.values()))
        self.scores = {s: math.log(w + 1) - normalizer for s, w in words.items()}
        self.unknown = -normalizer - math.log(10)

    def units(self, text):
        # Additive dictionary unigram Viterbi, deterministic longest-first tie break.
        scores, ends = [0.0] * (len(text) + 1), [0] * len(text)
        for start in range(len(text)-1, -1, -1):
            best = -math.inf
            for end in range(min(len(text), start+8), start, -1):
                word = text[start:end]
                score = self.scores.get(word, self.unknown if end == start+1 else -math.inf) + scores[end]
                if score > best:
                    best, ends[start] = score, end
            scores[start] = best
        offset, result = 0, []
        while offset < len(text):
            end = ends[offset]
            result.append(text[offset:end])
            offset = end
        return result

    def events(self, text):
        ends, end = [], 0
        for word in self.units(text):
            end += len(word)
            ends.append(end)
        i = 0
        for cut in range(1, len(text)+1):
            while i < len(ends) and ends[i] <= cut:
                i += 1
            next_unit = text[cut:ends[i]] if i < len(ends) else ''
            yield cut, text[max(0, cut-3):cut], next_unit, text[cut:], cut in ends


def fit(rows, segmenter):
    counts, totals, unigram = defaultdict(Counter), Counter(), Counter()
    for row in rows:
        for text in row['segments']:
            for _, context, unit, _, _ in segmenter.events(text):
                unigram[unit] += 1
                for size in range(1, len(context)+1):
                    key = context[-size:]
                    counts[key][unit] += 1
                    totals[key] += 1
    # Fixed resource cutoffs, selected without reading development answers.
    retained = {ctx: {w:c for w,c in counter.items() if c >= 2 or not w}
                for ctx,counter in counts.items() if totals[ctx] >= 3}
    retained = {ctx: dict(sorted(c.items(), key=lambda wc: (-wc[1], wc[0]))[:32]) for ctx,c in retained.items()}
    return retained, totals, unigram


class WordPredictor:
    def __init__(self, counts, totals, unigram):
        self.counts, self.totals, self.unigram = counts, totals, unigram
        self.denominator = sum(unigram.values()) + .1 * len(unigram)
        self.backoffs = {c: 1 - sum(n-.75 for n in v.values()) / totals[c] for c,v in counts.items()}

    @lru_cache(maxsize=100000)
    def distribution(self, context):
        contexts = [context[-size:] for size in range(1, len(context)+1) if context[-size:] in self.counts]
        pool = {w for ctx in contexts for w in self.counts[ctx]} | {''}
        values = {w:(self.unigram.get(w,0)+.1)/self.denominator for w in pool}
        for ctx in contexts:
            for word in values:
                n = self.counts[ctx].get(word, 0)
                values[word] = (n-.75)/self.totals[ctx] + self.backoffs[ctx]*values[word] if n else self.backoffs[ctx]*values[word]
        return values if contexts else {}

    def suggest(self, context, length_bonus, stop_ratio, limit=8):
        values = self.distribution(context)
        ranked = sorted(((word, math.log(prob) + length_bonus*math.log(len(word)))
                         for word,prob in values.items() if word), key=lambda x:(-x[1],x[0]))
        if not ranked or values[ranked[0][0]] < .02 or values[ranked[0][0]] <= values.get('',0)*stop_ratio:
            return []
        return ranked[:limit]


def baseline(data):
    if data[:6] != b'SBGM\x00\x01': raise ValueError('Expected SBGM/1')
    n = struct.unpack_from('>I',data,6)[0]
    if len(data) != 10 + 12*n: raise ValueError('Invalid baseline length')
    rows = defaultdict(list)
    for prev,cp,score in struct.iter_unpack('>IIf',data[10:]):
        if han(chr(cp)): rows[chr(prev)].append((chr(cp),score))
    return {c:sorted(v,key=lambda x:(-x[1],ord(x[0])))[:8] for c,v in rows.items()}


class CharacterPredictor:
    def __init__(self, model):
        self.model = model
        self.bi, self.tri = defaultdict(set), defaultdict(set)
        for a,b in model.bigrams:
            if b <= 0x10ffff and han(chr(b)): self.bi[a].add(b)
        for a,b,c in model.trigrams:
            if c <= 0x10ffff and han(chr(c)): self.tri[a,b].add(c)
        self.uni = set(cp for cp,_ in sorted(((c,p) for c,p in model.unigrams.items() if c <= 0x10ffff and han(chr(c))),key=lambda x:(-x[1],x[0]))[:8])

    @lru_cache(maxsize=100000)
    def suggest(self, context):
        a,b = (list(map(ord,context[-2:])) if len(context)>1 else [BOS,ord(context[-1])])
        a,b = self.model.token(a),self.model.token(b)
        pool = self.uni | self.bi[b] | self.tri[a,b]
        return [(chr(cp),p) for cp,p in sorted(((cp,self.model.log_probability(a,b,cp)) for cp in pool),key=lambda x:(-x[1],x[0]))[:8]]


def evaluate(rows, segmenter, predictors, output):
    totals = {name:Counter() for name in predictors}
    examples, observations = [], []
    for row in rows:
        for index,text in enumerate(row['segments']):
            for cut,context,unit,remaining,boundary in segmenter.events(text):
                obs={'id':row['id'],'group':row['group'],'segmentIndex':index,'cut':cut,'context':context,'expectedUnit':unit,'remaining':remaining,'wordBoundary':boundary,'predictions':{}}
                for name,predict in predictors.items():
                    predictions=[w for w,_ in predict(context)]; obs['predictions'][name]=predictions
                    t=totals[name]; t['opportunities']+=1; t['offers']+=bool(predictions)
                    if not remaining:
                        t['segmentEnds']+=1; t['endOffers']+=bool(predictions)
                        continue
                    t['continuations']+=1
                    for k in (1,3,8):
                        t[f'exactUnitTop{k}']+=unit in predictions[:k]
                        matching=[w for w in predictions[:k] if remaining.startswith(w)]
                        t[f'prefixTop{k}']+=bool(matching)
                        t[f'prefixCharactersTop{k}']+=max(map(len,matching),default=0)
                        t[f'multiCharacterTop{k}']+=any(len(w)>1 for w in matching)
                    t['wordBoundaryContinuations']+=boundary
                    if boundary: t['wordBoundaryExactTop3']+=unit in predictions[:3]
                observations.append(obs)
    output.write_text(json.dumps({'scope':'Observed continuation retrieval with dictionary-derived units on grouped corpus; not human acceptability, keystroke saving or natural error prevalence','summary':totals,'observations':observations},ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    return totals


def context_key(text):
    value=0
    for ch in text: value=(value<<21)|ord(ch)
    return value


def write_binary(predictor, length_bonus, stop_ratio, path):
    rows=[]
    for context in sorted(predictor.counts,key=context_key):
        values=predictor.suggest(context,length_bonus,stop_ratio)
        # Empty rows intentionally stop backoff for this known stopping context.
        rows.append((context,values))
    data=bytearray(struct.pack('>4sHI',b'SNWP',1,len(rows)))
    for ctx,values in rows:
        data.extend(struct.pack('>QB',context_key(ctx),len(values)))
        for word,score in values:
            encoded=word.encode('utf-8'); data.append(len(encoded)); data.extend(encoded); data.extend(struct.pack('>f',score))
    if len(data)>8*1024*1024: raise ValueError('Association model exceeds 8 MiB')
    path.write_bytes(data)
    return {'bytes':len(data),'contexts':len(rows),'nonemptyContexts':sum(bool(v) for _,v in rows),'sha256':sha256(path)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('corpus',type=Path); parser.add_argument('assets',type=Path); parser.add_argument('output',type=Path)
    args=parser.parse_args()
    if args.output.exists(): raise ValueError('Use a new directory; retain earlier evidence')
    manifest=json.loads((args.corpus/'corpus.json').read_text('utf-8'))
    if sha256(args.corpus/'attribution.jsonl')!=manifest['attribution']['sha256']: raise ValueError('Attribution changed')
    train=read_partition(args.corpus,manifest,'train'); dev=read_partition(args.corpus,manifest,'dev')
    if {r['group'] for r in train} & {r['group'] for r in dev}: raise ValueError('Source families cross partitions')
    args.output.mkdir(parents=True)
    words=dictionary_words((args.assets/'pinyin_lexicon.bin').read_bytes()); segmenter=Segmenter(words)
    print('Dictionary words:',len(words),flush=True)
    fitted=fit(train,segmenter); word_model=WordPredictor(*fitted)
    print('Retained contexts:',len(fitted[0]),flush=True)
    char_model=CharacterPredictor(read_model((args.assets/'pinyin_character_lm.scng').read_bytes()))
    old=baseline((args.assets/'pinyin_bigrams.bin').read_bytes())
    predictors={'legacy':lambda ctx:old.get(ctx[-1],[]),'character_lm':char_model.suggest}
    for bonus in (0.,.5,1.):
        for stop in (0.,1.,2.):
            predictors[f'word_l{bonus}_stop{stop}']=lambda ctx,b=bonus,s=stop:word_model.suggest(ctx,b,s)
    result=evaluate(dev,segmenter,predictors,args.output/'dev.json')
    metadata={'schemaVersion':1,'scope':'Development experiment only; test partition unopened by this tool; no APK promotion','sourceLicense':manifest['source'],'segmentation':'SPLX canonical word max weight, additive log unigram Viterbi, max8 Han; no HMM or gold token labels','context':'suffix 1..3 Han; next dictionary unit or remaining suffix; segment end is STOP','training':{'manifestSha256':sha256(args.corpus/'corpus.json'),'partition':manifest['outputs']['train'],'attributionSha256':manifest['attribution']['sha256']},'development':manifest['outputs']['dev'],'assets':{n:sha256(args.assets/n) for n in ('pinyin_lexicon.bin','pinyin_bigrams.bin','pinyin_character_lm.scng')},'trainerSha256':sha256(Path(__file__)),'fixedPruning':{'minimumContextEvents':3,'minimumDirectCount':2,'maxDirectSuccessors':32,'discount':.75,'unigramAlpha':.1,'minOfferedProbability':.02},'models':{}}
    for bonus in (0.,.5,1.):
        for stop in (0.,1.,2.):
            name=f'word_l{bonus}_stop{stop}'
            metadata['models'][name]=write_binary(word_model,bonus,stop,args.output/f'{name}.snwp')
    (args.output/'experiment.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for name,t in result.items(): print(name,json.dumps(t),flush=True)


if __name__=='__main__': main()
