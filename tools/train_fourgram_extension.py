"""SCQ4/1: observed-three-character-context adaptation over an immutable SCNG1 lower model."""
import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import struct
from train_character_lm import BOS,EOS,UNK,pack,unpack,read_model,read_partition,discounted_table
from audit_sentence_corpus import sha256

HEADER=struct.Struct('>4sH32sII')
MAX_BYTES=16*1024*1024


@dataclass
class Fourgram:
    base: object
    direct: dict
    backoffs: dict
    def log_probability(self,a,b,c,w):
        a,b,c,w=(self.base.token(x) for x in [a,b,c,w])
        if w==BOS:raise ValueError('BOS is context only')
        score=self.direct.get((a,b,c,w))
        return score if score is not None else self.backoffs.get((a,b,c),0.0)+self.base.log_probability(b,c,w)


def fit(base,segments,discount=.75,min_fourgram_count=2):
    if not math.isfinite(discount) or not 0<discount<1 or min_fourgram_count<1:raise ValueError('Invalid smoothing')
    counts=Counter();totals=Counter();special={BOS,EOS,UNK}
    for text in segments:
        if not text:raise ValueError('Empty training segment')
        a=b=c=BOS
        for w in [base.token(ord(ch)) for ch in text]+[EOS]:
            if not {a,b,c}&special:counts[(a,b,c,w)]+=1
            totals['characters' if w!=EOS else 'segments']+=1
            totals['unknownCharacters']+=w==UNK
            a,b,c=b,c,w
    direct,bow=discounted_table(counts,discount,min_fourgram_count,
        lambda k:math.exp(base.log_probability(k[1],k[2],k[3])))
    return Fourgram(base,direct,bow),dict(totals,rawFourgrams=len(counts),retainedFourgrams=len(direct),retainedContexts=len(bow))


def encode(model,base_bytes):
    data=bytearray(HEADER.pack(b'SCQ4',1,hashlib.sha256(base_bytes).digest(),len(model.direct),len(model.backoffs)))
    for (*context,w),p in sorted(model.direct.items()):data.extend(struct.pack('>QIf',pack(context),w,p))
    for context,p in sorted(model.backoffs.items()):data.extend(struct.pack('>Qf',pack(context),p))
    if len(data)+len(base_bytes)>MAX_BYTES:raise ValueError('Combined model exceeds budget')
    decode(bytes(data),base_bytes)
    return bytes(data)


def decode(data,base_bytes):
    if not HEADER.size<=len(data) or len(data)+len(base_bytes)>MAX_BYTES:raise ValueError('Invalid extension size')
    magic,version,digest,n,m=HEADER.unpack_from(data)
    if magic!=b'SCQ4' or version!=1 or digest!=hashlib.sha256(base_bytes).digest():raise ValueError('Model version/base hash mismatch')
    if len(data)!=HEADER.size+16*n+12*m:raise ValueError('Truncated/trailing model')
    base=read_model(base_bytes);direct={};bow={};cursor=HEADER.size
    def context(key):
        values=unpack(key,3)
        if key>=1<<63 or any(v not in base.unigrams or v in [EOS,UNK] for v in values):raise ValueError('Invalid context token')
        return values
    previous=None
    for _ in range(n):
        key,w,p=struct.unpack_from('>QIf',data,cursor);cursor+=16;identity=(key,w)
        if previous is not None and identity<=previous:raise ValueError('Unsorted fourgrams')
        if w not in base.unigrams or not math.isfinite(p) or not -80<=p<=0:raise ValueError('Invalid transition')
        direct[(*context(key),w)]=p;previous=identity
    previous=-1
    for _ in range(m):
        key,p=struct.unpack_from('>Qf',data,cursor);cursor+=12
        if key<=previous or not math.isfinite(p) or not -80<=p<=0:raise ValueError('Invalid backoff')
        bow[context(key)]=p;previous=key
    if any(k[:3] not in bow for k in direct):raise ValueError('Missing backoff context')
    return Fourgram(base,direct,bow)


def train(old_corpus,selection,baseline,policy_path,output):
    policy=json.loads(policy_path.read_text('utf-8'));base_bytes=baseline.read_bytes()
    if sha256(baseline)!=policy['baselineSha256']:raise ValueError('Changed lower model')
    corpus=json.loads((old_corpus/'corpus.json').read_text('utf-8'))
    if sha256(old_corpus/'attribution.jsonl')!=corpus['attribution']['sha256']:raise ValueError('Changed attribution')
    old=read_partition(old_corpus,corpus,'train')
    previous=json.loads((selection.parent/'training-lock.json').read_text('utf-8'))
    if sha256(selection)!=previous['pins']['selectedNewTrain'] or sha256(old_corpus/'corpus.json')!=previous['pins']['oldCorpus']:
        raise ValueError('Changed E15 train selection')
    new=[json.loads(l) for l in selection.read_text('utf-8').splitlines()]
    output.mkdir(parents=True,exist_ok=False)
    pins={str(p):sha256(p) for p in [old_corpus/'corpus.json',old_corpus/'train.jsonl',old_corpus/'attribution.jsonl',
        selection,selection.parent/'training-lock.json',baseline,policy_path,Path(__file__),Path(__file__).with_name('train_character_lm.py')]}
    lock=dict(schemaVersion=1,pins=pins,policy=policy,onlyTrainRead=True,candidateOutputsUsed=False)
    (output/'training-lock.json').write_text(json.dumps(lock,indent=2)+'\n',encoding='utf-8')
    reports={}
    for mode in policy['trials']:
        records=old if mode['name']=='daily-fourgram' else old+new
        model,stats=fit(read_model(base_bytes),(t for r in records for t in r['segments']),**policy['training'])
        data=encode(model,base_bytes);path=output/(mode['name']+'.scq4');path.write_bytes(data)
        reports[mode['name']]=dict(stats=stats,sha256=sha256(path),bytes=len(data),combinedBytes=len(data)+len(base_bytes))
    report=dict(schemaVersion=1,trainingLockSha256=sha256(output/'training-lock.json'),models=reports,
        devOrTestRead=False,productionChanged=False)
    (output/'training-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(reports))


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__)
    for n in ['old_corpus','selection','baseline','policy_path','output']:p.add_argument(n,type=Path)
    train(**vars(p.parse_args()))
